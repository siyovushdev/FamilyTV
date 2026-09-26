# FamilyTV Milestone 5.1 — Family-Trial Readiness

Device: ADT-3 / H728 (`192.168.68.109:5555`), 1920×1080.

## Fixes shipped in this milestone

1. **Stale screen-time session** — process-local session binding so force-stop no longer shows «Пора сделать перерыв» on cold Home.
2. **Home vertical focus** — ScrollView scrolls focused cards into view; compact hero/cards so lower sections are reachable with DPAD_DOWN.
3. **Large text / focus stroke** — Parent accessibility prefs applied in `GameStyle`.
4. **Parent PIN TV UX** — soft keyboard suppressed; number keys enter PIN.
5. **Captions** — optional `metadata.captionRef` (WebVTT/SRT) via Media3; Parent toggle; demo `demo_sky.vtt`.
6. **Continue Watching on short demos** — duration-aware resume/completion tolerance (3s clips no longer auto-complete after ~1s).
7. **Content quality** — retry hints added; `quality` linter run over Beta lessons.

## H728 measurements

| Metric | Result |
|--------|--------|
| Cold Home (5 runs) | 2364, 2372, 2387, 2407, 2412 ms — **median 2387 ms** |
| Fresh Home TOTAL PSS | **108481 KB** (~105.9 MiB) |
| Fresh Java / Native / Graphics | 9880 / 15192 / 29792 KB |
| Activities / ViewRootImpl / Views | 1 / 1 / 140 |
| AI on fresh Home | **none** (no VoiceRuntime/Qwen/Zipformer/sherpa) |

Cold start is ~0.4s above the historical 1.8–2.0s target; no optimization attempted without a deeper profile (per milestone rule).

## Known remaining blockers for family trial

1. **Continue Watching on 3–4s demo clips** — ExoPlayer often still reports `position=0` when BACK is pressed early; unit tests cover store logic. Prefer longer pack videos for CW demos.
2. **Russian system TTS** — not installed on this H728 image; narration falls back to text-only (OK).
3. **Full ≥30 min household run** — abbreviated BACK-loop stability done; schedule a real living-room session before handing to a family.
4. **Mic permission** — Math/Color prompt once; grant permanently for trial devices.
5. **Home vertical density** — many sections sit below the fold; DPAD_DOWN + scroll-into-view added, but first viewport still hero-heavy.

## Screenshots

`docs/images/family-trial/` — Home, Parent PIN, video, interactive, session, details, math, etc.
