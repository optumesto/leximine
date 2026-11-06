"""Build the clip that says a *different* short item than the one asked for.

The matcher decides short one-word names against the rest of the vocabulary,
so the interesting failure is no longer "that was wrong" but "that was the
other one": asked for `Hilo` (string), the player says `Hielo` (ice), which
scores 86 against the target and used to be accepted. The verdict now names
what it heard, and that sentence has to be *looked at* on screen -- it lands
on the failure flash at the same moment the punishment wheel is spinning, and
the two have collided before.

    python scripts/make_rival_audio.py        # needs the backend running

Pairs with `e2e_test.py --note-shot`, which photographs the result.
"""

from __future__ import annotations

import sys
import urllib.error
import urllib.parse
import urllib.request
import wave
from pathlib import Path

FOLDER = Path(__file__).resolve().parent.parent / "run" / "testaudio"
BACKEND = "http://127.0.0.1:8760"

# Hielo / Hilo: one of the real leaks the negative corpus turned up, not an
# invented pair. Said correctly and clearly -- the point is that the *word*
# is wrong, not that the audio is bad.
CLIPS = {"rival_hielo.wav": ("es_es", "Hielo")}

# The backend only starts buffering once it hears speech, and its 300 ms
# preroll is what saves the first syllable.
LEAD_SECONDS = 0.4


def fetch(lang: str, text: str) -> bytes:
    url = f"{BACKEND}/pronounce?" + urllib.parse.urlencode({"word": text, "lang": lang})
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        raise SystemExit(f"backend refused: {e.code} "
                         f"{e.read().decode('utf8', 'replace')}")
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"backend unreachable at {BACKEND}: {e}\n"
                         f"Start it with leximine-backend\\start-backend.cmd")


def main() -> int:
    FOLDER.mkdir(parents=True, exist_ok=True)
    scratch = FOLDER / "_rival_tts.wav"
    for name, (lang, text) in CLIPS.items():
        scratch.write_bytes(fetch(lang, text))
        with wave.open(str(scratch), "rb") as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
                raise SystemExit(f"{name}: backend returned {wav.getnchannels()}ch "
                                 f"{wav.getsampwidth() * 8}-bit, wanted mono 16-bit")
            rate = wav.getframerate()
            frames = wav.readframes(wav.getnframes())
        padded = b"\x00\x00" * int(rate * LEAD_SECONDS) + frames
        with wave.open(str(FOLDER / name), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(rate)
            out.writeframes(padded)
        print(f"{name:18} {len(padded) / 2 / rate:5.2f}s  {rate} Hz  {text!r}")
    scratch.unlink(missing_ok=True)
    print(f"\nwritten to {FOLDER}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
