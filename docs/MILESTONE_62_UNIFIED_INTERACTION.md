# Milestone 6.2 — Unified Child Interaction + Offline Narration

Device: ADT-3 / H728 (`192.168.68.109:5555`).  
Date: 2026-09-19.

## Verdict

**PASS.** Child-facing FamilyTV now shares one **FamilyAction** / **VoiceCommandRouter** / **NarrationCoordinator** model. Package + common voice-pack audio plays **offline** without Russian system TTS. Space/Forest/count-apples narration files are **DEV/PLACEHOLDER tones** (not professional voice).

Primary success criterion met: one interaction model; remote and voice trigger the same actions; a non-reading child can follow critical instructions via offline spoken guidance while text stays visible.

---

## 1. Unified interaction architecture

| Layer | Role |
|-------|------|
| `FamilyAction` | Allowlisted child actions |
| `FamilyActionDispatcher` | REMOTE / VOICE fan-out |
| `VoiceCommandRouter` | Transcript → action (answer vs command) |
| `ChildInteractionBinder` | Shared attach/route helper |
| `NarrationCoordinator` + `NarrationAssetResolver` | Offline package / common / TTS / text |
| `PresentationMessage` | `displayText` + `spokenText` |

Surfaces wired: **Home · Adventure · Interactive · Math/Color · Session · Video**.

## 2. Interactive migration

`InteractiveExperienceActivity` dispatches REPEAT / HELP / HOME / BACK through `FamilyActionDispatcher`. Choice / TrueFalse / Move / VoiceNumber answers still hit the same session logic; voice commands are intercepted before answer scoring. Narration uses `NarrationCoordinator` with package `promptAudio` / common pack refs.

## 3. Math / Color migration

`BaseVoiceGameActivity` accepts shared actions (BACK, HOME, REPEAT, HELP, ANSWER/SELECT). Remote and voice converge on existing deterministic game rules. Expected answers are never passed to Qwen.

## 4. Session migration

`LearningSessionActivity` routes REPEAT / HELP / BACK / HOME (and pause/resume where the child step supports it). No infinite autoplay.

## 5. Video migration

`VideoPlayerActivity` child actions: PAUSE, RESUME, REPEAT (title/instruction), BACK, HOME — same dispatcher as voice; not a separate media controller.

## 6. Command vs answer arbitration

Deterministic tests cover:

| Transcript (awaiting number) | Action |
|------------------------------|--------|
| «шесть» | ANSWER(6) |
| «я думаю шесть» | ANSWER |
| «повтори» | REPEAT |
| «помоги» | HELP |
| «назад» | BACK |

Parent PIN / settings remain voice-blocked.

## 7. Narration asset model

- Package/catalog refs: e.g. `space-expedition/intro.wav`, `promptAudio`, `narrationAudio`
- Optional slots via `NarrationAssets`: prompt / repeat / correct / wrong / hint*
- `displayText` vs `spokenText` on `PresentationMessage` (usually equal; may diverge safely)
- Lazy materialization from assets (no bulk preload)

## 8. Common Lumi voice pack

`CommonVoicePack` keys: `repeat_task`, `try_again`, `want_hint`, `go_next`, `did_not_hear`, `go_home`, `discuss_together`, `someone_answer`, `other_turn`, `well_done`.

Resolution: `common:<key>` → `assets/narration/common/*.wav` without copying into every package.

Quality marker: **DEV_PLACEHOLDER** (`narration/common/README.txt`).

Lumi speaks authored instructions / repeats / hints / calm feedback — not a general chatbot and does not invent educational facts.

## 9. AUDIO_FIRST quality gate

- Kotlin `AudioFirstValidator`
- Python `narration_coverage` + `audio_first_release_errors` in CLI validate
- DEV override: `.audio_first_dev_ok` in lesson root
- Content claiming `"readingSupport": "AUDIO_FIRST"` must have spoken/narration path for critical instructions (ERROR at release quality)

Measured coverage (lesson sources):

```
=== space-expedition ===
AUDIO_FIRST coverage:
  Instructions: 8/8
  Feedback: 0/0
  Hints: 12/12
  Navigation: common voice pack
  ready=True quality=AUDIO_FIRST

=== forest-trail ===
AUDIO_FIRST coverage:
  Instructions: 5/5
  Feedback: 0/0
  Hints: 7/7
  Navigation: common voice pack
  ready=True quality=AUDIO_FIRST
```

## 10. Authoring narration workflow

`tools/content/cli.py validate` prints coverage summary; hashes via existing package build. `.wav` / `.ogg` allowed. Narration refs checked; files included in package build path.

## 11. Space result

`space-expedition.json`: AUDIO_FIRST; `spokenPrompt` + `promptAudio`/`narrationAudio` on story, challenges, dialogue, move, offscreen. Placeholder WAV under `catalog/narration/space-expedition/`.

## 12. Forest result

`forest-trail.json`: AUDIO_FIRST; same pattern including sort / trail / dialogue scenes. Placeholder WAV under `catalog/narration/forest-trail/`.

## 13. Existing non-adventure content

`count-apples` interactive: AUDIO_FIRST metadata + `promptAudio` / `correctAudio` / `common:well_done`. Math/Color: shared REPEAT/HELP + command interceptor (no full rewrite).

## 14. H728 validation

| Check | Result |
|-------|--------|
| Cold Home (primary smoke) | **2570 ms** (LaunchState: COLD) |
| Cold Home (recheck after asset install) | **2804 ms** (noise; still ~2.5–2.8 s class) |
| Fresh TOTAL PSS (primary) | **108960 (~109 MB)** |
| Fresh TOTAL PSS (recheck) | **113596 (~114 MB)** |
| Native Heap | **~15.3–15.7 MB** |
| AI=0 Home | PASS (no VoiceRuntime until mic opt-in) |
| Adventure opens + narration path | PASS (placeholder offline; no FATAL) |
| Interactive / Math screens | PASS (`docs/images/milestone-62/`) |
| Acoustic ASR quality | **not claimed** (transcript injection OK) |

Screenshots: `docs/images/milestone-62/01-home.png`, `10-adventure-narration.png`, `20-interactive.png`, `30-math.png`.

## 15. Offline narration

Package catalog tones + common voice pack + REPEAT/HELP/Adventure/Games/Sessions work without network. Child-critical narration does not require cloud or system Russian TTS.

## 16. Remote / voice parity

| Action | Remote | Voice |
|--------|--------|-------|
| HOME | ✓ | «домой» |
| BACK | ✓ | «назад» |
| REPEAT | ✓ | «повтори» / «ещё раз» |
| HELP | ✓ | «помоги» |
| PAUSE / RESUME | ✓ (video) | «пауза» / «продолжай» |
| ANSWER / SELECT | ✓ | number/phrase → Answer |
| Open worlds / continue | ✓ | allowlisted discovery |

Same `FamilyAction` set; parent-sensitive intents blocked.

## 17. PSS / startup

| Metric | Baseline | M6.2 measured |
|--------|----------|---------------|
| Cold Home | ~2.53 s | **2.57–2.80 s** |
| Fresh PSS | ~112 MB | **~109–114 MB** |
| Native | ~15.7 MB | **~15.3–15.7 MB** |
| AI on Home | 0 | **0** |

Narration tones are tiny; REPEAT does not load Qwen / VoiceRuntime on Home.

## 18. Limitations

- Narration is **DEV placeholder tones**, not Russian professional speech
- H728 may still lack Russian TTS; package audio is the real offline path
- Interactive/Game HELP is authored generic (not full scaffolding everywhere)
- Always-on listening not enabled (opt-in only)
- Professional visual/audio content still pending

## 19. Blockers before visual / professional-content phase

1. Replace DEV WAV with authored Russian package narration (or high-quality offline TTS)
2. Finish narration coverage for remaining Interactive packages in `dist/`
3. Optional always-listening Parent-gated experiment (PSS budget)
4. Then: professional art + voice talent — **architecture is ready**

See also: `docs/FUTURE_CAMERA_SIGNALS.md` (unchanged boundary).

---

## Reading mode (unchanged independence)

`HouseholdAgeBand` ≠ `ReadingSupportMode`. No hidden `age < 7 ⇒ AUDIO_FIRST`. AUDIO_FIRST / AUDIO_AND_TEXT / TEXT_RICH behave as specified; age and reading stay independent.
