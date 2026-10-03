# FamilyTV — Большая космическая экспедиция V1 (production)

Presentation/interaction redesign of the existing `space-grand-expedition` journey to the approved Cosmic Hearth V1 bar. Domain, progress and catalog data were not replaced.

Device: ADT-3 / SoC h728, 1920×1080. No commit.

## 1. What the old Space Expedition consisted of

- Journey `space-grand-expedition`: nodes launch → moon-hub (light-lab / temp-lab) → stretch → final-mission → calm-end, plus optional story-side.
- Launch content `adv-space-expedition` / `space-expedition.json`: STORY, VISUAL_SEARCH (batteries), PATH_MAZE (even-number route), PATTERN_SEQUENCE (2,4,?,8), SORT_AND_CLASSIFY, DIALOGUE, MOVE, OFFSCREEN, REFLECTION.
- Child UI: `JourneyMapActivity` oval chips + white GameStyle buttons; `AdventureActivity` quiz-like white number buttons + Check, with a small `SpaceLaunchBoard` ship.

Before shots: `docs/v1/space-expedition-production/before/`.

## 2. Architecture / domain preserved

Retained:

- `JourneyEngine`, `JourneyCoordinator`, `JourneyProgressStore`, journey JSON.
- `AdventureRuntime`, mechanic engines, adventure JSON, `AdventureProgressStore` (scene-id resume).
- `FamilyAction`, `VoiceCommandRouter`, `NarrationCoordinator`.
- ActivityHistory / trial / journal via existing coordinator hooks.
- Forest `JourneyMapActivity` (PLANETS redirect only for this journey id).
- Non-space nodes still open through `ContentNavigator` (color, weather, move, video, story).

Dedicated `SpaceExpeditionActivity` hosts the same stores. No second progress store. No data migration.

## 3. Production UI replaced

New screens (1920×1080, `code.html` geometry, local `space_expedition_v1/*.webp` art):

1. Expedition Entry / Resume
2. Visual space route (real nodes)
3. Challenge intro (in-world)
4. Active challenge (authored mechanic)
5. In-scene Help
6. Success / world consequence
7. Updated journey (same map, real state)

Old `JourneyMapActivity` UI is not used for `space-grand-expedition`. Forest is unchanged.

## 4. Real journey → visual route

Waypoints are production nodes. Visual states map `JourneyEngine` availability:

| Engine | Visual |
| --- | --- |
| COMPLETED | Пройдено |
| resume node | Мы здесь (cyan + ship) |
| AVAILABLE | Можно |
| FUTURE | Потом |
| HUB/CONCLUSION without content | Можно (not “missing”) |

Stitch labels («Звёздная туманность», «85%», «этап 3/5») are not used. Meter is required completed / required total from the catalog.

Launch remains current until the **whole** launch adventure completes — completing one scene does not fake-unlock Луна.

## 5. Challenge mechanics and renderers

| Mechanic | Renderer |
| --- | --- |
| VISUAL_SEARCH | Energy objects in-world; authored required ids |
| PATH_MAZE | Star grid from authored cells + EVEN_NUMBERS rule |
| PATTERN_SEQUENCE | Large orbs from authored options (device evidence: 5 / 6 / 7, correct 6) |
| SORT_AND_CLASSIFY | Items then categories |
| STORY / DIALOGUE / MOVE / OFFSCREEN / REFLECTION | Same space language + Continue |

Correctness stays in `AdventureRuntime`. No hardcoded 3/6/5/7 as the answer.

## 6. Wrong / Help / Success

- Wrong: soft pulse + authored hint. Stage does not complete. Maze odd/blocked cells never move the path.
- Help: in-scene pairing dots (even vs leftover). `requestHelp()` only. Does not auto-submit.
- Success: world art + short guide («Сигнал восстановлен…» / «Курс зажёгся…»). Continue returns to the journey map with `worldReacted`.

## 7. History / persistence

| Event | Policy |
| --- | --- |
| Map opened | `JOURNEY_MAP_OPENED` + `JOURNEY_RESUMED` once per activity visit |
| Launch adventure opened | `onContentOpened(adv-space-expedition)` once (`visitOpened` saved) |
| Scene progress | existing `AdventureProgressStore` scene id |
| Adventure finished | `onContentCompleted` + history INTERACTIVE + trial complete |
| Pause/resume | no extra opened/completed events |
| Back | never completes a node |

## 8. Tests / build / lint / release

- `./gradlew :app:testDebugUnitTest` — PASS (including `SpaceProductionTest`, Home, Explore routing, Forest still on JourneyMap)
- `./gradlew :app:assembleDebug :app:lintDebug` — PASS
- `./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest` — PASS

`SpaceExpeditionActivity` is `exported=false`, production source, no Stitch/debug runtime.

## 9. H728 production acceptance

Install debug APK. Real device state (not a fixture). Cold Home C → mission «Большая космическая экспедиция» → Entry → Journey → current launch → intro → authored challenge (resume was `repair_pattern` after an earlier even-path completion) → wrong 5 → Help → correct 6 → consequence → updated Journey → Home (focus mission).

Explore → Космос → same `SpaceExpeditionActivity` → Back → Explore focus `destination:SPACE`.

AI=0 on every Space log. No ANR/FATAL. Physical remote: not verified (ADB keyevents).

## 10. Visual self-review + polish

1. One expedition: same ship/world/guide across entry → route → challenge → success → route. Yes.
2. Journey is a space world with a trail, not a chip dashboard. Yes.
3. Challenge is in-world orbs on the ship art, not a worksheet. Yes (tiny leftover capsule on PATTERN_SEQUENCE).
4. Help is in-scene pairing dots. Yes.
5. Success changes the world art (engine beam / crystal) before a short line. Yes.
6. Updated journey keeps real nodes; `worldReacted` + `journey_updated` art. Launch still current (true). Yes.
7. TV type 22–44px, cyan focus. Yes.
8. Old white GameStyle buttons gone from Space. Yes.

Polish 1: entry button overlap, duplicate caption, maze default focus on the ship, conclusion «Нет» → «Можно».
Polish 2: remove procedural USB-ship overlay on intro/success so Stitch art is the world.

## 11. PSS / AI

| Surface | PSS KiB | AI | .gguf/.onnx |
| --- | ---: | ---: | --- |
| Home | 110821 | 0 | none |
| Entry | 118242 | 0 | none |
| Journey | 119462 | 0 | none |
| Challenge | 122307 | 0 | none |
| Success | 123543 | 0 | none |
| Updated journey | 123475 | 0 | none |
| Home return | 125147 | 0 | none |

No runaway growth vs Home. No model mappings.

## 12. Remaining limitations

- Launch journey node completes only when the full space adventure completes; mid-adventure scenes persist via adventure resume, not fake map unlocks.
- PATTERN_SEQUENCE still draws a small capsule overlay on the left; PATH_MAZE does not.
- Optional story-side sits off the main trail.
- Calm-end / missing video content still cannot play a package; it is selectable as a manual conclusion.
- Physical Bluetooth remote not verified.
- Voice does not auto-start STT/Qwen (by design).

## 13. Evidence paths

- Report: `docs/v1/space-expedition-production/REPORT.md`
- Before: `docs/v1/space-expedition-production/before/`
- Screenshots: `docs/v1/space-expedition-production/screenshots/01_home_mission.png` … `13_explore_back_focus.png`
- Video: `docs/v1/space-expedition-production/recordings/space-expedition-production-full.mp4`
- Logs: `docs/v1/space-expedition-production/logs/` (`checks.json`, `performance.json`, `state.log`, `logcat.txt`, PSS/maps, `asset-provenance.json`)
- QA: `tools/qa/space_expedition_production_h728.py`
