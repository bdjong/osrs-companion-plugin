@echo off
rem Builds the plugin + RuneLite fat jar and launches it against the live servers.
rem Usage: run-dev.bat          (build + run)
rem        run-dev.bat nobuild  (run last build)
setlocal
cd /d "%~dp0"

for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-11*") do set "JAVA_HOME=%%D"
if not defined JAVA_HOME (
	echo JDK 11 not found. Install with: winget install EclipseAdoptium.Temurin.11.JDK
	exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%USERPROFILE%\.runelite\credentials.properties" (
	echo WARNING: no Jagex account credentials found - you won't be able to log in.
	echo Launch RuneLite once via the Jagex Launcher with --insecure-write-credentials enabled.
	echo.
)

if /i not "%~1"=="nobuild" (
	call gradlew.bat shadowJar --console=plain || exit /b 1
)

java -ea -jar build\libs\osrs-companion-plugin-1.0-SNAPSHOT-all.jar --developer-mode
