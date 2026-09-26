# Product Quality Sprint 1 — Space Launch Vertical Slice

**Device:** H728 / ADT-3 `192.168.68.109:5555` · 1920×1080  
**Date:** 2026-09-21  
**Flow:** Home → Content Details → Space intro → ship-power → flight → nav maze  
**No seeded progress.**

## Verdict

Reviewer can watch the continuous recording and understand without reading source:

ship had empty energy slots → child picks real objects → wrong rock soft-rejects → Help highlights slots → Repeat keeps state → three energy cells install into the ship → engine glows → flight beat → same ship continues into the course maze.

## What changed visually

- Intro / launch / flight use a dedicated **SpaceLaunchBoard**: recognizable ship vector, 3 energy slots, engine glow, problem pulse.
- Objects are distinct vectors: energy cell / rock / wrench / star (not identical circles).
- Soft wrong: bounce + Lumi line «Этот не подходит. Посмотрим ещё.» (no red X).
- Help: yellow slot highlight + Lumi HELP motion; second help highlights energy candidates.
- Success: slots fill → engine glow → «Двигатель запустился!» → flight story → nav maze with ship continuity.

## Assets added

`app/src/main/res/drawable/`:

- `space_ship.xml`, `space_engine_glow.xml`, `space_energy_cell.xml`
- `space_slot_empty.xml`, `space_rock.xml`, `space_wrench.xml`, `space_star_obj.xml`

Narration (DEV_VOICE): `intro.wav`, `ship-power.wav`, `launch-flight.wav` (+ updated `even-path.wav`).

## Interaction

- Mechanic: existing `VISUAL_SEARCH` (`ship-power`), presentation-only Space board.
- Remote: D-pad / OK on object tiles; Repeat / Help icon chrome.
- Voice: existing FamilyAction path; distractors soft-reject without scoring.

## H728 result

| Item | Path |
|------|------|
| Continuous recording | `docs/quality-sprint-1-space/recordings/space_launch_vertical_slice.mp4` |
| Screenshots | `docs/quality-sprint-1-space/screenshots/` |
| Driver log | `docs/quality-sprint-1-space/accept_v6.log` |

Required shots present: `before_problem`, `wrong_action`, `help_visual`, `object_installed`, `ship_repaired`, `engine_started`, `next_scene`.

**Audio:** `adb shell screenrecord` on this device path does **not** capture internal audio (same as visual-audit note). Spoken prompts play on device; external TV mic recording optional.

**PSS** (during Space adventure, not cold Home): TOTAL PSS ≈ **166 MB**.

## Known shortcomings

- Object tray still sits below the ship (world manipulation is slot install + bounce; not free drag in open space).
- First energy tile reports inflated accessibility bounds on H728 (visual/layout OK; automation taps left edge).
- Nav maze after flight is still grid/buttons — intentionally not redesigned this sprint.
- Art is GOOD_PROTOTYPE / replaceable vectors, not FINAL_ART.
- Lumi is header companion + pulse/point cues; not a full scene actor.
