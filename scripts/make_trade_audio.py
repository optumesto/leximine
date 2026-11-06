"""Build the spoken trade request the merchant scenario needs.

Made from the backend's own text-to-speech rather than SAPI: this machine has
no Spanish SAPI voice, and make_test_audio.ps1 says as much -- single words
survive an English voice reading Spanish, a whole sentence does not. The
backend's edge-tts voice is a real Spanish one, and /pronounce already returns
16 kHz mono wav, which is exactly what the fake microphone streams.

    python scripts/make_trade_audio.py

Needs the backend running (start-backend.cmd in leximine-backend).
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

# A plain, unambiguous request. The judge is a language model, so this is not
# matched against anything -- it just has to be a sensible way to ask a
# villager for bread.
CLIPS = {
    "trade_bread.wav": ("es_es", "Quiero comprar pan, por favor."),
    # The way a sentence actually goes wrong: you start, lose it, and begin
    # again. The first fragment is not a request and must be refused -- but
    # that refusal must not end the negotiation.
    "_trade_fragment.wav": ("es_es", "Eh, el pan."),
}

# fragment + a pause the segmenter will split on + the real request.
RETRY = ("trade_retry.wav", "_trade_fragment.wav", "trade_bread.wav")
GAP_SECONDS = 0.9

# Leading silence matters: the backend only starts buffering once it hears
# speech, and its 300 ms preroll is what saves the first syllable. A clip that
# opens mid-word gives the segmenter nothing to latch onto.
LEAD_SECONDS = 0.4


def read(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as wav:
        return wav.readframes(wav.getnframes()), wav.getframerate()


def fetch(lang: str, text: str) -> bytes:
    url = f"{BACKEND}/pronounce?" + urllib.parse.urlencode({"word": text, "lang": lang})
    try:
        with urllib.request.urlopen(url, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as e:
        raise SystemExit(f"backend refused: {e.code} {e.read().decode('utf8', 'replace')}")
    except (urllib.error.URLError, OSError) as e:
        raise SystemExit(f"backend unreachable at {BACKEND}: {e}\n"
                         f"Start it with leximine-backend\\start-backend.cmd")


def main() -> int:
    FOLDER.mkdir(parents=True, exist_ok=True)
    scratch = FOLDER / "_tts.wav"

    for name, (lang, text) in CLIPS.items():
        scratch.write_bytes(fetch(lang, text))
        with wave.open(str(scratch), "rb") as wav:
            if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
                raise SystemExit(f"{name}: backend returned {wav.getnchannels()}ch "
                                 f"{wav.getsampwidth() * 8}-bit, wanted mono 16-bit")
            rate = wav.getframerate()
            frames = wav.readframes(wav.getnframes())

        padded = b"\x00\x00" * int(rate * LEAD_SECONDS) + frames
        dest = FOLDER / name
        with wave.open(str(dest), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(rate)
            out.writeframes(padded)
        print(f"{name:18} {len(padded) / 2 / rate:5.2f}s  {rate} Hz  {text!r}")

    scratch.unlink(missing_ok=True)

    dest_name, first_name, second_name = RETRY
    first, rate = read(FOLDER / first_name)
    second, rate2 = read(FOLDER / second_name)
    if rate != rate2:
        raise SystemExit("sample rates differ")
    combined = first + b"\x00\x00" * int(rate * GAP_SECONDS) + second
    dest = FOLDER / dest_name
    with wave.open(str(dest), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(combined)
    print(f"{dest_name:18} {len(combined) / 2 / rate:5.2f}s  "
          f"fragment + {GAP_SECONDS:.1f}s pause + request")
    (FOLDER / first_name).unlink(missing_ok=True)

    print(f"\nwritten to {FOLDER}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
