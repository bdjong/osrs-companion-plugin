package com.osrscompanion;

import com.google.inject.Provides;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import com.osrscompanion.runelite.farming.CompostTracker;
import com.osrscompanion.runelite.farming.FarmingTracker;
import com.osrscompanion.runelite.farming.FarmingWorld;
import com.osrscompanion.runelite.farming.PaymentTracker;

@Slf4j
@PluginDescriptor(
	name = "OSRS Companion",
	description = "Syncs your game data (with an App Key) to the third-party OSRS Companion website: skills, bank, collection log, quests, diaries, loot, slayer, farming and more.",
	tags = {"farming", "timetracking", "slayer", "sync", "bank", "collection log"}
)
public class OSRSCompanionPlugin extends Plugin {
	/** The plugin's own handlers' feature for {@link Guard}. */
	static final String GUARD_FEATURE = "plugin";
	private static final String TEARS_CAVE_MESSAGE = "Your stories have entertained me. I will let you into the cave for a short time.";

	@Inject
	private Client client;

	@Inject
	private OSRSCompanionConfig config;

	private TimerMonitor timerMonitor;

	@Inject
	private PlayerSkillsMonitor playerSkillsMonitor;

	@Inject
	private BankMemoryMonitor bankMemoryMonitor;

	@Inject
	private VaultMonitor vaultMonitor;

	@Inject
	private PotionStorageMonitor potionStorageMonitor;

	@Inject
	private RunePouchMonitor runePouchMonitor;

	@Inject
	private PlankSackMonitor plankSackMonitor;

	@Inject
	private StorageItemMonitor storageItemMonitor;

	@Inject
	private LoadoutMonitor loadoutMonitor;

	@Inject
	private LootMonitor lootMonitor;

	@Inject
	private CollectionLogMonitor collectionLogMonitor;

	@Inject
	private QuestMonitor questMonitor;

	@Inject
	private AchievementDiaryMonitor achievementDiaryMonitor;

	@Inject
	private HouseLocationMonitor houseLocationMonitor;

	@Inject
	private FarmingUnlocksMonitor farmingUnlocksMonitor;

	@Inject
	private KingdomMonitor kingdomMonitor;

	@Inject
	private CombatAchievementMonitor combatAchievementMonitor;

	@Inject
	private KillCountMonitor killCountMonitor;

	@Inject
	private DeathMonitor deathMonitor;

	@Inject
	private SlayerMonitor slayerMonitor;

	@Inject
	private SlayerCatalogMonitor slayerCatalogMonitor;

	@Inject
	private SlayerRewardsMonitor slayerRewardsMonitor;

	@Inject
	private EventBus eventBus;

	@Inject
	private Notifier notifier;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private OSRSCompanionApiClient apiClient;

	@Inject
	private ScheduledExecutorService executorService;

	@Inject
	private SessionMonitor sessionMonitor;

	@Inject
	private FarmHarvestTracker farmHarvestTracker;

	@Inject
	private BossTripTracker bossTripTracker;

	@Inject
	private ClientThread clientThread;

	@Inject
	private DailiesManager dailiesManager;

	@Inject
	private WebVersionWatcher webVersionWatcher;

	@Inject
	private LiveActivityMonitor liveActivityMonitor;

	@Inject
	private ChatMessageManager chatMessageManager;

	private volatile ErrorReporter errorReporter;
	/** Every object registered on the event bus at start, unregistered at stop. */
	private java.util.List<Object> subscribers;
	private volatile FreezeWatchdog watchdog;
	private volatile IncidentStore incidentStore;
	/** A fatal error or freeze the last session persisted, reported once a player is logged in. */
	private volatile PreviousIncident previousIncident;
	private ErrorLogAppender errorLogAppender;
	private java.util.concurrent.ScheduledFuture<?> errorReportFlush;

	@Override
	protected void startUp() {
		// Never throws: each subsystem starts on its own, so one failure leaves the others working.
		log.debug("OSRS Companion started");
		step("crash safety", this::startCrashSafety);
		// Uploads queue in the outbox and go out batched (first, so the monitors' first syncs use it).
		step("outbox", () -> apiClient.startOutbox(GuardedExecutor.wrap(Guard.SYNC_FEATURE, executorService)));
		step("error reports", this::startErrorReports);
		step("previous incident", () -> {
			if (client.getGameState() == GameState.LOGGED_IN) {
				reportPreviousIncident();
			}
		});
		step("farming timers", this::startFarming);

		subscribers = java.util.Arrays.asList(this, timerMonitor, playerSkillsMonitor, bankMemoryMonitor, vaultMonitor,
				potionStorageMonitor, runePouchMonitor, plankSackMonitor, storageItemMonitor, loadoutMonitor, lootMonitor,
				collectionLogMonitor, questMonitor, achievementDiaryMonitor, houseLocationMonitor, farmingUnlocksMonitor,
				kingdomMonitor, combatAchievementMonitor, killCountMonitor, deathMonitor, slayerMonitor, slayerCatalogMonitor,
				slayerRewardsMonitor, farmHarvestTracker, sessionMonitor, bossTripTracker, dailiesManager, liveActivityMonitor);
		for (Object subscriber : subscribers) {
			if (subscriber != null) {
				step("register " + subscriber.getClass().getSimpleName(), () -> eventBus.register(subscriber));
			}
		}
		step("timers", timerMonitor::start);
		step("play sessions", sessionMonitor::start);
		step("loot", lootMonitor::start);
		step("skills", playerSkillsMonitor::start);
		step("storage links", this::connectStorage);
		// Live activity: every batch carries the status; the watcher below sends the heartbeat.
		step("live activity", liveActivityMonitor::start);
		// Website changes: the webVersion from batch answers or a small check every 30 s re-reads what changed.
		step("web updates", webVersionWatcher::start);
	}

	/** Breakers on, the freeze watchdog running, and a crash the last session could not report reported now. */
	private void startCrashSafety() {
		Guard guard = Guard.shared();
		guard.resetBreakers();
		incidentStore = new IncidentStore(new IncidentStore.Storage() {
			@Override
			public String get() {
				return configManager.getConfiguration(OSRSCompanionConfig.CONFIG_GROUP, IncidentStore.KEY);
			}

			@Override
			public void set(String value) {
				configManager.setConfiguration(OSRSCompanionConfig.CONFIG_GROUP, IncidentStore.KEY, value);
			}

			@Override
			public void unset() {
				configManager.unsetConfiguration(OSRSCompanionConfig.CONFIG_GROUP, IncidentStore.KEY);
			}

			@Override
			public void flush() {
				configManager.sendConfig();
			}
		});
		previousIncident = new PreviousIncident(incidentStore);
		watchdog = new FreezeWatchdog(guard);
		guard.setListener(new GuardListener());
		// The watchdog times our sections on the client thread: tell the Guard which thread that is.
		clientThread.invoke(() -> guard.watchThread(Thread.currentThread()));
		watchdog.start();
	}

	/** What the Guard's failures become: reports, a persisted incident when the client may not survive, one chat line. */
	private final class GuardListener implements Guard.Listener {
		@Override
		public void onFailure(String feature, Throwable error) {
			ErrorReporter reporter = errorReporter;
			if (reporter != null) {
				reporter.reportCaught(feature, error);
			}
		}

		@Override
		public void onFatal(String feature, Throwable error) {
			persist(new IncidentStore.Incident("fatal", feature, String.valueOf(error),
					IncidentStore.shortStack(error, 20), System.currentTimeMillis()));
		}

		@Override
		public void onFreeze(String feature, long elapsedMs, StackTraceElement[] stack) {
			// First persisted: the player may kill the frozen client any moment now.
			persist(new IncidentStore.Incident("freeze", feature, "The client froze for " + elapsedMs + " ms in " + feature,
					IncidentStore.shortStack(stack, 20), System.currentTimeMillis()));
			ErrorReporter reporter = errorReporter;
			if (reporter != null) {
				reporter.reportFreeze(feature, elapsedMs, stack);
			}
		}

		@Override
		public void onDisabled(String feature) {
			String text = "OSRS Companion: " + Guard.displayName(feature) + " was turned off after an error; it has been reported.";
			chatMessageManager.queue(QueuedMessage.builder()
					.type(ChatMessageType.CONSOLE)
					.runeLiteFormattedMessage(text)
					.build());
		}

		private void persist(IncidentStore.Incident incident) {
			IncidentStore store = incidentStore;
			FreezeWatchdog dog = watchdog;
			if (store == null) {
				return;
			}
			// Off the client thread: the config write and flush are file IO.
			if (dog != null) {
				dog.execute(() -> store.save(incident));
			} else {
				store.save(incident);
			}
		}
	}

	/**
	 * The incident the last session persisted but could not report (it ended first): reported once the
	 * player is known, and cleared once that report was sent.
	 */
	private void reportPreviousIncident() {
		PreviousIncident previous = previousIncident;
		if (previous != null) {
			previous.report(errorReporter);
		}
	}

	private void stopCrashSafety() {
		Guard guard = Guard.shared();
		guard.setListener(null);
		guard.watchThread(null);
		if (watchdog != null) {
			watchdog.stop();
			watchdog = null;
		}
		// A clean stop: whatever this session persisted was reported live. An earlier session's
		// incident not sent yet stays for the next start.
		IncidentStore store = incidentStore;
		if (store != null) {
			PreviousIncident previous = previousIncident;
			if (previous == null || !previous.isStillStored()) {
				store.clear();
			}
			incidentStore = null;
		}
	}

	/**
	 * Runs one start or stop step; a failure is logged and reported (like the Guard's, without
	 * counting towards a breaker) and the next step still runs.
	 */
	private static void step(String name, Runnable body) {
		try {
			body.run();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable e) {
			Guard.shared().reportOnly("plugin-" + name.replace(' ', '-'), e);
		}
	}

	private void startFarming() {
		TimeTrackingConfig timeTrackingConfig = configManager.getConfig(TimeTrackingConfig.class);
		FarmingWorld farmingWorld = new FarmingWorld();
		CompostTracker compostTracker = new CompostTracker(client, farmingWorld, configManager);
		PaymentTracker paymentTracker = new PaymentTracker(client, configManager, farmingWorld);
		FarmingTracker farmingTracker = new FarmingTracker(
				client,
				itemManager,
				configManager,
				timeTrackingConfig,
				farmingWorld,
				notifier,
				compostTracker,
				paymentTracker);

		timerMonitor = new TimerMonitor(
				client, config, apiClient, configManager, GuardedExecutor.wrap(TimerMonitor.GUARD_FEATURE, executorService),
				farmingTracker, farmingWorld);
		farmHarvestTracker.setFarming(farmingWorld, farmingTracker, compostTracker);
	}

	/** Items emptied from a storage item into a deposit box, or from boat cargo into the bank, count as banked. */
	private void connectStorage() {
		storageItemMonitor.setEmptiedToDepositBoxListener(bankMemoryMonitor::onItemsBankedUnseen);
		vaultMonitor.setCargoBankedListener(bankMemoryMonitor::onItemsBankedUnseen);
	}

	@Override
	protected void shutDown() {
		// Never throws, and every step runs even when one before it failed: the executor jobs and the
		// watchdog always stop.
		if (subscribers != null) {
			for (Object subscriber : subscribers) {
				if (subscriber != null) {
					step("unregister " + subscriber.getClass().getSimpleName(), () -> eventBus.unregister(subscriber));
				}
			}
			subscribers = null;
		}
		step("farm harvests", farmHarvestTracker::stop);
		step("play sessions", sessionMonitor::stop);
		step("boss trips", bossTripTracker::stop);
		step("dailies", dailiesManager::clear);
		step("storage links", () -> {
			storageItemMonitor.setEmptiedToDepositBoxListener(null);
			vaultMonitor.setCargoBankedListener(null);
		});

		step("stop timerMonitor", () -> timerMonitor.stop());
		step("stop playerSkillsMonitor", () -> playerSkillsMonitor.stop());
		step("stop bankMemoryMonitor", () -> bankMemoryMonitor.stop());
		step("stop vaultMonitor", () -> vaultMonitor.stop());
		step("stop potionStorageMonitor", () -> potionStorageMonitor.stop());
		step("stop runePouchMonitor", () -> runePouchMonitor.stop());
		step("stop plankSackMonitor", () -> plankSackMonitor.stop());
		step("stop storageItemMonitor", () -> storageItemMonitor.stop());
		step("stop loadoutMonitor", () -> loadoutMonitor.stop());
		step("stop lootMonitor", () -> lootMonitor.stop());
		step("stop collectionLogMonitor", () -> collectionLogMonitor.stop());
		step("stop questMonitor", () -> questMonitor.stop());
		step("stop achievementDiaryMonitor", () -> achievementDiaryMonitor.stop());
		step("stop houseLocationMonitor", () -> houseLocationMonitor.stop());
		step("stop farmingUnlocksMonitor", () -> farmingUnlocksMonitor.stop());
		step("stop kingdomMonitor", () -> kingdomMonitor.stop());
		step("stop combatAchievementMonitor", () -> combatAchievementMonitor.stop());
		step("stop killCountMonitor", () -> killCountMonitor.stop());
		step("stop deathMonitor", () -> deathMonitor.stop());
		step("stop slayerMonitor", () -> slayerMonitor.stop());
		step("stop slayerCatalogMonitor", () -> slayerCatalogMonitor.stop());
		step("stop slayerRewardsMonitor", () -> slayerRewardsMonitor.stop());
		// "Offline" for the player, queued before the outbox's last flush below.
		step("live activity", liveActivityMonitor::stop);

		step("web updates", webVersionWatcher::stop);
		step("error reports", this::stopErrorReports);
		// The monitors' stop() above queued their last uploads: send them now; nothing is retried after.
		step("outbox", apiClient::stopOutbox);
		// Whatever periodic job a feature left behind (the shared executor is RuneLite's: only ours stop).
		step("executor jobs", GuardedExecutor::cancelAllPeriodic);
		step("crash safety", this::stopCrashSafety);
		log.debug("OSRS Companion stopped");
	}

	/**
	 * Starts collecting this plugin's errors for the website (only sent while "Send error reports" is
	 * on, an App Key is set and the world is not a special game mode).
	 */
	private void startErrorReports() {
		ErrorReporter reporter = new ErrorReporter(
				apiClient::sendErrorReports,
				System::currentTimeMillis,
				() -> config.sendErrorReports() && SyncGuard.hasAppKey(config) && !GameModeUtil.isSpecialGameMode(client),
				() -> SyncGuard.getPlayerUsername(client),
				OSRSCompanionApiClient.PLUGIN_VERSION,
				runeLiteVersion());
		errorReporter = reporter;
		apiClient.setErrorListener(new OSRSCompanionApiClient.ErrorListener() {
			@Override
			public void onUploadDropped(String label, String path, int httpStatus) {
				reporter.reportUploadDropped(label, path, httpStatus);
			}

			@Override
			public void onResponseParseFailed(String label, String path, String error) {
				reporter.reportParseFailure(label, path, error);
			}
		});
		errorLogAppender = new ErrorLogAppender(reporter);
		if (!errorLogAppender.attach()) {
			errorLogAppender = null;
		}
		long every = ErrorReporter.FLUSH_INTERVAL_MS;
		// Not through the Guard: the reporter's own failures are never reported. Never throws either,
		// so the periodic job cannot be cancelled by an exception.
		errorReportFlush = executorService.scheduleWithFixedDelay(() -> {
			try {
				reporter.flush();
			} catch (RuntimeException e) {
				log.debug("Error report flush failed", e);
			}
		}, every, every,
				java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	private void stopErrorReports() {
		if (errorLogAppender != null) {
			errorLogAppender.detach();
			errorLogAppender = null;
		}
		apiClient.setErrorListener(null);
		if (errorReportFlush != null) {
			errorReportFlush.cancel(false);
			errorReportFlush = null;
		}
		if (errorReporter != null) {
			// Queued in the outbox, which sends it with the last uploads.
			errorReporter.flush(true);
			errorReporter = null;
		}
	}

	private static String runeLiteVersion() {
		try {
			return net.runelite.client.RuneLiteProperties.getVersion();
		} catch (Throwable e) {
			return null;
		}
	}

	/**
	 * Re-pushes every login sync the monitors normally send (timers, skills, bank,
	 * loadout, loot). Used when an App Key is entered mid-session. Each monitor
	 * schedules its own work on the executor / client thread, so this never
	 * blocks the caller.
	 */
	private void forceSyncAll() {
		// This is an explicit user re-sync: clear the request-dedupe cache so an
		// unchanged payload still gets sent (the monitors each reset their own
		// dedupe state in forceSync(), but the client-side cache is separate).
		apiClient.clearDedupeCache();
		timerMonitor.forceSync();
		playerSkillsMonitor.forceSync();
		bankMemoryMonitor.forceSync();
		loadoutMonitor.forceSync();
		lootMonitor.forceSync();
		collectionLogMonitor.forceSync();
		questMonitor.forceSync();
		achievementDiaryMonitor.forceSync();
		houseLocationMonitor.forceSync();
		farmingUnlocksMonitor.forceSync();
		kingdomMonitor.forceSync();
		combatAchievementMonitor.forceSync();
		killCountMonitor.forceSync();
		deathMonitor.forceSync();
		slayerMonitor.forceSync();
		slayerCatalogMonitor.forceSync();
		dailiesManager.forceSync();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> gameStateChanged(event));
	}

	private void gameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGGED_IN) {
			reportPreviousIncident();
		} else if (event.getGameState() == GameState.LOGIN_SCREEN) {
			webVersionWatcher.onLoggedOut();
			// The monitors queue their logout uploads (some via the client thread / executor):
			// send everything shortly after, without waiting for the batch interval or a backoff.
			executorService.schedule(Guard.wrap(Guard.SYNC_FEATURE, apiClient::flushOutbox), 2, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	/**
	 * Fires a full re-sync the moment a complete App Key is entered while the
	 * player is already logged in. The per-monitor login syncs only run on the
	 * login transition, so users who paste their key mid-session would otherwise
	 * see nothing sync until their next login. Gated on a minimum key length so we
	 * don't fire on partial edits, and on being logged in (at the login screen the
	 * normal login sync already covers it).
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event) {
		Guard.run(GUARD_FEATURE, () -> configChanged(event));
	}

	private void configChanged(ConfigChanged event) {
		if (!SyncGuard.isCompleteAppKeyEntry(event.getGroup(), event.getKey(), event.getNewValue())) {
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		log.debug("OSRS Companion App Key entered while logged in; running full sync");
		forceSyncAll();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		Guard.run(GUARD_FEATURE, () -> chatMessage(event));
	}

	private void chatMessage(ChatMessage event) {
		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.DIALOG) {
			return;
		}
		String msg = event.getMessage();
		if (msg != null && msg.contains(TEARS_CAVE_MESSAGE)) {
			timerMonitor.onTearsOfGuthixCompleted();
		}
	}

	@Provides
	OSRSCompanionConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(OSRSCompanionConfig.class);
	}
}
