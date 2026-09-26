# Interactive Engine v2

> Superseded by [`docs/INTERACTIVE_ENGINE_V3.md`](INTERACTIVE_ENGINE_V3.md) (true_false, move, feedback).

Data-driven lessons. Definitions describe **what** happens, not Android Activities/Views.

## Step types

| Type | Behavior |
|------|----------|
| `message` | Text + continue |
| `video` | Plays `mediaRef` via Media3 until completion, then advances |
| `choice` | TV/D-pad options; `correctOptionId` |
| `question` | Voice (`VOICE_NUMBER` / `VOICE_TEXT`) via shared `VoiceRuntime` |
| `game` / `break` | Parsed; lightly stubbed in runner |

## Transitions

Steps have stable ids. Optional:

```json
"transitions": {
  "next": "step-id-or-__end__",
  "onCorrect": "...",
  "onWrong": "...",
  "onUnknown": "..."
}
```

All targets validated before run. Intentional retry loops (`onWrong` → same step) are allowed.
Legacy schemaVersion 1 experiences get linear `next` filled automatically.

## Voice

Uses existing `VoiceRuntime` / `VoiceAnswerPipeline`.
Expected answers stay in `InteractiveSession` / `InteractiveJudge` — **never** sent to Qwen.
If mic/runtime unavailable: show error, allow skip via correct transition (no crash).

## Progress

Device-scoped (`ExperienceProgressStore`):

- experienceId, packageId, packageVersion, currentStepId, completed, updatedAt

Resume only when package identity/version matches and step still exists; otherwise clear.

## Demo

`apple-adventure` package experience: message → video → choice → message → voice apples → video → message.

See `docs/examples/apple-adventure.experience.json`.
