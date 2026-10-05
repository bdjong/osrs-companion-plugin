<#
  OSRS Companion - start RuneLite with the OSRS Companion plugin built in.

  Started by "Start OSRS Companion.cmd" (double-click it). It checks everything it needs,
  explains anything that is missing, builds RuneLite with the plugin and starts it.

  Why not the normal RuneLite: RuneLite only loads plugins that are not on the Plugin Hub
  when you build it yourself. This script does that build for you.
#>
param(
  [switch]$Refresh,
  # For testing the checks and the build: no desktop shortcut, RuneLite is not started.
  [switch]$NoLaunch
)

$ErrorActionPreference = 'Continue'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$PluginDir   = Split-Path $PSScriptRoot -Parent
$PackageDir  = Split-Path $PluginDir -Parent
$StartCmd    = Join-Path $PackageDir 'Start OSRS Companion.cmd'
$BuildLog    = Join-Path $PackageDir 'build-log.txt'
$RuneLiteDir = Join-Path $env:USERPROFILE '.runelite'
$Credentials = Join-Path $RuneLiteDir 'credentials.properties'
$RuneLiteApp = Join-Path $env:LOCALAPPDATA 'RuneLite'
$Website     = 'https://osrs-companion-web.vercel.app'

function Title($text) { Write-Host ''; Write-Host "  $text" -ForegroundColor Cyan }
function Ok($text)    { Write-Host "  [OK]    $text" -ForegroundColor Green }
function Info($text)  { Write-Host "          $text" }
function Warn($text)  { Write-Host "  [!]     $text" -ForegroundColor Yellow }
function Problem($title, [string[]]$lines) {
  Write-Host ''
  Write-Host "  [PROBLEM] $title" -ForegroundColor Red
  foreach ($l in $lines) { Write-Host "            $l" }
  Write-Host ''
}
function Ask($question) {
  $answer = Read-Host "  [?]     $question [y/n]"
  return $answer -match '^[yYjJ]'
}
function Stop-Here($code) {
  Write-Host ''
  Read-Host '  Press Enter to close this window' | Out-Null
  exit $code
}

Write-Host ''
Write-Host '  ==============================================' -ForegroundColor Cyan
Write-Host '   OSRS Companion - RuneLite with the plugin' -ForegroundColor Cyan
Write-Host '  ==============================================' -ForegroundColor Cyan

# ---------------------------------------------------------------------------
Title '1/5  Checking the files'
# ---------------------------------------------------------------------------
if (-not (Test-Path (Join-Path $PluginDir 'gradlew.bat')) -or -not (Test-Path (Join-Path $PluginDir 'src'))) {
  Problem 'The plugin files are incomplete.' @(
    "Expected the plugin in: $PluginDir",
    'Unzip the whole OSRS Companion zip again (all folders), then double-click',
    '"Start OSRS Companion.cmd" in the unzipped folder.')
  Stop-Here 1
}
# Opening a file straight from a zip makes Windows run it from "...\Temp1_<name>.zip\...".
if ($PackageDir -match '\\Temp\d*_[^\\]+\.zip(\\|$)' -or $PackageDir -match '\.zip\\') {
  Problem 'You started it from inside the zip.' @(
    'Windows runs it from a temporary folder, which does not work.',
    'Right-click the zip > "Extract All...", open the extracted folder and',
    'double-click "Start OSRS Companion.cmd" there.')
  Stop-Here 1
}
Ok "Plugin files found ($PluginDir)"

# ---------------------------------------------------------------------------
Title '2/5  Checking Java (JDK 11 or newer)'
# ---------------------------------------------------------------------------
function Get-JavaMajor($javac) {
  $out = (cmd /c "`"$javac`" -version 2>&1") | Out-String
  if ($out -match 'javac (\d+)(\.(\d+))?') {
    $major = [int]$Matches[1]
    if ($major -eq 1 -and $Matches[3]) { $major = [int]$Matches[3] }
    return $major
  }
  return 0
}
function Find-Jdk {
  $homes = New-Object System.Collections.Generic.List[string]
  if ($env:JAVA_HOME) { $homes.Add($env:JAVA_HOME) }
  foreach ($root in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
                      "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft",
                      "$env:LOCALAPPDATA\Programs\Eclipse Adoptium")) {
    if (Test-Path $root) { Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object { $homes.Add($_.FullName) } }
  }
  $onPath = Get-Command javac -ErrorAction SilentlyContinue
  if ($onPath) { $homes.Add((Split-Path (Split-Path $onPath.Source -Parent) -Parent)) }
  $found = @()
  foreach ($h in ($homes | Select-Object -Unique)) {
    $javac = Join-Path $h 'bin\javac.exe'
    if (Test-Path $javac) { $found += [pscustomobject]@{ Home = $h; Major = (Get-JavaMajor $javac) } }
  }
  # Prefer 11, then 17, then 21 (the versions the build tool supports best).
  $good = $found | Where-Object { $_.Major -ge 11 -and $_.Major -le 23 } | Sort-Object @{ Expression = { if ($_.Major -eq 11) { 0 } elseif ($_.Major -eq 17) { 1 } else { 2 } } }, Major
  return [pscustomobject]@{ Best = ($good | Select-Object -First 1); All = $found }
}

$jdk = Find-Jdk
if (-not $jdk.Best) {
  $tooNew = $jdk.All | Where-Object { $_.Major -gt 23 }
  $tooOld = $jdk.All | Where-Object { $_.Major -gt 0 -and $_.Major -lt 11 }
  $lines = @('Building RuneLite needs a Java Development Kit (JDK), version 11 (recommended), 17 or 21.')
  if ($tooOld) { $lines += "Found only an older Java ($(($tooOld | ForEach-Object { $_.Major }) -join ', ')), which is too old." }
  if ($tooNew) { $lines += "Found only Java $(($tooNew | ForEach-Object { $_.Major }) -join ', '), which is too new for the build tool." }
  if (-not $jdk.All) { $lines += 'No JDK was found on this PC. (A normal "Java" for browsers or Minecraft is not enough.)' }
  Problem 'Java (JDK 11) is missing.' $lines
  if (Get-Command winget -ErrorAction SilentlyContinue) {
    if (Ask 'Install Eclipse Temurin JDK 11 now with winget (free, from adoptium.net)?') {
      winget install --id EclipseAdoptium.Temurin.11.JDK -e
      $jdk = Find-Jdk
    }
  }
  if (-not $jdk.Best) {
    Info 'Install it yourself from: https://adoptium.net/temurin/releases/?version=11'
    Info '(choose Windows, x64, JDK, .msi), then double-click "Start OSRS Companion.cmd" again.'
    Stop-Here 1
  }
}
$env:JAVA_HOME = $jdk.Best.Home
$env:Path = "$($jdk.Best.Home)\bin;$env:Path"
Ok "Java $($jdk.Best.Major) found ($($jdk.Best.Home))"

# ---------------------------------------------------------------------------
Title '3/5  Checking the internet connection'
# ---------------------------------------------------------------------------
$firstBuild = -not (Test-Path (Join-Path $PluginDir 'build\libs'))
$reachable = $true
foreach ($url in @('https://repo.runelite.net/', 'https://services.gradle.org/')) {
  try { Invoke-WebRequest -Uri $url -Method Head -TimeoutSec 15 -UseBasicParsing | Out-Null }
  catch {
    if (-not $_.Exception.Response) { $reachable = $false; Warn "Cannot reach $url" }
  }
}
if (-not $reachable) {
  if ($firstBuild) {
    Problem 'No connection to the download servers.' @(
      'The first build downloads RuneLite and the build tool (about 150 MB).',
      'Check your internet connection (or VPN/firewall) and try again.')
    Stop-Here 1
  }
  Warn 'Offline: building with what was downloaded before. Updated RuneLite versions cannot be fetched now.'
} else {
  Ok 'Download servers reachable'
}

# ---------------------------------------------------------------------------
Title '4/5  Checking the login (Jagex account)'
# ---------------------------------------------------------------------------
if (Test-Path $Credentials) {
  Ok 'Jagex account login file found'
} else {
  Info 'A self-built RuneLite cannot log in through the Jagex Launcher.'
  Info 'If you use a Jagex account, RuneLite has to save your login to a file once.'
  if (-not (Ask 'Do you log in with a Jagex account (the Jagex Launcher)?')) {
    Ok 'Old-style account: you log in with your username and password in RuneLite.'
  } else {
    if (-not (Test-Path (Join-Path $RuneLiteApp 'RuneLite.exe'))) {
      Problem 'RuneLite is not installed.' @(
        'Install RuneLite from https://runelite.net and log in once through the Jagex Launcher,',
        'then run this again.')
      Stop-Here 1
    }
    Write-Host ''
    Warn 'RuneLite will write your Jagex login session to:'
    Info $Credentials
    Info 'Anyone with access to that file can log in to your account. Keep it private,'
    Info 'never share your .runelite folder, and delete that file if you stop using this.'
    $settingsFile = Join-Path $RuneLiteApp 'settings.json'
    if (Ask 'Turn this on now (adds --insecure-write-credentials to RuneLite''s settings)?') {
      $settings = $null
      if (Test-Path $settingsFile) { try { $settings = Get-Content $settingsFile -Raw | ConvertFrom-Json } catch { $settings = $null } }
      if (-not $settings) { $settings = New-Object PSObject }
      $clientArgs = @()
      if ($settings.PSObject.Properties['clientArguments']) { $clientArgs = @($settings.clientArguments) }
      if ($clientArgs -notcontains '--insecure-write-credentials') {
        $updated = [object[]]($clientArgs + '--insecure-write-credentials')
        if ($settings.PSObject.Properties['clientArguments']) { $settings.clientArguments = $updated }
        else { $settings | Add-Member -NotePropertyName clientArguments -NotePropertyValue $updated }
        [IO.File]::WriteAllText($settingsFile, ($settings | ConvertTo-Json -Depth 10), (New-Object Text.UTF8Encoding($false)))
      }
      Ok 'Turned on.'
    } else {
      Info 'You can also do it by hand: Start menu > "RuneLite (configure)" > Client arguments:'
      Info '--insecure-write-credentials > Save.'
    }
    Write-Host ''
    Write-Host '  NEXT: start RuneLite once through the Jagex Launcher (just open it, then close it),' -ForegroundColor Yellow
    Write-Host '        then double-click "Start OSRS Companion.cmd" again.' -ForegroundColor Yellow
    Stop-Here 0
  }
}

# ---------------------------------------------------------------------------
Title '5/5  Building RuneLite with OSRS Companion'
# ---------------------------------------------------------------------------
$running = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" -ErrorAction SilentlyContinue |
  Where-Object { $_.CommandLine -like '*osrs-companion-plugin*-all.jar*' }
if ($running) {
  Warn 'RuneLite (OSRS Companion) is still running. Close it first.'
  Read-Host '          Press Enter once it is closed' | Out-Null
}
if ($firstBuild) { Info 'First build: downloads RuneLite and the build tool. This can take 2 to 5 minutes.' }
else { Info 'Updating the build (usually a few seconds).' }
$gradleArgs = 'shadowJar --console=plain'
if ($Refresh) { $gradleArgs += ' --refresh-dependencies' }
# cmd does not inherit PowerShell's current folder, so change to the plugin folder inside cmd,
# and call gradlew by its full path (some PCs do not run programs from the current folder by name).
$gradlew = Join-Path $PluginDir 'gradlew.bat'
cmd /c "cd /d `"$PluginDir`" && `"$gradlew`" $gradleArgs > `"$BuildLog`" 2>&1"
$code = $LASTEXITCODE
if ($code -ne 0) {
  $log = Get-Content $BuildLog -ErrorAction SilentlyContinue
  $hint = @("The full log is in: $BuildLog")
  $text = $log -join "`n"
  if ($text -match 'Could not resolve|Could not GET|UnknownHost|Connection') { $hint = @('Downloading failed. Check your internet connection and try again.') + $hint }
  elseif ($text -match 'Unsupported class file major version|incompatible with the Java') { $hint = @('Java version problem. Install JDK 11 from https://adoptium.net and try again.') + $hint }
  elseif ($text -match 'being used by another process|AccessDenied') { $hint = @('A file is in use: close RuneLite (OSRS Companion) and try again.') + $hint }
  Problem 'The build failed.' $hint
  Write-Host '  Last lines of the log:' -ForegroundColor DarkGray
  $log | Select-Object -Last 15 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
  Stop-Here 1
}
$jar = Get-ChildItem (Join-Path $PluginDir 'build\libs') -Filter '*-all.jar' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { Problem 'The build finished but the RuneLite file is missing.' @("See $BuildLog"); Stop-Here 1 }
Ok 'Build ready'
if ($NoLaunch) {
  Ok "Test run finished: built $($jar.Name), not started."
  exit 0
}

# Desktop shortcut on first use.
$desktop = [Environment]::GetFolderPath('Desktop')
$lnkPath = Join-Path $desktop 'RuneLite (OSRS Companion).lnk'
if (-not (Test-Path $lnkPath) -and (Test-Path $StartCmd)) {
  $sh = New-Object -ComObject WScript.Shell
  $l = $sh.CreateShortcut($lnkPath)
  $l.TargetPath = $StartCmd
  $l.WorkingDirectory = $PackageDir
  $icon = Join-Path $RuneLiteApp 'RuneLite.exe'
  if (Test-Path $icon) { $l.IconLocation = "$icon,0" }
  $l.Save()
  Ok 'Desktop shortcut "RuneLite (OSRS Companion)" created'
}

# -ea is required: RuneLite only allows developer mode (which loads this plugin) with assertions on.
$javaw = Join-Path $jdk.Best.Home 'bin\javaw.exe'
Start-Process -FilePath $javaw -ArgumentList @('-ea', '-jar', "`"$($jar.FullName)`"", '--developer-mode') -WorkingDirectory $PluginDir
Ok 'RuneLite is starting.'
Write-Host ''
Write-Host '  First time? In RuneLite:' -ForegroundColor Cyan
Info "1. Make an account on $Website (Register), open Settings and copy your App Key."
Info '2. In RuneLite: wrench (Configuration) > search "OSRS Companion" > paste the App Key.'
Info '3. Log in to the game. Your data shows on the website within a minute.'
Write-Host ''
Info 'After a game update, if RuneLite says it is outdated: run "Start OSRS Companion.cmd" with'
Info 'the newest zip, or start "Update RuneLite.cmd" to fetch the newest RuneLite.'
Start-Sleep -Seconds 8
