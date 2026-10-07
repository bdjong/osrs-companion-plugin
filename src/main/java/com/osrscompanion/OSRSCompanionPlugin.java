package com.osrscompanion;

import com.google.inject.Provides;
import com.osrscompanion.api.OSRSCompanionApiClient;
import java.awt.image.BufferedImage;
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
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.timetracking.TimeTrackingConfig;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import com.osrscompanion.runelite.farming.CompostTracker;
import com.osrscompanion.runelite.farming.FarmingTracker;
import com.osrscompanion.runelite.farming.FarmingWorld;
import com.osrscompanion.runelite.farming.PaymentTracker;

@Slf4j
// Bank Tags: its public API (BankTagsService, LayoutManager, TagManager) lays out the bank for the boss gear and farm run.
@PluginDependency(BankTagsPlugin.class)
@PluginDescriptor(name = "OSRS Companion", description = "Syncs Farming Timers, Bank, Skills, Loadout, Loot, Collection Log, Quest, Achievement Diary, Combat Achievement, and Boss Kill Count data to the OSRS Companion website.")
public class OSRSCompanionPlugin extends Plugin {
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
	private ClientToolbar clientToolbar;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private RoadmapManager roadmapManager;

	@Inject
	private NextGoalOverlay nextGoalOverlay;

	@Inject
	private GoalProgressTracker goalProgressTracker;

	@Inject
	private GoalCompletionNotifier goalCompletionNotifier;

	@Inject
	private GoalCompletionOverlay goalCompletionOverlay;

	@Inject
	private GoalImageCache goalImageCache;

	@Inject
	private SpriteManager spriteManager;

	@Inject
	private GearProgressionManager gearProgressionManager;

	@Inject
	private GearProgressionOverlay gearProgressionOverlay;

	@Inject
	private GearNextOverlay gearNextOverlay;

	@Inject
	private SessionMonitor sessionMonitor;

	@Inject
	private FarmRunManager farmRunManager;

	@Inject
	private FarmHarvestTracker farmHarvestTracker;

	@Inject
	private FarmRunOverlay farmRunOverlay;

	@Inject
	private FarmRunSceneOverlay farmRunSceneOverlay;

	@Inject
	private FarmRunItemOverlay farmRunItemOverlay;

	@Inject
	private FarmRunWindow farmRunWindow;

	@Inject
	private BankLayoutPrep bankLayoutPrep;

	@Inject
	private BossGearManager bossGearManager;

	@Inject
	private BossGearWindow bossGearWindow;

	@Inject
	private RoadmapWindow roadmapWindow;

	@Inject
	private BossTripTracker bossTripTracker;

	@Inject
	private SuppliesCheck suppliesCheck;

	@Inject
	private SuppliesOverlay suppliesOverlay;

	@Inject
	private ClientThread clientThread;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private DailiesManager dailiesManager;

	@Inject
	private DailiesOverlay dailiesOverlay;

	@Inject
	private DailiesSceneOverlay dailiesSceneOverlay;


	private RoadmapPanel roadmapPanel;
	private NavigationButton navButton;

	@Override
	protected void startUp() throws Exception {
		log.debug("OSRS Companion started");

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
				client, config, apiClient, configManager, executorService,
				farmingTracker, farmingWorld);
		farmRunManager.setFarming(farmingWorld, farmingTracker, compostTracker);
		farmHarvestTracker.setFarming(farmingWorld, farmingTracker, compostTracker);

		eventBus.register(this);
		eventBus.register(timerMonitor);
		eventBus.register(playerSkillsMonitor);
		eventBus.register(bankMemoryMonitor);
		eventBus.register(vaultMonitor);
		eventBus.register(potionStorageMonitor);
		eventBus.register(runePouchMonitor);
		eventBus.register(plankSackMonitor);
		eventBus.register(storageItemMonitor);
		eventBus.register(loadoutMonitor);
		eventBus.register(lootMonitor);
		eventBus.register(collectionLogMonitor);
		eventBus.register(questMonitor);
		eventBus.register(achievementDiaryMonitor);
		eventBus.register(houseLocationMonitor);
		eventBus.register(farmingUnlocksMonitor);
		eventBus.register(kingdomMonitor);
		eventBus.register(combatAchievementMonitor);
		eventBus.register(killCountMonitor);
		eventBus.register(deathMonitor);
		eventBus.register(slayerMonitor);
		eventBus.register(slayerCatalogMonitor);
		eventBus.register(slayerRewardsMonitor);
		eventBus.register(goalProgressTracker);
		eventBus.register(farmRunManager);
		eventBus.register(farmHarvestTracker);
		eventBus.register(bankLayoutPrep);
		bankLayoutPrep.startUp();
		eventBus.register(farmRunSceneOverlay);
		eventBus.register(sessionMonitor);
		eventBus.register(bossTripTracker);
		eventBus.register(suppliesCheck);
		eventBus.register(dailiesManager);
		eventBus.register(dailiesSceneOverlay);

		timerMonitor.start();
		sessionMonitor.start();
		lootMonitor.start();
		playerSkillsMonitor.start();

		// Local goal progress: monitors report what they upload, the tracker
		// layers it on the server's roadmap and asks for a re-read afterwards.
		roadmapManager.setRoadmapListener(goalProgressTracker::onServerRoadmap);
		roadmapManager.setRoadmapSetListener(goalProgressTracker::retainRoadmaps);
		playerSkillsMonitor.setUploadListener(goalProgressTracker::onSkillsUploaded);
		lootMonitor.setDropListener(goalProgressTracker::onLootDrop);
		lootMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.LOOT));
		collectionLogMonitor.setObtainedListener(goalProgressTracker::onCollectionLogItemObtained);
		collectionLogMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.COLLECTION_LOG));
		questMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.QUESTS));
		questMonitor.setStatesListener(goalProgressTracker::onQuestStates);
		achievementDiaryMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.DIARIES));
		achievementDiaryMonitor.setReadListener(goalProgressTracker::onDiaryRead);
		combatAchievementMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.COMBAT_ACHIEVEMENTS));
		combatAchievementMonitor.setCompletedListener(goalProgressTracker::onCombatTasksCompleted);
		killCountMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.KILL_COUNTS));
		timerMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.TIMERS));
		timerMonitor.setTimersListener(goalProgressTracker::onFarmingTimers);
		bankMemoryMonitor.setSyncedListener(() -> goalProgressTracker.onSourceSynced(SyncSource.BANK));
		bankMemoryMonitor.setPayloadListener(goalProgressTracker::onBankPayload);
		vaultMonitor.setSnapshotListener(goalProgressTracker::onContainerSnapshot);
		bankMemoryMonitor.setGoalItems(goalProgressTracker::ownedGoalItemIds);
		vaultMonitor.setGoalItems(goalProgressTracker::ownedGoalItemIds);
		potionStorageMonitor.setGoalItems(goalProgressTracker::ownedGoalItemIds);
		runePouchMonitor.setGoalItems(goalProgressTracker::ownedGoalItemIds);
		plankSackMonitor.setGoalItems(goalProgressTracker::ownedGoalItemIds);
		storageItemMonitor.setGoalItems(goalProgressTracker::ownedGoalItemIds);
		storageItemMonitor.setSeedSupplier(goalProgressTracker::serverHeldFor);
		bankMemoryMonitor.setSeedSupplier(goalProgressTracker::serverHeldFor);
		storageItemMonitor.setEmptiedToDepositBoxListener(bankMemoryMonitor::onItemsBankedUnseen);
		vaultMonitor.setCargoBankedListener(bankMemoryMonitor::onItemsBankedUnseen);
		runePouchMonitor.setSnapshotListener(goalProgressTracker::onContainerSnapshot);
		plankSackMonitor.setSnapshotListener(goalProgressTracker::onContainerSnapshot);
		storageItemMonitor.setSnapshotListener(goalProgressTracker::onContainerSnapshot);
		goalProgressTracker.setStorageSyncNow(() -> {
			runePouchMonitor.syncNow();
			plankSackMonitor.syncNow();
			storageItemMonitor.syncNow();
		});
		roadmapManager.startPeriodicRefresh();

		// Side-panel roadmap tab.
		roadmapPanel = new RoadmapPanel(this::toggleRoadmap, gearProgressionManager::toggle, this::toggleFarmRun, this::toggleBossGear, itemManager,
				spriteManager, goalImageCache, config, configManager, roadmapManager, farmRunManager, bossGearManager, gearProgressionManager, clientThread,
				dailiesManager);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
				.tooltip("OSRS Companion Roadmaps")
				.icon(icon)
				.priority(7)
				.panel(roadmapPanel)
				.build();
		clientToolbar.addNavigation(navButton);

		// Next-goal game overlay (rendered only while showNextGoal is enabled), and the
		// gear roadmap's next step below it (showGearProgress).
		overlayManager.add(nextGoalOverlay);
		overlayManager.add(gearNextOverlay);
		// Roadmap progress drawn under the goal completion popup while it shows.
		overlayManager.add(goalCompletionOverlay);

		// Gear progression window: the side-panel button opens it, Escape closes it.
		overlayManager.add(gearProgressionOverlay);
		mouseManager.registerMouseListener(gearProgressionOverlay);
		mouseManager.registerMouseWheelListener(gearProgressionOverlay);
		keyManager.registerKeyListener(gearProgressionOverlay);

		// Farm run: the window (side-panel button), the step box, scene pointers and item highlights.
		overlayManager.add(farmRunWindow);
		mouseManager.registerMouseListener(farmRunWindow);
		mouseManager.registerMouseWheelListener(farmRunWindow);
		keyManager.registerKeyListener(farmRunWindow);
		overlayManager.add(farmRunOverlay);
		overlayManager.add(farmRunSceneOverlay);
		overlayManager.add(farmRunItemOverlay);

		// Boss gear: the window (side-panel button); the bank lays the set out while it is open.
		overlayManager.add(bossGearWindow);
		// Roadmap: the goal tree in its own window (side-panel tile).
		overlayManager.add(roadmapWindow);
		mouseManager.registerMouseListener(roadmapWindow);
		mouseManager.registerMouseWheelListener(roadmapWindow);
		keyManager.registerKeyListener(roadmapWindow);
		mouseManager.registerMouseListener(bossGearWindow);
		mouseManager.registerMouseWheelListener(bossGearWindow);
		keyManager.registerKeyListener(bossGearWindow);
		// Supplies check: beside the open bank.
		overlayManager.add(suppliesOverlay);

		// Dailies: the step box and the arrow / map mark of the guided run (started from the side panel).
		overlayManager.add(dailiesOverlay);
		overlayManager.add(dailiesSceneOverlay);
	}

	/** The roadmap, farm run and boss gear windows share the middle of the screen (and the bank): one at a time. */
	private void toggleFarmRun() {
		if (!farmRunManager.isOpen()) {
			bossGearManager.close();
			roadmapWindow.close();
		}
		farmRunManager.toggle();
	}

	private void toggleBossGear() {
		if (!bossGearManager.isOpen()) {
			farmRunManager.close();
			roadmapWindow.close();
		}
		bossGearManager.toggle();
	}

	private void toggleRoadmap() {
		if (!roadmapWindow.isOpen()) {
			farmRunManager.close();
			bossGearManager.close();
		}
		roadmapWindow.toggle();
	}

	@Override
	protected void shutDown() throws Exception {
		eventBus.unregister(this);
		eventBus.unregister(timerMonitor);
		eventBus.unregister(playerSkillsMonitor);
		eventBus.unregister(bankMemoryMonitor);
		eventBus.unregister(vaultMonitor);
		eventBus.unregister(potionStorageMonitor);
		eventBus.unregister(runePouchMonitor);
		eventBus.unregister(plankSackMonitor);
		eventBus.unregister(storageItemMonitor);
		eventBus.unregister(loadoutMonitor);
		eventBus.unregister(lootMonitor);
		eventBus.unregister(collectionLogMonitor);
		eventBus.unregister(questMonitor);
		eventBus.unregister(achievementDiaryMonitor);
		eventBus.unregister(houseLocationMonitor);
		eventBus.unregister(farmingUnlocksMonitor);
		eventBus.unregister(kingdomMonitor);
		eventBus.unregister(combatAchievementMonitor);
		eventBus.unregister(killCountMonitor);
		eventBus.unregister(deathMonitor);
		eventBus.unregister(slayerMonitor);
		eventBus.unregister(slayerCatalogMonitor);
		eventBus.unregister(slayerRewardsMonitor);
		eventBus.unregister(goalProgressTracker);
		eventBus.unregister(farmRunManager);
		eventBus.unregister(farmHarvestTracker);
		farmHarvestTracker.stop();
		eventBus.unregister(bankLayoutPrep);
		bankLayoutPrep.shutDown();
		eventBus.unregister(farmRunSceneOverlay);
		eventBus.unregister(sessionMonitor);
		sessionMonitor.stop();
		eventBus.unregister(bossTripTracker);
		bossTripTracker.stop();
		eventBus.unregister(suppliesCheck);
		suppliesCheck.clear();
		overlayManager.remove(suppliesOverlay);
		eventBus.unregister(dailiesManager);
		eventBus.unregister(dailiesSceneOverlay);
		overlayManager.remove(dailiesOverlay);
		overlayManager.remove(dailiesSceneOverlay);
		clientThread.invoke(dailiesSceneOverlay::clearPointers);
		dailiesManager.clear();

		roadmapManager.stopPeriodicRefresh();
		roadmapManager.setRoadmapListener(null);
		roadmapManager.setRoadmapSetListener(null);
		roadmapManager.setPanelListener(null);
		lootMonitor.setDropListener(null);
		lootMonitor.setSyncedListener(null);
		collectionLogMonitor.setObtainedListener(null);
		collectionLogMonitor.setSyncedListener(null);
		questMonitor.setSyncedListener(null);
		questMonitor.setStatesListener(null);
		achievementDiaryMonitor.setSyncedListener(null);
		achievementDiaryMonitor.setReadListener(null);
		combatAchievementMonitor.setSyncedListener(null);
		combatAchievementMonitor.setCompletedListener(null);
		killCountMonitor.setSyncedListener(null);
		timerMonitor.setSyncedListener(null);
		timerMonitor.setTimersListener(null);
		bankMemoryMonitor.setSyncedListener(null);
		bankMemoryMonitor.setPayloadListener(null);
		vaultMonitor.setSnapshotListener(null);
		bankMemoryMonitor.setGoalItems(null);
		vaultMonitor.setGoalItems(null);
		potionStorageMonitor.setGoalItems(null);
		runePouchMonitor.setGoalItems(null);
		plankSackMonitor.setGoalItems(null);
		storageItemMonitor.setGoalItems(null);
		storageItemMonitor.setSeedSupplier(null);
		bankMemoryMonitor.setSeedSupplier(null);
		storageItemMonitor.setEmptiedToDepositBoxListener(null);
		vaultMonitor.setCargoBankedListener(null);
		runePouchMonitor.setSnapshotListener(null);
		plankSackMonitor.setSnapshotListener(null);
		storageItemMonitor.setSnapshotListener(null);
		goalProgressTracker.setStorageSyncNow(null);
		goalCompletionNotifier.clear();
		goalProgressTracker.clear();
		goalImageCache.clear();

		timerMonitor.stop();
		playerSkillsMonitor.stop();
		bankMemoryMonitor.stop();
		vaultMonitor.stop();
		potionStorageMonitor.stop();
		runePouchMonitor.stop();
		plankSackMonitor.stop();
		storageItemMonitor.stop();
		loadoutMonitor.stop();
		lootMonitor.stop();
		collectionLogMonitor.stop();
		questMonitor.stop();
		achievementDiaryMonitor.stop();
		houseLocationMonitor.stop();
		farmingUnlocksMonitor.stop();
		kingdomMonitor.stop();
		combatAchievementMonitor.stop();
		killCountMonitor.stop();
		deathMonitor.stop();
		slayerMonitor.stop();
		slayerCatalogMonitor.stop();
		slayerRewardsMonitor.stop();

		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
		roadmapPanel = null;
		overlayManager.remove(nextGoalOverlay);
		overlayManager.remove(gearNextOverlay);
		overlayManager.remove(goalCompletionOverlay);
		overlayManager.remove(gearProgressionOverlay);
		mouseManager.unregisterMouseListener(gearProgressionOverlay);
		mouseManager.unregisterMouseWheelListener(gearProgressionOverlay);
		keyManager.unregisterKeyListener(gearProgressionOverlay);
		gearProgressionManager.clear();
		overlayManager.remove(farmRunWindow);
		mouseManager.unregisterMouseListener(farmRunWindow);
		mouseManager.unregisterMouseWheelListener(farmRunWindow);
		keyManager.unregisterKeyListener(farmRunWindow);
		overlayManager.remove(farmRunOverlay);
		overlayManager.remove(farmRunSceneOverlay);
		overlayManager.remove(farmRunItemOverlay);
		clientThread.invoke(farmRunSceneOverlay::clearPointers);
		farmRunManager.clear();
		overlayManager.remove(bossGearWindow);
		roadmapWindow.close();
		overlayManager.remove(roadmapWindow);
		mouseManager.unregisterMouseListener(roadmapWindow);
		mouseManager.unregisterMouseWheelListener(roadmapWindow);
		keyManager.unregisterKeyListener(roadmapWindow);
		mouseManager.unregisterMouseListener(bossGearWindow);
		mouseManager.unregisterMouseWheelListener(bossGearWindow);
		keyManager.unregisterKeyListener(bossGearWindow);
		bossGearManager.clear();
		roadmapManager.clear();
		log.debug("OSRS Companion stopped");
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
		if (event.getGameState() == GameState.LOGGED_IN) {
			// Load the roadmap almost immediately so it shows right after login
			// (a plain read, NOT a forced re-sync), then again once this session's
			// login syncs have landed so completion/progress is fresh.
			executorService.schedule(
					this::loadRoadmapQuietly, 1, java.util.concurrent.TimeUnit.SECONDS);
			executorService.schedule(
					this::loadRoadmapQuietly, 8, java.util.concurrent.TimeUnit.SECONDS);
			executorService.schedule(farmRunManager::refresh, 9, java.util.concurrent.TimeUnit.SECONDS);
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
		if (!SyncGuard.isCompleteAppKeyEntry(event.getGroup(), event.getKey(), event.getNewValue())) {
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN) {
			return;
		}
		log.debug("OSRS Companion App Key entered while logged in; running full sync");
		forceSyncAll();
	}

	/**
	 * Reloads the roadmaps without forcing a data re-sync: the list and every roadmap, which
	 * keeps the overlay and the roadmap window current.
	 */
	private void loadRoadmapQuietly() {
		roadmapManager.refreshAllQuietly();
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
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
