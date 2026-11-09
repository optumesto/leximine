# Assemble a release anyone can download, unzip and run.
#
#   powershell -ExecutionPolicy Bypass -File scripts\make_release.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\make_release.ps1 -Zip
#
# Produces dist\Leximine-<version>\ with both halves in the layout setup.cmd
# expects:
#
#   Leximine-<version>\
#     INSTALL.md
#     mod\       the built jar + install_mod.ps1
#     backend\   the speech server, minus everything machine-specific
#
# What is deliberately NOT shipped, and why:
#
#   .venv    2.3 GB of compiled wheels built against THIS machine's Python.
#            setup.cmd rebuilds it in a minute.
#   models   1.9 GB, and the weights are reached through relative symlinks
#            that Windows zip tools silently flatten to 0-byte files. A
#            shipped models folder would look complete and be empty, and the
#            backend would die on load talking about a corrupt model. It
#            downloads itself on first run instead.
#   .env     personal overrides, possibly pointing at paths or ports that
#            exist nowhere else. .env.example ships in its place.
#   data,
#   cache    one person's vocabulary history and cached speech audio.

param([switch]$Zip)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
# "lingua-stt-backend" is the pre-2026-11-05 folder name, still accepted so
# a clone made before the rename keeps building releases.
$parent = Split-Path $root -Parent
$backend = Join-Path $parent "leximine-backend"
if (-not (Test-Path $backend)) { $backend = Join-Path $parent "lingua-stt-backend" }

function Step($text) { Write-Host "  $text" -ForegroundColor Gray }
function Ok($text) { Write-Host "  $text" -ForegroundColor Green }

Write-Host ""
Write-Host "  Leximine - build a release" -ForegroundColor Cyan
Write-Host "  --------------------------" -ForegroundColor DarkGray
Write-Host ""

if (-not (Test-Path $backend)) { throw "backend not found next to this repo at $backend" }

# --- version, from the one place that defines it ----------------------
$version = (Select-String -Path (Join-Path $root "gradle.properties") -Pattern "^version=(.+)$").Matches[0].Groups[1].Value
Step "version $version"

# --- a fresh jar ------------------------------------------------------
Step "building..."
# --project-dir, not the working directory. gradlew.bat finds its own wrapper
# from where the script lives, but Gradle takes the PROJECT from the directory
# you happen to be standing in -- so this ran fine from inside the repo and
# failed with "does not contain a Gradle build" from anywhere else, including
# a double-click.
& (Join-Path $root "gradlew.bat") build --console=plain -q --project-dir $root
if ($LASTEXITCODE -ne 0) { throw "gradle build failed" }
$jar = Get-ChildItem (Join-Path $root "build\libs") -Filter "leximine-*.jar" |
    Where-Object { $_.Name -notlike "*-sources.jar" } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "no jar in build\libs after a successful build" }
Ok "jar  $($jar.Name)  ($([math]::Round($jar.Length / 1KB)) KB)"

# --- lay it out -------------------------------------------------------
$dist = Join-Path $root "dist"
$stage = Join-Path $dist "Leximine-$version"
if (Test-Path $stage) { Remove-Item $stage -Recurse -Force }
New-Item -ItemType Directory -Force -Path $stage | Out-Null

$modOut = Join-Path $stage "mod"
New-Item -ItemType Directory -Force -Path (Join-Path $modOut "scripts") | Out-Null
Copy-Item $jar.FullName (Join-Path $modOut $jar.Name)
Copy-Item (Join-Path $root "scripts\install_mod.ps1") (Join-Path $modOut "scripts\install_mod.ps1")
Copy-Item (Join-Path $root "leximine.png") (Join-Path $modOut "leximine.png") -ErrorAction SilentlyContinue

$backendOut = Join-Path $stage "backend"
New-Item -ItemType Directory -Force -Path $backendOut | Out-Null
foreach ($dir in @("app", "scripts")) {
    Copy-Item (Join-Path $backend $dir) (Join-Path $backendOut $dir) -Recurse
}

# lang\*.json is deliberately NOT shipped. Those are Minecraft's own
# translation strings, extracted from the game's asset cache -- Mojang's
# content, not ours to redistribute, and not ours to put under our licence
# either. It would also be a strange thing for this project of all projects to
# get wrong, given it exists to argue about the EULA.
#
# Nothing is lost by leaving them out: start_backend.ps1 extracts the
# dictionary from the player's own Minecraft install the first time it runs,
# and says so plainly if it cannot. It is also 3.4 of the 4 MB.
$langOut = Join-Path $backendOut "lang"
New-Item -ItemType Directory -Force -Path $langOut | Out-Null
@"
Dictionaries are not shipped with Leximine.

They are Minecraft's own translation files, and they belong to Mojang. The
backend extracts the one you need from your own Minecraft installation the
first time it starts, and tells you what to do if it cannot find it.

Nothing for you to do here.
"@ | Set-Content -Path (Join-Path $langOut "README.txt") -Encoding ascii
# LICENSE is in this list and asserted below. The backend is MPL-2.0 and the
# two halves ship in one zip, so a download without it is one the person who
# downloaded it cannot legally pass on.
foreach ($file in @("requirements.txt", ".env.example", "run.ps1",
                    "setup.cmd", "start-backend.cmd", "stop-backend.cmd",
                    "check-setup.cmd", "leximine.ico", "leximine.png",
                    "README.md", "PROTOCOL.md",
                    "LICENSE", "LICENSE.txt", "LICENSE.md")) {
    $src = Join-Path $backend $file
    if (Test-Path $src) { Copy-Item $src (Join-Path $backendOut $file) }
}
# Compiled bytecode from this machine's interpreter, which is noise at best.
Get-ChildItem $backendOut -Recurse -Directory -Filter "__pycache__" |
    Remove-Item -Recurse -Force

Copy-Item (Join-Path $root "INSTALL.md") (Join-Path $stage "INSTALL.md")
foreach ($file in @("LICENSE", "LICENSE.txt", "LICENSE.md")) {
    $src = Join-Path $root $file
    if (Test-Path $src) { Copy-Item $src (Join-Path $stage $file) }
}

# --- one click, at the top of the zip ---------------------------------
# Without this the first instruction after unzipping is "go into backend\",
# which is not a folder anyone expects an installer to live in. It forwards to
# the very script backend\setup.cmd runs, and setup.ps1 resolves the mod and
# the backend from its own location rather than the working directory, so the
# two entry points are interchangeable.
$setupCmd = @(
    '@echo off',
    'rem Double-click this. It installs both halves -- the speech backend and',
    'rem the mod -- and adds a Leximine profile to your Minecraft launcher.',
    'rem Identical to backend\setup.cmd; this copy is here so you do not have',
    'rem to go looking for it.',
    'title Leximine setup',
    'powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend\scripts\setup.ps1" %*',
    'if errorlevel 1 pause'
) -join "`r`n"
# CRLF written explicitly, not by Set-Content: a here-string carries the line
# endings of THIS file, and cmd.exe wants CRLF. See the check below.
[System.IO.File]::WriteAllText((Join-Path $stage "Setup.cmd"), $setupCmd + "`r`n",
                               [System.Text.Encoding]::ASCII)

# The diagnostic, next to the installer. Both issue templates, INSTALL.md and
# the release notes all say "run check-setup.cmd first" -- and it was the one
# file of the four that lived a folder down, where nobody told to run it in a
# hurry would look.
$checkCmd = @(
    '@echo off',
    'rem Double-click this when something is not working. It inspects every',
    'rem piece -- Minecraft, Fabric, the mod, Python, the GPU, the backend --',
    'rem and names the one that is missing, with the fix. It changes nothing.',
    'title Leximine setup check',
    'powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend\scripts\doctor.ps1"'
) -join "`r`n"
[System.IO.File]::WriteAllText((Join-Path $stage "check-setup.cmd"), $checkCmd + "`r`n",
                               [System.Text.Encoding]::ASCII)

# --- prove it is actually complete ------------------------------------
# Asserted rather than assumed: a release is the one artifact nobody tests
# before a stranger does.
$required = @(
    "INSTALL.md",
    "Setup.cmd",
    "check-setup.cmd",
    "mod\$($jar.Name)",
    "mod\scripts\install_mod.ps1",
    "backend\setup.cmd",
    "backend\check-setup.cmd",
    "backend\start-backend.cmd",
    "backend\requirements.txt",
    "backend\app\main.py",
    "backend\scripts\setup.ps1",
    "backend\scripts\doctor.ps1",
    "backend\lang\README.txt",
    "backend\LICENSE",
    "LICENSE"
)
$missing = @()
foreach ($rel in $required) {
    if (-not (Test-Path (Join-Path $stage $rel))) { $missing += $rel }
}
# And the things that must NOT be in there.
foreach ($rel in @("backend\.venv", "backend\models", "backend\.env", "backend\data")) {
    if (Test-Path (Join-Path $stage $rel)) { $missing += "SHOULD NOT SHIP: $rel" }
}
# Mojang's translation strings, specifically.
$strays = Get-ChildItem (Join-Path $stage "backend\lang") -Filter "*.json" -ErrorAction SilentlyContinue
foreach ($stray in $strays) { $missing += "SHOULD NOT SHIP: backend\lang\$($stray.Name)" }
# Every .cmd must use CRLF. cmd.exe tolerates LF right up until it does not --
# a label or an if-block is enough -- and the failure reads as anything but a
# line-ending problem. This has already shipped wrong once: check-setup.cmd
# went out in 0.1.0 with LF endings, because .gitattributes fixes a fresh clone
# and says nothing about a working copy that predates the rule, and `git status`
# stays silent because it compares normalised content.
foreach ($cmdFile in (Get-ChildItem $stage -Recurse -Filter "*.cmd")) {
    $bytes = [System.IO.File]::ReadAllBytes($cmdFile.FullName)
    $lf = 0; $crlf = 0
    for ($i = 0; $i -lt $bytes.Length; $i++) {
        if ($bytes[$i] -eq 10) {
            $lf++
            if ($i -gt 0 -and $bytes[$i - 1] -eq 13) { $crlf++ }
        }
    }
    if ($lf -ne $crlf) {
        $missing += "LF ENDINGS, cmd.exe needs CRLF: $($cmdFile.Name) ($crlf of $lf)"
    }
}
if ($missing.Count -gt 0) {
    Write-Host ""
    foreach ($m in $missing) { Write-Host "  !! $m" -ForegroundColor Red }
    throw "release is incomplete"
}

$size = [math]::Round((Get-ChildItem $stage -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)
Ok "staged  $stage  ($size MB)"

if ($Zip) {
    # Not version-stamped, deliberately. GitHub serves the newest release's
    # asset at /releases/latest/download/<name>, so a fixed name makes one URL
    # the download link forever -- the version is on the release and on the
    # folder inside, which is where a player can still read it.
    $zipPath = Join-Path $dist "Leximine.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path $stage -DestinationPath $zipPath
    Ok "zipped  $zipPath  ($([math]::Round((Get-Item $zipPath).Length / 1MB, 1)) MB)"
}

Write-Host ""
Step "Contents:"
Get-ChildItem $stage -Recurse -File |
    ForEach-Object { "    " + $_.FullName.Substring($stage.Length + 1) } |
    Sort-Object | Select-Object -First 40 | ForEach-Object { Write-Host $_ -ForegroundColor DarkGray }
Write-Host ""
