# Synthesize the wav files the end-to-end speech test streams through the
# fake microphone. Offline, via Windows SAPI -- no network, no accounts.
#
#   powershell -File scripts\make_test_audio.ps1
#
# Note: this machine has no Spanish SAPI voice, so an English voice reads the
# Spanish text. Whisper still transcribes single words like "Diamante"
# correctly (verified: scored 100), which is all these fixtures need. Longer
# Spanish phrases come out mangled and are deliberately not used here.

param([string]$OutDir = "run\testaudio")

$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.Speech

$full = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutDir))
New-Item -ItemType Directory -Force -Path $full | Out-Null

# 16 kHz mono 16-bit: exactly the format the mod captures and the backend
# expects, so nothing has to resample anywhere in the chain.
$format = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, `
    [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, `
    [System.Speech.AudioFormat.AudioChannel]::Mono)

# No silence fixture: SAPI emits a 46-byte header with no samples for an
# empty utterance, which the fake mic would finish streaming instantly. The
# no-speech path is covered by the backend's own test suite and by the
# timeout scenario instead.
$clips = @{
    "diamante.wav" = "Diamante"
    "wrong.wav"    = "elephant sandwich"
    # Long and deliberately unmatchable, for --hud-shot: the overlay has to
    # still be recording when the screenshot is taken, which means the clip
    # must outlast the capture delay and must never score a pass.
    "hudshot.wav"  = "the quick brown fox jumps over the lazy dog again and again"
}

foreach ($name in $clips.Keys) {
    $path = Join-Path $full $name
    $synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $spanish = $synth.GetInstalledVoices() | Where-Object {
        $_.VoiceInfo.Culture.Name -like "es*"
    } | Select-Object -First 1
    if ($spanish) { $synth.SelectVoice($spanish.VoiceInfo.Name) }

    $synth.SetOutputToWaveFile($path, $format)
    $synth.Speak($clips[$name])
    $synth.Dispose()

    $size = (Get-Item $path).Length
    Write-Output ("{0,-14} {1,8:N0} bytes  `"{2}`"" -f $name, $size, $clips[$name])
}

Write-Output ""
Write-Output "voice: $(if ($spanish) { $spanish.VoiceInfo.Name } else { 'system default (no Spanish voice installed)' })"
Write-Output "written to $full"
