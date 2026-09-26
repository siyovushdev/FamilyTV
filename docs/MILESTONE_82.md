# Milestone 8.2 — Learning Journeys, Persistent Worlds & Discovery Journal

**Status:** Complete — **H728 DEVICE VERIFIED** (192.168.68.109:5555), 2026-09-20.

Evidence: `tools/h728_m82_acceptance.sh` + device SharedPreferences dumps + deterministic debug node completion (not acoustic ASR).

Verification legend:

- **IMPLEMENTED** — code shipped
- **DEVICE VERIFIED** — proven on H728 this session
- **TEST ONLY** — JVM/unit coverage
- **NOT VERIFIED** — not claimed

PRIMARY SUCCESS CRITERION: continuous family/device progress (“we experienced/discovered this”), not mastery scores — **met**.

---

## 1. Journey model — IMPLEMENTED + TEST ONLY + DEVICE VERIFIED

- `Journey` / `JourneyNode` / branches / prerequisites / ageVariants / learningGoals / completionPolicy
- Bundled: `space-grand-expedition`, `forest-secrets` under `assets/catalog/journeys/`
- Orchestrates existing content IDs only (no duplicate runtimes)

## 2. Progress model — IMPLEMENTED + DEVICE VERIFIED

- `JourneyProgressStore` (device/family scoped SharedPreferences)
- Fields: open/completed nodes, lastVisited, branches, version, updatedAt
- No skill %, mastery, streaks, child profiles
- H728 prefs: both journeys `done=true` after acceptance path

## 3. Journey Map — IMPLEMENTED + DEVICE VERIFIED

- `JourneyMapActivity` — planet/trail nodes, completed/available/future visuals
- D-pad focusable; Continue / Journal / Home
- Map PSS ~132 MB after progress seeding

## 4. Voice journey actions — IMPLEMENTED + DEVICE VERIFIED (transcript inject)

- `FamilyAction.OpenJourneyMap` / `ContinueJourney` / `OpenJournal` / `AskWhatWeDiscovered`
- Router phrases: продолжить путешествие, куда дальше, покажи карту, что мы узнали, …
- Home inject: `inject=что мы узнали?` logged on device

## 5. Lumi journey behavior — IMPLEMENTED + TEST ONLY (+ DEVICE map lines)

- `JourneyNarrator` from structured progress only
- Map speaks where-we-are / what-next cues

## 6. Discovery Journal — IMPLEMENTED + DEVICE VERIFIED

- `DiscoveryJournalActivity` + `DiscoveryJournalStore`
- Child copy: «Мы узнали / нашли / создали / исследовали»
- Device journal entries include moon visit, ice fact, owl fact, journey summaries, stories

## 7. Fact unlock — IMPLEMENTED + DEVICE VERIFIED

- Authored `catalog/journal/unlocks.json` + node `journalUnlocks`
- Unlocks on node completion / experiment (not bare page browse for requireDiscovery)
- Device: `ice-melts-with-heat`, `owl-night-fact`, `space-visited-moon`, …

## 8. Story gallery — IMPLEMENTED + DEVICE VERIFIED

- Journal «Наши истории» from `StoryStore` + STORY_CREATED entries
- Replay / open Creative Studio / delete — local only

## 9. Experiment journal — IMPLEMENTED

- `ExperimentObservation` + unlock `exp-where-melts-faster`
- Wired from PredictAndTest TESTED observations in AdventureActivity
- **DEVICE path this session** used content-complete debug extras (weather node completed → ice fact unlock). Live in-adventure TESTED observation: **IMPLEMENTED**, not re-measured acoustically this run.

## 10. Home continuity — IMPLEMENTED + DEVICE VERIFIED

- Journey hero banner («Продолжить…» / «Начать»)
- Continuity chips: Наши открытия / Наши истории / Карта
- Fresh Home cold ~2469 ms, PSS ~121 MB, no Qwen load lines (AI=0)

## 11. Space Journey — IMPLEMENTED + DEVICE VERIFIED

- Launch → Moon hub branch (light + temp) → stretch → final → calm end
- Optional story side activity
- Device: all required nodes completed; `done=true`

## 12. Forest Journey — IMPLEMENTED + DEVICE VERIFIED

- Trail → owl hub → calm video → end (+ optional story)
- Device: `forest-secrets` `done=true`; journal forest/owl unlocks

## 13. Authoring changes — IMPLEMENTED

- `tools/content/schema.py` → `validate_journey_json`
- Journey assets validated against known content IDs

## 14. Quality linter — IMPLEMENTED

- `tools/content/quality.py` → `check_journey` (length, branches, rest, AUDIO_FIRST spokenLabel, fake branch)

## 15. Offline / resume — IMPLEMENTED + DEVICE VERIFIED

- Journeys/journal from APK assets — work without publisher reverse
- Force-stop: prefs retained; both journeys still `done=true`
- Version sanitize drops missing nodes (`JourneyEngine.sanitize`)

## 16. H728 real acceptance — DEVICE VERIFIED

| Check | Result |
|-------|--------|
| Fresh Home | TotalTime **2469 ms**, PSS **~121 MB**, AI log empty |
| Space start/complete/branch | prefs + FamilyJourney logs |
| Forest reuse | prefs `forest-secrets` done |
| Map / Journal PSS | ~132 MB / ~126 MB |
| «что мы узнали?» | Home inject logged |
| Offline | Home+Map+Journal without reverse |
| Force-stop resume | prefs persist |

Harness: `tools/h728_m82_acceptance.sh`

## 17. PSS / startup

| Surface | Measurement |
|---------|-------------|
| Fresh Home | 2469 ms · ~121083 KB PSS |
| Journey Map (after progress) | ~131918 KB |
| Journal | ~125696 KB |
| Offline Home | ~66140 KB (process after force-stop; lighter snapshot) |

No substantial AI regression; Home remains AI=0 on cold start.

## 18. Limitations

- Journey node completion on device acceptance used **debug extras** to prove orchestration/persistence quickly; full physical remote playthrough of every adventure scene was not re-timed end-to-end this session
- Acoustic ASR not measured (M8.2 is not an ASR milestone)
- Today Session ← journey metadata composer link is light (journeys reusable from Home/map; no new recommendation AI)
- Light/shadow Space branch reuses `game-color` as visual classify stand-in (authored label «Свет»)
- Idle Qwen unload still deferred (unchanged from 8.1)

## 19. Next recommended step

- **Milestone 8.3 candidate:** richer Today Session composition from journey open nodes + calm endings; optional spoken journal pack; Parent Mode timeline polish
- Or deepen authored Space “light & shadow” discovery content without leaving grounded knowledge rules

---

### Checklist vs report items

| # | Item | Status |
|---|------|--------|
| 1 | Journey model | IMPLEMENTED + DEVICE VERIFIED |
| 2 | Progress model | IMPLEMENTED + DEVICE VERIFIED |
| 3 | Journey Map | IMPLEMENTED + DEVICE VERIFIED |
| 4 | Voice journey actions | IMPLEMENTED + DEVICE VERIFIED (inject) |
| 5 | Lumi journey behavior | IMPLEMENTED + TEST ONLY |
| 6 | Discovery Journal | IMPLEMENTED + DEVICE VERIFIED |
| 7 | Fact unlock | IMPLEMENTED + DEVICE VERIFIED |
| 8 | Story gallery | IMPLEMENTED + DEVICE VERIFIED |
| 9 | Experiment journal | IMPLEMENTED (+ fact unlock DEVICE) |
| 10 | Home continuity | IMPLEMENTED + DEVICE VERIFIED |
| 11 | Space Journey | DEVICE VERIFIED |
| 12 | Forest Journey | DEVICE VERIFIED |
| 13 | Authoring | IMPLEMENTED |
| 14 | Quality linter | IMPLEMENTED |
| 15 | Offline/resume | DEVICE VERIFIED |
| 16 | H728 acceptance | DEVICE VERIFIED |
| 17 | PSS/startup | DEVICE VERIFIED |
| 18 | Limitations | documented |
| 19 | Next step | above |
