# FamilyTV Beta

FamilyTV Beta is a usable children's TV product for the home — not a pile of demos.

## Product philosophy

**FamilyTV optimizes for useful screen time, not maximum engagement.**

- Finite sessions with calm endings
- No infinite autoplay feed
- No shame, streaks, loot, or ads
- Family/device progress by default (no mandatory child profiles)
- Local and private: PIN, settings, and history stay on the device

## Architecture (child UX)

| Surface | Role |
|---------|------|
| **Home** | TV-first launcher: hero, Continue, sectioned rows (data-driven `homeSections`) |
| **Details** | Title, artwork, duration, topics, Start/Continue before longer content |
| **Interactive Engine v3** | message / video / choice / true_false / move / voice |
| **Learning Session** | Curated finite path; calm end: «На сегодня всё» |
| **Games** | Math + Color via shared `VoiceRuntime` (optional mic) |
| **Parent Mode** | PIN-gated; screen time, activity, packages, a11y |

Progress scope remains **device/family** (`ProgressScope.Device`). Future profiles may exist architecturally but are **not required**.

## Home sections

Data-driven IDs (Russian titles in UI):

- `continue` — Продолжить
- `today` — Сегодня вместе
- `play` — Поиграть
- `learn` — Узнать новое
- `stories` — Истории
- `move` — Подвигаться

Do **not** hardcode package IDs in Home UI.

## Performance guarantees (protect)

Verified H728 baseline before Beta UX work:

- Cold Home ≈ **1.8–2.0 s**
- Fresh Home PSS ≈ **105–106 MB**
- **No AI init** on fresh Home
- Catalog rebuild skipped when `catalogGeneration` unchanged

Profile after redesign on device; do not sync network or decode huge bitmaps on the main thread during Home.

## Content for Beta

Bundled catalog + installable packages under `lessons/` / `dist/`:

- Counting / apples, colors/shapes, animals, space, memory/attention, movement, short story
- Sessions: **Сегодня вместе** (~20 min), **Короткое утро** (~15 min)

Authoring: `python3 tools/content/cli.py validate|build|quality <lesson>`

## Continue Watching

Resume threshold is duration-aware. **Ultra-short clips under 8 seconds are not Continuable** — any save with a known duration below that marks the item completed and keeps it out of Continue Watching. Real FamilyTV videos are longer; this avoids bogus rows when ExoPlayer still reports `position=0` on early BACK from 3–4s demos.

Captions: optional `metadata.captionRef` (WebVTT/SRT) via Media3; Parent accessibility toggle.

## Out of scope (Beta)

Production cloud, CMS, accounts, mandatory profiles, recommendation AI, camera, payments, ads, social, streak economy, large TTS/ASR research.
