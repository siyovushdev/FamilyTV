# Learning Experiences

## Interactive Engine v3

Step types:

| Type | Purpose |
|------|---------|
| `message` | Text + continue |
| `video` | Media3 playback until end |
| `choice` | D-pad multi-option, single correct |
| `true_false` | Правда / Неправда |
| `question` | Voice (`VOICE_NUMBER` / `VOICE_TEXT`) via VoiceRuntime |
| `move` | Movement break (no camera); button continue |
| `break` | Soft pause (legacy) |

Feedback is data-defined:

```json
"feedback": {
  "correct": "Верно!",
  "wrong": "Посчитай ещё раз",
  "unknown": "Скажи число",
  "hint": "2 + 3",
  "retryLimit": 3
}
```

Retry loops use `transitions.onWrong` → same step; after `retryLimit` the engine soft-advances.
Wrong answers retry gently; after `retryLimit` the engine soft-advances
(not punitive). Expected answers never go to Qwen.

## Learning sessions

Finite curated paths (`type: SESSION`), e.g. 10–20 minutes:

video → interactive → move

No infinite autoplay. Resume via device-scoped `SessionProgressStore`.

## Demo packages

| Package | Focus |
|---------|--------|
| `apple-adventure` v2 | Math + choice + voice + move |
| `animal-world` v1 | World knowledge + true/false |
| `move-break` v1 | Watch → think → move |
| `morning-session` v1 | Curated session referencing the above |

Built with `tools/content/build.py` into `dist/`.
