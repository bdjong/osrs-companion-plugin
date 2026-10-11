# The rework: one Plugin Hub plugin under 200k tokens

Goal: every feature stays, the in-game UI looks exactly as it does now, and `src/main/java` fits the Plugin Hub's 200,000 token review limit (`PluginSizeTest` enforces 195,000 once the rework is done; see [feature-sizes.md](feature-sizes.md)).

## Decided (2026-10-11)

- **The UI looks exactly the same.** Every window, overlay and side panel page is recorded by the UI golden tests (`*GoldenTest`, logs in `src/test/resources/golden/`); the code behind them is rebuilt compactly and the goldens prove nothing changed.
- **Guides are driven by the website.** The plugin sends what happens (a patch picked, a kill, a step reached) at once, and the website answers with the guide's new state; no calculations in the plugin. Website edits keep arriving through the version check (about 30 s); no real push channel.
- **Kept in the plugin:** the bank layouts (laid out the moment the bank opens), and the click swaps (teleport left click, Farming cape Force) as readable rules fed by the website's route data.
- **The last guide state is stored** in the RuneLite profile, so after a restart or while the website is unreachable the guide still shows where you were (it moves on again once the website answers).
- **No website UI changes:** the website only gets new API endpoints and the calculations behind them.
- **The in-game dailies guide is dropped** (the owner's choice; the only feature that goes): the side panel's dailies page and tile, the step box, the NPC arrow and map marker, and ticking dailies by hand in the plugin. The dailies sync stays, so the website's Dailies page keeps showing what is done; its instructions and timers are on the website.

## Open: decide when the rework is done

- **Advancing guide steps offline (the owner's preference).** The website would send each step with simple conditions ("this patch is planted", "this item is in the inventory", "you are in this area") and the plugin checks them itself: guides keep moving without the website, and simple steps react instantly again. Estimated 4,000-6,000 tokens. Decide with the exact token count at the end of the rework: do it if it fits under 195,000.
- **Two Plugin Hub plugins** (sync + guides) stays the fallback if the single plugin does not fit.

## Done so far

| Step | Tokens |
|---|---:|
| Start: the full plugin | 351,691 |
| Farm patch rules as a data file (`patch-states.txt`, checked against RuneLite for every value) | -18,816 |
| Developer-only slayer debug mode removed | -2,053 |
| 219 plain getters as Lombok `@Getter` (same methods) | -2,280 |
| RuneLite's produce list as a data file (`produce.txt`, checked against RuneLite for every value) | -1,977 |
| The five windows on one `GameWindow` base (frame, clickable areas, tooltips, mouse and keys) | -5,322 |
