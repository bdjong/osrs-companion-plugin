# OSRS Companion

A RuneLite plugin that syncs your game data (skills, bank and other storage, loadouts, loot, collection log, quests, diaries, combat achievements, boss kill counts and trips, slayer, farming timers and harvests, dailies and your Kingdom of Miscellania) to the OSRS Companion website, where your roadmaps, gear progression, farm routes, boss gear and slayer advice use it.

## Settings

Your OSRS Companion App Key sits at the top. **Data Syncing** has one switch per data source, and **Dailies** the dailies sync. Nothing is sent without an App Key.

## Data sent and privacy

This plugin sends data to a **third-party server**: the OSRS Companion website at `https://osrs-companion-web.vercel.app`. It is not run or checked by the RuneLite developers.

- **Nothing is sent until you enter an App Key** (from your account settings on the website). RuneLite asks you to confirm when you set it. Clear the App Key to stop all sending.
- Every request carries your App Key (to link it to your website account) and your RuneScape name.
- Each source under **Data Syncing** has its own switch. With the App Key set, the plugin sends: skill levels and XP (with your total and combat level and account type); play sessions (login, logout and a ping while you play); farming patches, bird houses and farming unlocks; bank, seed vault, looting bag, potion storage, boat cargo holds, rune pouch, plank sack and storage items; the collection log; worn gear and your Inventory Setups loadouts; loot drops and kill counts (with a random per-install client id that is used to deduplicate them); quests; achievement diaries; combat achievements; boss kill counts; slayer task, points and unlocks; boss trips (supplies used and loot per trip) and the boss history (each visit to a boss: its trips, bank visits, kills, kill times, deaths, drops with their GE value, the supplies used and the map regions the kills happened in); your house location; Kingdom of Miscellania; farm harvests; dailies. These are on by default once you set an App Key; **Deaths** is off by default.
- **Share live activity** (**off** by default, under Data Syncing): when you switch it on, it sends while you are logged in your online or idle status, your current activity (a raid or minigame, boss, slayer task, the NPC you fight, the skill you train with the item it gives, banking, or idle; for Magic the spell: where you teleported to, the item you high alch, the monster you train Magic on), how long you have been at it, your world and the map region. It goes along with the other uploads, or on its own about every 15 seconds when nothing else was sent, and "offline" when you log out. You and the website's admin can see it. Switch it off and nothing more is sent; the website then shows you as offline.
- **Send error reports** (**off** by default, under Data Syncing): when you switch it on and this plugin hits an error, or one of its features blocks the game for more than 2 seconds, it sends a short report: the error message, the stack trace of this plugin's own code (other code is cut off after the first line), the feature it happened in, the plugin and RuneLite versions and your character name. The feature that failed is switched off for the session either way. Nothing else is sent.
- The plugin also loads from the website the dailies list and the boss lair map regions (at most every 6 hours).
- The plugin skips syncing on Leagues, Deadman and other special game-mode worlds. It collects no data about other players.
- You can delete your account on the website (Settings): that removes your email, your characters and all their data.
- The website's [privacy page](https://osrs-companion-web.vercel.app/privacy) explains what is stored, who can see it and how long it is kept.

## Collection log: why the plugin opens the log's search

The game only draws the collection log items of the page you look at. To read the whole log at once, the plugin does what the [WikiSync](https://github.com/weirdgloop/WikiSync) plugin does: the first time you open your collection log in a session (with **Collection Log** syncing on and an App Key set), it calls `client.menuAction` once for the log's own **Search** button and runs the client's search script. That makes the game draw every item you have, which the plugin reads. It is a client-side action on an interface you opened yourself; it sends no game action to the server, clicks nothing else, and never runs on its own outside that moment.

## In-game guides (parked)

Version 1.0.0 only syncs. The Plugin Hub reviews plugins of at most 200,000 tokens of code, and the plugin with its in-game guides was about 352,000. The guides are kept, unchanged, on the `parked/all-features` branch: the roadmap and goals (side panel, goal box, completion popup), gear progression, the farm run guide, the Slayer helper and guide, the boss gear window, the trip planner, the dailies guide and the bank layouts. They come back in later versions, one at a time, as far as the size allows; see [docs/feature-sizes.md](docs/feature-sizes.md).

`PluginSizeTest` estimates the review size on every build and fails above 195,000 tokens.

## Plugin Hub

Version `1.0.0` is set in `src/main/resources/com/osrscompanion/version.properties` and in `runelite-plugin.properties` (which the Plugin Hub shows). For every update, raise `version` in both files (1.0.1, 1.1.0 ...) in the commit the hub update points at. The pull request text is in [docs/plugin-hub-pr.md](docs/plugin-hub-pr.md); the review notes are in [docs/plugin-hub-review.md](docs/plugin-hub-review.md).

## License

BSD 2-Clause, see [LICENSE](LICENSE). This plugin includes third-party code; see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) for their copyright notices and licenses.
