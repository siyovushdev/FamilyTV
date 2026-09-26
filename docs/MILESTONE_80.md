# Milestone 8.0 — Curiosity, Discovery & Creative Play

**Status:** Implemented (unit-tested; H728 device pass pending mic availability)

## 1. Curated Knowledge architecture

- `KnowledgeEntry` / `KnowledgePack` in `ru.familytv.knowledge`
- Package JSON under `assets/catalog/knowledge/{adventureId}.json`
- Fields: topic, aliases, ageMin/Max, questionPatterns, facts[], shortAnswer, spokenAnswer, followUps, relatedContentIds, sourceMetadata
- Educational truth comes only from authored packs — not model world knowledge

## 2. Ask Lumi / curiosity intents

- `CuriosityIntent`: WHY, HOW, WHAT_IS, WHAT_IF, TELL_ME_MORE
- `CuriosityIntentDetector` + `FamilyAction.AskLumi`
- `VoiceCommandRouter` routes curiosity **before** challenge answers on Adventure/Interactive/Home

## 3. Grounded AI behavior

- `KnowledgeResolver`: deterministic match (adventure pack → world → common)
- `KnowledgeGrounding.ground()` builds answers only from approved fields
- `isGroundedInEntry()` rejects external-fact candidates
- Missing match → honest UNKNOWN soft reply (no fabrication)
- Qwen may later match/rephrase **provided** facts only; resolver path does not call Qwen for facts

## 4. Discovery architecture

- Extends existing `MechanicType` / `MechanicRegistry` (no new runtime)
- `MechanicVerdict.OBSERVED` — never scored as failure
- Limited `CauseEffectModel` + `CauseEffectEngine` for authored rules (not general physics)
- No points / leaderboard / “you lost”

## 5. Predict & Test

- `PREDICT_AND_TEST`: choose hypothesis → «Проверить» → observation by option
- Wrong hypothesis → OBSERVED with outcome text (science-safe)

## 6. Build & Test

- `BUILD_AND_TEST`: place parts in slots → test
- Fail shows **why** (`failObservation`); stay active for rebuild without wrong-count

## 7. First Discovery experience

- **Лаборатория погоды** (`weather-lab`): predict melt location → build ice shelter → reflection
- Catalog entry `adv-weather-lab` on Explore / Today
- Knowledge pack `weather-lab.json` for Ask Lumi («почему лёд тает»)

## 8. Creative Studio architecture

- `CreativeStudioActivity` + `StoryBuilderRuntime` + `StoryStore`
- Authored blocks only — not free generative chat
- Routed via `ContentNavigator` (`route=creative/story` / id `lumi-story`)

## 9. Story Builder

- Grammar steps: HERO → WORLD → GOAL → OBJECT → OBSTACLE → CHOICE → ENDING
- `choiceToEnding` map → real branching endings
- Voice alias + safe adjectives map free speech → option ids
- Template retell composition

## 10. First created-story experience

- **Придумай историю с Луми** (~5–10 min), Home «Создавай»
- Remote tiles + voice mapping; REPEAT supported

## 11. Group creative mode

- «Вместе с семьёй» sets `groupMode`; per-step `groupHint` (“Пусть один выберет…”)
- No speaker identification

## 12. Space / Forest WHY integration

- `space-expedition.json`: moon air, dark sky, orbit
- `forest-trail.json`: owl night, living things
- AdventureActivity resolves Ask Lumi against adventure-scoped packs

## 13. Age / audio-first

- Knowledge entries carry optional ageMin/Max (soft filter)
- Discovery & story prompts use spokenPrompt; options have visual glyphs + voiceAliases
- ReadingSupportMode unchanged / separate

## 14. Authoring changes

- `tools/content/quality.py`: discovery fake-experiment, missing observations, knowledge facts, story dead ends / non-branching endings, reading dependency

## 15. Trial telemetry (local)

- CURIOSITY_QUESTION, KNOWLEDGE_FOUND/UNKNOWN, EXPERIMENT_*, PREDICTION_MADE, STORY_*, REFLECTION_COMPLETED
- No raw audio; no cloud upload

## 16. H728 result

- **Device not attached** at verification time (`adb devices` empty).
- Unit tests: knowledge / discovery / creative / AdventureRuntime — **PASS**.
- On next H728 connect: installDebug → Weather Lab predict/test/build → Ask Lumi WHY + UNKNOWN → Story Builder remote/voice + replay + group → confirm Home AI=0 / PSS.
- Acoustic mic: use deterministic transcript injection if unavailable; do not claim acoustic quality.

## 17. Memory / performance

- Knowledge + story are JSON/data — no Home AI load
- Creative Studio does not auto-start VoiceRuntime
- Idle AI unload: left as documented risk from 7.x (no new runtime)

## 18. Limitations

- Qwen semantic matching not yet wired for knowledge (deterministic alias/pattern scoring first)
- Weather Lab uses functional UI (not FINAL_ART)
- Story Builder voice lease optional (remote-first in Creative Studio)
- Cause/effect rule engine available but Weather Lab uses outcome maps primarily
- Age adaptation is soft (entry filters + shorter prompts), not dynamic rewrite

## 19. Next recommended milestone

**8.1 — Grounded Qwen Matching + Narration Completeness**

- Optional on-demand Qwen for option/knowledge matching with fact-only prompts
- Full V1_CANDIDATE narration for Weather Lab + Story Builder
- Idle AI release experiment if PSS after AI stays ~1GB
- More discovery packs (light / sound / plants)

---

### Success criterion

FamilyTV now supports three activity kinds:

1. **ADVENTURE** — Space / Forest missions  
2. **DISCOVERY** — Weather Lab predict/build/observe  
3. **CREATE** — Story Builder with real choice→ending agency  

Plus Ask Lumi WHY grounded only in approved knowledge.
