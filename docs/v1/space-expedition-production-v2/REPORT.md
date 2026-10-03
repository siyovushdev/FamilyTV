# Space Expedition V2 — interaction / visual quality correction

Architecture from V1 is unchanged. This pass only rewrites child-facing presentation and interaction on `SpaceExpeditionActivity` / `SpaceExpeditionState` / `SpaceWorldView`.

Device: ADT-3 / SoC **h728**, 1920×1080. App data was **not** reset. No commit.

First-attempt evidence remains at `docs/v1/space-expedition-production/`. This folder is additive.

## 1. Why the first attempt failed product review

V1 was technically prettier than the old white `AdventureActivity` boards, but it still played as:

tracker graph → question → answer buttons → text success.

Specifically:

- Journey was circles + connecting lines + badges on a space background (a dashboard, not a world).
- PATTERN_SEQUENCE used three large numbered orbs that still read as quiz buttons.
- Help added pairing dots and extra chrome instead of simplifying the scene.
- Success was a darkened still + «Путь открылся» + large «Дальше».
- The child’s OK press did not visibly move an object through the world.

FamilyTV V1 bar: **WORLD → PHYSICAL ACTION → VISIBLE CONSEQUENCE → JOURNEY PROGRESSES.**

## 2. What presentation was changed

Unchanged: JourneyEngine, JourneyCoordinator, JourneyProgressStore, AdventureRuntime, AdventureProgressStore, routing, production IDs, Forest, Home, Watch/Games/Weather/Create/Move, guide branding, AI.

Changed (presentation only):

- Journey destinations are world objects on one cosmic path (Earth pad, Moon, light crystal, sun station, flight corridor, beacon, home Earth). Real node IDs and availability stay truthful.
- Challenge intro is no longer a separate «Дальше» gate for interactive mechanics. The problem is on the challenge stage itself.
- PATTERN_SEQUENCE is an energy route `2 → 4 → ? → 8` plus physical orbs that fly into the missing slot.
- Wrong is a bounce / stay-in-world beat. No red X, no failure panel, no scene reset.
- Help dims the rest of the world and shows the authored pattern step (`+2` derived from display numbers) or zone marks for sort. It does not auto-select or submit.
- Success is the same world: object locks, route/engines, ship moves, short line «Получилось! Летим дальше.», then auto-return to Journey. «Дальше» is an invisible a11y target only.
- Entry dropped the large opaque center panel. Title + current stage sit on the cockpit art.
- The same ship / route / Lumi language is used across Entry, Journey, Challenge, Wrong, Help, Success.

## 3. How PATTERN_SEQUENCE now works physically

Authored params stay `display: 2,4,?,8` / options `5,6,7` / `correctOption: 6`.

1. Child focuses an energy orb (not a submit button).
2. OK starts `SpaceBeat.INSTALL` — the orb moves toward the missing slot.
3. Wrong (`5` / `7`): slot rejects, orb returns, hint e.g. «Почти. Посмотри, как растут числа.», route stays broken, child tries again.
4. Correct (`6`): orb locks in the slot, energy path lights, engines wake, ship flies, then Journey.

Unit regressions cover physical-option transition, wrong without completion, Help without auto-answer, consequence then Journey, launch remaining CURRENT (Moon stays locked).

**Live H728 this session was not PATTERN_SEQUENCE.** Persisted adventure had already moved to `planet_sort` (`SORT_AND_CLASSIFY`). Spec: use the real current mechanic for main evidence. PATTERN is covered by tests + renderer; not faked on device.

## 4. How other real mechanics were adapted

| Mechanic | In-world action |
| --- | --- |
| VISUAL_SEARCH | Energy cells vs rock/wrench/star; cells sit in ship bays |
| PATH_MAZE | Even stars as a corridor; ship marker on the cursor; odd/blocked never move the path |
| PATTERN_SEQUENCE | Orbs install into the broken energy route |
| SORT_AND_CLASSIFY | Find (whale/tree/cloud/fish) then place into water/land/air world zones. Wrong zone rejects without completing. |
| STORY / DIALOGUE / MOVE / OFFSCREEN / REFLECTION | Same space stage + continue; no white prototype boards |

Live H728 evidence is **SORT_AND_CLASSIFY** at `planet_sort`. After a correct sort the runtime advanced to `science_talk` (DIALOGUE). Launch node stayed CURRENT.

Post-review polish (after the capture): sort tokens are drawn as creatures, placed finds stay inside their zone, and the extra «Проверить» control was removed (placement already evaluates). Those pixels are newer than `04_physical_choices.png`.

## 5. Wrong / Help / Success

- **Wrong:** local reject beat + authored/short hint. Domain is not completed. Child immediately retries.
- **Help:** `requestHelp()` then return to CHALLENGE_ACTIVE. Does not change `chosenOption`, does not submit. Visual scaffold only.
- **Success:** `SUCCESS` screen is the consequence animation on the same world, then `tick` auto-calls `leaveSuccess()` after `SPACE_CONSEQUENCE_MS` (3200 ms). Journey `worldReacted=true`. Adventure scene advances via existing `finishFeedback()`; journey node does **not** complete until the whole launch adventure completes.

## 6. How real Journey progress remains truthful

- Waypoints still come from `JourneyEngine.views`.
- Launch = CURRENT, Moon = LOCKED, Light/Heat/Stretch/Final = COMPLETED, Home = AVAILABLE — matching persisted store (`4 из 7`).
- Completing `planet_sort` did **not** unlock Луна. Earth destination shows inner launch depth (energy pips) from the real adventure scene id.
- Settle animation no longer interpolates the ship from a later completed node (e.g. Финал) onto Старт — that lie was visible on `08`/`09` and is fixed in code.

## 7. H728 visual acceptance

Continuous flow (real state, no reset):

Home mission → Entry → Journey → `SORT_AND_CLASSIFY` (`planet_sort`) → Wrong (whale onto land) → Help → Correct placements → ship consequence → Journey (launch still current, scene `science_talk`) → Home.

Self-review:

| Bar | Result |
| --- | --- |
| Journey looks like a space adventure, not a graph dashboard | **Pass** — destinations + cosmic path, not numbered tracker chips |
| Active challenge does not look like a quiz | **Partial on captured SORT** — zones are world-like; item cards in the capture still read UI. Tokens + no Check are in code after review |
| Child physically manipulates a world object | **Pass for SORT** (pick find → put in zone). PATTERN orbs covered in tests |
| Wrong is soft and stays in world | **Pass** — no red X / failure panel |
| Help simplifies instead of adding clutter | **Partial** — zone marks; Help chrome still present |
| Correct answer visibly changes the world | **Pass** on `07` — items gone, ship engines + motion, «Получилось! Летим дальше.» |
| Ship visibly progresses / reacts | **Pass** on `07`; `08`/`09` already auto-returned to Journey |
| Return to Journey visibly reflects real progress | **Pass** — launch current, Moon locked, `worldReacted=true` |
| No generic white prototype controls | **Pass** vs V1 white boards; captured SORT still had «Проверить» (removed after review) |
| No large static success page with only text | **Pass** |

Overall: **architecture accepted; V2 is a real correction; SORT capture is honest to device state; PATTERN physical install is implemented and unit-tested, not live-shot this run.**

## 8. Tests / build / lint / release

- `:app:testDebugUnitTest` — `SpaceProductionTest` (existing + V2 regressions), `HomeProductionTest`, `ExploreRoutingTest`
- `:app:lintDebug` — success
- `:app:assembleDebug` — success
- Forest assertions still pass
- No commit

New regressions:

- physical-option state (`INSTALL` / `REJECT`)
- wrong returns without completion
- Help does not auto-answer
- correct enters consequence then Journey
- consequence completes before Journey
- real domain progress unchanged (launch current, Moon locked)
- SORT places into zones; wrong stays in world
- other Space mechanics remain playable
- AI=0, Forest unchanged

## 9. PSS / AI

| Surface | PSS KiB | AI | models in maps |
| --- | --- | --- | --- |
| home | 111960 | 0 | none |
| entry | 118809 | 0 | none |
| journey | 175063 | 0 | none |
| challenge | 154919 | 0 | none |
| success / journey | 199107 | 0 | none |
| updated journey | 175591 | 0 | none |
| home-return | 154691 | 0 | none |

`VoiceRuntime.peek()` only. No ensureVoice / STT / gguf / onnx.

## 10. Remaining limitations

- Live evidence is SORT, not PATTERN_SEQUENCE, because the device had already left `repair_pattern`. Resetting progress to reshoot PATTERN was forbidden.
- Captured SORT still shows text chips + «Проверить»; creature tokens and Check removal landed immediately after that review and are not in those PNGs.
- `08_ship_reacts` / `09_ship_moves` raced the auto-return and show Journey; the ship-on-success frame is `07`. Settle-from-Финал on `08` was a presentation bug and is fixed.
- Destination glyphs (diamond / beacon) are still simplified icons, not full 3D stations.
- DIALOGUE / MOVE / OFFSCREEN remain continue-gated space scenes (authored non-interactive).
- Help button remains as a chrome control (needed for D-pad Help).

## 11. Evidence paths

Do not use `docs/v1/space-expedition-production/` as this pass — that is V1.

| Item | Path |
| --- | --- |
| Report | `docs/v1/space-expedition-production-v2/REPORT.md` |
| Screenshots | `docs/v1/space-expedition-production-v2/screenshots/01_entry.png` … `11_home_return.png` |
| Recording | `docs/v1/space-expedition-production-v2/recordings/space-expedition-v2-full.mp4` |
| Logs | `docs/v1/space-expedition-production-v2/logs/` |
| QA | `tools/qa/space_expedition_production_v2_h728.py` |

### V1 first attempt → V2 correction

| V1 | V2 |
| --- | --- |
| Graph circles + lines | Earth / Moon / light / sun / corridor / beacon / home |
| Quiz orbs + «Дальше» intro | Problem in the world; orbs install into `2→4→?→8` |
| Help pairing clutter | Dim + pattern/zone scaffold, no auto-answer |
| Dark success + «Путь открылся» | Ship consequence, then auto Journey |
| Fake-looking node unlock | Launch stays current until the launch adventure is done |
| Overwrote nothing | New `…-v2/` tree |
