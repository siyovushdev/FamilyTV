# FamilyTV Production Russian Voice Strategy

**Version:** 7.1  
**Runtime rule:** offline package / common assets only — **no cloud TTS on device**.

## Quality ladder

| Marker | Use | AUDIO_FIRST release |
|--------|-----|---------------------|
| `DEV_PLACEHOLDER` | Beeps/tones for wiring | **No** |
| `DEV_VOICE` | Rough TTS (e.g. espeak) for early labs | Debug only (`.audio_first_dev_ok`) |
| `INTERIM_VOICE` | Clear semantic Russian TTS interim | **Yes** (SEMANTIC_NARRATION_READY) |
| `FINAL_VOICE` | Professional talent | PRODUCTION_READY |

## Pipeline

```
Authoring PC
  → high-quality Russian TTS **or** human recording
  → WAV/OGG (mono, 16–22 kHz OK)
  → common voice pack + package narration/
  → quality.json marker
  → signed content package
  → H728 offline playback via NarrationCoordinator
```

Tools:

- `tools/content/generate_dev_voice.py` — lab/espeak (DEV_VOICE)
- Prefer commercial/local HQ TTS or talent for INTERIM/FINAL; keep generation **off-device**

## Common Lumi phrase list (production)

| Key | Russian |
|-----|---------|
| hello | Привет! |
| lets_start | Давай начнём. |
| explore | Исследуй. |
| solve | Решай. |
| create | Создавай. |
| stories | Истории. |
| move | Двигайся. |
| play | Играй. |
| repeat_task | Повторю. |
| try_again | Попробуй ещё раз. |
| want_hint | Хочешь подсказку? |
| want_repeat | Хочешь, повторю? |
| good_thought | Хорошая мысль. |
| did_not_hear | Я не расслышал. |
| discuss_together | Обсудите вместе. |
| someone_answer | Кто-нибудь скажите ответ. |
| other_turn | Теперь пусть отвечает другой. |
| choose_together | Теперь выберите вместе. |
| go_next | Идём дальше. |
| go_home | Вернёмся домой. |
| well_done | Отлично справились. |
| take_break | Сделаем небольшой перерыв. |

## Pacing rules (children)

- One idea per utterance
- Prefer short sentences + pause (separate clips or `[pause]` in authoring notes)
- Avoid bureaucratic phrasing
- Prefer: «Посмотри.» → «Найди самую большую.»

## Mix

During narration: duck ambient/music/SFX below speech. Soft confirm only — no casino stingers.
