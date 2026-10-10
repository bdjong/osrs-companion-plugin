# OSRS Companion

A RuneLite plugin that syncs your game data (farming timers, bank, skills, loadouts, loot, collection log, quests, diaries, combat achievements, boss kill counts, slayer and your Kingdom of Miscellania) to the OSRS Companion website, and shows your current roadmap goal in game.

## Settings

Settings are grouped into **Data Syncing** (which data the plugin sends to the app; one toggle per source) and **Plugin Features** (the in-game current goal overlay with its progress bar, the goal completion popup and its sound). Your OSRS Companion App Key sits at the top.

The goal overlay updates as you play: XP, kills, drops and collection log unlocks move the bar immediately and the plugin syncs with the app in the background. The completion sound (an in-game sound effect, at your sound effect volume) can be switched off in the settings.

## Gear progression

Load the ironman gear progression on the website (Tools > Gear progression), then open it in game with the **Gear progression** button in the side panel. It shows the tiers like the Leagues menus: steps from left to right, items stacked in one column are on the same level. Select an item to mark it done or make it your current roadmap goal. Escape closes the window.

## Slayer helper

The **Slayer helper** tile in the side panel shows your task (kills left, master) and the best place to do it (or the one you picked on the website). Switch the **Guide** on and a step box, the hint arrow and a Slayer icon on the world map lead you there; click the icon for the way there in a popup. Once you are in the area the box shows the task's stats instead: time at the location, average kill time (each monster timed on its own, so barraging stays valid), kills per hour, estimated time left and loot. Each visit is sent to the website (with Slayer syncing on), which charts it per task.

## Data sent and privacy

This plugin sends data to a **third-party server**: the OSRS Companion website at `https://osrs-companion-web.vercel.app`. It is not run or checked by the RuneLite developers.

- **Nothing is sent until you enter an App Key** (from your account settings on the website). RuneLite asks you to confirm when you set it. Clear the App Key to stop all sending.
- Every request carries your App Key (to link it to your website account) and your RuneScape name.
- Each source under **Data Syncing** has its own switch. With the App Key set, the plugin sends: skill levels and XP (with your total and combat level and account type); play sessions (login, logout and a ping while you play); farming patches, bird houses and farming unlocks; bank, seed vault, looting bag, potion storage, boat cargo holds, rune pouch, plank sack and storage items; the collection log; worn gear and your Inventory Setups loadouts; loot drops and kill counts (with a random per-install client id that is used to deduplicate them); quests; achievement diaries; combat achievements; boss kill counts; slayer task, points, unlocks and visits; boss trips (supplies used and loot per trip) and the boss history (each visit to a boss: its trips, bank visits, kills, kill times, deaths, drops with their GE value, the supplies used and the map regions the kills happened in); your house location; Kingdom of Miscellania; farm harvests and farm runs; dailies. These are on by default once you set an App Key; **Deaths** is off by default.
- **Share live activity** (**off** by default, under Data Syncing): when you switch it on, it sends while you are logged in your online or idle status, your current activity (a raid or minigame, boss, slayer task, farm run, the NPC you fight, the skill you train with the item it gives, banking, or idle; for Magic the spell: where you teleported to, the item you high alch, the monster you train Magic on), how long you have been at it, your world and the area name (or map region). It goes along with the other uploads, or on its own about every 15 seconds when nothing else was sent, and "offline" when you log out. You and the website's admin can see it. Switch it off and nothing more is sent; the website then shows you as offline.
- **Send error reports** (**off** by default, under Data Syncing): when you switch it on and this plugin hits an error, or one of its features blocks the game for more than 2 seconds, it sends a short report: the error message, the stack trace of this plugin's own code (other code is cut off after the first line), the feature it happened in, the plugin and RuneLite versions and your character name. The feature that failed is switched off for the session either way. Nothing else is sent.
- The plugin also loads your roadmaps, gear plans, farm routes and slayer guide from the website, the boss lair map regions (at most every 6 hours), and goal images from the OSRS Wiki (`oldschool.runescape.wiki`).
- The plugin skips syncing on Leagues, Deadman and other special game-mode worlds. It collects no data about other players.
- You can delete your account on the website (Settings): that removes your email, your characters and all their data.
- The website's [privacy page](https://osrs-companion-web.vercel.app/privacy) explains what is stored, who can see it and how long it is kept.

## Collection log: why the plugin opens the log's search

The game only draws the collection log items of the page you look at. To read the whole log at once, the plugin does what the [WikiSync](https://github.com/weirdgloop/WikiSync) plugin does: the first time you open your collection log in a session (with **Collection Log** syncing on and an App Key set), it calls `client.menuAction` once for the log's own **Search** button and runs the client's search script. That makes the game draw every item you have, which the plugin reads. It is a client-side action on an interface you opened yourself; it sends no game action to the server, clicks nothing else, and never runs on its own outside that moment.

## Settings that change clicks (off by default)

- **Auto set teleport left click** (Plugin Features): during a farm run or a Slayer guide step, the teleport the step uses becomes the left click of that item, the way RuneLite's Menu Entry Swapper swaps options. Off by default.
- **Farming cape for herbs** (Plugin Features): **Remind** (the default) adds the cape to the run and reminds you to wear it. **Force** also makes a left click on a ready herb patch walk instead of pick until you wear the cape. **Off** does neither.

## Bank Tag Layouts plugin

The boss, farm run and slayer gear bank layouts use RuneLite's built-in Bank Tags layouts. If you also run the **Bank Tag Layouts** plugin from the Plugin Hub with **Enable layout by default** on, it re-sorts our layout into its own order (and keeps that order for good): the bank still shows only the items you need, just not in their slots. OSRS Companion never changes another plugin's settings, so it tells you once in the chat that turning the Bank Tag Layouts plugin off gives our neater layout.

## Plugin Hub release: what still has to be done

Done for 1.0: the repository is public; the dev launcher kit (`friend/`, `run-dev.bat`) lives on the `development` branch only; the defaults above; switches for every data source; a fixed website address; the whole-client stall watchdog is removed; version `1.0` (set in `src/main/resources/com/osrscompanion/version.properties`, no `-dev` builds); author `bdjong`; the plugin no longer writes the Bank Tag Layouts plugin's settings.

Still to do, in this order:

1. **Test in game** on `development` (see [DEVELOPMENT.md](DEVELOPMENT.md#testing-a-release)): syncing with an App Key, each Data Syncing switch, the side panel tools, the farm run and Slayer guides, the bank layouts (with and without the Bank Tag Layouts plugin), the collection log sync and the new defaults on a fresh profile.
2. **Check the review points again** against the release commit, using [docs/plugin-hub-review.md](docs/plugin-hub-review.md) (the 29 rules and the owner decisions O1–O9). Still open from that review:
   - **O3 Licence notices in the hub jar**: the hub replaces `build.gradle`, so `LICENSE` and `THIRD_PARTY_NOTICES.txt` are not put in the jar. Accept it (the repository carries them) or move both into `src/main/resources/META-INF/osrs-companion/`.
   - **O4 Review size**: about 170 source files. Submit as one plugin (and explain it in the PR) or release the sync and roadmap first and the guides in later updates.
   - Re-check the sensitive APIs before submitting: the single `client.menuAction` (collection log), `client.runScript` uses, the menu swaps, hint arrows, and that nothing writes another plugin's settings.
3. **Website**: the privacy page and account deletion are on the website's `development` branch; they go live when it is merged into `main`.
4. **Release**: merge `development` into `main` in both repositories (the `friend/` kit stays out of `main`, see DEVELOPMENT.md), and check the GitHub Actions builds of those commits.
5. **Submit**: fork `runelite/plugin-hub`, add `plugins/osrs-companion` with `repository=https://github.com/bdjong/osrs-companion-plugin.git`, `commit=<the 40-character hash of that main commit>`, `authors=bdjong` and the `warning=` line (the third-party data disclosure RuneLite shows before install; the exact text is in docs/plugin-hub-pr.md), and open the pull request with the text in [docs/plugin-hub-pr.md](docs/plugin-hub-pr.md). Watch its build and review checks and answer the reviewers' questions in the PR.
6. **Every later update**: raise `version` in `version.properties` (1.1, 1.2 ...) in the commit the hub update points at.

## License

BSD 2-Clause, see [LICENSE](LICENSE). This plugin includes third-party code; see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) for their copyright notices and licenses.
