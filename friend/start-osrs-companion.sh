#!/bin/bash
# OSRS Companion - start RuneLite with the OSRS Companion plugin built in (macOS).
#
# Started by "Start OSRS Companion.command" (double-click it). It checks everything it needs,
# explains anything that is missing, builds RuneLite with the plugin and starts it.
#
# Why not the normal RuneLite: RuneLite only loads plugins that are not on the Plugin Hub
# when you build it yourself. This script does that build for you.
#
# Options: --refresh   fetch the newest RuneLite (after a game update)
#          --no-launch for testing: build only, no shortcut, RuneLite is not started

REFRESH=0
NO_LAUNCH=0
for arg in "$@"; do
  case "$arg" in
    --refresh) REFRESH=1 ;;
    --no-launch) NO_LAUNCH=1 ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PLUGIN_DIR="$(dirname "$SCRIPT_DIR")"
PACKAGE_DIR="$(dirname "$PLUGIN_DIR")"
START_COMMAND="$PACKAGE_DIR/Start OSRS Companion.command"
BUILD_LOG="$PACKAGE_DIR/build-log.txt"
CREDENTIALS="$HOME/.runelite/credentials.properties"
RUNELITE_APP="/Applications/RuneLite.app"
WEBSITE="https://osrs-companion-web.vercel.app"
# Overridable so the checks can be tested on other systems.
JAVA_HOME_TOOL="${OSRS_JAVA_HOME_TOOL:-/usr/libexec/java_home}"

if [ -t 1 ]; then
  CYAN=$'\033[36m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RED=$'\033[31m'; GREY=$'\033[90m'; RESET=$'\033[0m'
else
  CYAN=''; GREEN=''; YELLOW=''; RED=''; GREY=''; RESET=''
fi
title()   { echo; echo "  ${CYAN}$1${RESET}"; }
ok()      { echo "  ${GREEN}[OK]${RESET}    $1"; }
info()    { echo "          $1"; }
warn()    { echo "  ${YELLOW}[!]${RESET}     $1"; }
problem() {
  echo
  echo "  ${RED}[PROBLEM] $1${RESET}"
  shift
  for line in "$@"; do echo "            $line"; done
  echo
}
ask() {
  local answer
  read -r -p "  [?]     $1 [y/n] " answer
  [[ "$answer" =~ ^[yYjJ] ]]
}
stop_here() {
  echo
  read -r -p "  Press Enter to close this window" _
  exit "$1"
}

echo
echo "  ${CYAN}==============================================${RESET}"
echo "  ${CYAN} OSRS Companion - RuneLite with the plugin${RESET}"
echo "  ${CYAN}==============================================${RESET}"

if [ "$(uname)" != "Darwin" ] && [ -z "$OSRS_ALLOW_NON_MAC" ]; then
  problem "This is the Mac starter." "On Windows, double-click \"Start OSRS Companion.cmd\" instead."
  stop_here 1
fi

# ---------------------------------------------------------------------------
title "1/5  Checking the files"
# ---------------------------------------------------------------------------
if [ ! -f "$PLUGIN_DIR/gradlew" ] || [ ! -d "$PLUGIN_DIR/src" ]; then
  problem "The plugin files are incomplete." \
    "Expected the plugin in: $PLUGIN_DIR" \
    "Unzip the whole OSRS Companion zip again (double-click it in Finder), then" \
    "double-click \"Start OSRS Companion.command\" in the unzipped folder."
  stop_here 1
fi
case "$PACKAGE_DIR" in
  */AppTranslocation/*)
    problem "macOS is running this from a protected temporary copy." \
      "Move the unzipped OSRS-Companion folder somewhere else (for example to Documents)" \
      "with Finder, then double-click \"Start OSRS Companion.command\" there."
    stop_here 1 ;;
esac
ok "Plugin files found ($PLUGIN_DIR)"
# Files from a downloaded zip carry macOS's "downloaded from the internet" mark, which makes
# Gatekeeper block every double-click. The player already allowed this folder once to get
# here, so clear the mark for the whole folder: Update RuneLite.command and the desktop
# shortcut then open without the warning.
if command -v xattr >/dev/null 2>&1 && xattr -r "$PACKAGE_DIR" 2>/dev/null | grep -q com.apple.quarantine; then
  xattr -dr com.apple.quarantine "$PACKAGE_DIR" 2>/dev/null && ok "Allowed the OSRS-Companion folder to run on this Mac"
fi

# ---------------------------------------------------------------------------
title "2/5  Checking Java (JDK 11 or newer)"
# ---------------------------------------------------------------------------
java_major() {
  # Major version of the JDK in $1 (0 when it is not a JDK).
  local out
  [ -x "$1/bin/javac" ] || [ -x "$1/bin/javac.exe" ] || { echo 0; return; }
  out="$("$1/bin/java" -version 2>&1 | head -n 1)"
  if [[ "$out" =~ \"1\.([0-9]+) ]]; then echo "${BASH_REMATCH[1]}"
  elif [[ "$out" =~ \"([0-9]+) ]]; then echo "${BASH_REMATCH[1]}"
  else echo 0; fi
}
find_jdk() {
  # Prefer 11, then 17, then 21 (the versions the build tool supports best).
  local v home major
  for v in 11 17 21; do
    home="$("$JAVA_HOME_TOOL" -v "$v" 2>/dev/null)" || continue
    major="$(java_major "$home")"
    if [ "$major" -ge 11 ] && [ "$major" -le 23 ]; then JDK_HOME="$home"; JDK_MAJOR="$major"; return 0; fi
  done
  if [ -n "$JAVA_HOME" ]; then
    major="$(java_major "$JAVA_HOME")"
    if [ "$major" -ge 11 ] && [ "$major" -le 23 ]; then JDK_HOME="$JAVA_HOME"; JDK_MAJOR="$major"; return 0; fi
  fi
  return 1
}

if ! find_jdk; then
  problem "Java (JDK 11) is missing." \
    "Building RuneLite needs a Java Development Kit (JDK), version 11 (recommended), 17 or 21." \
    "None was found on this Mac. (The Java that RuneLite brings along is not enough.)"
  # The installer (.pkg) for this Mac's chip; the .tar.gz downloads are only a folder of files.
  if [ "$(uname -m)" = "arm64" ]; then JDK_ARCH=aarch64; CHIP="Apple M-chip"; else JDK_ARCH=x64; CHIP="Intel"; fi
  PKG_URL="https://api.adoptium.net/v3/installer/latest/11/ga/mac/$JDK_ARCH/jdk/hotspot/normal/eclipse?project=jdk"
  if ask "Download the free Java 11 installer for your Mac ($CHIP, Eclipse Temurin from adoptium.net) and open it?"; then
    PKG="$HOME/Downloads/Temurin-JDK-11-$JDK_ARCH.pkg"
    info "Downloading (about 180 MB)..."
    if curl -L --fail --progress-bar -o "$PKG" "$PKG_URL"; then
      open "$PKG"
      echo
      echo "  ${YELLOW}NEXT: the Java installer is open. Click Continue and Install (it asks for your Mac password),${RESET}"
      echo "  ${YELLOW}      then double-click \"Start OSRS Companion.command\" again.${RESET}"
      stop_here 0
    fi
    warn "The download failed."
  elif command -v brew >/dev/null 2>&1 && ask "Install it with Homebrew instead?"; then
    brew install --cask temurin@11
    find_jdk
  fi
  if [ -z "$JDK_HOME" ]; then
    info "Download the installer yourself (a .pkg file, double-click it to install):"
    info "$PKG_URL"
    info "then double-click \"Start OSRS Companion.command\" again."
    stop_here 1
  fi
fi
export JAVA_HOME="$JDK_HOME"
export PATH="$JDK_HOME/bin:$PATH"
ok "Java $JDK_MAJOR found ($JDK_HOME)"

# ---------------------------------------------------------------------------
title "3/5  Checking the internet connection"
# ---------------------------------------------------------------------------
FIRST_BUILD=1
[ -d "$PLUGIN_DIR/build/libs" ] && FIRST_BUILD=0
REACHABLE=1
for url in https://repo.runelite.net/ https://services.gradle.org/; do
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 15 -I "$url")"
  if [ "$code" = "000" ]; then REACHABLE=0; warn "Cannot reach $url"; fi
done
if [ "$REACHABLE" = 0 ]; then
  if [ "$FIRST_BUILD" = 1 ]; then
    problem "No connection to the download servers." \
      "The first build downloads RuneLite and the build tool (about 150 MB)." \
      "Check your internet connection (or VPN/firewall) and try again."
    stop_here 1
  fi
  warn "Offline: building with what was downloaded before. Updated RuneLite versions cannot be fetched now."
else
  ok "Download servers reachable"
fi

# ---------------------------------------------------------------------------
title "4/5  Checking the login (Jagex account)"
# ---------------------------------------------------------------------------
if [ -f "$CREDENTIALS" ]; then
  ok "Jagex account login file found"
else
  info "A self-built RuneLite cannot log in through the Jagex Launcher."
  info "If you use a Jagex account, RuneLite has to save your login to a file once."
  if ! ask "Do you log in with a Jagex account (the Jagex Launcher)?"; then
    ok "Old-style account: you log in with your username and password in RuneLite."
  else
    if [ ! -d "$RUNELITE_APP" ]; then
      problem "RuneLite is not installed." \
        "Install RuneLite from https://runelite.net (drag it to Applications) and log in once" \
        "through the Jagex Launcher, then run this again."
      stop_here 1
    fi
    echo
    warn "RuneLite will write your Jagex login session to:"
    info "$CREDENTIALS"
    info "Anyone with access to that file can log in to your account. Keep it private,"
    info "never share your .runelite folder, and delete that file if you stop using this."
    echo
    info "Turn it on in RuneLite's configure window:"
    info "  1. In \"Client arguments\" type:  --insecure-write-credentials"
    info "  2. Click Save."
    if ask "Open RuneLite's configure window now?"; then
      "$RUNELITE_APP/Contents/MacOS/RuneLite" --configure >/dev/null 2>&1 &
    else
      info "You can open it later in Terminal with:"
      info "  /Applications/RuneLite.app/Contents/MacOS/RuneLite --configure"
    fi
    echo
    echo "  ${YELLOW}NEXT: after saving, start RuneLite once through the Jagex Launcher (open it, then quit it),${RESET}"
    echo "  ${YELLOW}      then double-click \"Start OSRS Companion.command\" again.${RESET}"
    stop_here 0
  fi
fi

# ---------------------------------------------------------------------------
title "5/5  Building RuneLite with OSRS Companion"
# ---------------------------------------------------------------------------
if pgrep -f "osrs-companion-plugin.*-all.jar" >/dev/null 2>&1; then
  warn "RuneLite (OSRS Companion) is still running. Quit it first."
  read -r -p "          Press Enter once it is closed " _
fi
if [ "$FIRST_BUILD" = 1 ]; then
  info "First build: downloads RuneLite and the build tool. This can take 2 to 5 minutes."
else
  info "Updating the build (usually a few seconds)."
fi
GRADLE_ARGS=(shadowJar --console=plain)
[ "$REFRESH" = 1 ] && GRADLE_ARGS+=(--refresh-dependencies)
# "sh gradlew" works even when the zip tool dropped the file's executable flag.
( cd "$PLUGIN_DIR" && sh ./gradlew "${GRADLE_ARGS[@]}" ) >"$BUILD_LOG" 2>&1
code=$?
if [ "$code" -ne 0 ]; then
  hint="The full log is in: $BUILD_LOG"
  if grep -qE "Could not resolve|Could not GET|UnknownHost|Connection" "$BUILD_LOG"; then
    problem "The build failed." "Downloading failed. Check your internet connection and try again." "$hint"
  elif grep -qE "Unsupported class file major version|incompatible with the Java" "$BUILD_LOG"; then
    problem "The build failed." "Java version problem. Install JDK 11 from https://adoptium.net and try again." "$hint"
  else
    problem "The build failed." "$hint"
  fi
  echo "  ${GREY}Last lines of the log:${RESET}"
  tail -n 15 "$BUILD_LOG" | sed "s/^/    /"
  stop_here 1
fi
JAR="$(ls -t "$PLUGIN_DIR"/build/libs/*-all.jar 2>/dev/null | head -n 1)"
if [ -z "$JAR" ]; then
  problem "The build finished but the RuneLite file is missing." "See $BUILD_LOG"
  stop_here 1
fi
ok "Build ready"
if [ "$NO_LAUNCH" = 1 ]; then
  ok "Test run finished: built $(basename "$JAR"), not started."
  exit 0
fi

# Desktop shortcut on first use: a small start file that points at this folder. (A link would
# make the start file look for the plugin next to the link, on the Desktop.)
SHORTCUT="$HOME/Desktop/RuneLite (OSRS Companion).command"
if [ ! -e "$SHORTCUT" ] && [ -d "$HOME/Desktop" ]; then
  printf '#!/bin/bash\n# Starts RuneLite with OSRS Companion (made by %s).\nexec bash "%s"\n' \
    "$START_COMMAND" "$SCRIPT_DIR/start-osrs-companion.sh" >"$SHORTCUT" \
    && chmod +x "$SHORTCUT" && ok "Desktop shortcut \"RuneLite (OSRS Companion)\" created"
fi

# -ea is required: RuneLite only allows developer mode (which loads this plugin) with assertions on.
# --add-exports lets RuneLite use the macOS window features.
nohup "$JDK_HOME/bin/java" -ea --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED \
  -jar "$JAR" --developer-mode >/dev/null 2>&1 &
ok "RuneLite is starting."
echo
echo "  ${CYAN}First time? In RuneLite:${RESET}"
info "1. Make an account on $WEBSITE (Register), open Settings and copy your App Key."
info "2. In RuneLite: wrench (Configuration) > search \"OSRS Companion\" > paste the App Key."
info "3. Log in to the game. Your data shows on the website within a minute."
echo
info "After a game update, if RuneLite says it is outdated: double-click \"Update RuneLite.command\"."
info "You can close this window now."
