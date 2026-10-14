# Plugin Hub readiness review

## Status 2026-10-10 (release prep for 1.0)

Decided and done (branch `feature/hub-release-prep`, merged into `development`):

- Repository public; `friend/` and `run-dev.bat` only on `development` (O6).
- Defaults: Share live activity, Send error reports and Auto set teleport left click off; Farming cape for herbs Remind (O1 b, O2 b).
- Switches for Skills & XP and Play sessions; boss trips already had one (O5 b).
- The hidden `apiBaseUrl` setting is removed; the URL is the constant `OSRSCompanionApiClient.BASE_URL` (O7 b).
- No more writes to the Bank Tag Layouts plugin's config: read-only detection and a chat hint (O4).
- The whole-client stall watchdog (heartbeat, stall reports) is removed; the check of the plugin's own sections stays.
- The menu-swap log line is debug; `author=bdjong` (O8).
- Version `1.0`, set by hand in `version.properties`; no git-counted or `-dev` versions (O9).
- `client.menuAction` (collection log) is explained in the README and in `docs/plugin-hub-pr.md`.

Decided 2026-10-11: O3 accepted (the licence notices stay in the public repository, not in the hub jar, as with most hub plugins, Mystix included); O4 one submission of the whole plugin, split only if the reviewers ask. The hub entry gets `authors=bdjong` and a `warning=` line (docs/plugin-hub-pr.md). The website has a privacy page and account deletion (its `development` branch).

Still open: the in-game test of `development`, then the release merge and the hub pull request (README, "Plugin Hub release: what still has to be done").

## Status 2026-10-12 (resubmission after the rework)

Re-checked against `development` after the rework (docs/rework.md): no reflection, `Unsafe`, `Runtime`, subprocesses, `Thread.sleep` or file I/O; no `new Gson()` / `new OkHttpClient()` (`Tables` parses tables.json with `JsonParser`); one thread of its own (roadmap refreshes; the freeze watchdog went with the error reporting); `client.menuAction` only for the collection log; `runScript` only for the collection-log search, quest status, potion storage doses and the notification popup; hint arrows only from the farm run and Slayer guides (the dailies guide was dropped); `LinkBrowser.browse` only (OSRS Wiki, the website); no writes to other plugins' settings; the website address is the constant `OSRSCompanionApiClient.BASE_URL` (O7). New since the first submission: the game events (`GameEvents`) are disclosed in the App Key warning, the README, the hub `warning=` line and the PR text (docs/plugin-hub-pr.md). Review size about 193,500 tokens.

The review below is the one of 2026-10-08 and is kept as the reasoning behind these decisions.

---

Review date: 2026-10-08, against `origin/main` at `254254f`.

This review checks the plugin against the RuneLite Plugin Hub rules. It covers what already complies, what this branch fixed, and what the owner must decide before submitting.

## Rule sources

- [plugin-hub README](https://github.com/runelite/plugin-hub/blob/master/README.md): submission steps, `runelite-plugin.properties`, icon size, licence, resources, third-party dependencies, build type, review policy.
- [Rejected or Rolled Back Features](https://github.com/runelite/runelite/wiki/Rejected-or-Rolled-Back-Features) (RuneLite wiki): forbidden language features and APIs, sensitive and restricted APIs, menu swapping, rejected feature types.
- [Jagex third-party client guidelines](https://secure.runescape.com/m=news/third-party-client-guidelines?oldschool=1): menu and interface rules, boss-fight helpers.
- [RuneLite `ConfigPanel`](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/config/ConfigPanel.java): `@ConfigItem(warning = ...)` shows a Yes/No "Are you sure?" dialog before the value changes.
- [Menu Entry Swapper](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/plugins/menuentryswapper/MenuEntrySwapperPlugin.java): core reference for left-click swaps and sub-menu clones.
- [verification-template/build.gradle](https://github.com/runelite/plugin-hub/blob/master/package/verification-template/build.gradle): the extra dependencies the hub allows.

The plugin-hub repository has no separate CONTRIBUTING file or PR template. Its README links to the wiki page above for the review rules.

## Verdicts

Legend: **compliant**: no change needed. **fixed**: changed on this branch. **owner**: needs the owner's decision.

| # | Rule (source) | Verdict | Notes |
|---|---|---|---|
| 1 | Third-party data must be disclosed (hub review practice, `ConfigItem.warning`) | **fixed** | The App Key now has a `warning`, so RuneLite asks for confirmation before the key is set or changed. The Data Syncing section text, the `@PluginDescriptor` description, `runelite-plugin.properties` and a new README section "Data sent and privacy" all name the third-party website. |
| 2 | No sending without opting in | **compliant**, with an owner note | Every upload path checks `SyncGuard.hasAppKey`, and nothing is sent until the user pastes an App Key. The per-source switches default to on, which is normal because the App Key is the opt-in. Skills/XP, play sessions and boss trips have no switch of their own (see O5). |
| 3 | No reflection, JNI/JNA, `Unsafe` (wiki: forbidden language features) | **compliant** | None in `src/main`. `java.lang.reflect.Type` is imported only for Gson `TypeToken` types (`AchievementDiariesReader`, `BossTripTracker`), which is standard and does not reflect. |
| 4 | No external programs, `Runtime`, subprocesses (wiki) | **compliant** | None in the plugin. See O6 for the `friend/` launcher scripts in the repository. |
| 5 | No downloading or vendoring code at runtime (wiki) | **compliant** | The plugin downloads only JSON from the website and PNG images from `oldschool.runescape.wiki`. `scripts/update.js` copies RuneLite timer classes at development time, which is not runtime. |
| 6 | No `Desktop.open/browse` or `LinkBrowser.open`; `LinkBrowser.browse` allowed (wiki) | **compliant** | Only `LinkBrowser.browse` to the OSRS Wiki (`BossGearWindow`). |
| 7 | No `Thread.sleep` / `Thread.interrupt` (wiki) | **compliant** | None. |
| 8 | No `java.io` file APIs; use `Filepath` (wiki) | **compliant** | No file I/O. Resources load with `getResourceAsStream`, as the README asks. |
| 9 | No `KeyboardFocusManager`, no window manipulation (wiki) | **compliant** | Key listeners only consume Escape and mouse events while the plugin's own overlay windows are open. |
| 10 | OkHttp only, inject the client's `OkHttpClient` and `Gson`; `newBuilder()` allowed (wiki) | **compliant** | `OSRSCompanionApiClient` and `GoalImageCache` derive from the injected `OkHttpClient` with `newBuilder()`, and Gson uses the injected instance's `newBuilder()`. There is no `new Gson()` or `new OkHttpClient()`. |
| 11 | Package must not be `net.runelite` (wiki) | **compliant** | `com.osrscompanion`, including the vendored timer classes under `com.osrscompanion.runelite`. |
| 12 | Java only (wiki) | **compliant** | |
| 13 | `Client.menuAction`: one allowed use, the collection-log search toggle (wiki: sensitive APIs) | **compliant** | `CollectionLogMonitor.triggerFullRead` calls exactly `client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null)` and then `client.runScript(2240)`. This is the pattern WikiSync uses and the wiki allows. It runs only once per session after the log interface is set up (script 7797), and only with Collection Log sync on and an App Key set. Reviewers will still look at it, because it is a sensitive API. |
| 14 | `Client.runScript` must not send server actions (wiki: sensitive APIs) | **compliant** | `runScript` is used for the collection-log search (2240), `QUEST_STATUS_GET`, `POTIONSTORE_DOSES` and the notification popup (`NOTIFICATION_DISPLAY_INIT`). All are client-side scripts. |
| 15 | Restricted APIs: `hopToWorld`, `Filepath.Unchecked`, `setCanSendPackets` (wiki) | **compliant** | None used. |
| 16 | No new menu entries that send actions to the server (Jagex guidelines) | **owner** | See O1. `TeleportLeftClick.copyToTop` creates a top-level copy of a sub-menu teleport. Core Menu Entry Swapper does the same (`clone`), but there the user picks the swap. |
| 17 | Menu swaps only as Menu Entry Swapper allows; no conditional menu removing (wiki: menu entry swapping) | **owner** | See O1 (automatic, per-step left-click teleport swaps) and O2 (the conditional deprioritising of the herb "Pick"). Nothing is removed, nothing touches combat, PvP or player options, and nothing clicks for the user. |
| 18 | No automated input, one-click or autotyping (wiki, Jagex) | **compliant** | No synthetic input, no chatbox text injection and no `menuAction` besides #13. Swaps only change what the user's own click does. |
| 19 | No boss-fight helpers, no crowdsourcing other players, no player info over HTTP (wiki, Jagex) | **compliant** | Boss gear and trip planner features cover setups and supplies, not mechanics. Only the user's own account data is sent, and only to the user's own website account. |
| 20 | Dependencies: only `client` + Lombok unless verified (README: third-party dependencies) | **compliant** | `compileOnly` client and Lombok 1.18.30; the test dependencies match the example-plugin template. |
| 21 | `build=standard`: `build.gradle` / `settings.gradle` are replaced at packaging (README: build type) | **owner** | See O3. The hub build drops the custom `Jar` block that puts `LICENSE` into the jar. The `run` task's Mac `--add-exports` flag only matters locally. So does the `test` task's `osrscompanion.guard.rethrowTestFailures` property (the Guard rethrows a failed assertion under tests; without it, the plugin behaves as in production). Otherwise the file matches the template and is tab-indented. |
| 22 | Icon `icon.png` at most 48x72 (README step 10) | **compliant** | Exactly 48x72 RGBA. |
| 23 | `runelite-plugin.properties` fields (README step 9) | **fixed** | The description now names the third-party site and the App Key, and the tags include `slayer` and `sync`. `version=` is optional (the commit is used when it is missing). |
| 24 | LICENSE, BSD 2-Clause (README: licensing) | **compliant** | BSD 2-Clause, the copyright holders listed in `LICENSE` (a reviewer asked to attribute the authors there, without explaining where the code comes from, so there is no separate notices file). |
| 25 | README (README step 11) | **fixed** | Added "Data sent and privacy". |
| 26 | No hard-coded secrets | **compliant** | No keys in the source or in git history. The App Key is a `secret = true` config item. The only hosts in the code are the website and the OSRS Wiki. |
| 27 | Thread safety: no blocking network on the client thread | **compliant** | All API calls use OkHttp `enqueue` (async), `GoalImageCache` included. The roadmap's background refreshes run on the plugin's own daemon thread (`osrs-companion-roadmap`, started on demand, ended after 30 s idle), not on RuneLite's shared executor that every plugin uses. The freeze watchdog is the plugin's own daemon thread too; it only times the plugin's own guarded sections (the whole-client stall watch was removed on 2026-10-10), reads the client thread's stack with `Thread.getStackTrace` only when one of them blocks it, and installs no global handlers. |
| 28 | Resource use | **compliant** | The boss history does a map-region lookup per tick, keeps where its kills happened (at most 12 regions per session) and asks the website for the boss lair regions at most every 6 hours (with the version it has, so an unchanged list is a tiny answer) and sends an open session at most every 30 s (at once on a kill, a drop or logout); session pings every 5 minutes; with "Share live activity" on, the activity status rides along with each batch, and a heartbeat batch goes out only when nothing reached the website for 60 s (with the 30 s version check in between); the per-tick activity check reads a few varbits and the local player only. Dedup of identical payloads, a 2-tick collection-log settle and an in-memory image cache limited to wiki goal icons. |
| 29 | "If we cannot review every line, we will not accept it" (wiki) | **owner** | See O4. The plugin is large (about 146 main source files), which slows review. A clear README and this document help. |

## What this branch changed

- `OSRSCompanionConfig`:
  - Added `APP_KEY_WARNING` and `warning = APP_KEY_WARNING` on the App Key item. Before the key is set or changed, RuneLite now shows "Are you sure?" with a disclosure of what is sent and to which third-party server.
  - The App Key description and the Data Syncing section description now say the server is third-party and that nothing is sent without a key.
  - The side-panel toggles do not touch the App Key or the sync switches, so they work as before.
- `OSRSCompanionPlugin`: the `@PluginDescriptor` description is current and names the third-party site, and tags are added for config search.
- `runelite-plugin.properties`: the hub description names the third-party site and the App Key, and two tags are added.
- `README.md`: a new "Data sent and privacy" section covers the host, the App Key opt-in, each data source, what is sent without a switch, what is downloaded, special game modes, and that no data about other players is collected.
- No feature was removed or changed in behaviour.

## Owner decisions before submitting

**O1. Automatic teleport left click (`TeleportLeftClick`, used by the farm run and the Slayer guide; config "Auto set teleport left click", default on).**
- What it does: while a guide step is active, it moves the teleport option of the item the step uses to the left click (`moveToLeftClick`, a plain reorder like Menu Entry Swapper's `swap`). When the teleport is only in a sub-menu (the Max cape's "Teleports"), it adds a top-level copy of that entry (`copyToTop`, like Menu Entry Swapper's `clone`).
- Risks:
  - The swap is automatic and changes with game state (the current route step). Menu Entry Swapper swaps are fixed per item and chosen by the user.
  - The Jagex guidelines forbid "addition of new menu entries which cause actions to be sent to the server". The sub-menu copy is an existing action moved up, which is what core does, but a reviewer may still question it.
- Options:
  - a) Keep it as is and explain it in the PR as "MES-style swaps, only during a guide step, off with one switch".
  - b) Make the setting **off by default**, so the user opts in. This is the lowest-effort way to lower review risk.
  - c) Keep the reorder (`moveToLeftClick`) and drop the sub-menu copy (`copyToTop`). The inventory Max cape would then keep its sub-menu.
  - d) Replace it with a highlight: outline or label the item and leave the menu alone. This has no review risk.

**O2. "Farming cape for herbs: Force" (`FarmRunManager.onMenuEntryAdded`, default FORCE).**
- What it does: while the Farming cape is not worn, it deprioritises "Pick" on herb patches, so a left click walks instead.
- Risk: this is conditional deprioritising based on game state. It is not removal, and it is not combat or PvP, but the wiki lists "conditional menu entry removing" as rejected.
- Options:
  - a) Keep it.
  - b) Default it to Remind, so Force becomes opt-in.
  - c) Drop Force and keep only the reminder and overlay text.

**O3. Licence notices in the hub jar.**
- What happens: with `build=standard`, the hub replaces `build.gradle`, so `LICENSE` is not copied into the jar. The CI check in `.github/workflows/plugin.yml` only covers this repository's own build.
- Why it matters: `LICENSE` says the notices "must be kept with every copy".
- Options:
  - a) Accept it: the source repository carries them, which is what most hub plugins rely on.
  - b) Move both files into `src/main/resources/META-INF/osrs-companion/` (the only path that survives a standard build) and drop the `Jar` block, so there are no duplicate entries.
  - c) Switch to `build=gradle`. This is not recommended, because it slows review.

**O4. Review size.**
- What it is: about 146 main source files, many overlay "windows", bank layouts, and writes to another plugin's config (`BankLayoutPrep.keepHubLayoutsOff` sets the Bank Tag Layouts hub plugin's `layout_<tag>` key).
- Options:
  - a) Submit as one plugin and explain the other plugin's config write in the PR.
  - b) Submit a smaller first version (sync, roadmap and goals) and add the guides in later updates, so each update gets a smaller review.

**O5. Data without its own switch.**
- What it is: with an App Key, skills/XP, play sessions (login, logout and pings) and boss trips are always sent. The README now discloses this, but there is no switch to turn them off.
- Options:
  - a) Keep it, since it is disclosed.
  - b) Add "Skills", "Play sessions" and "Boss trips" switches under Data Syncing, which matches the per-source pattern.

**O6. Repository extras.**
- What they are:
  - `friend/` builds and launches a self-built RuneLite with the plugin, and reads `~/.runelite/credentials.properties` for Jagex-account dev logins.
  - `run-dev.bat` and `scripts/` (Node) are also in the repository.
- Why they matter: they are not part of the plugin jar, but reviewers read the repository, and a launcher that handles credentials may slow or block review. Once the plugin is on the hub, the friend kit is no longer needed.
- Options:
  - a) Remove `friend/` (or move it to a separate private repository) before submitting.
  - b) Keep it and explain it in the PR.

**O7. Hidden `apiBaseUrl` setting.**
- What it is: a hidden config item that lets the App Key be sent to any URL, meant for local testing (`http://localhost:3000`).
- Why it matters: it is hidden in the UI, but a reviewer may ask why the API host can be configured.
- Options:
  - a) Keep it and explain it.
  - b) Hard-code the production URL in release builds and keep the override for the dev `run` task only (for example a system property read in the test launcher).

**O8. Plugin name and author.**
- What it is: `author=OSRS Companion` and the name "OSRS Companion".
- Why it matters: both are fine under the rules. Reviewers sometimes ask for a person or GitHub handle as the author. This is optional.

**O9. Plugin version in the hub build.**
- What happens: the version is `<base>.<patch>`; the base is in `src/main/resources/com/osrscompanion/version.properties` and our `build.gradle` counts the patch from git and writes `com/osrscompanion/build-version.properties` into the jar. With `build=standard` the hub replaces `build.gradle`, so that file is not generated and hub users report `<base>-dev` (for example `0.1-dev`). The same happens for any build without the full git history (a source tarball or a shallow clone); the build never fails because of git.
- Why it matters: the website shows each player's plugin version; all hub users would show the same `-dev` value, so hub releases are only told apart by the base.
- Options:
  - a) Accept it, and bump `base` in `version.properties` in the commit each hub update points at.
  - b) Before a hub update, commit the generated `build-version.properties` into `src/main/resources` (it then survives the standard build) and delete it again after.

## Submission checklist (after the decisions above)

1. Repository public, README and LICENSE in place (done).
2. Push the chosen commit, then fork `runelite/plugin-hub`, add `plugins/osrs-companion` with `repository=https://github.com/bdjong/osrs-companion-plugin.git` and `commit=<40-char hash>`, and open the PR with a short description.
3. In the PR description, mention:
   - the third-party website and the App Key opt-in;
   - the single `menuAction` use (the collection log, as WikiSync does);
   - the teleport left-click behaviour (O1).
4. Watch the PR's `build` and "RuneLite Plugin Hub Checks" results and push fixes to the same PR.
