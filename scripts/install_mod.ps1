# Install Leximine into Minecraft as its own launcher profile.
#
#   powershell -ExecutionPolicy Bypass -File scripts\install_mod.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\install_mod.ps1 -Uninstall
#
# Deliberately does NOT use %APPDATA%\.minecraft\mods. That folder is shared by
# every Fabric profile, and this machine already has a 1.21.11 mod sitting in
# it -- dropping a 1.20.4 mod alongside it makes both profiles throw dependency
# errors on launch. Instead the profile gets its own game directory, so
# Leximine and everything else stay out of each other's way.
#
# Safe to re-run: it overwrites the jar in place, which is how you update after
# a rebuild.

param(
    [string]$MinecraftRoot = "$env:APPDATA\.minecraft",
    [string]$GameDir = "",
    [string]$ProfileName = "Leximine",
    [string]$ProfileKey = "leximine",
    [switch]$Uninstall,
    [switch]$NoWait
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

function Write-Step($text) { Write-Host "  $text" -ForegroundColor Gray }
function Write-Ok($text) { Write-Host "  $text" -ForegroundColor Green }
function Write-Warn2($text) { Write-Host "  $text" -ForegroundColor Yellow }
function Write-Bad($text) { Write-Host "  $text" -ForegroundColor Red }

function Fail($text) {
    Write-Host ""
    Write-Bad $text
    Write-Host ""
    if (-not $NoWait) { Read-Host "  Press Enter to close" }
    exit 1
}

$MC_VERSION = "1.20.4"
$FABRIC_API_URL = "https://cdn.modrinth.com/data/P7dR8mSH/versions/BPX6fK06/fabric-api-0.97.3%2B1.20.4.jar"
$FABRIC_API_NAME = "fabric-api-0.97.3+1.20.4.jar"
$FABRIC_API_SHA1 = "a21f3c7e700b9fc3a28e896979a07ec489507143"

if ($GameDir -eq "") { $GameDir = Join-Path $MinecraftRoot "leximine" }
$profilesPath = Join-Path $MinecraftRoot "launcher_profiles.json"

Write-Host ""
Write-Host "  Leximine - install into Minecraft" -ForegroundColor Cyan
Write-Host "  ------------------------------------" -ForegroundColor DarkGray
Write-Host ""

if (-not (Test-Path $MinecraftRoot)) { Fail "No Minecraft install at $MinecraftRoot" }
if (-not (Test-Path $profilesPath)) { Fail "launcher_profiles.json not found. Run the Minecraft launcher once first." }

# The launcher rewrites launcher_profiles.json when it closes, which would
# silently throw away a profile added underneath it. Only the profile edit is
# affected though -- dropping a newer jar into the mods folder is safe at any
# time, and refusing to do that while the launcher is open made updating the
# mod after a rebuild needlessly annoying.
$launcherRunning = [bool](Get-Process -Name "MinecraftLauncher", "Minecraft" -ErrorAction SilentlyContinue)

# --- uninstall --------------------------------------------------------
if ($Uninstall) {
    if ($launcherRunning) {
        Fail "Close the Minecraft launcher first - it rewrites its profile list on exit."
    }
    $json = Get-Content $profilesPath -Raw | ConvertFrom-Json
    if ($json.profiles.PSObject.Properties.Name -contains $ProfileKey) {
        $backup = "$profilesPath.leximine-backup"
        Copy-Item $profilesPath $backup -Force
        $json.profiles.PSObject.Properties.Remove($ProfileKey)
        $json | ConvertTo-Json -Depth 100 | Set-Content $profilesPath -Encoding ascii
        Write-Ok "Removed the '$ProfileName' profile (backup: $backup)."
    } else {
        Write-Step "No '$ProfileName' profile to remove."
    }
    Write-Step "Your saves and mods are left alone at $GameDir"
    Write-Step "Delete that folder by hand if you want them gone."
    Write-Host ""
    if (-not $NoWait) { Read-Host "  Press Enter to close" }
    exit 0
}

# --- the mod jar ------------------------------------------------------
# Looked for in three places so one script serves both audiences: a source
# checkout builds to build\libs, while a downloaded release is just a jar
# sitting next to these scripts with no Gradle, no JDK and no source anywhere
# near it.
function Get-ModJar {
    foreach ($dir in @((Join-Path $root "build\libs"), $root, (Join-Path $root "mod"))) {
        $found = Get-ChildItem $dir -Filter "leximine-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notlike "*-sources.jar" } |
            Sort-Object LastWriteTime -Descending |
            Select-Object -First 1
        if ($found) { return $found }
    }
    return $null
}

$jar = Get-ModJar
if (-not $jar) {
    $gradlew = Join-Path $root "gradlew.bat"
    if (-not (Test-Path $gradlew)) {
        # A release with no jar in it is a packaging mistake, and telling
        # someone without a JDK to "run gradlew" is a dead end.
        Fail "No leximine-*.jar found next to this script. Re-download the release."
    }
    Write-Step "No built jar yet - building it now (first build takes a while)..."
    & $gradlew build --no-daemon | Out-Null
    $jar = Get-ModJar
    if (-not $jar) { Fail "Build failed. Run gradlew.bat build and read the error." }
}
Write-Step "mod        $($jar.Name)  ($([math]::Round($jar.Length/1KB)) KB)"

# --- the Fabric loader version ---------------------------------------
$loader = Get-ChildItem (Join-Path $MinecraftRoot "versions") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like "fabric-loader-*-$MC_VERSION" } |
    Sort-Object Name -Descending |
    Select-Object -First 1

if (-not $loader) {
    Write-Bad "No Fabric loader installed for Minecraft $MC_VERSION."
    Write-Step "Get it from https://fabricmc.net/use/installer - pick $MC_VERSION,"
    Write-Step "tick 'Create profile', then run this again."
    Fail "Cannot continue without the Fabric loader."
}
Write-Step "loader     $($loader.Name)"

# --- game directory ---------------------------------------------------
$modsDir = Join-Path $GameDir "mods"
New-Item -ItemType Directory -Force -Path $modsDir | Out-Null
Write-Step "game dir   $GameDir"

# Clear out older builds so two versions of the mod never load at once.
Get-ChildItem $modsDir -Filter "leximine-*.jar" -ErrorAction SilentlyContinue |
    ForEach-Object { Remove-Item $_.FullName -Force }
Copy-Item $jar.FullName (Join-Path $modsDir $jar.Name) -Force

# --- Fabric API -------------------------------------------------------
# Required: the mod declares a hard dependency on it, and nothing on this
# machine ships it.
$apiPath = Join-Path $modsDir $FABRIC_API_NAME
$haveApi = Test-Path $apiPath
if ($haveApi) {
    $actual = (Get-FileHash $apiPath -Algorithm SHA1).Hash.ToLower()
    if ($actual -ne $FABRIC_API_SHA1) {
        Write-Warn2 "Existing Fabric API looks corrupt; downloading again."
        $haveApi = $false
    }
}

if (-not $haveApi) {
    Write-Step "Downloading Fabric API (2 MB, from modrinth.com)..."
    try {
        Invoke-WebRequest $FABRIC_API_URL -OutFile $apiPath -UseBasicParsing
    } catch {
        Fail "Could not download Fabric API: $($_.Exception.Message)"
    }
    $actual = (Get-FileHash $apiPath -Algorithm SHA1).Hash.ToLower()
    if ($actual -ne $FABRIC_API_SHA1) {
        Remove-Item $apiPath -Force
        Fail "Fabric API download did not match its published checksum; deleted it."
    }
}
Write-Step "fabric api $FABRIC_API_NAME  (checksum verified)"

# --- the launcher profile ---------------------------------------------
$json = Get-Content $profilesPath -Raw | ConvertFrom-Json
$profileExists = $json.profiles.PSObject.Properties.Name -contains $ProfileKey

if ($launcherRunning) {
    if ($profileExists) {
        # The jar is already updated above, which is the part that matters
        # for an update. Leave the profile list alone.
        Write-Host ""
        Write-Ok "Mod updated. The '$ProfileName' profile already exists, so nothing else to do."
        Write-Step "Restart Minecraft (not just the world) to load the new jar."
        Write-Host ""
        if (-not $NoWait) { Read-Host "  Press Enter to close" }
        exit 0
    }
    Fail ("The mod files are in place, but the '$ProfileName' profile could not be created: " +
          "close the Minecraft launcher and run this again. It rewrites its profile list on exit, " +
          "which would throw the new profile away.")
}

$backup = "$profilesPath.leximine-backup"
Copy-Item $profilesPath $backup -Force

$now = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss.fffZ")

$icon = "Furnace"
$iconPng = Join-Path $root "leximine.png"
if (Test-Path $iconPng) {
    $bytes = [System.IO.File]::ReadAllBytes($iconPng)
    $icon = "data:image/png;base64," + [Convert]::ToBase64String($bytes)
}

$entry = [ordered]@{
    created        = $now
    gameDir        = $GameDir
    icon           = $icon
    lastUsed       = $now
    lastVersionId  = $loader.Name
    name           = $ProfileName
    type           = "custom"
}

if ($profileExists) {
    $json.profiles.PSObject.Properties.Remove($ProfileKey)
    $verb = "Updated"
} else {
    $verb = "Created"
}
$json.profiles | Add-Member -NotePropertyName $ProfileKey -NotePropertyValue ([PSCustomObject]$entry)

# -Encoding ascii on purpose: a BOM here is the kind of thing that makes the
# launcher quietly rebuild its profile list from scratch. The icon is already
# base64 and everything else is plain, so nothing needs a wider encoding.
$json | ConvertTo-Json -Depth 100 | Set-Content $profilesPath -Encoding ascii

# Read it straight back rather than trusting the write.
try {
    $check = Get-Content $profilesPath -Raw | ConvertFrom-Json
    if (-not ($check.profiles.PSObject.Properties.Name -contains $ProfileKey)) {
        throw "profile missing after write"
    }
} catch {
    Copy-Item $backup $profilesPath -Force
    Fail "Profile write failed and was rolled back from the backup: $($_.Exception.Message)"
}

Write-Host ""
Write-Ok "$verb the '$ProfileName' profile."
Write-Host ""
Write-Step "Installed:"
Write-Step "  $modsDir"
Get-ChildItem $modsDir -Filter *.jar | ForEach-Object {
    Write-Step ("    {0,-34} {1,6} KB" -f $_.Name, [math]::Round($_.Length / 1KB))
}
Write-Host ""
Write-Host "  Next:" -ForegroundColor Cyan
Write-Step "  1. Start the backend (desktop shortcut: Leximine Backend)"
Write-Step "  2. Open the Minecraft launcher and pick the '$ProfileName' profile"
Write-Step "  3. Play - pick something up and say its name"
Write-Host ""
Write-Step "Your other profiles are untouched; this one has its own saves and mods."
Write-Step "Backup of the profile list: $backup"
Write-Host ""

if (-not $NoWait) { Read-Host "  Press Enter to close" }
