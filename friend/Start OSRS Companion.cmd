@echo off
title OSRS Companion
rem Checks everything that is needed, builds RuneLite with the OSRS Companion plugin and starts it.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0plugin\friend\start-osrs-companion.ps1"
