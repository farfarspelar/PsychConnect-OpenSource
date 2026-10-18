#!/usr/bin/env python3
"""Generate PsychConnect's short procedural menu click using only the Python stdlib."""

from __future__ import annotations

import math
import random
import struct
import wave
from pathlib import Path

SAMPLE_RATE = 44_100
DURATION_SECONDS = 0.075
SEED = 26018
OUTPUT = Path(__file__).resolve().parents[1] / "app/src/main/res/raw/button_click.wav"


def sample_at(index: int, count: int, rng: random.Random) -> int:
    t = index / SAMPLE_RATE
    duration = count / SAMPLE_RATE
    progress = t / duration

    # A small, original two-tone transient with a quick, smooth decay.
    envelope = math.exp(-48.0 * t) * min(1.0, t / 0.0015)
    frequency = 1480.0 - 680.0 * progress
    phase = 2.0 * math.pi * (1480.0 * t - 340.0 * t * progress)
    tonal = 0.68 * math.sin(phase) + 0.20 * math.sin(2.0 * math.pi * 520.0 * t)
    click = 0.12 * rng.uniform(-1.0, 1.0) * math.exp(-95.0 * t)
    value = max(-1.0, min(1.0, (tonal + click) * envelope * 0.52))
    return round(value * 32767)


def main() -> None:
    count = round(SAMPLE_RATE * DURATION_SECONDS)
    rng = random.Random(SEED)
    frames = b"".join(struct.pack("<h", sample_at(i, count, rng)) for i in range(count))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUTPUT), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(SAMPLE_RATE)
        audio.writeframes(frames)
    print(f"Wrote {OUTPUT} ({count} mono PCM samples at {SAMPLE_RATE} Hz)")


if __name__ == "__main__":
    main()
