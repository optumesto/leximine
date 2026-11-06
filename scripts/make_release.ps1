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
& (Join-Path $root "gradlew.bat") build --console=plain -q
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
# LICENSE is in this list on purpose. The backend has none at the time of
# writing, and the two halves ship in one zip -- so until it gets one, that
# half of the download is technically not redistributable by anyone. Drop a
# LICENSE into the backend folder and it will start shipping with no further
# change here.
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

# --- prove it is actually complete ------------------------------------
# Asserted rather than assumed: a release is the one artifact nobody tests
# before a stranger does.
$required = @(
    "INSTALL.md",
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
if ($missing.Count -gt 0) {
    Write-Host ""
    foreach ($m in $missing) { Write-Host "  !! $m" -ForegroundColor Red }
    throw "release is incomplete"
}

$size = [math]::Round((Get-ChildItem $stage -Recurse -File | Measure-Object Length -Sum).Sum / 1MB, 1)
Ok "staged  $stage  ($size MB)"

if ($Zip) {
    $zipPath = Join-Path $dist "Leximine-$version.zip"
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
