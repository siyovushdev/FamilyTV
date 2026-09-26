# Interactive Engine v3

Successor to `docs/INTERACTIVE_ENGINE_V2.md`. Data-driven lessons still describe
**what** happens — not Android Activities.

## Step types

| Type | Behavior |
|------|----------|
| `message` | Text + continue |
| `video` | Media3 until completion, then advances |
| `choice` | D-pad options; optional `correctOptionId` (multi-choice / single-correct) |
| `true_false` | Two large options; `correct` boolean |
| `move` | Movement break: prompt + timer + D-pad continue (`continueMode: button`) |
| `question` | Voice (`VOICE_NUMBER` / `VOICE_TEXT`) via shared `VoiceRuntime` |
| `game` / `break` | Parsed; lightly stubbed where unused |

## Feedback (data-driven)

Steps may declare:

```json
"feedback": {
  "correct": "Отлично!",
  "wrong": "Посчитай ещё раз",
  "unknown": "Не расслышал",
  "hint": "Посмотри на экран",
  "retryLimit": 2
}
```

Retry is expressed by `transitions.onWrong` pointing at the same step.
After `retryLimit`, soft-advance via `onCorrect` / `next` so learning stays non-punitive.
No score economy.

## Transitions

```json
"transitions": {
  "next": "step-id-or-__end__",
  "onCorrect": "...",
  "onWrong": "...",
  "onUnknown": "..."
}
```

## Voice

Existing `VoiceRuntime` only. Expected answers stay in `InteractiveSession` /
`InteractiveJudge` — **never** sent to Qwen. Mic unavailable → recoverable skip path.

## Progress

`ProgressScope.Device` via `ExperienceProgressStore` (packageId + packageVersion + step).
Resume only when identity/version match.

## Sessions

Curated `SESSION` content items run `LearningSessionActivity` over an ordered list of
catalog items (video → interactive → move → …). Stop/resume supported; no infinite autoplay.

See `docs/LEARNING_EXPERIENCES.md`.
