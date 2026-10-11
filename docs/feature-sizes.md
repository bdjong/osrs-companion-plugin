# Feature sizes and the Plugin Hub limit

The Plugin Hub's review bot only reviews plugins whose `src/main/java` is at most **200,000 tokens** (comments stripped; tests and resources do not count). The limit applies to every update too, so the whole plugin must stay under it. `PluginSizeTest` estimates the size on every build and fails above 195,000 (5,000 kept for the estimate's error); during the rework it only prints the size (`ENFORCE = false`).

Measured on 2026-10-11, before the rework (the plugin's logic moves to the website; the UI stays exactly as it is), in tokens, with the same estimate as `PluginSizeTest`:

| | Tokens |
|---|---:|
| Version 1.0.0, every feature (the hub counted) | 351,624 |
| **Version 1.0.0 sync-only** | **≈ 185,000** |
| Free below the test's 195,000 | ≈ 10,000 |
| Free below the hub's 200,000 | ≈ 15,000 |

## The parked features

The full code is on the `parked/all-features` branch. Sizes include the website requests each one needs; bringing one back also needs about 1,000 for its settings and the wiring in `OSRSCompanionPlugin`.

| Feature | Tokens | Also needs |
|---|---:|---|
| Side panel (`RoadmapPanel` 8,200, `PanelTiles` 6,500, `FeatureTile` 2,300) | 16,900 | Any feature opened from the side panel. Today it refers to every feature, so it has to become modular (only the tiles and pages of the features that are back). |
| Window frame (`GameUi`) | 1,700 | Every in-game window. |
| Roadmap & goals (goal box, progress, completion popup, roadmap window) | 29,200 | Window frame, side panel. |
| Gear progression | 12,200 | Window frame, side panel; the roadmap manager only for two small helpers (easy to replace). |
| Farm run guide (window, overlays, teleport left click, farming cape, route ready, farm run history) | 33,000 | Window frame, side panel; the roadmap manager for two small helpers. |
| Slayer helper and guide (also the slayer task sessions, the kills/h on the website) | 31,500 | Window frame, side panel; small checks on the farm run guide ("is a run going?") and the boss gear setups. |
| Boss gear window and setups | 18,800 | Window frame, side panel; the trip planner and small farm run checks. |
| Trip planner (supplies check in the bank) | 3,700 | Boss gear or the farm run guide. |
| Dailies guide (list, step box, arrow) | 5,300 | Side panel; one "is a farm run going?" check. |
| Bank layouts | 7,300 | Boss gear, farm run or slayer gear (it lays out their items). |
| **All parked features** | **≈ 160,000** | |

So one at a time does work, but each needs a little glue (the cross-checks above become optional), and the first one back brings a slimmed side panel with it.

## What fits

- **Now (≈ 10,000 free):** only the smallest: the dailies guide with a small side panel, or the trip planner once a tool it serves is back.
- **Making room in the sync code**, largest first:
  - Farming timers, RuneLite's copied time-tracking code: **35,200**. If the plugin sent the raw patch values and the website decoded them, most of this goes (≈ 25,000–30,000), but the farm harvest tracking that uses it locally would have to be reworked.
  - Boss trips and boss history: 21,400. They share a lot and could be merged.
  - Slayer sync 16,700, API client and outbox 12,100, error reports and safety 11,500, dailies sync 9,700, live activity 9,400.
- **With the farming decoding on the website (≈ 40,000 free):** one big guide (farm run, Slayer helper or roadmap) with the side panel, or boss gear plus gear progression.
- **All guides back** would need about 160,000 of room, more than the whole sync code. That only works if the guides themselves get much smaller (their route, guide and gear calculations done by the website, and fewer in-game windows where the website already has the page).
