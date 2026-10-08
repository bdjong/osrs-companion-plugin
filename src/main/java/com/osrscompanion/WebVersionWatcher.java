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
 */
@Slf4j
@Singleton
public class WebVersionWatcher {
	static final int CHECK_SECONDS = 30;
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

	private ScheduledFuture<?> task;

	@Inject
	public WebVersionWatcher(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient,
			ScheduledExecutorService executor, RoadmapManager roadmapManager, FarmRunManager farmRunManager,
			SlayerHelperManager slayerHelperManager, BossGearManager bossGearManager,
			GearProgressionManager gearProgressionManager, DailiesManager dailiesManager) {
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
	}

	public synchronized void start() {
		apiClient.setWebVersionChangedListener(() -> executor.execute(this::refreshAll));
		if (task == null) {
			task = executor.scheduleWithFixedDelay(this::check, CHECK_SECONDS, CHECK_SECONDS, TimeUnit.SECONDS);
		}
	}

	public synchronized void stop() {
		if (task != null) {
			task.cancel(false);
			task = null;
		}
		apiClient.setWebVersionChangedListener(null);
		apiClient.resetWebVersion();
	}

	/** Logged out: the next login's first webVersion is a new baseline (the login loads everything anyway). */
	public void onLoggedOut() {
		apiClient.resetWebVersion();
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
			String player = SyncGuard.getPlayerUsername(client);
			if (!shouldCheck(client.getGameState() == GameState.LOGGED_IN, SyncGuard.hasAppKey(config), player,
					System.currentTimeMillis(), apiClient.getLastBatchResponseMs())) {
				return;
			}
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
