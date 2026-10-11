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

Adds **OSRS Companion**: it syncs the player's own game data to the OSRS Companion website (a third-party site, `https://osrs-companion-web.vercel.app`) and shows the player's roadmap goals, gear progression, farm run guide, boss gear and slayer helper in game.

### Third-party website and data

- Nothing is sent until the player enters an App Key from their website account. RuneLite shows a confirmation (`@ConfigItem(warning = ...)`) that names the third-party server before the key is set.
- Every data source has its own switch under **Data Syncing**. **Share live activity** and **Send error reports** are off by default.
- Only the player's own data is sent, to their own account. Nothing about other players is collected. Special game-mode worlds (Leagues, Deadman...) are skipped.
- The README's "Data sent and privacy" section lists everything that is sent and downloaded. Players can delete their account and all data on the website.

### Things worth a look

- **`client.menuAction` (collection log).** `CollectionLogMonitor.triggerFullRead` calls `client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)` and then `client.runScript(2240)`, exactly as WikiSync does, so the game draws every obtained item and the plugin can read the whole log. It runs once per session, only when the player opens the collection log themselves, with Collection Log sync on and an App Key set. It is client-side only and sends no game action to the server. This is the only `menuAction` call in the plugin.
- **Menu swaps (off by default).** "Auto set teleport left click" moves the teleport option the current farm run or Slayer guide step uses to the left click of that item, the way Menu Entry Swapper does, and adds a top-level copy of a sub-menu teleport (Max cape "Teleports") like Menu Entry Swapper's sub-menu swaps. "Farming cape for herbs: Force" deprioritises "Pick" on ready herb patches until the Farming cape is worn. Both are off (Remind) by default; nothing is removed, and nothing touches combat, PvP or other players.
- **Hint arrows.** The farm run, Slayer and dailies guides set the client's hint arrow to the next stop and only ever clear an arrow they set themselves.
- **Bank layouts.** Built on the Bank Tags API (`TagManager`, `LayoutManager`, `BankTagsService`), as Inventory Setups does. The plugin reads (never writes) other plugins' settings: Inventory Setups loadouts, the loot tracker and Charges Improved storage, and the Bank Tag Layouts plugin's "Enable layout by default", to tell the player once in the chat that turning that plugin off gives our layout instead of its re-sorted one.
- **Threads.** All network calls are asynchronous (OkHttp `enqueue`, from the injected `OkHttpClient` and `Gson`). The plugin has two small daemon threads of its own: roadmap refreshes (ended after 30 s idle) and a watchdog that switches off one of the plugin's own features if its code blocks the client thread for more than 2 seconds (it only looks at the plugin's own guarded sections).
- **Vendored code.** `com.osrscompanion.runelite.farming` / `.hunter` are RuneLite's time-tracking classes (BSD 2-Clause, notices kept, see THIRD_PARTY_NOTICES.txt) so each patch's timer can be named.

### Build

`build=standard`; only the RuneLite client and Lombok. `./gradlew build` passes, including the unit tests.
