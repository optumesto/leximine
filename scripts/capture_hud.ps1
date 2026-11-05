# Bring the dev Minecraft window to the front and save a screenshot.
#
# Used to actually look at the challenge overlay rather than infer it from
# logs. Called by scripts/e2e_test.py --hud-shot while a challenge is on
# screen; also fine to run by hand.
#
#   powershell -File scripts\capture_hud.ps1 -Out run\hud.png

param([string]$Out = "run\hud.png")

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing
Add-Type @"
using System;
using System.Runtime.InteropServices;
public class Win {
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr h);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr h, int cmd);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
    [StructLayout(LayoutKind.Sequential)]
    public struct RECT { public int Left, Top, Right, Bottom; }
}
"@

# PrintWindow asks the window to redraw itself into our bitmap, so anything
# sitting on top of it (a Windows firewall prompt, for one) is not captured.
# PW_RENDERFULLCONTENT (2) is what makes it work for GPU-composited windows;
# without it an OpenGL surface comes back black. Falls back to a plain screen
# grab if the result is blank anyway.
function Test-MostlyBlank([System.Drawing.Bitmap]$bmp) {
    $samples = 0
    $dark = 0
    for ($x = 10; $x -lt $bmp.Width - 10; $x += 40) {
        for ($y = 10; $y -lt $bmp.Height - 10; $y += 40) {
            $p = $bmp.GetPixel($x, $y)
            $samples++
            if ($p.R -lt 8 -and $p.G -lt 8 -and $p.B -lt 8) { $dark++ }
        }
    }
    return ($samples -gt 0 -and ($dark / $samples) -gt 0.9)
}

# The auto-connecting client is the only JVM carrying this argument, which is
# what keeps this from grabbing the dedicated server instead.
$target = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
    Where-Object { $_.CommandLine -match "quickPlayMultiplayer" } |
    Select-Object -First 1

$proc = $null
if ($target) {
    $proc = Get-Process -Id $target.ProcessId -ErrorAction SilentlyContinue
} else {
    # Fall back to any Minecraft window. The harness's client is the usual
    # subject, but it joins a world straight away and so can never show the
    # title screen -- which is where the language picker lives. A plain
    # `gradlew runClient` has no quickPlay argument and used to be
    # unphotographable for that reason alone. Matched on the window title
    # rather than the command line, because a dedicated server has no window
    # at all and so cannot be caught by this.
    $proc = Get-Process -Name "java", "javaw" -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowTitle -like "Minecraft*" } |
        Select-Object -First 1
}

if (-not $proc) { Write-Error "no Minecraft client window found"; exit 1 }
$handle = $proc.MainWindowHandle
if ($handle -eq [IntPtr]::Zero) { Write-Error "client has no window yet"; exit 1 }

[Win]::ShowWindow($handle, 9) | Out-Null   # SW_RESTORE
[Win]::SetForegroundWindow($handle) | Out-Null
Start-Sleep -Milliseconds 700

$rect = New-Object Win+RECT
[Win]::GetWindowRect($handle, [ref]$rect) | Out-Null
$width = $rect.Right - $rect.Left
$height = $rect.Bottom - $rect.Top
if ($width -le 0 -or $height -le 0) { Write-Error "bad window rect"; exit 1 }

$bitmap = New-Object System.Drawing.Bitmap $width, $height
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)

$hdc = $graphics.GetHdc()
$printed = [Win]::PrintWindow($handle, $hdc, 2)   # PW_RENDERFULLCONTENT
$graphics.ReleaseHdc($hdc)

if (-not $printed -or (Test-MostlyBlank $bitmap)) {
    Write-Output "PrintWindow unusable; falling back to screen capture"
    $graphics.CopyFromScreen($rect.Left, $rect.Top, 0, 0, $bitmap.Size)
}

$full = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $Out))
New-Item -ItemType Directory -Force -Path (Split-Path $full) | Out-Null
$bitmap.Save($full, [System.Drawing.Imaging.ImageFormat]::Png)
$graphics.Dispose()
$bitmap.Dispose()

Write-Output "saved $full ($width x $height)"
