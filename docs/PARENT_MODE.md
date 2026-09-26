# Parent Mode

Local-only parent surface, clearly separated from child Home.

## Entry

- Home: **Родителям** (opens PIN gate)
- Optional: hold OK on the FamilyTV brand (~2s)

PIN is the real gate — long-press alone is not the security model.

## PIN

- First visit: create 4–6 digit PIN (`ParentPinStore`, salted SHA-256)
- Later: verify PIN
- Never sent to a network; prefs are device-local

## Dashboard

- **Сегодня** — activity summary (neutral language; no mastery claims)
- **Экранное время** — reminders / optional daily limit / session length
- **Диапазон контента** — household age filter (e.g. 4–8), **not** a child profile
- **Доступность** — narration on/off, large text
- **Контент** — installed packages + Content Admin
- **О приложении**
- **Разработка** — VoiceLab / Admin only in **debug** builds

## Family Mode default

Children may watch together. No login or profile picker is required for Beta.

## Related

- [SCREEN_TIME.md](SCREEN_TIME.md)
- [FAMILYTV_BETA.md](FAMILYTV_BETA.md)
