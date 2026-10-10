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
- Each source under **Data Syncing** has its own switch. With the App Key set, the plugin sends: farming patches, bird houses and farming unlocks; bank, seed vault, looting bag, potion storage, boat cargo holds, rune pouch, plank sack and storage items; the collection log; worn gear and your Inventory Setups loadouts; loot drops and kill counts (with a random per-install client id that is used to deduplicate them); quests; achievement diaries; combat achievements; boss kill counts; slayer task, points, unlocks and visits; the boss history (each visit to a boss: its trips, bank visits, kills, kill times, deaths, drops with their GE value and the supplies used); your house location; Kingdom of Miscellania; farm harvests and farm runs; dailies. Deaths are off by default.
- **Share live activity** (on by default, under Data Syncing) sends, while you are logged in, your online or idle status, your current activity (a raid or minigame, boss, slayer task, farm run, the NPC you fight, the skill you train with the item it gives, banking, or idle; for Magic the spell: where you teleported to, the item you high alch, the monster you train Magic on), how long you have been at it, your world and the area name (or map region). It goes along with the other uploads, or on its own about every 15 seconds when nothing else was sent, and "offline" when you log out. You and the website's admin can see it. Switch it off and nothing more is sent; the website then shows you as offline.
- With the App Key set, these are sent without a switch of their own: skill levels and XP, play sessions (login, logout and a ping while you play), and boss trips (supplies used and loot per trip).
- The plugin also loads your roadmaps, gear plans, farm routes and slayer guide from the website, and goal images from the OSRS Wiki (`oldschool.runescape.wiki`).
- The plugin skips syncing on Leagues, Deadman and other special game-mode worlds. It collects no data about other players.

## License

BSD 2-Clause, see [LICENSE](LICENSE). This plugin includes third-party code; see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) for their copyright notices and licenses.
