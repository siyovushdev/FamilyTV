# Space Expedition V1 — visual layer (Stitch → Native)

Challenge Active (`PATTERN_SEQUENCE`) is already accepted and was not polished further.

Presentation only. JourneyEngine, routing, Forest, and other FamilyTV worlds were not changed. No commit.

## Approach

Offline 1920×1080 HTML + WebView (`SpaceHearthBoard` / accepted `SpacePatternBoard`), local JPEG art, Plus Jakarta Sans, `zoom = 1/devicePixelRatio`. Production journey/adventure values fill the Stitch layout.

## Screens

| # | Screen | Stitch source | Android | Comparison |
| --- | --- | --- | --- | --- |
| 0 | Challenge Active | `04_challenge_active` | accepted | `comparisons/00_challenge_active.png` |
| 1 | Success | `06_success` | `android/01_success.png` | `comparisons/01_success.png` |
| 2 | Help | `05_help` | `android/02_help.png` | `comparisons/02_help.png` |
| 3 | Wrong | same world as 04 | `android/03_wrong.png` | `comparisons/03_wrong_base.png` |
| 4 | Intro | `03_challenge_intro` | `android/04_intro.png` | `comparisons/04_intro.png` |
| 5 | Journey | `02_expedition_journey` | `android/05_journey.png` | `comparisons/05_journey.png` |
| 6 | Updated Journey | `07` art, `02` layout | `android/06_updated.png` | `comparisons/06_updated.png` |
| 7 | Entry | `01_expedition_entry` | `android/07_entry.png` | `comparisons/07_entry.png` |

Video: `recordings/space-expedition-visual-layer.mp4`

Device: ADT-3 / h728, 1920×1080.

## Domain vs Stitch copy

Stitch stage names («Звёздная туманность», «Этап 3 из 5», «85%») are not used. Labels come from `space-grand-expedition` / `space-expedition` (Старт, Луна, Свет, Тепло, …; options 5/6/7).

## Checks

- `./gradlew :app:testDebugUnitTest` (Space/Home/Explore) — pass
- `./gradlew :app:assembleDebug` — pass
- `./gradlew :app:lintDebug` — pass
- `./gradlew :app:assembleRelease` — pass
- PSS samples: entry 361467, journey 336371, challenge 433718, help 400440, success 350763, updated 416215, intro 357453 KiB
- AI=0 on every FamilySpace log; no `.gguf`/`.onnx` mappings

## Limitations

- Success TRAVEL overlay races the 3.2s auto-return; the H728 still of consequence is INSTALL/IGNITE (energy crystal on the living ship art, then Journey).
- Updated Journey uses production node states on the device (launch already completed, current = Луна), not Stitch’s five fake stages.
- SORT / maze / search still use the Canvas world fallback; the Stitch boards cover Entry, Journey, Intro, PATTERN Help/Wrong/Success.
- First Entry paint needs ~2s after WebView load (recaptured).
