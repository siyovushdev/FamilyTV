#!/usr/bin/env python3
"""Generate soft original SFX (PCM WAV) for FamilyTV — no third-party samples."""

from __future__ import annotations

import math
import struct
import wave
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "app/src/main/assets/sfx"
RATE = 22050


def write(name: str, samples: list[float]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"{name}.wav"
    with wave.open(str(path), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        frames = b"".join(
            struct.pack("<h", max(-32767, min(32767, int(s * 32767)))) for s in samples
        )
        w.writeframes(frames)
    print(path.name, path.stat().st_size)


def tone(freq: float, ms: float, vol: float = 0.25, attack: float = 0.01, release: float = 0.05) -> list[float]:
    n = int(RATE * ms / 1000)
    a = int(RATE * attack)
    r = int(RATE * release)
    out = []
    for i in range(n):
        env = 1.0
        if i < a:
            env = i / max(1, a)
        if i > n - r:
            env = max(0.0, (n - i) / max(1, r))
        out.append(vol * env * math.sin(2 * math.pi * freq * i / RATE))
    return out


def chord(freqs: list[float], ms: float, vol: float = 0.18) -> list[float]:
    layers = [tone(f, ms, vol / len(freqs)) for f in freqs]
    n = max(len(x) for x in layers)
    return [sum(layer[i] if i < len(layer) else 0.0 for layer in layers) for i in range(n)]


def main() -> None:
    write("select", tone(660, 60, 0.18))
    write("focus", tone(520, 80, 0.12) + tone(780, 40, 0.08))
    write("confirm", chord([523, 659, 784], 220, 0.2))
    write("soft_wrong", tone(220, 120, 0.12) + tone(180, 100, 0.1))
    write("hint", chord([392, 494], 180, 0.15))
    write("objective_complete", chord([523, 659, 784, 1046], 420, 0.18))
    write("scene_transition", tone(300, 100, 0.08) + tone(400, 120, 0.1) + tone(500, 80, 0.08))
    write("lumi_appear", chord([440, 554, 659], 280, 0.16))
    write("movement_start", tone(350, 90, 0.14))
    write("movement_end", tone(280, 110, 0.12))
    (OUT / "README.txt").write_text(
        "FamilyTV original procedural SFX — DEV_VOICE era.\n"
        "Soft tones for select/focus/confirm/soft_wrong/hint/objective/scene/lumi/move.\n"
        "Replace with FINAL designed assets when available.\n",
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
