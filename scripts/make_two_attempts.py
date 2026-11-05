"""Build a wav of two separate attempts with a pause between them.

Reproduces the reported bug: saying the wrong thing, pausing, then saying the
right thing. Before utterance segmentation the backend transcribed both
together and the good attempt was dragged down by the bad one.

    python scripts/make_two_attempts.py
"""

from __future__ import annotations

import sys
import wave
from pathlib import Path

FOLDER = Path(__file__).resolve().parent.parent / "run" / "testaudio"
GAP_SECONDS = 0.9  # comfortably over the backend's utterance_gap_ms


def read(path: Path) -> tuple[bytes, int]:
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2:
            raise SystemExit(f"{path.name} must be mono 16-bit")
        return wav.readframes(wav.getnframes()), wav.getframerate()


def main() -> int:
    first, rate = read(FOLDER / "wrong.wav")
    second, rate2 = read(FOLDER / "diamante.wav")
    if rate != rate2:
        raise SystemExit("sample rates differ")

    silence = b"\x00\x00" * int(rate * GAP_SECONDS)
    combined = first + silence + second

    dest = FOLDER / "two_attempts.wav"
    with wave.open(str(dest), "wb") as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)
        out.writeframes(combined)

    total = len(combined) / 2 / rate
    print(f"wrote {dest.name}: wrong ({len(first)/2/rate:.2f}s) "
          f"+ {GAP_SECONDS:.2f}s pause + diamante ({len(second)/2/rate:.2f}s) "
          f"= {total:.2f}s")
    return 0


if __name__ == "__main__":
    sys.exit(main())
