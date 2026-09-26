# Milestone 7.1 — Non-Reader Ready Production Pass

Device: ADT-3 / H728 (`192.168.68.109:5555`).  
Date: 2026-09-19.

## Verdict

**PASS (non-reader production foundation).** Flagship Home / Space / Forest can be driven by **visuals + INTERIM_VOICE Russian speech + remote/voice** without requiring reading. Text remains visible for readers/adults. Final illustrator art and FINAL_VOICE talent remain the main blockers before real V1.

Primary success criterion: architecture + interaction model support a non-reading child; flagship flows are QA-able in AUDIO_FIRST + NON_READER_SIM.

---

## 1. Non-reader Home

- Large world **vector icons** (explore/solve/create/stories/move)
- Lumi in chrome; hero mission banner
- `NON_READER_SIMULATION` dims dense copy and leans on icons
- Spoken world labels when `ReadingSupportMode=AUDIO_FIRST` (debounced)

Screenshot: `docs/images/milestone-71/01-home-nonreader.png`

## 2. World iconography

| World | Drawable |
|-------|----------|
| Исследуй | `ic_world_explore` (rocket) |
| Решай | `ic_world_solve` (blocks) |
| Создавай | `ic_world_create` (brush) |
| Истории | `ic_world_stories` (book) |
| Двигайся | `ic_world_move` (footpath) |

## 3. Lumi production spec

`docs/LUMI_PRODUCTION_SPEC.md` — silhouette, proportions, face, emotion sheet, UI/scene sizes, gestures, delivery sizes.

## 4. Lumi asset pipeline

`LumiAssets` slots (`lumi_idle`, `lumi_listening`, …). `LumiView` prefers drawable art when present; canvas remains fallback. Runtime does not depend on placeholder geometry once slots are filled.

## 5. Russian voice strategy

`docs/VOICE_PRODUCTION.md` — Authoring PC → WAV → package → offline playback. Ladder: DEV_PLACEHOLDER → DEV_VOICE → **INTERIM_VOICE** → FINAL_VOICE. Full common phrase list including world labels + `want_repeat` / `choose_together`.

## 6. Semantic narration coverage

Space/Forest + common pack regenerated as **INTERIM_VOICE** (paced espeak-ng, not beeps).

```
Space: 8/8 instructions — ready=True voice=INTERIM_VOICE SEMANTIC_NARRATION_READY
Forest: 5/5 — same
```

## 7. Space production result

Paced spoken prompts; mission purpose in guide line; world stage + glyph tiles; icon Repeat/Help; group discuss cue. Screenshots: `10-space.png`, `11-space-mechanic.png`.

## 8. Forest production result

Calmer copy + forest stage; same runtime, different presentation. Screenshots: `20-forest.png`, `21-forest-mechanic.png`.

## 9. Mechanic visual improvements

- VISUAL_SEARCH: large glyph tiles + contentDescription; labels optional under NON_READER_SIM  
- SORT: category bins; items appear inside chosen bin  
- PATTERN: blank slot highlighted  
- PATH: ship/hero glyph moves on cursor cell  

## 10. Repeat / Help no-reading UX

Icon buttons (`ic_action_repeat`, `ic_action_help`) with contentDescription — not text-only. Voice/remote still map to `FamilyAction.REPEAT` / `HELP`. Idle (~28s) soft prompt `common:want_repeat` + Lumi helping pose.

## 11. AUDIO_FIRST release readiness

Gate ERROR if critical instruction lacks spoken path, choices require reading without visuals, or text-only critical control. WARNING for long narration / dense choice counts vs age band. Placeholder tones ≠ ready.

## 12. Non-reader simulation

Debug toggle in Parent Mode (debuggable): `NON_READER_SIMULATION` prefs. Dims adventure/home text; not a child setting.

## 13. Age complexity guidelines

`docs/AGE_COMPLEXITY.md` — 3–5 / 5–7 / 7–9 choice & instruction guidelines; independent of ReadingSupportMode.

## 14. Group UX

Common phrases: discuss_together, someone_answer, other_turn, choose_together. On-screen group prompts retained.

## 15. Sound mix

`FamilySfx.setNarrationDucking(true)` while package narration plays — SFX volume reduced so speech stays intelligible. Soft confirm; no casino stingers.

## 16–17. H728 / PSS / startup

| Metric | Target | Measured |
|--------|--------|----------|
| Cold Home | ≤ ~3 s | **2430–2693 ms** |
| Home PSS | ≤ ~130 MB | **~119–120 MB** |
| Adventure PSS | — | **~121–123 MB** |
| Native Home | — | **~18–19 MB** |
| AI=0 Home | yes | **yes** |

Screenshots under `docs/images/milestone-71/`.

## 18. Remaining blockers for first real V1

1. **FINAL_VOICE** talent recordings (replace INTERIM espeak)
2. Illustrator Lumi + prop art into `LumiAssets` / mechanic tiles (glyphs ≠ real objects yet)
3. Stronger Home hero illustration for non-readers (Start CTA still text-labeled)
4. Full seated child playtest with adults silent
5. Optional short flagship screen recording

Architecture is ready; experience quality now hinges on **final art + final voice**.
