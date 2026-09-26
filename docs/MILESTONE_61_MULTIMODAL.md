# Milestone 6.1 — Multimodal Interaction, Audio-first UX, Age/Reading Modes

Device: ADT-3 / H728 (`192.168.68.109:5555`).  
Date: 2026-09-19.

## Verdict

**PASS (core).** FamilyTV now separates **household age band** from **ReadingSupportMode**, routes child-facing speech through **NarrationCoordinator**, and maps remote + voice to the same **FamilyAction** set via **VoiceCommandRouter** + **FamilyActionDispatcher**. REPEAT / HELP are first-class. Parent-sensitive intents are blocked. Home voice listening is **opt-in** (mic button) so cold Home stays AI=0.

Primary success criterion: a non-reading child can follow adventures via spoken guidance + D-pad/voice, while the same text remains visible for readers/adults.

---

## 1. Age vs reading-mode architecture

| Concept | Store | Role |
|---------|-------|------|
| `HouseholdAgeBand` / `FamilyContentFilter` | Parent Mode | Content filtering — not identity |
| `ReadingSupportMode` | Parent Mode (accessibility) | `AUDIO_FIRST` · `AUDIO_AND_TEXT` · `TEXT_RICH` |

Age never auto-sets reading mode. Family Mode remains default (no child profile picker).

## 2. Audio-first contract

`PresentationMessage`: `text`, optional `spokenText`, optional `narrationRef`, `role`, `importance`, `repeatable`.

Important child-facing lines go through this contract (instruction / question / hint / feedback / dialogue / move).

## 3. NarrationCoordinator

`PlatformServices.narrationCoordinator()`:

1. package audio (`narrationRef` / `promptAudio`)
2. local/system TTS via `NarrationProvider`
3. text-only fallback

`AUDIO_FIRST` + text-only → `audioFirstGap=true` (explicit; not pretended as spoken).

Keeps `current` for REPEAT; `restore()` for resume/lifecycle.

## 4. REPEAT

Voice: «повтори», «ещё раз», «что нужно сделать?»  
UI: «↻ Повтори» on Adventure.

Re-presents current message — **no** challenge mutation, retry, or difficulty change.

## 5. HELP

Voice: «помоги», «я не понимаю», «дай подсказку»  
UI: «? Помоги»

`AdventureRuntime.requestHelp()` advances scaffolding (hint1 → hint2 → …) without treating as a scored wrong for difficulty.

## 6. FamilyAction model

`Home`, `Back`, `Repeat`, `Help`, `Pause`, `Resume`, `Next`, `Select`, `ContinueCurrent`, `OpenWorld`, `OpenContent`, `OpenGame`, `StartAdventure`, `Answer`, `OpenParentGate`, `BlockedSensitive`.

## 7. ActionDispatcher

`FamilyActionDispatcher` + `FamilyActionHandler` — REMOTE and VOICE share one handler path. Surfaces attach/detach on lifecycle.

## 8. VoiceCommandRouter

Allowlisted transcript → `FamilyAction`. No autonomous Android agent. Uses catalog metadata for discovery (`ContentDiscovery`).

## 9. Context arbitration

`RouteContext.awaitingAnswer`:

- «повтори» / «помоги» / «назад» / «домой» → commands
- «шесть» / «я думаю шесть» → `Answer(transcript)`
- Global commands never enter mechanic answer path first

## 10. Home voice navigation

Mic button on Home (explicit). Commands: «покажи игры», «истории», «начать космическую экспедицию», «продолжить», «родителям» (gate only).

**Does not** start `VoiceRuntime` on cold Home.

## 11. Adventure voice control

Answer / select via voice, REPEAT, HELP, NEXT where allowed. Expected answers never enter Qwen. Runtime remains authority.

## 12. D-pad / voice parity

| Action | Remote | Voice |
|--------|--------|-------|
| HOME / BACK | ✓ | ✓ |
| REPEAT / HELP | ✓ buttons | ✓ |
| NEXT / Готово | ✓ | ✓ (when allowNext) |
| Answer / select | ✓ | ✓ |
| Open content/world | ✓ focus | ✓ discovery |
| Parent PIN / screen-time / admin | remote + PIN only | **blocked** |

## 13. Parent security

Voice may open Parent Mode gate only. PIN/settings/package admin never via child voice router (`BlockedSensitive`).

## 14. Lumi integration

Guide lines + NarrationCoordinator: REPEAT re-speaks current task; HELP speaks next scaffold as Луми. Not a free chatbot.

## 15. Authoring / schema

- `spokenPrompt` on story/challenge JSON
- existing `narrationAudio` / `promptAudio` / `voiceAliases`
- Parent `readingSupportMode` preference

## 16. Audio-first linter

`tools/content/quality.py` → `check_adventure_audio_first` warnings for missing spoken paths / long text without spokenPrompt. Kotlin `AudioFirstValidator` for unit tests.

## 17–18. Space & Forest

Both adventures updated with `spokenPrompt`; share interaction layer (REPEAT/HELP/router/narration).

## 19. Future Camera boundary

See `docs/FUTURE_CAMERA_SIGNALS.md` — ContextSignals only through controlled interaction layer; no face ID; age estimate is approximate and never overrides Parent settings.

## 20. H728 acceptance

| Check | Result |
|-------|--------|
| Install + cold Home | PASS — TotalTime **2526 ms** |
| Fresh Home AI=0 | PASS — Native ~**15.7 MB**, TOTAL PSS ~**111.6 MB** |
| Adventure REPEAT/HELP UI | PASS — buttons on challenge/dialogue |
| Spoken + visible text | PASS |
| Reading mode in Parent Mode | PASS (UI + store) |
| Router unit tests (transcript injection) | PASS |
| Acoustic ASR validation | **not claimed** |

Screenshots: `docs/images/milestone-61/`.

## 21. Memory / startup

| Metric | 6.0 baseline | 6.1 measured |
|--------|--------------|--------------|
| Cold Home | ~2.47 s | **2.53 s** |
| TOTAL PSS | ~108–110 MB | **~112 MB** |
| Native (AI=0) | ~15 MB | **~15.7 MB** |

VoiceRuntime still deferred until Home mic button or in-content «Сказать».

## 22. Limitations

- No package narration WAV assets yet → TTS / text-only; AUDIO_FIRST gaps flagged when TTS unavailable.
- H728 Russian system TTS may be missing (known from 5.1) — gap is explicit.
- Home voice is push-to-talk style, not always-on listening.
- Interactive/legacy games not fully migrated to FamilyAction (Adventure + Home are).

## 23. Recommended next milestone

1. Ship package narration audio for flagship prompts (true AUDIO_FIRST offline).  
2. Extend FamilyAction wiring to Interactive Engine + games.  
3. Optional always-listening mode as Parent-gated experiment (measure PSS).  
4. Camera ContextSignals spike (presence only) behind the documented boundary.
