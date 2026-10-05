#!/bin/bash
# Mac: use after a game update when RuneLite says it is outdated: fetches the newest RuneLite, then starts.
cd "$(dirname "$0")" || exit 1
bash "./plugin/friend/start-osrs-companion.sh" --refresh
