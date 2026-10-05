# OSRS Companion

A RuneLite plugin that syncs your game data (farming timers, bank, skills, loadouts, loot, collection log, quests, diaries, combat achievements, boss kill counts, slayer and your Kingdom of Miscellania) to the OSRS Companion website, and shows your current roadmap goal in game.

## Settings

Settings are grouped into **Data Syncing** (which data the plugin sends to the app; one toggle per source) and **Plugin Features** (the in-game current goal overlay with its progress bar, the goal completion popup and its sound). Your OSRS Companion App Key sits at the top.

The goal overlay updates as you play: XP, kills, drops and collection log unlocks move the bar immediately and the plugin syncs with the app in the background. For the completion sound, "Custom file" plays `~/.runelite/osrscompanion/goal-complete.wav`.

## License

BSD 2-Clause, see [LICENSE](LICENSE). This plugin includes third-party code; see [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt) for their copyright notices and licenses.
