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
import net.runelite.api.events.*;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

@Slf4j
// Bank Tags: its public API (BankTagsService, LayoutManager, TagManager) lays out the bank for the boss gear and farm run.
@PluginDependency(BankTagsPlugin.class)
@PluginDescriptor(
	name = "OSRS Companion",
	description = "Syncs your game data (with an App Key) to the third-party OSRS Companion website and shows your roadmap goals, farm runs and slayer guide in game.",
	tags = {"farming", "timetracking", "roadmap", "goals", "slayer", "sync"}
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
	private LoadoutMonitor loadoutMonitor;

	@Inject
	private LootMonitor lootMonitor;

	@Inject
	private CollectionLogMonitor collectionLogMonitor;

	@Inject
	private QuestMonitor questMonitor;


	@Inject
	private VarPump varPump;


	@Inject
	private FarmingUnlocksMonitor farmingUnlocksMonitor;



	@Inject
	private KillCountMonitor killCountMonitor;


	@Inject
	private SlayerMonitor slayerMonitor;

	@Inject
	private SlayerCatalogMonitor slayerCatalogMonitor;

	@Inject
	private SlayerRewardsMonitor slayerRewardsMonitor;

	@Inject
	private EventBus eventBus;


	@Inject
	private com.google.gson.Gson gson;

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
	private SessionMonitor sessionMonitor;

	@Inject
	private FarmRunManager farmRunManager;


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
	private SlayerHelperManager slayerHelperManager;

	@Inject
	private SlayerHelperOverlay slayerHelperOverlay;

	@Inject
	private SlayerGuideMarkers slayerGuideMarkers;

	@Inject
	private SlayerGuideHouseOverlay slayerGuideHouseOverlay;

	@Inject
	private BossGearManager bossGearManager;

	@Inject
	private BossGearWindow bossGearWindow;

	@Inject
	private SlayerGearWindow slayerGearWindow;

	@Inject
	private GameEvents gameEvents;

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
	private WebVersionWatcher webVersionWatcher;

	@Inject
	private LiveActivityMonitor liveActivityMonitor;

	@Inject
	private ChatMessageManager chatMessageManager;

	private RoadmapPanel roadmapPanel;
	private NavigationButton navButton;
	private java.util.concurrent.ScheduledFuture<?> roadmapWaitingPoll;
	private java.util.concurrent.ScheduledFuture<?> farmRouteReadyPoll;
	/** Every object registered on the event bus at start, unregistered at stop. */
	private java.util.List<Object> subscribers;
	/** A player was logged in since the last login screen: later LOGGED_IN events are region loads. */
	private boolean sessionLoaded;

	@Override
	protected void startUp() {
		// Never throws: each subsystem starts on its own, so one failure leaves the others working.
		step("crash safety", this::startCrashSafety);
		step("settings", this::migrateSettings);
		// Uploads queue in the outbox and go out batched (first, so the monitors' first syncs use it).
		step("outbox", () -> apiClient.startOutbox(GuardedExecutor.wrap(Guard.SYNC_FEATURE, executorService)));
		step("farming timers", this::startFarming);

		subscribers = java.util.Arrays.asList(this, timerMonitor, playerSkillsMonitor, bankMemoryMonitor, vaultMonitor,
				potionStorageMonitor, runePouchMonitor, plankSackMonitor, loadoutMonitor, lootMonitor,
				collectionLogMonitor, questMonitor, varPump, farmingUnlocksMonitor,
				killCountMonitor, slayerMonitor, slayerCatalogMonitor,
				slayerRewardsMonitor, farmRunManager, bankLayoutPrep,
				farmRunSceneOverlay, slayerHelperManager, slayerGuideMarkers, slayerGuideHouseOverlay, sessionMonitor,
				gameEvents, suppliesCheck, liveActivityMonitor);
		for (Object subscriber : subscribers) {
			if (subscriber != null) {
				step("register " + subscriber.getClass().getSimpleName(), () -> eventBus.register(subscriber));
			}
		}
		step("bank layout", bankLayoutPrep::startUp);
		step("timers", timerMonitor::start);
		step("play sessions", sessionMonitor::start);
		step("loot", lootMonitor::start);
		step("skills", playerSkillsMonitor::start);
		step("goal progress", this::connectGoalProgress);
		step("roadmap", () -> {
			roadmapManager.startPeriodicRefresh();
			// On the roadmap's own thread (guarded there), never RuneLite's shared executor.
			roadmapWaitingPoll = roadmapManager.executor().scheduleWithFixedDelay(roadmapManager::pollWhileWaiting,
					RoadmapManager.WAITING_POLL_SECONDS, RoadmapManager.WAITING_POLL_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
		});
		// Live activity: every batch carries the status; the watcher below sends the heartbeat.
		step("live activity", liveActivityMonitor::start);
		// Website changes: the webVersion from batch answers or a small check every 30 s re-reads what changed.
		step("web updates", webVersionWatcher::start);
		// The last farm route ready again (all its patches grown), checked logged in or not.
		step("farm route ready", () -> farmRouteReadyPoll = executorService.scheduleWithFixedDelay(
				Guard.wrap(FarmRunManager.GUARD_FEATURE, farmRunManager::checkRouteReady), 30, 30, java.util.concurrent.TimeUnit.SECONDS));
		step("side panel", this::addSidePanel);
		step("overlays", this::addOverlays);
	}

	/** Every feature on again (a fresh session); one turned off after errors is told in the chat. */
	private void startCrashSafety() {
		Guard guard = Guard.shared();
		guard.resetBreakers();
		guard.setOnDisabled(feature -> chatMessageManager.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage("OSRS Companion: " + Guard.displayName(feature) + " was turned off after an error.")
				.build()));
	}

	/**
	 * Runs one start or stop step; a failure is logged (without counting towards a breaker) and the
	 * next step still runs.
	 */
	private static void step(String name, Runnable body) {
		try {
			body.run();
		} catch (ThreadDeath death) {
			throw death;
		} catch (Throwable e) {
			log.warn("OSRS Companion: {} failed", name, e);
		}
	}

	private void startFarming() {

		timerMonitor = new TimerMonitor(client, config, apiClient, configManager,
				GuardedExecutor.wrap(TimerMonitor.GUARD_FEATURE, executorService), gson);
	}

	/**
	 * Local goal progress: monitors report what they upload, the tracker
	 * layers it on the server's roadmap and asks for a re-read afterwards.
	 */
	private void connectGoalProgress() {
		roadmapManager.setRoadmapListener(goalProgressTracker::onServerRoadmap);
		roadmapManager.setRoadmapSetListener(goalProgressTracker::retainRoadmaps);
		vaultMonitor.setCargoBankedListener(bankMemoryMonitor::onItemsBankedUnseen);
	}

	private void addSidePanel() {
		// Side-panel roadmap tab.
		roadmapPanel = new RoadmapPanel(this::toggleFarmRun, this::toggleBossGear, this::toggleSlayerGear, itemManager,
				spriteManager, goalImageCache, config, configManager, roadmapManager, farmRunManager, bossGearManager,
				slayerHelperManager, clientThread);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "panel_icon.png");
		navButton = NavigationButton.builder()
				.tooltip("OSRS Companion Roadmaps")
				.icon(icon)
				.priority(7)
				.panel(roadmapPanel)
				.build();
		clientToolbar.addNavigation(navButton);
	}

	private void addOverlays() {
		// Next-goal game overlay (rendered only while showNextGoal is enabled).
		overlayManager.add(nextGoalOverlay);
		// Roadmap progress drawn under the goal completion popup while it shows.
		overlayManager.add(goalCompletionOverlay);

		// Farm run: the window (side-panel button), the step box, scene pointers and item highlights.
		overlayManager.add(farmRunWindow);
		mouseManager.registerMouseListener(farmRunWindow);
		mouseManager.registerMouseWheelListener(farmRunWindow);
		keyManager.registerKeyListener(farmRunWindow);
		overlayManager.add(farmRunOverlay);
		overlayManager.add(farmRunSceneOverlay);
		overlayManager.add(farmRunItemOverlay);

		// Slayer helper: the guide's box (stats at the location); the arrow and map icon come from the markers.
		overlayManager.add(slayerHelperOverlay);
		// In your house on the way: the jewellery box (portal, nexus...) the way there uses, outlined.
		overlayManager.add(slayerGuideHouseOverlay);

		// Boss gear: the window (side-panel button); the bank lays the set out while it is open.
		overlayManager.add(bossGearWindow);
		mouseManager.registerMouseListener(bossGearWindow);
		mouseManager.registerMouseWheelListener(bossGearWindow);
		keyManager.registerKeyListener(bossGearWindow);
		// Slayer gear: the DPS setups for the task (Slayer helper page tile); the picked one is what the bank lays out.
		overlayManager.add(slayerGearWindow);
		mouseManager.registerMouseListener(slayerGearWindow);
		mouseManager.registerMouseWheelListener(slayerGearWindow);
		keyManager.registerKeyListener(slayerGearWindow);
		// Supplies check: beside the open bank.
		overlayManager.add(suppliesOverlay);

	}

	/** The farm run, boss gear and slayer gear windows share the middle of the screen (and the bank): one at a time. */
	private void toggleFarmRun() {
		if (!farmRunManager.isOpen()) {
			bossGearManager.close();
			slayerHelperManager.closeGearWindow();
		}
		farmRunManager.toggle();
	}

	private void toggleBossGear() {
		if (!bossGearManager.isOpen()) {
			farmRunManager.close();
			slayerHelperManager.closeGearWindow();
		}
		bossGearManager.toggle();
	}

	private void toggleSlayerGear() {
		if (!slayerHelperManager.isGearOpen()) {
			farmRunManager.close();
			bossGearManager.close();
		}
		slayerHelperManager.toggleGearWindow();
	}

	@Override
	protected void shutDown() {
		// Never throws, and every step runs even when one before it failed: the executor jobs always stop.
		if (subscribers != null) {
			for (Object subscriber : subscribers) {
				if (subscriber != null) {
					step("unregister " + subscriber.getClass().getSimpleName(), () -> eventBus.unregister(subscriber));
				}
			}
			subscribers = null;
		}
		step("bank layout", bankLayoutPrep::shutDown);
		step("play sessions", sessionMonitor::stop);
		step("game events", gameEvents::stop);
		step("supplies", () -> {
			suppliesCheck.clear();
			overlayManager.remove(suppliesOverlay);
		});
		step("goal progress", this::disconnectGoalProgress);

		step("stop timerMonitor", () -> timerMonitor.stop());
		step("stop playerSkillsMonitor", () -> playerSkillsMonitor.stop());
		step("stop bankMemoryMonitor", () -> bankMemoryMonitor.stop());
		step("stop vaultMonitor", () -> vaultMonitor.stop());
		step("stop potionStorageMonitor", () -> potionStorageMonitor.stop());
		step("stop runePouchMonitor", () -> runePouchMonitor.stop());
		step("stop plankSackMonitor", () -> plankSackMonitor.stop());
		step("stop loadoutMonitor", () -> loadoutMonitor.stop());
		step("stop lootMonitor", () -> lootMonitor.stop());
		step("stop collectionLogMonitor", () -> collectionLogMonitor.stop());
		step("stop questMonitor", () -> questMonitor.stop());
		step("stop varPump", () -> varPump.stop());
		step("stop farmingUnlocksMonitor", () -> farmingUnlocksMonitor.stop());
		step("stop killCountMonitor", () -> killCountMonitor.stop());
		step("stop slayerMonitor", () -> slayerMonitor.stop());
		step("stop slayerCatalogMonitor", () -> slayerCatalogMonitor.stop());
		step("stop slayerRewardsMonitor", () -> slayerRewardsMonitor.stop());
		// "Offline" for the player, queued before the outbox's last flush below.
		step("live activity", liveActivityMonitor::stop);

		step("side panel", () -> {
			if (navButton != null) {
				clientToolbar.removeNavigation(navButton);
				navButton = null;
			}
			roadmapPanel = null;
		});
		step("overlays", this::removeOverlays);
		step("polls", () -> {
			if (roadmapWaitingPoll != null) {
				roadmapWaitingPoll.cancel(false);
				roadmapWaitingPoll = null;
			}
			if (farmRouteReadyPoll != null) {
				farmRouteReadyPoll.cancel(false);
				farmRouteReadyPoll = null;
			}
		});
		step("roadmap", roadmapManager::clear);
		step("web updates", webVersionWatcher::stop);
		// The monitors' stop() above queued their last uploads: send them now; nothing is retried after.
		step("outbox", apiClient::stopOutbox);
		// Whatever periodic job a feature left behind (the shared executor is RuneLite's: only ours stop).
		step("executor jobs", GuardedExecutor::cancelAllPeriodic);
		step("crash safety", () -> Guard.shared().setOnDisabled(null));
	}

	private void disconnectGoalProgress() {
		roadmapManager.stopPeriodicRefresh();
		roadmapManager.setRoadmapListener(null);
		roadmapManager.setRoadmapSetListener(null);
		roadmapManager.setPanelListener(null);
		vaultMonitor.setCargoBankedListener(null);
		goalCompletionNotifier.clear();
		goalProgressTracker.clear();
		goalImageCache.clear();
	}

	private void removeOverlays() {
		overlayManager.remove(nextGoalOverlay);
		overlayManager.remove(goalCompletionOverlay);
		overlayManager.remove(farmRunWindow);
		mouseManager.unregisterMouseListener(farmRunWindow);
		mouseManager.unregisterMouseWheelListener(farmRunWindow);
		keyManager.unregisterKeyListener(farmRunWindow);
		overlayManager.remove(farmRunOverlay);
		overlayManager.remove(farmRunSceneOverlay);
		overlayManager.remove(farmRunItemOverlay);
		clientThread.invoke(Guard.wrapCleanup(FarmRunSceneOverlay.GUARD_FEATURE,
				() -> step("farm run markers", farmRunSceneOverlay::clearPointers)));
		overlayManager.remove(slayerHelperOverlay);
		overlayManager.remove(slayerGuideHouseOverlay);
		// The open slayer session is stored and sent before it is forgotten.
		clientThread.invoke(Guard.wrapCleanup(SlayerHelperManager.GUARD_FEATURE, () -> step("slayer helper", () -> {
			slayerGuideMarkers.clear();
			slayerGuideHouseOverlay.clear();
			slayerHelperManager.shutDown();
		})));
		farmRunManager.clear();
		overlayManager.remove(bossGearWindow);
		mouseManager.unregisterMouseListener(bossGearWindow);
		mouseManager.unregisterMouseWheelListener(bossGearWindow);
		keyManager.unregisterKeyListener(bossGearWindow);
		slayerHelperManager.closeGearWindow();
		overlayManager.remove(slayerGearWindow);
		mouseManager.unregisterMouseListener(slayerGearWindow);
		mouseManager.unregisterMouseWheelListener(slayerGearWindow);
		keyManager.unregisterKeyListener(slayerGearWindow);
		bossGearManager.clear();
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
		varPump.forceSync();
		farmingUnlocksMonitor.forceSync();
		killCountMonitor.forceSync();
		slayerMonitor.forceSync();
		slayerCatalogMonitor.forceSync();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		Guard.run(GUARD_FEATURE, () -> gameStateChanged(event));
	}

	private void gameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOGIN_SCREEN) {
			sessionLoaded = false;
		}
		if (event.getGameState() == GameState.LOGGED_IN) {
			// LOGGED_IN also follows every region load (LOADING): the roadmap loads only on a real login.
			boolean login = !sessionLoaded;
			sessionLoaded = true;
			if (login) {
				// Load the roadmap almost immediately so it shows right after login
				// (a plain read, NOT a forced re-sync), then again once this session's
				// login syncs have landed so completion/progress is fresh. On the
				// roadmap's own thread, never RuneLite's shared executor.
				roadmapManager.executor().schedule(this::loadRoadmapQuietly, 1, java.util.concurrent.TimeUnit.SECONDS);
				roadmapManager.executor().schedule(this::loadRoadmapQuietly, 8, java.util.concurrent.TimeUnit.SECONDS);
			}
			executorService.schedule(Guard.wrap(FarmRunManager.GUARD_FEATURE, farmRunManager::refresh),
					9, java.util.concurrent.TimeUnit.SECONDS);
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

	/** Carries settings that were merged or renamed over to their new keys, once. */
	private void migrateSettings() {
		String group = OSRSCompanionConfig.CONFIG_GROUP;
		String oldSound = configManager.getConfiguration(group, "goalCompleteSound");
		if (oldSound != null) {
			if (configManager.getConfiguration(group, OSRSCompanionConfig.GOAL_SOUND_KEY) == null) {
				configManager.setConfiguration(group, OSRSCompanionConfig.GOAL_SOUND_KEY, !"OFF".equals(oldSound));
			}
			configManager.unsetConfiguration(group, "goalCompleteSound");
		}
		String herbs = configManager.getConfiguration(group, "farmingCapeForHerbs");
		String force = configManager.getConfiguration(group, "forceFarmingCape");
		if (herbs != null || force != null) {
			if (configManager.getConfiguration(group, OSRSCompanionConfig.FARMING_CAPE_KEY) == null) {
				// Force is the default now: only a switched-off reminder stays off.
				FarmingCapeMode mode = "false".equals(herbs) && !"true".equals(force) ? FarmingCapeMode.OFF : FarmingCapeMode.FORCE;
				configManager.setConfiguration(group, OSRSCompanionConfig.FARMING_CAPE_KEY, mode);
			}
			configManager.unsetConfiguration(group, "farmingCapeForHerbs");
			configManager.unsetConfiguration(group, "forceFarmingCape");
		}
		// Once: Remind (what the first merge of the two cape settings gave) becomes the new default, Force.
		if (configManager.getConfiguration(group, "farmingCapeForceDefault") == null) {
			if ("REMIND".equals(configManager.getConfiguration(group, OSRSCompanionConfig.FARMING_CAPE_KEY))) {
				configManager.setConfiguration(group, OSRSCompanionConfig.FARMING_CAPE_KEY, FarmingCapeMode.FORCE);
			}
			configManager.setConfiguration(group, "farmingCapeForceDefault", true);
		}
		String supplies = configManager.getConfiguration(group, "suppliesCheck");
		if (supplies != null) {
			boolean on = !"false".equals(supplies);
			for (String key : new String[]{OSRSCompanionConfig.TRIP_PLANNER_BOSS_KEY, OSRSCompanionConfig.TRIP_PLANNER_FARM_KEY}) {
				if (configManager.getConfiguration(group, key) == null) {
					configManager.setConfiguration(group, key, on);
				}
			}
			configManager.unsetConfiguration(group, "suppliesCheck");
		}
	}

	@Provides
	OSRSCompanionConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(OSRSCompanionConfig.class);
	}
}
