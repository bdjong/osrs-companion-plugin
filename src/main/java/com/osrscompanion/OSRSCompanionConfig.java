package com.osrscompanion;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(OSRSCompanionConfig.CONFIG_GROUP)
public interface OSRSCompanionConfig extends Config {
	String CONFIG_GROUP = "osrscompanion";
	String APP_KEY = "osrsCompanionAppKey";
	/** The goal sound switch; replaced the old "goalCompleteSound" choice (In-game / Off). */
	String GOAL_SOUND_KEY = "goalSoundOn";
	/** The farming cape setting; replaced the "farmingCapeForHerbs" and "forceFarmingCape" switches. */
	String FARMING_CAPE_KEY = "farmingCapeMode";
	/** The trip planner per tool; replaced the one "suppliesCheck" switch. */
	String TRIP_PLANNER_BOSS_KEY = "tripPlannerBoss";
	String TRIP_PLANNER_FARM_KEY = "tripPlannerFarm";
	String TRIP_PLANNER_RUNES_KEY = "tripPlannerRunes";

	@ConfigSection(
			name = "Data Syncing",
			description = "Which game data the plugin sends to the OSRS Companion website.",
			position = 1)
	String SYNC_SECTION = "syncSection";

	@ConfigSection(
			name = "Plugin Features",
			description = "Goal sound and progress bar, the farming cape, teleport left clicks, popups and the bank layout options. The rest is switched in the side panel.",
			position = 2)
	String FEATURES_SECTION = "featuresSection";

	@ConfigItem(
			keyName = APP_KEY,
			name = "App Key",
			description = "API key from your account settings. Required to sync data.",
			position = 0,
			secret = true)
	default String osrsCompanionAppKey() {
		return "";
	}

	@ConfigItem(
			keyName = "apiBaseUrl",
			name = "API Base URL",
			description = "Base URL of the OSRS Companion website. Use http://localhost:3000 when testing the website locally.",
			hidden = true)
	default String apiBaseUrl() {
		return "https://osrs-companion-web.vercel.app";
	}

	// --- Data Syncing ---

	@ConfigItem(
			keyName = "syncTimeTracking",
			name = "Farming Time Tracking",
			description = "Sync farming patches and bird houses to OSRS Companion.",
			section = SYNC_SECTION,
			position = 0)
	default boolean syncTimeTracking() {
		return true;
	}

	@ConfigItem(
			keyName = "syncBankMemory",
			name = "Bank Memory",
			description = "Sync your bank, seed vault, looting bag, potion storage, boat cargo holds, rune pouch, plank sack and storage items (fish barrel, herb sack, log basket, gem bag, coal bag, seed box, ...) to OSRS Companion.",
			section = SYNC_SECTION,
			position = 1)
	default boolean syncBankMemory() {
		return true;
	}

	@ConfigItem(
			keyName = "syncCollectionLog",
			name = "Collection Log",
			description = "Sync your Collection Log to OSRS Companion. Reads automatically the first time you open your collection log each session, then updates as you unlock new items.",
			section = SYNC_SECTION,
			position = 2)
	default boolean syncCollectionLog() {
		return true;
	}

	@ConfigItem(
			keyName = "syncLoadouts",
			name = "Sync Loadouts",
			description = "Sync your active equipment and Inventory Setups loadouts to OSRS Companion.",
			section = SYNC_SECTION,
			position = 3)
	default boolean syncLoadouts() {
		return true;
	}

	@ConfigItem(
			keyName = "syncLoot",
			name = "Loot Tracking",
			description = "Sync loot drops and kill counts to OSRS Companion.",
			section = SYNC_SECTION,
			position = 4)
	default boolean syncLoot() {
		return true;
	}

	@ConfigItem(
			keyName = "syncQuests",
			name = "Quests",
			description = "Sync your quest progress to OSRS Companion. Reads on login and updates in real time as you complete quests.",
			section = SYNC_SECTION,
			position = 5)
	default boolean syncQuests() {
		return true;
	}

	@ConfigItem(
			keyName = "syncAchievementDiaries",
			name = "Achievement Diaries",
			description = "Sync your achievement diary completion to OSRS Companion. Reads on login and updates in real time as you complete diary tasks.",
			section = SYNC_SECTION,
			position = 6)
	default boolean syncAchievementDiaries() {
		return true;
	}

	@ConfigItem(
			keyName = "syncCombatAchievements",
			name = "Combat Achievements",
			description = "Sync your combat achievement completion to OSRS Companion. Reads on login and updates in real time as you complete combat achievements.",
			section = SYNC_SECTION,
			position = 7)
	default boolean syncCombatAchievements() {
		return true;
	}

	@ConfigItem(
			keyName = "syncKillCounts",
			name = "Boss Kill Counts",
			description = "Sync your boss kill counts to OSRS Companion, read from RuneLite's stored kill counts (the same source the !kc command uses). Updates on login and as you get new kills.",
			section = SYNC_SECTION,
			position = 8)
	default boolean syncKillCounts() {
		return true;
	}

	@ConfigItem(
			keyName = "syncSlayer",
			name = "Slayer",
			description = "Sync your slayer task, points, streak, block list and unlocks to OSRS Companion. Reads on login and updates as tasks progress.",
			section = SYNC_SECTION,
			position = 9)
	default boolean syncSlayer() {
		return true;
	}

	@ConfigItem(
			keyName = "syncHouseLocation",
			name = "House Location",
			description = "Sync where your player-owned house is to OSRS Companion so Farm Routes only suggest house teleports that land there. Reads on login and updates when you move house.",
			section = SYNC_SECTION,
			position = 10)
	default boolean syncHouseLocation() {
		return true;
	}

	@ConfigItem(
			keyName = "syncKingdom",
			name = "Kingdom of Miscellania",
			description = "Sync your Managing Miscellania approval rating, coffer and worker allocation to OSRS Companion. Reads on login and updates when they change.",
			section = SYNC_SECTION,
			position = 11)
	default boolean syncKingdom() {
		return true;
	}

	@ConfigItem(
			keyName = "syncDeaths",
			name = "Deaths",
			description = "Send your deaths (what killed you, where and when) to OSRS Companion. Off by default: the website does not store deaths yet.",
			section = SYNC_SECTION,
			position = 12)
	default boolean syncDeaths() {
		return false;
	}

	@ConfigItem(
			keyName = "syncFarmHarvests",
			name = "Farm Harvests",
			description = "Send each patch you pick clean (crop, how many, Farming cape, magic secateurs, compost, disease, anima plant, diaries and Farming level) and each dead crop you clear to OSRS Companion for the Harvest stats page.",
			section = SYNC_SECTION,
			position = 13)
	default boolean syncFarmHarvests() {
		return true;
	}

	@ConfigItem(
			keyName = "syncFarmRuns",
			name = "Farm runs",
			description = "When a guided farm run ends (complete, or stopped after at least one patch), send it to OSRS Companion for the Run history: route, time, patches done, Farming XP gained and the seeds planted. Its harvests are matched by time.",
			section = SYNC_SECTION,
			position = 14)
	default boolean syncFarmRuns() {
		return true;
	}

	// --- Plugin Features ---

	@ConfigItem(
			keyName = "showNextGoal",
			name = "Show roadmap box",
			description = "Show your current (next uncompleted) goal from the selected roadmap as a box in the game window.",
			hidden = true)
	default boolean showNextGoal() {
		return true;
	}

	@ConfigItem(
			keyName = "showGoalProgress",
			name = "Show goal progress bar",
			description = "Show a progress bar with current / target numbers under the goal in the roadmap box.",
			section = FEATURES_SECTION,
			position = 2)
	default boolean showGoalProgress() {
		return true;
	}

	@ConfigItem(
			keyName = "showGoalPopup",
			name = "Goal completion popup",
			description = "Show an in-game popup (like the collection log one) when you complete a roadmap goal.",
			hidden = true)
	default boolean showGoalPopup() {
		return true;
	}

	@ConfigItem(
			keyName = GOAL_SOUND_KEY,
			name = "Goal completion sound",
			description = "Play an in-game sound when you complete a roadmap goal.",
			section = FEATURES_SECTION,
			position = 4)
	default boolean goalCompleteSound() {
		return true;
	}

	@ConfigItem(
			keyName = "showGearProgress",
			name = "Show gear progression box",
			description = "Show the next step of your active gear roadmap as a box in the game window, next to the roadmap box.",
			hidden = true)
	default boolean showGearProgress() {
		return true;
	}



	@ConfigItem(
			keyName = "showFarmRun",
			name = "Show farm run guide",
			description = "While a farm run is started from the farm run window: the step box, the arrow and outlines on the patches, and highlighted items.",
			hidden = true)
	default boolean showFarmRun() {
		return true;
	}

	@ConfigItem(
			keyName = "farmRunBankFilter",
			name = "Farm run active in the bank",
			description = "While a farm run is going (started in the farm run window and not complete), the bank shows only what your route needs, laid out like the worn gear and the inventory (see Exact bank layout). Without a run going the bank is left alone. Click another bank tab to see everything: the bank then stays as you left it until you reopen it. Only one bank layout is active at a time: turning this on turns the Boss gear and Slayer gear bank layouts off.",
			hidden = true)
	default boolean farmRunBankFilter() {
		return true;
	}

	@ConfigItem(
			keyName = FARMING_CAPE_KEY,
			name = "Farming cape for herbs",
			description = "With 99 Farming, when the farm run has herbs. Remind: adds the Farming cape to the run (and the bank layout) and reminds you to put it on before you pick them, for the 5% extra herbs it gives. Force: as Remind, and until you wear the cape a herb patch to pick says WEAR Farming cape and a left click walks there instead of picking. Off: neither.",
			section = FEATURES_SECTION,
			position = 9)
	default FarmingCapeMode farmingCapeMode() {
		return FarmingCapeMode.FORCE;
	}

	@ConfigItem(
			keyName = "autoCapeTeleport",
			name = "Auto set teleport left click",
			description = "During a farm run (and while the Slayer guide leads you to your task), the teleport item the current stop uses gets that teleport as its left click, worn or in the inventory: capes (Tele to POH, a town's house portal, the Farming Guild), the Ardougne cloak, Kharedst's memoirs / Book of the dead, jewellery and others.",
			section = FEATURES_SECTION,
			position = 9)
	default boolean autoCapeTeleport() {
		return true;
	}

	@ConfigItem(
			keyName = "slayerTaskPopup",
			name = "Slayer task complete popup",
			description = "Show an in-game popup when you complete a slayer task: the task, its kills and the Slayer XP you gained on it.",
			section = FEATURES_SECTION,
			position = 10)
	default boolean slayerTaskPopup() {
		return true;
	}

	@ConfigItem(
			keyName = "farmRunPopup",
			name = "Farm run complete popup",
			description = "Show an in-game popup (like the goal completion one) when every patch of a started farm run is done; the run then stops by itself.",
			section = FEATURES_SECTION,
			position = 10)
	default boolean farmRunPopup() {
		return true;
	}

	@ConfigItem(
			keyName = "slayerGuide",
			name = "Slayer guide",
			description = "With a slayer task: guides you to the task's best location (or the one picked on the website) with a step box, the arrow and a Slayer icon on the world map (click it for the way there). Once you are there the box shows the task's live stats instead. Also switched in the side panel's Slayer helper page.",
			hidden = true)
	default boolean slayerGuide() {
		return false;
	}

	@ConfigItem(
			keyName = "slayerGearBankLayout",
			name = "Slayer gear bank layout",
			description = "With a slayer task, when you open the bank, it shows only the worn gear of the setup picked in the Slayer gear window (the best until you pick one), laid out like the worn equipment (see Exact bank layout). Click another bank tab to see everything: the bank then stays as you left it until you reopen it. Only one bank layout is active at a time: turning this on turns the Boss gear and Farm run bank layouts off.",
			hidden = true)
	default boolean slayerGearBankLayout() {
		return false;
	}

	@Range(min = 0, max = 5)
	@ConfigItem(
			keyName = "slayerGearSupplyOptions",
			name = "Gear: ammo / spell options",
			description = "How many ammo or spell options the Slayer gear and Boss gear bank layouts show, best first: the setup's numbers use the best (e.g. dragon darts in a blowpipe), the bank also shows downgrades you own (amethyst, rune darts...), and for magic the runes of the best spells you can cast (e.g. Fire Surge, Fire Wave, Fire Blast). 0: none.",
			section = FEATURES_SECTION,
			position = 11)
	default int slayerGearSupplyOptions() {
		return 3;
	}


	@ConfigItem(
			keyName = "bossGearBankLayout",
			name = "Boss gear bank layout",
			description = "While the setup is Active, when you open the bank, it shows only your boss setup's items, laid out like the worn gear and the inventory (see Exact bank layout), with or without the window open. Click another bank tab to see everything: the bank then stays as you left it until you reopen it. Only one bank layout is active at a time: turning this on turns the Farm run and Slayer gear bank layouts off.",
			hidden = true)
	default boolean bossGearBankLayout() {
		return true;
	}

	@ConfigItem(
			keyName = "bankLayoutPositions",
			name = "Exact bank layout",
			description = "For the boss gear, farm run and slayer gear bank layouts: put each item where it goes (gear in the equipment doll's shape, the inventory as 4 x 7, the rune pouch under the doll), like Inventory Setups. Needs the Bank Tags plugin; off only filters the bank (no positions).",
			section = FEATURES_SECTION,
			position = 13)
	default boolean bankLayoutPositions() {
		return true;
	}

	@ConfigItem(
			keyName = "bossGearPanelDisplay",
			name = "Boss gear in side panel",
			description = "Shows the active setup's worn gear, inventory and rune pouch in the side panel, coloured by what you carry.",
			hidden = true)
	default boolean bossGearPanelDisplay() {
		return false;
	}

	/** Set from the side panel's Active / Inactive switch: Inactive keeps the setup selected but leaves the bank alone. */
	@ConfigItem(
			keyName = "bossGearActive",
			name = "Boss gear setup active",
			description = "Off (Inactive): the setup stays selected, but the bank is left alone and no gear is shown.",
			hidden = true)
	default boolean bossGearActive() {
		return true;
	}

	// --- Boss trips (the trip planner is switched in the side panel) ---

	@ConfigItem(
			keyName = "bossTrips",
			name = "Boss trip log",
			description = "Record each boss trip (from leaving the bank to the next bank, death or logout): kills, supplies used and loot, and send it to the website's Boss Gear page (Trips).",
			section = SYNC_SECTION,
			position = 15)
	default boolean bossTrips() {
		return true;
	}

	/** Set from the side panel's Trip planner: the boss setup's trips in the bank. */
	@ConfigItem(
			keyName = TRIP_PLANNER_BOSS_KEY,
			name = "Trip planner: boss trips",
			description = "With the bank open: how many trips your bank supports for the active boss setup and which supply runs out first (no bank layout needed).",
			hidden = true)
	default boolean tripPlannerBoss() {
		return true;
	}

	/** Set from the side panel's Trip planner: the farm route's runs in the bank. */
	@ConfigItem(
			keyName = TRIP_PLANNER_FARM_KEY,
			name = "Trip planner: farm runs",
			description = "With the bank open: how many runs your bank supports for the farm route and which supply runs out first (no bank layout needed).",
			hidden = true)
	default boolean tripPlannerFarm() {
		return true;
	}

	/** Set from the side panel's Trip planner: whether runes count towards the trips (off: they never limit them). */
	@ConfigItem(
			keyName = TRIP_PLANNER_RUNES_KEY,
			name = "Trip planner: count runes",
			description = "Whether runes count towards how many trips / runs your bank supports.",
			hidden = true)
	default boolean tripPlannerRunes() {
		return true;
	}

	// --- Dailies ---

	@ConfigSection(
			name = "Dailies",
			description = "Daily and weekly tasks (battlestaves, essence, sand...): tracking and the guided run.",
			position = 5)
	String DAILIES_SECTION = "dailiesSection";

	@ConfigItem(
			keyName = "syncDailies",
			name = "Sync dailies",
			description = "Send which dailies are done, open or locked to OSRS Companion (Tools > Dailies). Reads on login and when you collect one.",
			section = DAILIES_SECTION,
			position = 0)
	default boolean syncDailies() {
		return true;
	}

	@ConfigItem(
			keyName = "showDailiesGuide",
			name = "Show dailies guide",
			description = "While a dailies run is started from the side panel: the step box (teleport, where to go, who to talk to), the arrow to the NPC and its mark on the world map.",
			hidden = true)
	default boolean showDailiesGuide() {
		return true;
	}

	/** Dailies ticked by hand today: "<UTC day>:id,id" (set from the side panel). */
	@ConfigItem(
			keyName = "dailiesTicked",
			name = "Dailies ticked by hand",
			description = "Dailies you ticked off yourself today.",
			hidden = true)
	default String dailiesTicked() {
		return "";
	}
}
