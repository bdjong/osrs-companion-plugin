package com.osrscompanion;

import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * Notices changes made on the website (roadmaps, farm routes, gear setups, slayer picks, settings...)
 * without each feature polling: the website bumps a webVersion per character, every batch upload
 * answers with it, and every {@link #CHECK_SECONDS} a tiny {@code GET /api/runelite/version} asks
 * for it when no batch answered lately. A different value than the last one seen re-reads
 * everything that shows website data; the features' own periodic re-reads are only a backstop.
 *
 * <p>It is also the live activity heartbeat: while the status is shared ({@link LiveActivityMonitor}),
 * every batch carries it, and when no batch answered for {@link LiveActivityMonitor#HEARTBEAT_MS} the
 * status goes out on its own (a batch, whose answer carries the webVersion too, so no version check is
 * needed then). Idle, that is one request per 15 s instead of the version check's one per 30 s.
 */
@Slf4j
@Singleton
public class WebVersionWatcher {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "web-updates";

	static final int CHECK_SECONDS = 30;
	/** How often the (network-free) decision runs; the heartbeat then goes out 15 to 16 s after the last contact. */
	static final int TICK_SECONDS = 1;
	/** A batch response this recent already carried the webVersion. */
	static final long RECENT_BATCH_MS = TimeUnit.SECONDS.toMillis(CHECK_SECONDS);

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ScheduledExecutorService executor;
	private final RoadmapManager roadmapManager;
	private final FarmRunManager farmRunManager;
	private final SlayerHelperManager slayerHelperManager;
	private final BossGearManager bossGearManager;
	private final GearProgressionManager gearProgressionManager;
	private final DailiesManager dailiesManager;
	private final LiveActivityMonitor liveActivity;

	private ScheduledFuture<?> task;
	private volatile long lastVersionCheckMs = Long.MIN_VALUE;
	private volatile long lastHeartbeatMs = Long.MIN_VALUE;

	@Inject
	public WebVersionWatcher(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient,
			ScheduledExecutorService executor, RoadmapManager roadmapManager, FarmRunManager farmRunManager,
			SlayerHelperManager slayerHelperManager, BossGearManager bossGearManager,
			GearProgressionManager gearProgressionManager, DailiesManager dailiesManager, LiveActivityMonitor liveActivity) {
		executor = GuardedExecutor.wrap(GUARD_FEATURE, executor);
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.executor = executor;
		this.roadmapManager = roadmapManager;
		this.farmRunManager = farmRunManager;
		this.slayerHelperManager = slayerHelperManager;
		this.bossGearManager = bossGearManager;
		this.gearProgressionManager = gearProgressionManager;
		this.dailiesManager = dailiesManager;
		this.liveActivity = liveActivity;
	}

	public synchronized void start() {
		apiClient.setWebVersionChangedListener(() -> executor.execute(this::refreshAll));
		if (task == null) {
			task = executor.scheduleWithFixedDelay(this::check, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
		}
	}

	public synchronized void stop() {
		if (task != null) {
			task.cancel(false);
			task = null;
		}
		apiClient.setWebVersionChangedListener(null);
		apiClient.resetWebVersion();
		lastVersionCheckMs = Long.MIN_VALUE;
		lastHeartbeatMs = Long.MIN_VALUE;
	}

	/** Logged out: the next login's first webVersion is a new baseline (the login loads everything anyway). */
	public void onLoggedOut() {
		apiClient.resetWebVersion();
		lastHeartbeatMs = Long.MIN_VALUE;
	}

	/** Whether the live activity heartbeat is due: nothing reached the website (batch or heartbeat) for 15 s. */
	static boolean shouldHeartbeat(long nowMs, long lastBatchResponseMs, long lastHeartbeatMs) {
		long last = Math.max(lastBatchResponseMs, lastHeartbeatMs);
		return last == Long.MIN_VALUE || nowMs - last >= LiveActivityMonitor.HEARTBEAT_MS;
	}

	/** Whether the version GET is worth sending now. */
	static boolean shouldCheck(boolean loggedIn, boolean hasAppKey, String player, long nowMs, long lastBatchResponseMs) {
		if (!loggedIn || !hasAppKey || player == null) {
			return false;
		}
		return lastBatchResponseMs == Long.MIN_VALUE || nowMs - lastBatchResponseMs >= RECENT_BATCH_MS;
	}

	private void check() {
		try {
			long now = System.currentTimeMillis();
			long lastBatch = apiClient.getLastBatchResponseMs();
			if (liveActivity.isSharing()) {
				// The heartbeat's batch answers with the webVersion: it is the version check too.
				if (shouldHeartbeat(now, lastBatch, lastHeartbeatMs)) {
					lastHeartbeatMs = now;
					liveActivity.heartbeat();
				}
				return;
			}
			String player = SyncGuard.getPlayerUsername(client);
			if (!shouldCheck(client.getGameState() == GameState.LOGGED_IN, SyncGuard.hasAppKey(config), player,
					now, Math.max(lastBatch, lastVersionCheckMs))) {
				return;
			}
			lastVersionCheckMs = now;
			apiClient.getWebVersion(player, new OSRSCompanionApiClient.RoadmapCallback<Integer>() {
				@Override
				public void onSuccess(Integer result) {
					// observeWebVersion (in the client) already told the listener about a change.
				}

				@Override
				public void onError(String message) {
					log.debug("Web version check: {}", message);
				}
			});
		} catch (RuntimeException e) {
			log.debug("Web version check failed", e);
		}
	}

	/** Re-reads everything that shows website data (each feature only if it is in use). */
	void refreshAll() {
		if (client.getGameState() != GameState.LOGGED_IN || !SyncGuard.hasAppKey(config)) {
			return;
		}
		run("roadmaps", roadmapManager::refreshAllFresh);
		run("farm route", farmRunManager::refresh);
		if (slayerHelperManager.getGuide() != null) {
			run("slayer guide", slayerHelperManager::refresh);
		}
		if (bossGearManager.isLoaded() || bossGearManager.isOpen()) {
			run("boss gear", bossGearManager::refresh);
		}
		if (gearProgressionManager.isLoaded() || gearProgressionManager.isOpen()) {
			run("gear progression", gearProgressionManager::refresh);
		}
		if (dailiesManager.getGuide() != null) {
			run("dailies", dailiesManager::refresh);
		}
	}

	private static void run(String what, Runnable refresh) {
		try {
			refresh.run();
		} catch (RuntimeException e) {
			log.debug("Refreshing the {} after a website change failed", what, e);
		}
	}
}
