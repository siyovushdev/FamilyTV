# Screen time

Family-level, local controls. Soft reminders only — **do not** abruptly kill playback without warning.

## Settings (`FamilySettingsStore` / Parent Mode)

| Setting | Default | Behavior |
|---------|---------|----------|
| Reminders enabled | on | Master switch |
| Daily limit (min) | off (0) | Warn near limit, then rest message |
| Session reminder (min) | 20 | Soft cap for continuous use |
| Rest reminder (min) | 25 | «Пора сделать перерыв» |
| Warn minutes before | 5 | e.g. «Осталось 5 мин» |
| Bedtime window | off | Soft «Сейчас время отдыха» |

## UX examples

1. «Осталось 5 минут»
2. «Пора сделать перерыв»

Parent can disable any of the above. Child Home shows the current reminder in the top bar and refreshes it about every 30 seconds while Home is resumed (so rest messages appear without requiring navigation).

## Tracking

`ScreenTimeTracker` stores local day totals. Activity history records content events separately for Parent Mode summaries.

## Privacy

No cloud, no accounts, no per-child surveillance claims.
