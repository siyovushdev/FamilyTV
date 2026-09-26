#!/usr/bin/env python3
"""Generate DEV_VOICE Russian narration via espeak-ng (offline, authoring PC).

Requires: libespeak-ng.so.1 with Russian voice.
Marks output quality as DEV_VOICE — not FINAL_VOICE talent.
"""

from __future__ import annotations

import argparse
import struct
import wave
from ctypes import CDLL, CFUNCTYPE, POINTER, c_char_p, c_int, c_short, c_size_t, c_uint, c_void_p
from pathlib import Path

COMMON = {
    "hello": "Привет!",
    "lets_start": "Давай начнём.",
    "repeat_task": "Повторю задание.",
    "try_again": "Попробуй ещё раз.",
    "want_hint": "Хочешь подсказку?",
    "good_thought": "Хорошая мысль.",
    "did_not_hear": "Я не расслышал.",
    "discuss_together": "Обсудите вместе.",
    "someone_answer": "Кто-нибудь скажите ответ.",
    "other_turn": "Теперь пусть отвечает другой.",
    "go_next": "Идём дальше.",
    "go_home": "Вернёмся домой.",
    "well_done": "Отлично справились.",
    "take_break": "Сделаем небольшой перерыв.",
}


def synth_to_wav(lib: CDLL, rate: int, text: str, out: Path) -> None:
    samples: list[int] = []
    CALLBACK = CFUNCTYPE(c_int, POINTER(c_short), c_int, c_void_p)

    @CALLBACK
    def synth_cb(wav, numsamples, events):
        if wav and numsamples > 0:
            samples.extend(wav[i] for i in range(numsamples))
        return 0

    lib.espeak_SetSynthCallback(synth_cb)
    raw = text.encode("utf-8")
    lib.espeak_Synth(raw, len(raw) + 1, 0, 1, 0, 1, None, None)
    lib.espeak_Synchronize()
    out.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out), "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(int(rate))
        w.writeframes(b"".join(struct.pack("<h", max(-32767, min(32767, s))) for s in samples))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", type=Path, default=Path("app/src/main/assets/narration/common"))
    args = ap.parse_args()
    lib = CDLL("libespeak-ng.so.1")
    rate = lib.espeak_Initialize(2, 500, None, 0)
    lib.espeak_SetVoiceByName.argtypes = [c_char_p]
    if lib.espeak_SetVoiceByName(b"ru") != 0:
        raise SystemExit("Russian espeak voice not available")
    lib.espeak_SetParameter(1, 140, 0)
    lib.espeak_Synth.argtypes = [c_void_p, c_size_t, c_uint, c_int, c_uint, c_uint, c_void_p, c_void_p]
    for key, phrase in COMMON.items():
        path = args.out / f"{key}.wav"
        synth_to_wav(lib, rate, phrase, path)
        print("wrote", path)
    print("quality=DEV_VOICE")


if __name__ == "__main__":
    main()
