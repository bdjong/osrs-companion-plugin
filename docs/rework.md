# The rework: one Plugin Hub plugin under 200k tokens

Goal: every feature stays, the in-game UI looks exactly as it does now, and `src/main/java` fits the Plugin Hub's 200,000 token review limit (`PluginSizeTest` enforces 195,000 once the rework is done; see [feature-sizes.md](feature-sizes.md)).

## Decided (2026-10-11)

- **The UI looks exactly the same.** Every window, overlay and side panel page is recorded by the UI golden tests (`*GoldenTest`, logs in `src/test/resources/golden/`); the code behind them is rebuilt compactly and the goldens prove nothing changed.
- **Guides are driven by the website.** The plugin sends what happens (a patch picked, a kill, a step reached) at once, and the website answers with the guide's new state; no calculations in the plugin. Website edits keep arriving through the version check (about 30 s); no real push channel.
- **Kept in the plugin:** the bank layouts (laid out the moment the bank opens), and the click swaps (teleport left click, Farming cape Force) as readable rules fed by the website's route data.
- **The last guide state is stored** in the RuneLite profile, so after a restart or while the website is unreachable the guide still shows where you were (it moves on again once the website answers).
- **No website UI changes:** the website only gets new API endpoints and the calculations behind them.
- **The in-game dailies guide is dropped** (the owner's choice; the only feature that goes): the side panel's dailies page and tile, the step box, the NPC arrow and map marker, and ticking dailies by hand in the plugin. The dailies sync stays, so the website's Dailies page keeps showing what is done; its instructions and timers are on the website.

## How it works now (2026-10-11, owner: "keep going, report when done or a decision is needed")

- **Data pump**: the plugin sends raw game values (varbits, varplayers; later item containers and game events) and the website works out what they mean (`web/src/lib/varPump.ts`, `POST /api/runelite/vars`). The website's watch list (`GET /api/runelite/vars`) is grouped per data source, and the plugin only sends a source whose Data Syncing switch is on.
- **Proof without playing**: before plugin logic is removed, a Java "oracle" test runs it on many inputs and writes the results to a fixture in the website repo (`web/src/lib/__tests__/fixtures/`); the website's tests must reproduce them exactly. The owner's test with the DEV client at the end covers the live side.
- **Website releases** to production happen as part of the plan (new endpoints only; no UI change, no change for the live plugin on `main`, which keeps using the old endpoints).

## The plan (checked off as it lands on `development`)

1. [x] Goal progress from the website: drop the plugin's local goal progress layer (GoalProgressState/Tracker hooks in the monitors); the goal box shows the website's progress.
2. [x] Data pump, varbit sources (proof: oracle fixtures): achievement diaries, combat achievements, Kingdom, house location, farming unlocks, dailies; also the timers (RuneLite's Time Tracking data). The slayer state moves with step 4.
3. [ ] Data pump, item containers and game events: bank and storage, loadouts, loot, deaths, kill counts, skills, play sessions, live activity signals.
4. [ ] Boss trips and boss history, slayer task sessions: built on the website from the pumped events.
5. [ ] Guides driven by the website: farm run (patch states from the pump), Slayer guide, boss gear (bank layout stays local), trip planner, gear progression, roadmap window actions; the last guide state stored locally.
6. [ ] Remaining fixed tables as data files; UI sharing (same look, goldens).
7. [ ] Turn `PluginSizeTest` enforcement on (195k), final count, then the open decision below.

## Projection (2026-10-11, at 275,672)

Per file, what is left once every planned step is done: UI kept as it is (≈ 73k, ≈ 65k after compacting), the
plumbing (plugin, settings, error reports, outbox, data pump: ≈ 36k), the website client (≈ 6k after making it
generic), the local features (bank layouts, teleport swaps: ≈ 10k), the website's answers the UI reads (≈ 9k), the
guides once website-driven (≈ 20k of 40k), and the sync monitors with their logic on the website (≈ 50k of 96k).
**About 198k, give or take 15k**: at the limit rather than the 170-190k first expected, so the way on is the
owner's decision.

**Decided (2026-10-11): one plugin.** The owner dropped the error reporting and the debug logging (≈ 15k, so about
185k projected). Checkpoint: the farm run guide goes website-driven next and is measured; if it falls short of the
projection, two plugins (sync + guides) is the fallback, the data pump being needed either way.

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
| In-game dailies guide dropped (the sync stays) | -10,046 |
| One switcher list for three windows | -398 |
| Data pump added (generic; diaries compared side by side) | +1,492 |
| Goal progress from the website (local goal layer and its hooks in the monitors removed) | -11,899 |
| Data pump: diaries, combat achievements, Kingdom, house location, farming unlocks, dailies (six monitors removed; diaries and dailies proven with 100 and 600 oracle cases) | -10,718 |
| Farming: RuneLite's patch list as a data file (`farming-patches.txt`, checked against RuneLite for every tab); the unused parts of the copied RuneLite trackers removed (nothing wrote or subscribed them); unused imports and fields | -10,158 |
| Timers from the website: the plugin sends RuneLite's stored Time Tracking data, the website works out every timer (proven with 150 oracle cases) | -3,844 |
| Error reporting dropped (owner's choice: no reports to Admin > Errors, no incidents or freeze watchdog; features still turn off safely) | -10,444 |
| Debug logging dropped (owner's choice; warnings and errors still logged) | -5,122 |
