OSRS Companion - RuneLite with the OSRS Companion plugin
========================================================

How to start
------------
Windows:
1. Unzip this zip (right-click > Extract All...). Don't run it from inside the zip.
2. Double-click "Start OSRS Companion.cmd".

Mac:
1. Double-click the zip in Finder to unzip it. Move the OSRS-Companion folder somewhere
   handy, for example Documents.
2. Double-click "Start OSRS Companion.command". A Terminal window opens.
   If macOS says it cannot be opened ("unidentified developer" / "not verified"):
   - right-click (or Control-click) the file > Open > Open, or
   - System Settings > Privacy & Security > scroll down > "Open Anyway", or
   - open Terminal, type  bash  and a space, drag the file into the Terminal window,
     and press Enter.

Either way it checks everything it needs and tells you in plain words if something is missing:
- Java (JDK 11). It can install it for you (free, Eclipse Temurin from adoptium.net):
  with winget on Windows, with Homebrew on a Mac (otherwise it opens the download page).
- An internet connection (the first start downloads about 150 MB).
- For Jagex accounts: a one-time login step (it explains what to do).
Then it builds RuneLite with the plugin (first time 2-5 minutes, later a few seconds) and
starts it. It also puts a "RuneLite (OSRS Companion)" shortcut on your desktop.

First time in RuneLite
----------------------
1. Make an account on https://osrs-companion-web.vercel.app (Register),
   open Settings and copy your App Key.
2. In RuneLite: wrench (Configuration) > search "OSRS Companion" > paste the App Key.
3. Log in to the game.

Good to know
------------
- Use the desktop shortcut from now on, not the normal RuneLite / Jagex Launcher entry.
  The normal RuneLite never loads this plugin.
- Your other RuneLite plugins and settings are the same: it uses your normal .runelite folder.
- After a game update (Wednesdays), if RuneLite says it is outdated:
  double-click "Update RuneLite.cmd" (Windows) or "Update RuneLite.command" (Mac).
- A new version of the plugin: you get a new zip. Close RuneLite, unzip it over this
  folder (replace the files) and start it again.
- Jagex accounts: your login session is stored in .runelite/credentials.properties in your
  user folder. Keep it private and never share your .runelite folder. If logging in stops
  working, start RuneLite once through the Jagex Launcher again.
- Something went wrong while building? The full log is in build-log.txt in this folder.
