package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.KillCountsSyncPayload;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Monitors the player's boss kill counts and syncs them to the OSRS Companion API.
 *
 * <p>Reads RuneLite's persisted {@code killcount} RSProfile config — the store the
 * in-game {@code !kc} command reads, which RuneLite populates from the game's own
 * kill-count chat messages. That makes it the accurate, self-correcting count
 * (unlike loot-tracker-derived KC) and lets us pull the player's whole KC history
 * in one read, instead of waiting to witness a kill.
 *
 * <p>Triggers: a sync on login (after a delay so the RSProfile is resolved) and on
 * logout, plus a near-real-time sync when a kill count changes. RuneLite rewrites
 * the config on each kill, firing {@link ConfigChanged}; we mark a re-check and
 * read + dedupe on the next {@link GameTick} (throttled). A JSON equality check
 * means an unchanged set is never resent.
 */
@Slf4j
@Singleton
public class KillCountMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "kill-count-sync";

	private static final String KILLCOUNT_GROUP = "killcount";
	// The RSProfile is set a little after LOGGED_IN, so wait before the first read.
	private static final int LOGIN_SYNC_DELAY_SECONDS = 5;
	private static final int RESYNC_THROTTLE_TICKS = 3;
	// killcount keys that aren't boss KCs (Duel Arena win/loss/streak counters).
	private static final String DUEL_ARENA_PREFIX = "duel arena";

	private final Client client;
	private final ClientThread clientThread;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private GameState previousGameState = GameState.UNKNOWN;
	private boolean kcCheckPending;
	private int lastReadTick = -1;
	private String lastSyncJson;

	@Inject
	public KillCountMonitor(
			Client client,
			ClientThread clientThread,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService,
			Gson gson) {
		executorService = GuardedExecutor.wrap(GUARD_FEATURE, executorService);
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.gson = gson;
	}

	/** Invoked after each upload so roadmap progress can be re-read; set by the plugin. */
	private volatile Runnable syncedListener;

	public void setSyncedListener(Runnable listener) {
		this.syncedListener = listener;
	}

	private void notifySynced() {
		Runnable listener = syncedListener;
		if (listener != null) {
			listener.run();
		}
	}

	public void stop() {
		previousGameState = GameState.UNKNOWN;
		kcCheckPending = false;
		lastReadTick = -1;
		lastSyncJson = null;
	}

	/**
	 * Re-reads and re-pushes the stored kill counts on the client thread. Used by the
	 * roadmap panel's "Sync &amp; refresh"; clears the dedup cache so an unchanged set
	 * still sends.
	 */
	public void forceSync() {
		clientThread.invokeLater(() -> {
			lastSyncJson = null;
			syncKillCounts();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();
			if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
				// The RSProfile the kill counts live under is resolved shortly after login.
				executorService.schedule(() -> clientThread.invokeLater(this::syncKillCounts),
						LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
			} else if (previousGameState == GameState.LOGGED_IN && newState != GameState.LOGGED_IN) {
				// Logging out: flush final state (this handler runs on the client thread).
				syncKillCounts();
			}
			previousGameState = newState;
		});
	}

	/** RuneLite rewrites a killcount config key on each kill; mark a re-check. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (KILLCOUNT_GROUP.equals(event.getGroup())) {
				kcCheckPending = true;
			}
		});
	}

	@Subscribe
	public void onGameTick(GameTick event) {
		Guard.run(GUARD_FEATURE, () -> {
			if (!kcCheckPending) {
				return;
			}
			if (lastReadTick != -1 && client.getTickCount() - lastReadTick < RESYNC_THROTTLE_TICKS) {
				return;
			}
			kcCheckPending = false;
			lastReadTick = client.getTickCount();
			syncKillCounts();
		});
	}

	/** Reads RuneLite's stored kill counts, builds the payload, and syncs (deduped). */
	private void syncKillCounts() {
		if (!config.syncKillCounts() || !SyncGuard.hasAppKey(config)) {
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			return;
		}
		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			return;
		}
		String profile = configManager.getRSProfileKey();
		if (profile == null) {
			return;  // RSProfile not resolved yet
		}

		// TreeMap gives a stable JSON key order so the dedup check is reliable.
		Map<String, Integer> killCounts = new TreeMap<>();
		for (String boss : configManager.getRSProfileConfigurationKeys(KILLCOUNT_GROUP, profile, "")) {
			if (boss == null || boss.startsWith(DUEL_ARENA_PREFIX)) {
				continue;
			}
			Integer kc = configManager.getRSProfileConfiguration(KILLCOUNT_GROUP, boss, int.class);
			if (kc != null) {
				killCounts.put(boss, kc);
			}
		}

		if (killCounts.isEmpty()) {
			return;
		}

		KillCountsSyncPayload payload = new KillCountsSyncPayload(playerUsername, killCounts);
		String json = payload.toJson(gson);

		if (json.equals(lastSyncJson)) {
			log.debug("Kill counts unchanged, skipping sync");
			return;
		}
		lastSyncJson = json;
		log.debug("Syncing {} kill counts for player: {}", killCounts.size(), playerUsername);
		apiClient.sendKillCountsSync(payload);
		notifySynced();
	}
}
