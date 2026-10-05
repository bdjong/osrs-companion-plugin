package com.osrscompanion;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OSRSCompanionConfig.CONFIG_GROUP)
public interface OSRSCompanionConfig extends Config {
	String CONFIG_GROUP = "osrscompanion";
	String APP_KEY = "osrsCompanionAppKey";

	@ConfigSection(
			name = "Data Syncing",
			description = "Which game data the plugin sends to the OSRS Companion website.",
			position = 1)
	String SYNC_SECTION = "syncSection";

	@ConfigSection(
			name = "Plugin Features",
			description = "In-game overlay, goal completion popup and sound.",
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
			position = 1)
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

	// --- Plugin Features ---

	@ConfigItem(
			keyName = "showNextGoal",
			name = "Show current goal overlay",
			description = "Show your current (next uncompleted) goal from the selected roadmap as an overlay in the game window.",
			section = FEATURES_SECTION,
			position = 0)
	default boolean showNextGoal() {
		return true;
	}

	@ConfigItem(
			keyName = "showGoalProgress",
			name = "Show goal progress bar",
			description = "Show a progress bar with current / target numbers under the goal in the overlay.",
			section = FEATURES_SECTION,
			position = 1)
	default boolean showGoalProgress() {
		return true;
	}

	@ConfigItem(
			keyName = "showGoalPopup",
			name = "Goal completion popup",
			description = "Show an in-game popup (like the collection log one) when you complete a roadmap goal.",
			section = FEATURES_SECTION,
			position = 2)
	default boolean showGoalPopup() {
		return true;
	}

	@ConfigItem(
			keyName = "goalCompleteSound",
			name = "Goal completion sound",
			description = "Sound to play when you complete a roadmap goal. Custom file: ~/.runelite/osrscompanion/goal-complete.wav",
			section = FEATURES_SECTION,
			position = 3)
	default GoalCompleteSound goalCompleteSound() {
		return GoalCompleteSound.IN_GAME;
	}
}
