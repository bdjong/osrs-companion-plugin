package com.osrscompanion;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(OSRSCompanionConfig.CONFIG_GROUP)
public interface OSRSCompanionConfig extends Config {
	String CONFIG_GROUP = "osrscompanion";
	String APP_KEY = "osrsCompanionAppKey";
	/**
	 * Shown by RuneLite before the App Key is changed: entering it starts sending game data to a
	 * third-party server, which the Plugin Hub requires plugins to disclose.
	 */
	String APP_KEY_WARNING = "With an App Key this plugin sends your account's game data (your RuneScape name, skills, bank and other"
		+ " storage, collection log, quests, diaries, combat achievements, kill counts, loot, slayer, farming, your online status and"
		+ " current activity, and the other items switched on under Data Syncing) to the OSRS Companion website (osrs-companion-web.vercel.app). This is a third-party"
		+ " server that is not run or checked by the RuneLite developers. Continue?";
	/** Sends this plugin's errors to the website (see {@link ErrorReporter}). */
	String SEND_ERROR_REPORTS_KEY = "sendErrorReports";
	/** Shares the live activity status (see {@link LiveActivityMonitor}). */
	String SHARE_LIVE_ACTIVITY_KEY = "shareLiveActivity";

	@ConfigSection(
			name = "Data Syncing",
			description = "Which game data the plugin sends to the OSRS Companion website, a third-party server not run by RuneLite. Nothing is sent without an App Key.",
			position = 1)
	String SYNC_SECTION = "syncSection";

	@ConfigItem(
			keyName = APP_KEY,
			name = "App Key",
			description = "API key from your account settings on the OSRS Companion website. Required to sync data: without it the plugin sends nothing.",
			// The App Key is what turns sending on, so the Plugin Hub's third-party disclosure sits here.
			warning = APP_KEY_WARNING,
			position = 0,
			secret = true)
	default String osrsCompanionAppKey() {
		return "";
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
			keyName = SEND_ERROR_REPORTS_KEY,
			name = "Send error reports",
			description = "When this plugin hits an error, send a short report to OSRS Companion so the bug can be found and fixed: the error message and stack trace of this plugin's own code, which feature it happened in, the plugin and RuneLite versions and your character name. Nothing else is sent.",
			section = SYNC_SECTION,
			position = 15)
	default boolean sendErrorReports() {
		// Off until the player chooses to help with bug reports (telemetry is opt-in).
		return false;
	}

	@ConfigItem(
			keyName = SHARE_LIVE_ACTIVITY_KEY,
			name = "Share live activity",
			description = "While you are logged in, share your online status, your current activity (for example a raid, boss, slayer task, farm run, skill or banking, or idle), your world and the area name with OSRS Companion, so you and the site admin can see what you are doing right now. Off: nothing new is sent, and the website shows you as offline after about half a minute.",
			section = SYNC_SECTION,
			position = 16)
	default boolean shareLiveActivity() {
		// Off until the player turns it on: it shares their world and area every few seconds.
		return false;
	}

	@ConfigItem(
			keyName = "syncSkills",
			name = "Skills & XP",
			description = "Sync your levels and XP (with your total and combat level and account type) to OSRS Companion, for your XP history, goals and the tools that check your levels.",
			section = SYNC_SECTION,
			position = 17)
	default boolean syncSkills() {
		return true;
	}

	@ConfigItem(
			keyName = "syncSessions",
			name = "Play sessions",
			description = "Tell OSRS Companion when you log in and out (and a ping every few minutes while you play), for your play time and the session summary on your home page.",
			section = SYNC_SECTION,
			position = 18)
	default boolean syncSessions() {
		return true;
	}

	// --- Boss trips ---

	@ConfigItem(
			keyName = "bossTrips",
			name = "Boss trip log",
			description = "Record each boss trip (from leaving the bank to the next bank, death or logout): kills, supplies used and loot, and send it to the website's Boss Gear page (Trips).",
			section = SYNC_SECTION,
			position = 15)
	default boolean bossTrips() {
		return true;
	}

	@ConfigItem(
			keyName = "bossHistory",
			name = "Boss history",
			description = "Record each visit to a boss (entering its lair until you leave it for good): trips, bank visits, kills, kill times, deaths, every drop with its value and the supplies used, and send it to the website's Boss Gear page (Boss history).",
			section = SYNC_SECTION,
			position = 17)
	default boolean bossHistory() {
		return true;
	}

	// --- Dailies ---

	@ConfigSection(
			name = "Dailies",
			description = "Daily and weekly tasks (battlestaves, essence, sand...): which are done, open or locked.",
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

	/** Dailies ticked off by hand: "id=until,id=until" (kept so they still count after a restart). */
	@ConfigItem(
			keyName = "dailiesTicked",
			name = "Dailies ticked by hand",
			description = "Dailies you ticked off yourself today.",
			hidden = true)
	default String dailiesTicked() {
		return "";
	}
}
