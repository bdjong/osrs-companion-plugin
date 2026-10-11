package com.osrscompanion;

import com.google.gson.Gson;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;

/**
 * The timers: sends what RuneLite's Time Tracking plugin stored for this account (its {@code timetracking}
 * config: each patch's last seen value and time, the farm tick offset, bird houses, the farming contract) and the
 * clock tab's timers, and the website works out the farming, bird house, contract and clock timers from it
 * (POST /api/runelite/time-tracking). Checked every 45 seconds while logged in and sent when it changed; once
 * after login, and at once after Tears of Guthix.
 */
public class TimerMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "timers-sync";

	private static final int SYNC_INTERVAL_SECONDS = 45;
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	/** Clock timers that loop and the Tears of Guthix reset change with time alone: sent again this often. */
	private static final long RESEND_MS = 10 * 60_000L;

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final Gson gson;

	private ScheduledFuture<?> scheduledFuture;
	private String lastSent;
	private long lastSentMs;
	/** Tears of Guthix is playable again 7 days after it, from 00:00 UTC; known only this session. */
	private volatile Instant tearsOfGuthixNextReset;
	private GameState previousGameState = GameState.UNKNOWN;

	public TimerMonitor(Client client, OSRSCompanionConfig config, OSRSCompanionApiClient apiClient, ConfigManager configManager,
			ScheduledExecutorService executorService, Gson gson) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.gson = gson;
	}

	public void start() {
		if (scheduledFuture == null) {
			scheduledFuture = executorService.scheduleAtFixedRate(this::sync, 10, SYNC_INTERVAL_SECONDS, TimeUnit.SECONDS);
		}
	}

	public void stop() {
		if (scheduledFuture != null) {
			scheduledFuture.cancel(false);
			scheduledFuture = null;
		}
		lastSent = null;
		tearsOfGuthixNextReset = null;
		previousGameState = GameState.UNKNOWN;
	}

	/** Sends again at once, even unchanged (the panel's "Sync &amp; refresh"). */
	public void forceSync() {
		executorService.execute(() -> {
			lastSent = null;
			sync();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();
			if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
				lastSent = null;
				executorService.schedule(this::sync, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
			}
			previousGameState = newState;
		});
	}

	/** Entering the Tears of Guthix cave. */
	public void onTearsOfGuthixCompleted() {
		tearsOfGuthixNextReset = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().plusDays(7).atStartOfDay(ZoneOffset.UTC).toInstant();
		lastSent = null;
		executorService.execute(this::sync);
	}

	/** What RuneLite's Time Tracking stored for the account logged in (its RS profile's timetracking keys). */
	static Map<String, String> timeTracking(ConfigManager configManager) {
		Map<String, String> profile = new TreeMap<>();
		String profileKey = configManager.getRSProfileKey();
		if (profileKey != null) {
			for (String key : configManager.getRSProfileConfigurationKeys(TimeTrackingConfig.CONFIG_GROUP, profileKey, "")) {
				String value = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profileKey, key);
				if (value != null) {
					profile.put(key, value);
				}
			}
		}
		return profile;
	}

	private void sync() {
		String player = SyncGuard.getPlayerUsername(client);
		String profileKey = configManager.getRSProfileKey();
		if (!SyncGuard.hasAppKey(config) || !config.syncTimeTracking() || client.getGameState() != GameState.LOGGED_IN
				|| GameModeUtil.isSpecialGameMode(client) || player == null || profileKey == null) {
			return;
		}
		Map<String, String> profile = timeTracking(configManager);
		Instant tog = tearsOfGuthixNextReset;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("player_username", player);
		body.put("profile", profile);
		body.put("clock", configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, TimeTrackingConfig.TIMERS));
		body.put("tog_reset", tog == null ? null : tog.getEpochSecond());
		String json = gson.toJson(body);
		long now = System.currentTimeMillis();
		boolean timed = body.get("clock") != null || tog != null;
		if (json.equals(lastSent) && !(timed && now - lastSentMs >= RESEND_MS)) {
			return;
		}
		lastSent = json;
		lastSentMs = now;
		apiClient.sendTimeTracking(json, player);
	}
}
