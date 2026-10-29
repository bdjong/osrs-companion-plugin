# OSRS Companion — Development

This document covers building, distributing, and running the plugin from source. For plugin usage and configuration, see the [README](README.md).

## Companion website

The plugin sends gameplay data to the OSRS Companion website (the `osrs-companion-web` project). Sign in there, copy your App Key from **Settings**, then paste it into the plugin settings in RuneLite (**Configuration** > **OSRS Companion**). The website address is fixed in the code (`OSRSCompanionApiClient.BASE_URL`); to test against a local website, change that constant in your local build only and never commit it.

## Building

Before building, copy the farming/hunter timer classes from RuneLite (required for per-patch timer names):

```bash
# 1. Clone RuneLite as a sibling directory (one-time)
cd .. && git clone https://github.com/runelite/runelite.git

# 2. Copy and patch the timer classes into this project
cd osrs-companion-plugin && npm install && node update.js
```

Then build:

```bash
./gradlew build
```

The `update.js` script copies `farming/` and `hunter/` from RuneLite and patches package names/visibility. See [runelite-time-tracking-reminder](https://github.com/queicherius/runelite-time-tracking-reminder) for the pattern.

## Distribution (sending the plugin to someone else)

You can package the plugin in two ways:

### Option 1: Single runnable JAR (easiest for the recipient)

Build a fat JAR that includes RuneLite and the plugin. The recipient only needs Java 11+ installed; they do not need to install RuneLite separately.

```bash
./gradlew shadowJar
```

Send them the file **`build/libs/osrs-companion-plugin-1.0-SNAPSHOT-all.jar`**. They run (on macOS the `--add-exports` flag is required for the client to use Apple’s window APIs):

```bash
java -ea --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED -jar osrs-companion-plugin-1.0-SNAPSHOT-all.jar
```

For Jagex accounts you may need developer mode:  
`java -ea --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED -jar osrs-companion-plugin-1.0-SNAPSHOT-all.jar --developer-mode`

### Option 2: Plugin JAR for sideloading (if they already use RuneLite)

Build the plugin-only JAR (this is produced by `./gradlew build`):

```bash
./gradlew build
```

Send them **`build/libs/osrs-companion-plugin-1.0-SNAPSHOT.jar`** (the one *without* `-all`). They then:

1. Install and open RuneLite normally.
2. Enable developer mode: **Configuration** → **Developer tools** → enable **Developer mode**, or run RuneLite with `--developer-mode`.
3. Put the JAR in RuneLite’s sideloaded-plugins folder:
   - **macOS**: `~/Library/Application Support/RuneLite/sideloaded-plugins/`
   - **Windows**: `%USERPROFILE%\.runelite\sideloaded-plugins\`
   - **Linux**: `~/.runelite/sideloaded-plugins/`
4. Restart RuneLite. OSRS Companion will appear in the plugin list.

## Running Locally

```bash
./gradlew run
```

Or use the **RuneLite (OSRS Companion Plugin)** launch configuration in VS Code (Run and Debug).

### Jagex Accounts

If you use a Jagex account and can't log in when running from the IDE, you need to allow credential storage first. See [Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts) for details:

1. Run RuneLite with `--configure` (Mac: `/Applications/RuneLite.app/Contents/MacOS/RuneLite --configure`)
2. Add `--insecure-write-credentials` to **Client arguments**
3. Save, then launch RuneLite via the Jagex launcher once so it writes credentials to `~/.runelite/credentials.properties`
4. After that, running from the IDE will use the saved credentials

## Branches

- **`main`**: the live version. The Plugin Hub builds the commit `plugins/osrs-companion` in `runelite/plugin-hub` points at, always a commit on `main`. Nothing is committed to `main` directly.
- **`development`**: the testing area. Finished features are merged here and tested in game before a release. It also keeps the dev launcher kit (`friend/`, `run-dev.bat`), which never goes to `main`.
- **Feature branches** (`feature/<name>`): one per change, made from `development` and merged back into it when done.

A release merges `development` into `main`. `main` deleted `friend/` and `run-dev.bat` after `development` branched off, so a normal `git merge development` on `main` keeps them out, as long as nobody changes those files on `development` (then delete them again in the merge commit). Do not merge `main` back into `development`: that would delete the kit there. A hotfix made on `main` goes to `development` as a cherry-pick.

**The friend kit never goes to `main`** (for people and for AI assistants alike): never commit, merge, cherry-pick or copy `friend/` or `run-dev.bat` into `main`. Before committing a release merge on `main`, check `git diff --cached --name-only` for them and delete them again in the merge commit if they are there. The `Plugin` workflow fails on `main` when either is present (step "The friend kit is not on main").

## Testing a release

Before merging `development` into `main`, build it (`./gradlew build` must pass) and check in game with a fresh RuneLite profile (or the plugin's settings reset) and an App Key:

1. The defaults: Share live activity, Send error reports and Auto set teleport left click are off; Farming cape for herbs is Remind.
2. Syncing: log in and check the website shows skills, bank, quests, diaries, collection log (open the log once), slayer, timers and a play session. Switch a Data Syncing source off (for example Skills & XP or Play sessions) and check it stops updating.
3. The side panel tools: roadmap, gear progression, farm run (start a run, the guide and arrow), boss gear, slayer helper and trip planner open and work.
4. Bank layouts: open the bank with a boss setup or farm run active. With the Bank Tag Layouts plugin installed and its "Enable layout by default" on, the needed items show in its order and the chat says once to turn that plugin off for ours; turn it off, reopen the bank and our layout shows. (Your own profile still has the old "off" markers for our views, so test this on a fresh profile.)
5. The plugin version in the side panel reads `OSRS Companion v1.0.0` (or the version you set).

## Releasing to the Plugin Hub

A Plugin Hub update points `plugins/osrs-companion` in `runelite/plugin-hub` at a commit on `main`. The plugin version is set by hand in `src/main/resources/com/osrscompanion/version.properties` (`version=1.0.0`) and again as `version=` in `runelite-plugin.properties`, which is the version the Plugin Hub shows (without it the hub shows the commit hash); raise both (1.0.1, 1.1.0 ... 2.0.0) in the commit a hub update points at. `PluginVersionTest` fails when the two differ. Every request to OSRS Companion carries it as `X-OSRSCompanion-Plugin-Version` and the live activity status as `plugin_version`; the website shows it per character in Admin, Activity. The pull request text for the first submission is in `docs/plugin-hub-pr.md`.

## After 1.0.2 is on the Plugin Hub

1.0.2 is the plugin-hub pull request `update osrs-companion to 1.0.2` (`commit=d73d031`). When it is merged:

Works by itself once players update (nothing to do):

- The farm run markers stay on after leaving your house (the POH bug that turned them off for the session).
- The arrow follows the way there the website sends: Harmony Island by boat (Bill Teach at Port Phasmatys, then Brother Tranquility) is already set on the website.
- Skill goals tick off about a second after reaching them; skills go up every 10 s while training (the website already keeps one snapshot per 5 minutes).

To do then:

1. Delete the `release/1.0.1` branch: it only keeps the commit the 1.0.1 hub entry pointed at (`91fe798`, the history before the authorship rewrite) from being cleaned up.
2. Update or remove the website's announcement banner about the farm markers turning off after the POH.
3. Arrow tiles on the website (`way` on a teleport in `src/lib/farmCatalog.ts`, or on a Slayer location in `src/lib/slayerLocations.ts`):
   done for the Crash Site Cavern (the pass-through, then the cavern entrance, then the gorillas). The Ectophial walk
   needs none: the minimap's red hint arrow already points to the patches west of Port Phasmatys before they load.
