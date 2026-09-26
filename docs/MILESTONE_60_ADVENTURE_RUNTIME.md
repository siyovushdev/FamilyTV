# Milestone 6.0 — FamilyTV Adventure Runtime

Device: ADT-3 / H728 (`192.168.68.109:5555`), 1920×1080.  
Date: 2026-09-19.

## Verdict

**PASS (core).** FamilyTV now ships a package-defined Adventure content type with a reusable `AdventureRuntime`, four mechanic plugins, scaffolding, session-local difficulty, group-mode prompts, guide character reactions, off-screen challenges, Home 2.0 worlds, flagship + reuse adventures, authoring validation, and local anonymous events — without breaking signed packages, Parent Mode, Learning Sessions, or Home AI=0.

Primary success criterion met: learning is required inside mission mechanics (launch cargo → even-number navigation → engine sequence → planet classify → dialogue → move → off-screen), not bolted-on quizzes after unrelated story beats.

---

## 1. Adventure architecture

| Piece | Role |
|-------|------|
| `ContentType.ADVENTURE` + `adventureRef` | First-class catalog type (bundled + packages) |
| `AdventureParser` | JSON → domain model + structural validate |
| `AdventureRuntime` | Deterministic scene/mission/variables/objectives/scaffold/difficulty state machine |
| `AdventureActivity` | Single Activity for all adventures (no per-adventure Activity) |
| `AdventureProgressStore` / `AdventureEventStore` | Resume + local anonymous events |

Scenes: `STORY`, `CHALLENGE`, `DIALOGUE`, `OFFSCREEN`, `MOVE`, `REFLECTION`.

## 2. Mechanic plugin architecture

`MechanicType` + `MechanicEngine` registry (`MechanicRegistry`):

- `initial` / `applySelect` / `applyVoice` / `evaluate`
- Params are package data (`MechanicParams` sealed types)
- UI only renders snapshots; correctness stays in engines + runtime

## 3. Four implemented mechanics

| Mechanic | Example in flagship |
|----------|---------------------|
| `VISUAL_SEARCH` | Find three floating objects for life jackets |
| `PATH_MAZE` | Course on even numbers only |
| `PATTERN_SEQUENCE` | Engine signals `2, 4, ?, 8` |
| `SORT_AND_CLASSIFY` | Planet finds → water / land / air |

Reuse proven in **Следопыты: тайна леса** (search + classify + sequence + voice).

## 4. Scaffolding

Challenge `scaffolding`: `hint1`, `hint2`, `visualHint`, `simplifiedParams`, `explanation`.

Wrong attempts escalate: conceptual hint → visual highlight / second hint → simplified variant or explanation. No shame score; guide stays encouraging.

## 5. Session-local adaptation

`consecutiveCorrect` / `consecutiveWrong` nudge `difficulty` within `[difficultyMin, difficultyMax]` for the current play session only. No child profiles / mastery claims. Reset per adventure start.

## 6. Family / group interaction

`GroupMode`: `SOLO_OR_GROUP`, `DISCUSS_TOGETHER`, `TAKE_TURNS`, `ALL_TOGETHER` with Russian prompts on scene (e.g. «Обсудите вместе…», «Выберите всей командой.»). No player accounts.

## 7. Character architecture

`GuideDirector` + `GuideEvent` / `GuideEmotion` (`CURIOUS`, `EXCITED`, `THINKING`, `ENCOURAGING`, `SURPRISED`, `CALM`). Reactions are authored/context-aware from events — not a free chatbot. Supports text + narration (TTS fallback) + emotion state for future animation.

Guide id in packages: **Луми**.

## 8. Voice dialogue integration

- Voice starts only when the child taps «Сказать» / dialogue needs mic (`VoiceRuntime` lazy).
- Expected answers never enter Qwen prompts; runtime compares normalized transcripts / mapped dialogue phrases.
- Dialogue scene: authored `accepted` mappings + `unknownReply`; UNKNOWN → ask again without inventing curriculum facts.

## 9. Off-screen challenges

`OFFSCREEN` (+ `MOVE`) scenes: room/body tasks; complete via remote «Готово» or voice confirm phrases. No camera.

## 10. Home 2.0

World sections: Продолжить · Сейчас в пути · Сегодня вместе · Исследуй · Решай · Создавай · Истории · Двигайся (+ legacy play/learn). Hero prefers mission `ADVENTURE` (lowest `sortOrder`). Data-driven catalog; no AI startup on Home.

## 11. Flagship adventure

**Космическая экспедиция** (`space-expedition`, ~12 min authored): intro → visual search → path maze → pattern → classify → science dialogue → move break → off-screen circle → calm reflection.

Bundled: `app/src/main/assets/catalog/adventures/space-expedition.json`  
Package: `dist/space-expedition-v1.zip` / `lessons/space-expedition/`.

## 12. Second reuse adventure

**Следопыты: тайна леса** (`forest-trail`, ~7 min) — same runtime/mechanics, different story data.

## 13. Authoring changes

- CLI collects `adventures/` payloads; `CONTENT_TYPES` includes `ADVENTURE`.
- `validate_adventure_json` checks scenes, mechanics, learning goals, scaffolding, transitions, reachability.
- `python3 tools/content/cli.py validate lessons/space-expedition` → OK.

## 14. H728 results

| Check | Result |
|-------|--------|
| Install debug APK | PASS |
| Home 2.0 worlds + mission hero | PASS |
| Flagship Adventure opens | PASS |
| Visual search (D-pad/tap) | PASS |
| Path maze (even route) | PASS |
| Pattern + classify (automated path) | PASS (scripted) |
| Group prompts visible | PASS |
| Guide lines (Луми) | PASS |
| Voice button present (lazy) | PASS |
| Forest reuse adventure | PASS |
| BACK / Домой → Home `sz=1` | PASS |
| Offline | PASS (bundled assets) |
| Local events recorded | PASS (`adventure_started` in prefs) |

## 15. Memory / startup

Fresh Home (force-stop, AI not loaded):

| Metric | Value | Target |
|--------|-------|--------|
| Cold Home `TotalTime` | **2458–2473 ms** | ~2.4 s |
| TOTAL PSS | **~108–110 MB** (108028–110351 KB) | ~108 MB |
| Native Heap | ~15–16 MB | AI=0 |
| Activities | 1 (Home) | — |

Adventure story/challenge before mic: no model warm; VoiceRuntime still deferred until «Сказать».

## 16. Screenshots

Under `docs/images/milestone-60/`:

- `01-home.png` / `03-home-hero.png` — Home 2.0 + mission
- `10-space-intro.png` — flagship intro + group mode
- `13-visual-search.png` — VISUAL_SEARCH
- `16-path-maze.png` — PATH_MAZE
- `18-pattern.png` / `20-classify.png` — PATTERN_SEQUENCE / SORT_AND_CLASSIFY
- `22-dialogue.png` — guide dialogue + voice affordance
- `20-forest-intro.png` — forest reuse challenge

## 17. Limitations

- Adventure UI is polished TV Views (large focus targets, gradient scenes) but still placeholder/procedural art — ready for professional assets via refs.
- Package narration audio optional; TTS/text fallback used heavily.
- Contingent dialogue is authored semantic buckets, not open LLM teaching.
- Session difficulty adaptation is light (integer step), not full IRT.
- Maze grid can scroll on small viewports; directional pad is primary.
- Hero may also list adventures under «Сейчас в пути» section (by design).

## 18. Next recommended product step

1. **Art & motion pack for Луми + space/forest layers** (swap placeholders without schema change).  
2. **Authoring preview** (desktop/TV dry-run of adventure graph + mechanic boards).  
3. **Richer dialogue mappings** (confidence thresholds + multi-turn authored trees) still without free curriculum invention.  
4. Short **family trial protocol** focused on one flagship run + sibling group modes.

---

## Tests

`./gradlew :app:testDebugUnitTest` — includes `AdventureRuntimeTest` / `MechanicEnginesTest` (transitions, four mechanics, scaffolding, group prompts, offscreen, validation, flagship+forest parse).  
Authoring: `validate_adventure_json` + CLI validate on both lessons.

## Compatibility

Signed packages, ContentPackageStore, LAN distribution/rollback, Parent Mode, screen-time, Continue Watching, Learning Sessions, Interactive experiences, Math/Color games, VoiceRuntime process scope — preserved.
