package com.osrscompanion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.TimerSyncItem;
import com.osrscompanion.model.TimersSyncPayload;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import com.osrscompanion.runelite.farming.CropState;
import com.osrscompanion.runelite.farming.FarmingPatch;
import com.osrscompanion.runelite.farming.FarmingTracker;
import com.osrscompanion.runelite.farming.FarmingWorld;
import com.osrscompanion.runelite.farming.PatchImplementation;
import com.osrscompanion.runelite.farming.PatchPrediction;
import com.osrscompanion.runelite.farming.Produce;
import com.osrscompanion.runelite.Tab;
import com.osrscompanion.runelite.hunter.BirdHouse;
import com.osrscompanion.runelite.hunter.BirdHouseSpace;

@Slf4j
public class TimerMonitor {
	/** This class's feature for {@link Guard} (breaker, reports, the chat message). */
	static final String GUARD_FEATURE = "timers-sync";

	private static final int INITIAL_DELAY_SECONDS = 10;
	private static final int SYNC_INTERVAL_SECONDS = 45;
	private static final int LOGIN_SYNC_DELAY_SECONDS = 3;
	private static final int TEARS_OF_GUTHIX_RESET_DAYS = 7;
	private static final int LEAGUES_GROWTH_RATE_DIVISOR = 5;
	// Average time to harvest 10 birds, matches RuneLite's BirdHouseTracker.BIRD_HOUSE_DURATION.
	private static final long BIRD_HOUSE_DURATION_SECONDS = Duration.ofMinutes(50).getSeconds();
	/** RuneLite FarmingContractManager's key in the timetracking RS profile config (the crop's item id). */
	private static final String FARMING_CONTRACT_KEY = "contract";
	private static final String FARMING_CONTRACT_TYPE = "farming contract";
	private static final String FARMING_GUILD = "Farming Guild";
	/** RuneLite ClockManager's key (plain config, not per RS profile): the panel's timers as a JSON list. */
	private static final String CLOCK_TIMERS_KEY = "timers";

	private final Client client;
	private final OSRSCompanionConfig config;
	private final OSRSCompanionApiClient apiClient;
	private final ConfigManager configManager;
	private final ScheduledExecutorService executorService;
	private final FarmingTracker farmingTracker;
	private final FarmingWorld farmingWorld;

	private ScheduledFuture<?> scheduledFuture;
	private String lastSentSnapshot;
	private volatile Instant tearsOfGuthixNextReset = null;
	private GameState previousGameState = GameState.UNKNOWN;

	public TimerMonitor(
			Client client,
			OSRSCompanionConfig config,
			OSRSCompanionApiClient apiClient,
			ConfigManager configManager,
			ScheduledExecutorService executorService,
			FarmingTracker farmingTracker,
			FarmingWorld farmingWorld) {
		this.client = client;
		this.config = config;
		this.apiClient = apiClient;
		this.configManager = configManager;
		this.executorService = executorService;
		this.farmingTracker = farmingTracker;
		this.farmingWorld = farmingWorld;
	}

	public void start() {
		if (scheduledFuture != null) {
			return;
		}
		scheduledFuture = executorService.scheduleAtFixedRate(
				this::sync,
				INITIAL_DELAY_SECONDS,
				SYNC_INTERVAL_SECONDS,
				TimeUnit.SECONDS);
	}

	public void stop() {
		if (scheduledFuture != null) {
			scheduledFuture.cancel(false);
			scheduledFuture = null;
		}
		lastSentSnapshot = null;
		tearsOfGuthixNextReset = null;
		previousGameState = GameState.UNKNOWN;
	}

	/**
	 * Re-runs the timer sync immediately on the background executor, forcing a
	 * resend by clearing the change-detection snapshot. Used by the roadmap
	 * panel's "Sync &amp; refresh" button.
	 */
	public void forceSync() {
		executorService.execute(() -> {
			lastSentSnapshot = null;
			sync();
		});
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> {
			GameState newState = event.getGameState();
			if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
				log.debug("Player logged in, scheduling timer sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
				lastSentSnapshot = null;
				executorService.schedule(this::sync, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
			}
			previousGameState = newState;
		});
	}

	/**
	 * Called when the player enters the Tears of Guthix cave.
	 * ToG is playable again 7 days after completion, rounded down to 00:00 UTC.
	 */
	public void onTearsOfGuthixCompleted() {
		ZonedDateTime nowUtc = Instant.now().atZone(ZoneOffset.UTC);
		ZonedDateTime completionDayStart = nowUtc.toLocalDate().atStartOfDay(ZoneOffset.UTC);
		tearsOfGuthixNextReset = completionDayStart.plusDays(TEARS_OF_GUTHIX_RESET_DAYS).toInstant();
		lastSentSnapshot = null;
		log.debug("Tears of Guthix completed; next reset at {}", tearsOfGuthixNextReset);
		// Push it now rather than waiting for the periodic sync.
		executorService.execute(this::sync);
	}

	/**
	 * Collects all timer data (farming, bird houses, Tears of Guthix) and sends
	 * to the OSRS Companion API if anything changed since the last sync.
	 */
	private void sync() {
		if (!SyncGuard.hasAppKey(config)) {
			log.debug("OSRS Companion sync skipped: no App Key configured");
			return;
		}
		if (!config.syncTimeTracking()) {
			log.debug("OSRS Companion sync skipped: sync disabled");
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			log.debug("OSRS Companion sync skipped: not logged in");
			return;
		}
		if (GameModeUtil.isSpecialGameMode(client)) {
			log.debug("OSRS Companion sync skipped: special game mode detected (Leagues, DMM, etc.)");
			return;
		}

		String playerUsername = SyncGuard.getPlayerUsername(client);
		if (playerUsername == null) {
			log.warn("OSRS Companion sync skipped: could not get player username");
			return;
		}

		boolean syncEnabled = config.syncTimeTracking();
		List<TimerSyncItem> timers = new ArrayList<>();

		boolean farmingKnown = collectFarmingTimers(timers, playerUsername, syncEnabled);
		collectFarmingContractTimer(timers, playerUsername, syncEnabled);
		collectBirdHouseTimers(timers, playerUsername, syncEnabled);
		collectTearsOfGuthixTimer(timers, playerUsername, syncEnabled);
		collectClockTimers(timers, playerUsername, syncEnabled);

		// No patch could be read at all (Time Tracking data not loaded for this account yet,
		// right after login): an empty list now would wipe every timer on the website.
		if (timers.isEmpty() && !farmingKnown) {
			log.debug("OSRS Companion timer sync skipped: no Time Tracking data read yet");
			return;
		}

		String snapshot = TimersSyncPayload.toJson(timers, playerUsername);
		if (!snapshot.equals(lastSentSnapshot)) {
			lastSentSnapshot = snapshot;
			log.debug("OSRS Companion syncing {} timer(s) for {}", timers.size(), playerUsername);
			apiClient.sendTimersSync(timers, playerUsername);
		}
	}

	/**
	 * Every farming patch the core Time Tracking plugin knows (seen in game at least once),
	 * in the state its panel shows: growing (with the finish time), harvestable, diseased,
	 * dead, empty (weeds, an empty compost bin) or filling (a compost bin being filled).
	 * Only a growing crop, or produce regrowing on a bush / fruit tree, has a finish time.
	 * Ready, diseased and dead patches used to be left out (their prediction has no finish
	 * time), so a patch vanished from the website once it was seen ready in game.
	 * Returns whether any patch had Time Tracking data at all.
	 */
	private boolean collectFarmingTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		boolean anyKnown = false;
		for (var entry : farmingWorld.getTabs().entrySet()) {
			for (FarmingPatch patch : entry.getValue()) {
				PatchPrediction prediction = farmingTracker.predictPatch(patch);
				if (prediction != null) {
					anyKnown = true;
				}
				if (prediction == null || prediction.getProduce() == Produce.SCARECROW) {
					// Never seen in game (Time Tracking shows "Unknown"), or a scarecrow (no timer).
					continue;
				}

				Produce produce = prediction.getProduce();
				CropState state = prediction.getCropState();
				boolean empty = state == CropState.EMPTY || produce == Produce.WEEDS;
				long doneEstimate = prediction.getDoneEstimate();
				Instant completedAt = !empty && doneEstimate > 0 ? Instant.ofEpochSecond(doneEstimate) : null;
				Instant startedAt = state == CropState.GROWING && completedAt != null
						? computeFarmingStartedAt(prediction, doneEstimate) : null;
				int produceItemId = produce.getItemID();
				// The empty bins' item is a placeholder RuneLite draws nothing meaningful with.
				boolean emptyBin = produce == Produce.EMPTY_COMPOST_BIN || produce == Produce.EMPTY_BIG_COMPOST_BIN;

				timers.add(new TimerSyncItem(
						resolveTabName(entry.getKey()),
						resolveRegionName(patch, entry.getKey()),
						produce.getName(),
						completedAt,
						syncEnabled && isFarmingNotifyEnabled(patch),
						playerUsername,
						startedAt,
						empty ? CropState.EMPTY.name().toLowerCase() : state.name().toLowerCase(),
						produceItemId >= 0 && !emptyBin ? produceItemId : null,
						patch.getVarbit()));
			}
		}
		return anyKnown;
	}

	private String resolveRegionName(FarmingPatch patch, com.osrscompanion.runelite.Tab tab) {
		String regionName = patch.getRegion().getName();
		if (regionName == null || regionName.isBlank()) {
			return tab.name().toLowerCase();
		}
		return regionName;
	}

	private String resolveTabName(com.osrscompanion.runelite.Tab tab) {
		String tabName = tab.getName();
		if (tabName == null || tabName.isBlank()) {
			return tab.name().toLowerCase();
		}
		return tabName;
	}

	/**
	 * Reads bird house state directly from the {@code timetracking} RS profile config
	 * (written by the core Time Tracking plugin) and emits a TimerSyncItem for each
	 * seeded house, also once it is done (RuneLite shows it as done until it is emptied).
	 */
	private void collectBirdHouseTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		boolean birdHouseNotify = syncEnabled && isBirdHouseNotifyEnabled();

		for (BirdHouseSpace space : BirdHouseSpace.values()) {
			String key = TimeTrackingConfig.BIRD_HOUSE + "." + space.getVarp();
			String stored = configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP, key);
			if (stored == null) {
				continue;
			}

			String[] parts = stored.split(":");
			if (parts.length != 2) {
				continue;
			}

			int varp;
			long timestamp;
			try {
				varp = Integer.parseInt(parts[0]);
				timestamp = Long.parseLong(parts[1]);
			} catch (NumberFormatException e) {
				continue;
			}

			// Seeded when varp is a positive multiple of 3; see RuneLite BirdHouseState.
			if (varp <= 0 || varp % 3 != 0) {
				continue;
			}

			long spaceCompletionTime = timestamp + BIRD_HOUSE_DURATION_SECONDS;

			BirdHouse birdHouse = BirdHouse.fromVarpValue(varp);
			Integer birdHouseEntityId = birdHouse != null ? birdHouse.getItemID() : null;
			String entityName = birdHouse != null ? birdHouse.getName() : "Bird House";

			timers.add(new TimerSyncItem(
					"bird house",
					space.getName(),
					entityName,
					Instant.ofEpochSecond(spaceCompletionTime),
					birdHouseNotify,
					playerUsername,
					Instant.ofEpochSecond(timestamp),
					null,
					birdHouseEntityId,
					space.getVarp()));
		}
	}

	/**
	 * The farming contract from Guildmaster Jane, as the core Time Tracking plugin stores it
	 * (the contract crop's item id). Its state is that of the Farming Guild patch growing
	 * the crop; "empty" (no completion time) while it is not planted yet.
	 */
	private void collectFarmingContractTimer(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		String stored = configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP, FARMING_CONTRACT_KEY);
		Produce contract = stored == null ? null : contractProduce(stored);
		if (contract == null) {
			return;
		}

		for (var entry : farmingWorld.getTabs().entrySet()) {
			for (FarmingPatch patch : entry.getValue()) {
				if (!FARMING_GUILD.equals(patch.getRegion().getName())) {
					continue;
				}
				PatchPrediction prediction = farmingTracker.predictPatch(patch);
				if (prediction == null || prediction.getProduce().getItemID() != contract.getItemID()
						|| isCompost(prediction.getProduce())) {
					continue;
				}
				long done = prediction.getDoneEstimate();
				timers.add(new TimerSyncItem(
						FARMING_CONTRACT_TYPE,
						FARMING_GUILD,
						contract.getName(),
						done > 0 ? Instant.ofEpochSecond(done) : null,
						syncEnabled && isFarmingNotifyEnabled(patch),
						playerUsername,
						done > 0 ? computeFarmingStartedAt(prediction, done) : null,
						prediction.getCropState().name().toLowerCase(),
						contract.getItemID(),
						patch.getVarbit()));
				return;
			}
		}

		timers.add(new TimerSyncItem(FARMING_CONTRACT_TYPE, FARMING_GUILD, contract.getName(), null, syncEnabled,
				playerUsername, null, CropState.EMPTY.name().toLowerCase(), contract.getItemID(), 0));
	}

	/** The crop a stored contract item id stands for (compost bins share item ids; they are never contracts). */
	private static Produce contractProduce(String stored) {
		int itemId;
		try {
			itemId = Integer.parseInt(stored.trim());
		} catch (NumberFormatException e) {
			return null;
		}
		for (Produce produce : Produce.values()) {
			if (produce.getItemID() == itemId && produce != Produce.WEEDS && produce != Produce.SCARECROW && !isCompost(produce)) {
				return produce;
			}
		}
		return null;
	}

	private static boolean isCompost(Produce produce) {
		return produce.getPatchImplementation() == PatchImplementation.COMPOST
				|| produce.getPatchImplementation() == PatchImplementation.BIG_COMPOST;
	}

	private void collectTearsOfGuthixTimer(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		Instant togReset = tearsOfGuthixNextReset;
		if (togReset != null && togReset.isAfter(Instant.now())) {
			timers.add(new TimerSyncItem(
					"tears of guthix",
					"tears of guthix",
					"tears of guthix",
					togReset,
					syncEnabled,
					playerUsername,
					null,
					null,
					null,
					0));
		}
	}

	/**
	 * The countdown timers from Time Tracking's "Timers &amp; Stopwatches" tab, as RuneLite's
	 * ClockManager stores them: [{name, duration, remaining, lastUpdate, active, loop}, ...]
	 * (seconds). A running timer ends at lastUpdate + remaining; a paused one has no end
	 * yet and is sent as "paused". Stopwatches count up, so they are not timers here.
	 */
	private void collectClockTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		String json = configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, CLOCK_TIMERS_KEY);
		if (json == null || json.isBlank()) {
			return;
		}
		JsonElement root;
		try {
			root = new JsonParser().parse(json);
		} catch (RuntimeException e) {
			log.debug("Unreadable Time Tracking timers: {}", e.getMessage());
			return;
		}
		if (!root.isJsonArray()) {
			return;
		}
		long now = Instant.now().getEpochSecond();
		int index = 0;
		for (JsonElement el : root.getAsJsonArray()) {
			index++;
			if (!el.isJsonObject()) {
				continue;
			}
			JsonObject t = el.getAsJsonObject();
			String name = string(t, "name");
			long duration = number(t, "duration");
			long remaining = number(t, "remaining");
			long lastUpdate = number(t, "lastUpdate");
			boolean active = t.has("active") && t.get("active").isJsonPrimitive() && t.get("active").getAsBoolean();
			boolean loop = t.has("loop") && t.get("loop").isJsonPrimitive() && t.get("loop").getAsBoolean();

			Instant completedAt = null;
			Instant startedAt = null;
			if (active && lastUpdate > 0) {
				long end = lastUpdate + Math.max(0, remaining);
				if (loop && duration > 0 && end < now) {
					// A looping timer restarts each time it ends.
					end += ((now - end) / duration + 1) * duration;
				}
				completedAt = Instant.ofEpochSecond(end);
				startedAt = duration > 0 ? Instant.ofEpochSecond(end - duration) : null;
			}
			timers.add(new TimerSyncItem(
					Tab.CLOCK.getName(),
					"timer",
					name == null || name.isBlank() ? "Timer " + index : name,
					completedAt,
					syncEnabled,
					playerUsername,
					startedAt,
					active ? null : "paused",
					null,
					index));
		}
	}

	private static String string(JsonObject o, String key) {
		JsonElement e = o.get(key);
		return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
	}

	private static long number(JsonObject o, String key) {
		JsonElement e = o.get(key);
		try {
			return e != null && e.isJsonPrimitive() ? e.getAsLong() : 0;
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	private boolean isFarmingNotifyEnabled(FarmingPatch patch) {
		String notifyKey = TimeTrackingConfig.NOTIFY + "." + patch.getRegion().getRegionID() + "." + patch.getVarbit();
		String profileKey = configManager.getRSProfileKey();
		return Boolean.TRUE
				.equals(configManager.getConfiguration(TimeTrackingConfig.CONFIG_GROUP, profileKey, notifyKey, Boolean.class));
	}

	private boolean isBirdHouseNotifyEnabled() {
		return Boolean.TRUE.equals(configManager.getRSProfileConfiguration(TimeTrackingConfig.CONFIG_GROUP,
				TimeTrackingConfig.BIRDHOUSE_NOTIFY, boolean.class));
	}

	/**
	 * Computes started_at for a farming patch by subtracting the total growth
	 * duration from the estimated completion time. Adjusts tick rate for Leagues worlds.
	 */
	private Instant computeFarmingStartedAt(PatchPrediction prediction, long doneEstimate) {
		int tickRate = prediction.getProduce().getTickrate();
		if (GameModeUtil.isLeaguesWorld(client)) {
			tickRate = tickRate / LEAGUES_GROWTH_RATE_DIVISOR;
		}
		int stages = prediction.getStages();
		if (tickRate <= 0 || stages <= 1) {
			return null;
		}
		long growthSeconds = (long) (stages - 1) * tickRate * 60;
		return Instant.ofEpochSecond(doneEstimate - growthSeconds);
	}
}
