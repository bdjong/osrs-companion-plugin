# Plugin Hub pull request text

The `plugins/osrs-companion` file on `runelite/plugin-hub` (`commit=` is the 40-character hash of the `main` commit released):

```
repository=https://github.com/bdjong/osrs-companion-plugin.git
commit=<40-character hash of the main commit to release>
authors=bdjong
warning=This plugin submits your RSN, skills, bank and other storage, collection log, quests, diaries, loot, slayer, farming and boss data and game events (the game's messages, inventory changes, XP gains) to a 3rd-party server (OSRS Companion) not controlled or verified by the RuneLite developers, but only after you enter an App Key from your account on that website.
```

The `warning=` line is shown by RuneLite before someone installs the plugin; the hub asks for it on plugins that send data to a third-party server.

The pull request text (below the line):

---

## OSRS Companion

Adds **OSRS Companion**: it syncs the player's own game data to the OSRS Companion website (a third-party site, `https://osrs-companion.duckdns.org`) and brings the website's tools into the game: the current roadmap goal, a farm run guide, boss gear setups with bank layouts, a slayer helper and a trip planner.

This is a resubmission of #18254, which was closed because the plugin was over the 200,000-token review limit. It has been reworked to about 193,500 tokens (estimated by `PluginSizeTest`, which fails the build above 195,000): the calculations moved to the website, which works them out from the game data the plugin sends, and fixed tables are a data file (`src/main/resources/com/osrscompanion/tables.json`).

### Third-party website and data

- Nothing is sent until the player enters an App Key from their website account. RuneLite shows a confirmation (`@ConfigItem(warning = ...)`) that names the third-party server before the key is set.
- Every data source has its own switch under **Data Syncing**. **Share live activity** is off by default.
- Some features are worked out by the website from game events the plugin sends as they happen (`GameEvents`, in batches about every 30 s): the game's own messages (game and filtered messages only, never chat from players), inventory and worn items when they change, Cooking / Firemaking / Farming XP gains, the map region, the NPC the player attacks, loot, bank and deposit box opening, clicks on farming patches and storage items, and RuneLite's stored Time Tracking data. They are only sent with one of the switches that need them on (Boss trip log, Boss history, Farm Harvests, Slayer, Bank Memory).
- Only the player's own data is sent, to their own account. Nothing about other players is collected. Special game-mode worlds (Leagues, Deadman...) are skipped.
- The README's "Data sent and privacy" section lists everything that is sent and downloaded. Players can delete their account and all data on the website.

### Things worth a look

- **`client.menuAction` (collection log).** `CollectionLogMonitor.triggerFullRead` calls `client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)` and then `client.runScript(2240)`, exactly as WikiSync does, so the game draws every obtained item and the plugin can read the whole log. It runs once per session, only when the player opens the collection log themselves, with Collection Log sync on and an App Key set. It is client-side only and sends no game action to the server. This is the only `menuAction` call in the plugin.
- **Other client scripts (read only).** `QuestMonitor` runs `QUEST_STATUS_GET`, `PotionStorageMonitor` runs `POTIONSTORE_DOSES`, and `GoalCompletionNotifier` shows the game's own notification popup (`NOTIFICATION_DISPLAY_INIT`).
- **Menu swaps (off by default).** "Auto set teleport left click" moves the teleport option the current farm run or Slayer guide step uses to the left click of that item, the way Menu Entry Swapper does, and adds a top-level copy of a sub-menu teleport (Max cape "Teleports") like Menu Entry Swapper's sub-menu swaps. "Farming cape for herbs: Force" deprioritises "Pick" on ready herb patches until the Farming cape is worn (the default is Remind). Nothing is removed, and nothing touches combat, PvP or other players.
- **Hint arrows.** The farm run and Slayer guides set the client's hint arrow to the next stop and only ever clear an arrow they set themselves.
- **Bank layouts.** Built on the Bank Tags API (`TagManager`, `LayoutManager`, `BankTagsService`), as Inventory Setups does. The plugin reads (never writes) other plugins' settings: Inventory Setups loadouts, the loot tracker, Charges Improved storage and the Bank Tag Layouts plugin's "Enable layout by default" (to tell the player once in the chat that turning that plugin off gives our layout instead of its re-sorted one).
- **Threads.** All network calls are asynchronous (OkHttp `enqueue`, from the injected `OkHttpClient` and `Gson`). The plugin has one small daemon thread of its own, for roadmap refreshes (ended after 30 s idle).

### Build

`build=standard`; only the RuneLite client and Lombok. `./gradlew build` passes, including the unit tests (UI golden tests for every window and side panel page, and tests that check the website reproduces what the plugin's earlier code computed).
