# FamilyTV Platform Alpha

Child-facing offline-first foundation for Android TV / set-top boxes.
Voice remains a subsystem; Home is the product surface.

## Product architecture

```
HomeActivity (launcher)
 ├─ ContentRepository → LocalContentRepository
 │    ├─ Bundled assets/catalog/manifest.json
 │    └─ Optional imported FamilyTV/catalog/active/
 ├─ WatchProgressStore (ProgressScope.Device)
 ├─ ContentNavigator
 │    ├─ VIDEO → VideoPlayerActivity (Media3)
 │    ├─ GAME route → Math / Color activities
 │    └─ INTERACTIVE → InteractiveExperienceActivity
 └─ Debug long-press brand → ContentAdminActivity / VoiceLab
```

Existing `VoiceRuntime`, Voice Accuracy Lab, VoiceLab, and voice games are preserved.
Home does **not** prewarm Qwen/STT — video playback works while models are cold.

## Content model

`ContentItem`: id, title, description, type (`VIDEO` | `GAME` | `INTERACTIVE`),
optional ageRange, categories/tags, thumbnail, durationMs, mediaRef / route / experienceRef,
enabled, sortOrder, version, homeSections.

Home sections (empty ones hidden): Продолжить, Сегодня для тебя, Поиграть, Узнать новое, Истории, Подвигаться.

## Catalog schema

```json
{
  "schemaVersion": 1,
  "catalogVersion": 1,
  "items": [ { "...": "..." } ]
}
```

Validation:

- Unsupported schema or missing items → reject manifest
- Bad individual items → skip + warning log; catalog still loads if ≥1 valid item
- Active import that fails validation → keep previous working catalog / fall back to bundled

Example: `docs/examples/catalog-manifest.example.json`

## Repository

`ContentRepository` is the only UI entry point.

`LocalContentRepository` today:

| Source | Path |
|--------|------|
| Bundled | `assets/catalog/` |
| Local import | app-specific `FamilyTV/import/` → validate → atomic activate `FamilyTV/catalog/active/` |

Future (not implemented): `RemoteContentRepository`, `CachedContentRepository`, CMS sync.
UI and games do not need to change when those appear.

## Watch progress

Device/family scoped (`ProgressScope.Device`). Storage keys reserve optional `profileId`.

Fields: contentId, positionMs, durationMs, completed, lastPlayedAt.

Resume from a sensible position (≥3s, not near end). Completion clears Continue Watching.

## Continue Watching

Home section lists unfinished videos only (not completed, not tiny scrub positions), newest first.

## Interactive experiences

JSON under `catalog/experiences/`. Steps: `VideoStep`, `MessageStep`, `QuestionStep`
(+ `GameStep` / `BreakStep` parsed, lightly stubbed in runner).

Question types: `CHOICE`, `VOICE_NUMBER`, `VOICE_TEXT`.

`InteractiveSession` owns expected answers. Voice uses `VoiceRuntime` / `VoiceAnswerPipeline`.
Normalized transcript is judged in-app — **never** passed into Qwen as an expected answer.

Demo: `count-apples.json` — message → 2+3 apples VOICE_NUMBER → message.

## Local admin / import

Debug-only entry: long-press **FamilyTV** brand on Home (debuggable builds).

Admin can reload, validate, enable/disable, import from `FamilyTV/import/manifest.json`
(+ optional `media/`, `experiences/`). Bad import never wipes active catalog.

## Navigation

| From | To |
|------|----|
| Home | Video / Game / Interactive |
| Any child screen | back → Home |
| VoiceLab / Accuracy Lab | developer tools, not Home cards |

## Authoring (Milestone 4.2)

Use `tools/content/` to build packages from `lessons/` sources without manual hashes.
See `docs/CONTENT_AUTHORING.md` and `docs/LEARNING_EXPERIENCES.md`.

Interactive Engine v3 adds `true_false`, `move`, data-driven feedback, and curated SESSION items.
H728 rollback proof: `docs/MILESTONE_42_H728.md`.

Signed LAN distribution: `docs/CONTENT_DISTRIBUTION.md`, `docs/CONTENT_SIGNING.md`,
`docs/LOCAL_PUBLISHER.md`, H728 proof `docs/MILESTONE_DISTRIBUTION_H728.md`.


## Future remote CMS path

```
CMS → package index → download staging → hash(/signature) → ContentPackageStore
  → atomic activation → ContentRepository → Home
```

Compatible with local package format (`docs/CONTENT_PACKAGES.md`).


## Intentionally not implemented

Backend, login, required profiles, recommendation AI, TTS, camera, parental dashboard,
ads, payments, reward economy, infinite autoplay feeds, new voice research.
