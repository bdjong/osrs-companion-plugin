@echo off
title OSRS Companion - update RuneLite
rem Use after a game update when RuneLite says it is outdated: fetches the newest RuneLite, then starts.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0plugin\friend\start-osrs-companion.ps1" -Refresh
