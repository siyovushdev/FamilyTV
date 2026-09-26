# FamilyTV Audio Direction

**Status:** Milestone 7.0 product baseline  
**Runtime rule:** all child-critical speech & SFX ship in packages / app assets — **offline**.

## Voice personality — Луми

Lumi is a calm, curious guide — never a chatbot, never a scolding teacher.

- Warm, clear Russian
- Encouraging on mistakes
- Excited but brief on success
- Speaks in short sentences children can hold in working memory

## Narration style & pacing

- Prefer **one idea per utterance**
- Speaking rate slightly slower than adult conversation
- Pause before critical keywords (“три”, “чётным”, “готово”)
- displayText may shorten numbers (“3”); spokenText may expand (“три”)

## Age suitability

- Vocabulary concrete and visual
- No sarcasm, no fear-based feedback
- Group mode lines invite cooperation without identifying speakers

## Quality levels (authoring)

| Level | Meaning |
|-------|---------|
| `DEV_PLACEHOLDER` | Beep/tone — wiring only |
| `DEV_VOICE` | Semantic Russian speech (TTS or scratch talent) — **AUDIO_FIRST testable** |
| `FINAL_VOICE` | Professional talent — production |

Release AUDIO_FIRST readiness requires at least **SEMANTIC_NARRATION_READY** (`DEV_VOICE` or `FINAL_VOICE`), not placeholder tones.

## UI / SFX

Soft, short, original/legal:

- select, focus, confirm
- soft_wrong (gentle, not buzzers)
- hint, objective_complete
- scene_transition, lumi_appear
- movement_start / movement_end

Avoid casino jingles and loud “reward” stingers.

## Correct / wrong

- **Correct:** world change + Lumi celebrate + short spoken + subtle confirm SFX
- **Wrong:** world stable + encouraging line + soft_wrong + hint available — **no red X punishment**

## Ambient & music

- Music optional, loop-safe, low intensity, always below speech
- Easy to disable in Parent Mode
- Understanding must never depend on music
- Silence / rest is intentional (MOVE breaks, finale calm)

## Authoring pipeline

Generation may happen on a PC (TTS or recording). Results are packaged as WAV/OGG and marked with quality in manifests / README. Device playback stays offline via `NarrationCoordinator`.
