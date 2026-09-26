# Milestone 7.0 — FamilyTV Visual & Audio Production

Device: ADT-3 / H728 (`192.168.68.109:5555`).  
Date: 2026-09-19.

## Verdict

**PASS (experience-quality foundation).** Architecture from 6.2 unchanged. Product now has a coherent art/audio direction, recognizable Lumi, Home 3.0 portals, distinct Space vs Forest atmospheres, **DEV_VOICE** Russian offline narration (not beeps), SFX pack, and a quality gate that separates wiring from semantic speech.

Primary success: FamilyTV no longer reads as a pure engineering shell on flagship surfaces — Home / Lumi / Space / Forest form one world language. Not yet FINAL professional illustration/voice talent.

---

## 1. Art direction

`docs/ART_DIRECTION.md` — mood (warm, curious, calm, high-quality), forms, depth, typography, focus language, worlds, motion, age suitability. Explicitly avoids babyish / casino / Material-demo looks.

## 2. Lumi character design / system

`docs/LUMI_CHARACTER.md` + `LumiView` (canvas, original silhouette: teal orb + antenna).

Emotions: CALM, HAPPY, CURIOUS, THINKING, ENCOURAGING, SURPRISED, CELEBRATING, LISTENING, HELPING, REST (+ legacy EXCITED).

Readable at TV distance; UI ~72–96 dp Home, ~96 dp Adventure.

## 3. Lumi motion states

Lightweight `LumiView.Motion`: idle, appear, listen, think, react-correct, react-wrong, point/help, celebrate, transition. Short ValueAnimators; no perpetual distracting loops; no skeletal engine.

## 4. Home 3.0

- Gradient atmosphere + Lumi appear
- Tagline «Куда отправимся?» / «Миры для семьи»
- Hero mission banner (Space flagship)
- World-tinted portal cards + motifs (explore/solve/create/stories/move)
- Focus scale + teal rim preserved

Screenshot: `docs/images/milestone-70/01-home30.png`

## 5. World identities

`FamilyTvTheme.worlds` — related palettes/motifs per Home world. Adventures map to Space vs Forest stage themes.

## 6. Space visual result

Dark space gradient, starfield + ship/planet/path stage (`AdventureWorldStage`), Lumi companion, world-reaction line on correct, soft wrong colors (no red X). Mechanic tiles with glyphs.

Screenshot: `docs/images/milestone-70/10-space.png`

## 7. Forest visual result

Green canopy/ground identity (same runtime, different stage art), Lumi, VISUAL_SEARCH object tiles.

Screenshot: `docs/images/milestone-70/20-forest.png`

## 8. Mechanic presentation

VISUAL_SEARCH → selectable scene tiles (not bare form buttons). Sort/pattern/path keep game logic; focus scale integrated. Further illustration depth still open for Beta polish.

## 9. Russian DEV voice pack

Offline **espeak-ng ru** on authoring PC → packaged WAV.

Common phrases (14): hello, lets_start, repeat_task, try_again, want_hint, good_thought, did_not_hear, discuss_together, someone_answer, other_turn, go_next, go_home, well_done, take_break.

Space/Forest critical instructions regenerated as speech.

Marked **DEV_VOICE** (not FINAL_VOICE). Tools: `tools/content/generate_dev_voice.py`.

## 10. AUDIO_FIRST real readiness

Quality model:

| Level | Meaning |
|-------|---------|
| TECHNICALLY_WIRED | refs/spoken present (tones OK for debug) |
| SEMANTIC_NARRATION_READY | DEV_VOICE+ |
| PRODUCTION_READY | FINAL_VOICE |

Release gate (`audio_first_release_errors`) requires semantic voice — **placeholder tones no longer count**. Override: `.audio_first_dev_ok`.

Measured: Space/Forest `ready=True`, `voice=DEV_VOICE`, `readiness=SEMANTIC_NARRATION_READY`.

## 11. Audio direction

`docs/AUDIO_DIRECTION.md` — Lumi personality, pacing, correct/wrong, SFX, optional low music, silence.

## 12. SFX / music

Original procedural SFX under `assets/sfx/` (select, focus, confirm, soft_wrong, hint, objective_complete, scene_transition, lumi_appear, movement_*). `FamilySfx` lazy play. Music not shipped (optional later; Parent-disable hook via prefs pattern).

## 13. Asset pipeline

- Narration coverage reports readiness levels
- `generate_dev_voice.py` / `generate_sfx.py`
- quality.json + README markers in packages
- Hashes still via existing package build

## 14. H728 screenshots

| Surface | File |
|---------|------|
| Home 3.0 | `docs/images/milestone-70/01-home30.png` |
| Space | `docs/images/milestone-70/10-space.png` |
| Forest | `docs/images/milestone-70/20-forest.png` |

## 15. Home / Adventure PSS

| Metric | M6.2 band | M7.0 measured |
|--------|-----------|---------------|
| Cold Home | 2.57–2.80 s | **2677 ms** |
| Home TOTAL PSS | ~109–114 MB | **~121 MB** |
| Home Native | ~15–16 MB | **~19 MB** |
| Space Adventure PSS (no AI) | — | **~121 MB** |
| Forest Adventure PSS (no AI) | — | **~122 MB** |
| AI on Home | 0 | **0** |

Dialogue no longer auto-starts VoiceRuntime (opt-in mic) — prevents accidental ~800 MB model load on scene open.

## 16. Frame / performance observations

Canvas Lumi + world stage are light. No bulk audio preload. Scene fade transitions short. No FATAL in smoke. Frame timing not instrumented with systrace this pass — no visible jank in smoke opens.

## 17. Limitations

- Lumi/world art is **procedural canvas**, not final illustration/talent
- DEV_VOICE = espeak-ng (semantic, robotic) — not FINAL_VOICE actor
- Home portal cards still mostly text + tint (not full illustrated posters)
- Mechanic objects use glyphs, not full prop art
- Music not included
- Only flagship surfaces polished; other lessons remain simpler
- Acoustic ASR quality still not claimed

## 18. What remains before real child-facing Beta

1. Replace DEV_VOICE with professional Russian Lumi talent (FINAL_VOICE)
2. Illustrated Lumi sheet + Space/Forest scene layers (artist pass)
3. Richer mechanic props + stronger world reactions (engine light, trail map)
4. Parent Mode narration/SFX/music toggles UX polish
5. Full child playtest: Home → Space/Forest without reading
6. Keep H728 budget while adding art (texture size discipline)

---

## Tests

`ProductionVisualLogicTest` — voice pack keys, narration readiness gate, world id stability. Existing interaction/adventure tests still green.

Architecture intentionally **not** rewritten.
