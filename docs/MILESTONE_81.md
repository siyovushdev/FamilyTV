# Milestone 8.1 — Grounded Lumi Dialogue + Natural Child Language + Narration

**Status:** Complete — **H728 DEVICE VERIFIED** (192.168.68.109:5555), 2026-09-20.

Evidence harness: `tools/h728_m81_acceptance.sh` + deterministic `inject_transcript` (not acoustic ASR).

Verification legend used below:

- **IMPLEMENTED** — code shipped
- **DEVICE VERIFIED** — proven on H728 this session
- **TEST ONLY** — JVM/unit coverage
- **NOT VERIFIED** — not claimed

---

## 1. Grounded Qwen matcher — IMPLEMENTED + TEST ONLY (+ DEVICE path uses deterministic)

- `GroundedQwenMatcher` + `DeterministicGroundedMatcher`
- Optional `KnowledgeMatchBackend` for constrained JSON completion
- On H728 acceptance: deterministic matcher (no Qwen load) — Home AI=0 preserved

## 2. Strict structured output — IMPLEMENTED + TEST ONLY

- Allowlisted JSON contract; invalid ID → UNKNOWN

## 3. Fact-locking — IMPLEMENTED + DEVICE VERIFIED + TEST ONLY

- Answers from approved `shortAnswer` / `facts` / `additionalFacts` only
- Device logs show grounded Space/Forest/Weather wording; invent/black-hole → UNKNOWN

## 4. Dialogue structured context — IMPLEMENTED + DEVICE VERIFIED

- Follow-ups reuse `lastKnowledgeId` / `entityId`
- Space: «А почему?» after moon-air stayed on moon-air
- Forest: «А днём?» → owl-day; «А ещё?» → additionalFacts

## 5. Follow-up behavior — DEVICE VERIFIED

| Phrase | Result |
|--------|--------|
| Почему на Луне почти нет воздуха? | FOUND moon-air |
| А почему? | FOUND moon-air (context) |
| А ещё? | TELL_ME_MORE additional fact |
| Почему сова летает ночью? | FOUND owl-night |
| А днём? | FOUND owl-day |

## 6. TellMeMore — DEVICE VERIFIED

- Walks `additionalFacts`; does not invent beyond pack

## 7. WhatIf → experiment — DEVICE VERIFIED

Weather Lab:

| Phrase | Result |
|--------|--------|
| Почему лед тает? | FOUND ice-melting |
| А если сделать теплее? | **EXPERIMENT** ref=`where-melts-faster` invite=«Давай проверим!» |
| А если убрать солнце? | EXPERIMENT |
| А если наоборот? | EXPERIMENT |

## 8. Clarification — DEVICE VERIFIED

- «Почему Луна или корабль?» → `CLARIFY options=[moon-air, ship-float]`

## 9. UNKNOWN — DEVICE VERIFIED

- «Расскажи про черную дыру» → UNKNOWN soft reply
- «придумай сам» → UNKNOWN
- No general-model encyclopedia answer observed

## 10–12. Space / Forest / Weather — DEVICE VERIFIED

- Bundled knowledge packs in APK assets (no signature bypass required for these packs)
- Weather adventure validate bug fixed: JSONObject `optString(null)` → `"null"` treated as missing next

## 13–14. Story Builder mapping + recap — DEVICE VERIFIED

```
MAPPED robot + маленький
MAPPED forest
MAPPED find_star → map → dark → help_friend
RETELL … ENDING=ending_friends
```

Choices stayed allowlisted; ending followed `choiceToEnding`. Recap did not invent new characters (template composition).

## 15. Adversarial grounding — DEVICE VERIFIED + TEST ONLY

Device: black hole / invent → UNKNOWN; TellMeMore stays inside additionalFacts.  
JVM: `GroundedDialogueTest` corpus.

## 16. H728 real acceptance — DEVICE VERIFIED

- Device: ADT-3 / H728 @ `192.168.68.109:5555`
- Publisher reverse `tcp:8080` available (indexVersion 13); dialogue under test used **bundled** knowledge/adventures
- Acoustic mic: **not** used — deterministic transcript injection only (**ASR quality NOT claimed**)

## 17. Home PSS / AI=0 — DEVICE VERIFIED

| Metric | Value |
|--------|-------|
| Cold Home `TotalTime` | **2293 ms** (also 2234–2308 across runs) |
| Fresh Home PSS | **~120 MB** (119616–120497 KB) |
| AI init logs on Home | **0** (`llmInitMs` / `runtimeInitialization` absent) |
| home_ai_loaded | **no** |

## 18. First / warm grounded dialogue latency — DEVICE VERIFIED

Injection wall times (includes `am start` delivery + dialogue), not ASR:

| | |
|--|--|
| First Space inject latency | ~1.7 s wall |
| Warm injects | ~1.7–1.9 s wall |
| End-to-end resolve in-process | ≪100 ms (log inject→result ~50–80 ms) |

Qwen **not** loaded for these asks → no “first Qwen load” PSS spike.

## 19. AI-loaded PSS — NOT VERIFIED (deferred)

- Idle unload remains **deferred** (documented risk)
- Curiosity path did not auto-load Qwen on H728 acceptance

## 20. Offline — DEVICE VERIFIED

- Removed `adb reverse` 8080; Space WHY still **FOUND moon-air** from bundled assets

## 21. Remaining limitations

- Live Qwen semantic ranking not exercised on device this pass (deterministic matcher sufficient for acceptance)
- Pre-recorded `spokenAnswerAudio` still mostly missing (spokenAnswer text + NarrationCoordinator TTS/text)
- Story retell template still slightly awkward Russian (“В лес”); agency/grounding OK
- Injection harness requires careful adb quoting for Cyrillic spaces
- `tools/h728_m81_acceptance.sh` updated for quoted inject + singleTop

## Fixes landed during acceptance

1. Deterministic transcript inject via `singleTop` + `inject_transcript`
2. Curiosity routing before challenge answers for «расскажи/придумай/знаешь»
3. Weather validate: `"next": null` JSON → string `"null"` rejected by parser
4. Safe `onNewIntent` inject when runtime not ready (queue/flush)

## Next recommended step

**8.2** — optional warm-Qwen matcher when voice already loaded; spokenAnswerAudio for critical knowledge; Parent Mode explicit `releaseIdle`; polish Story retell templates.
