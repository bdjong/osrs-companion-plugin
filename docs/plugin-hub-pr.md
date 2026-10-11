# Plugin Hub pull request text (first submission)

Copy the part below the line into the pull request on `runelite/plugin-hub` that adds `plugins/osrs-companion`:

```
repository=https://github.com/bdjong/osrs-companion-plugin.git
commit=<40-character hash of the main commit to release>
authors=bdjong
warning=This plugin submits your RSN, skills, bank and other storage, collection log, quests, diaries, loot, slayer, farming and boss data to a 3rd-party server (OSRS Companion) not controlled or verified by the RuneLite developers, but only after you enter an App Key from your account on that website.
```

The `warning=` line is shown by RuneLite before someone installs the plugin; the hub asks for it on plugins that send data to a third-party server (other syncing plugins, such as Mystix, have one too).

---

## OSRS Companion

Adds **OSRS Companion**: it syncs the player's own game data (skills, bank and other storage, collection log, quests, diaries, combat achievements, loot and kill counts, slayer, farming timers and harvests, boss trips, dailies) to the OSRS Companion website, a third-party site (`https://osrs-companion-web.vercel.app`) where the player's roadmaps, gear progression, farm routes, boss gear and slayer advice use it.

### Third-party website and data

- Nothing is sent until the player enters an App Key from their website account. RuneLite shows a confirmation (`@ConfigItem(warning = ...)`) that names the third-party server before the key is set.
- Every data source has its own switch under **Data Syncing**. **Share live activity** and **Send error reports** are off by default.
- Only the player's own data is sent, to their own account. Nothing about other players is collected. Special game-mode worlds (Leagues, Deadman...) are skipped.
- The README's "Data sent and privacy" section lists everything that is sent and downloaded. Players can delete their account and all data on the website.

### Things worth a look

- **`client.menuAction` (collection log).** `CollectionLogMonitor.triggerFullRead` calls `client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)` and then `client.runScript(2240)`, exactly as WikiSync does, so the game draws every obtained item and the plugin can read the whole log. It runs once per session, only when the player opens the collection log themselves, with Collection Log sync on and an App Key set. It is client-side only and sends no game action to the server. This is the only `menuAction` call in the plugin.
- **Other client scripts (read only).** `QuestMonitor` runs `QUEST_STATUS_GET` to read each quest's state and `PotionStorageMonitor` runs `POTIONSTORE_DOSES` to read the potion storage's doses.
- **No menu changes, overlays or hint arrows.** The plugin only reads game state and sends it; it changes nothing in the game. Other plugins' settings are only read (Inventory Setups loadouts, the kill counts RuneLite stores for `!kc` and Charges Improved storage), never written.
- **Threads.** All network calls are asynchronous (OkHttp `enqueue`, from the injected `OkHttpClient` and `Gson`). The plugin has one small daemon thread of its own: a watchdog that switches off one of the plugin's own features if its code blocks the client thread for more than 2 seconds (it only looks at the plugin's own guarded sections).
- **Vendored code.** `com.osrscompanion.runelite.farming` / `.hunter` are RuneLite's time-tracking classes (BSD 2-Clause, notices kept, see THIRD_PARTY_NOTICES.txt) so each farming patch can be named and read.

### Build

`build=standard`; only the RuneLite client and Lombok. `./gradlew build` passes, including the unit tests. The review size is about 185,000 tokens (estimated by `PluginSizeTest`).
