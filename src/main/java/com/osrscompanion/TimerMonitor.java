package com.osrscompanion;

import com.osrscompanion.api.OSRSCompanionApiClient;
import com.osrscompanion.model.TimerSyncItem;
import com.osrscompanion.model.TimersSyncPayload;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
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
import com.osrscompanion.runelite.hunter.BirdHouse;
import com.osrscompanion.runelite.hunter.BirdHouseSpace;

@Slf4j
public class TimerMonitor {
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

	/** Receives every recomputed timer set (farming, bird houses, ...); set by the plugin. */
	private volatile Consumer<List<TimerSyncItem>> timersListener;

	public void setTimersListener(Consumer<List<TimerSyncItem>> listener) {
		this.timersListener = listener;
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
		GameState newState = event.getGameState();
		if (newState == GameState.LOGGED_IN && previousGameState != GameState.LOGGED_IN) {
			log.debug("Player logged in, scheduling timer sync in {}s", LOGIN_SYNC_DELAY_SECONDS);
			lastSentSnapshot = null;
			executorService.schedule(this::sync, LOGIN_SYNC_DELAY_SECONDS, TimeUnit.SECONDS);
		}
		previousGameState = newState;
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

		farmingTracker.loadCompletionTimes();
		farmingTracker.updateCompletionTime();

		boolean syncEnabled = config.syncTimeTracking();
		List<TimerSyncItem> timers = new ArrayList<>();

		collectFarmingTimers(timers, playerUsername, syncEnabled);
		collectFarmingContractTimer(timers, playerUsername, syncEnabled);
		collectBirdHouseTimers(timers, playerUsername, syncEnabled);
		collectTearsOfGuthixTimer(timers, playerUsername, syncEnabled);

		Consumer<List<TimerSyncItem>> listener = timersListener;
		if (listener != null) {
			listener.accept(timers);
		}

		String snapshot = TimersSyncPayload.toJson(timers, playerUsername);
		if (!snapshot.equals(lastSentSnapshot)) {
			lastSentSnapshot = snapshot;
			log.debug("OSRS Companion syncing {} timer(s) for {}", timers.size(), playerUsername);
			apiClient.sendTimersSync(timers, playerUsername);
			notifySynced();
		}
	}

	/**
	 * Iterates all farming patches across all tabs, builds a TimerSyncItem for each
	 * patch with a valid, in-progress prediction, and appends them to the timers list.
	 */
	private void collectFarmingTimers(List<TimerSyncItem> timers, String playerUsername, boolean syncEnabled) {
		for (var entry : farmingWorld.getTabs().entrySet()) {
			for (FarmingPatch patch : entry.getValue()) {
				PatchPrediction prediction = farmingTracker.predictPatch(patch);
				if (!isValidFarmingPrediction(prediction)) {
					continue;
				}

				long doneEstimate = prediction.getDoneEstimate();
				if (doneEstimate <= 0) {
					continue;
				}

				boolean notificationsEnabled = syncEnabled && isFarmingNotifyEnabled(patch);
				String regionName = resolveRegionName(patch, entry.getKey());
				String tabName = resolveTabName(entry.getKey());

				Instant completedAt = Instant.ofEpochSecond(doneEstimate);
				Instant startedAt = computeFarmingStartedAt(prediction, doneEstimate);
				int produceItemId = prediction.getProduce().getItemID();

				timers.add(new TimerSyncItem(
						tabName,
						regionName,
						prediction.getProduce().getName(),
						completedAt,
						notificationsEnabled,
						playerUsername,
						startedAt,
						prediction.getCropState().name().toLowerCase(),
						produceItemId >= 0 ? produceItemId : null,
						patch.getVarbit()));
			}
		}
	}

	private boolean isValidFarmingPrediction(PatchPrediction prediction) {
		if (prediction == null || prediction.getProduce().getItemID() < 0) {
			return false;
		}
		if (prediction.getProduce() == Produce.WEEDS || prediction.getProduce() == Produce.SCARECROW) {
			return false;
		}
		return prediction.getCropState() != CropState.EMPTY && prediction.getCropState() != CropState.FILLING;
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
