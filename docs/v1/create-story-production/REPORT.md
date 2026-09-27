# Create / Story Builder V1 — production promotion

Статус: **перенесено в production, без commit.**
Вход: только `ru.familytv/.home.HomeActivity` → «Создавать» / карточка «Придумать историю».
Activity: `ru.familytv/.creative.CreativeStudioActivity`.
Debug `StitchStoryBuilderPreviewActivity` **не запускался** на acceptance.
Устройство: H728 / ADT-3, Android 14, 1920×1080, density 320, animator_duration_scale=0.25.
Ввод: ADB keyevent. **Физический пульт — NOT VERIFIED.**

## Canonical path

Production Home → Создавать → Create Home → «Придумать историю» → Роби → Волшебный лес →
Золотой ключ → Открыть дверь → визуальное следствие (библиотека) → Ending
«Тайна зачарованного дуба» → «В наши истории» (реальный `StoryStore`) →
«Посмотреть ещё раз» (та же история) → Ending → «Новая история» → Back / Home.

## Принятое решение по authored story

Не создавался второй Story engine.

| Слой | Решение |
|---|---|
| Catalog item | id остаётся `lumi-story` (Journey CREATE / `forest-secrets` node `story-optional` не ломаются) |
| Новый grammar | `app/src/main/assets/catalog/stories/create-story-v1.json` |
| Старый grammar | `catalog/stories/lumi-story.json` **оставлен на месте**, не подменён молча |
| Парсер / runtime | тот же `StoryGrammarParser` → `StoryBuilderRuntime` |
| Art | `app/src/main/assets/create_story_v1/*.jpg` + `provenance.json` (SHA-256, без runtime URL) |
| Маршрут | `StoryRoutes` (`CONTENT_ID=lumi-story`, `WORLD_ID=create`, `GRAMMAR_ASSET=catalog/stories/create-story-v1.json`) |

`manifest.json` для `lumi-story`: title «Придумать историю», `experienceRef` = новый JSON,
`homeSections` = `["create","today"]`, `route` = `creative/story`.

## Production files

Добавлено в `app/src/main`:

- `java/ru/familytv/creative/StoryPreviewDomain.kt`
- `java/ru/familytv/creative/StoryPreviewState.kt`
- `java/ru/familytv/creative/StoryPreviewViews.kt`
- `java/ru/familytv/creative/StoryRoutes.kt`
- `assets/catalog/stories/create-story-v1.json`
- `assets/create_story_v1/` (6 JPEG + provenance)

Изменено:

- `CreativeStudioActivity.kt` — host утверждённого flow; `recordsActivity` / `logTag`; persist через `StoryStore`; visit open/complete; recycle bitmap в `onDestroy`
- `StoryStore.kt` — `findBySelections()` (повторный Save не плодит дубликаты)
- `HomeActivity.kt` — `StoryRoutes.opens` / `OpenWorld("create")` → `CreativeStudioActivity`; `lastFocusId=lumi-story`
- `ContentNavigator.kt` — catalog route `creative/story` → тот же production intent
- `AndroidManifest.xml` — landscape + `Theme.FamilyTV.Games`, label «Создавать»
- `catalog/manifest.json` — `experienceRef` + секции Home

Debug harness оставлен:

- `StitchStoryBuilderPreviewActivity` наследует production `CreativeStudioActivity`, `recordsActivity=false`
- debug Domain/State/Views удалены (больше не дублируют engine)

Тесты / QA:

- `app/src/test/java/ru/familytv/creative/StoryProductionTest.kt` (23 regressions)
- `app/src/testDebug/java/ru/familytv/creative/stitch/StoryPreviewStateTest.kt` (20, пакет сохранён)
- `tools/qa/create_story_production_h728.py`

## Save / Replay / New Story

- **Save («В наши истории»)** пишет в существующий `StoryStore` (`familytv_stories`, max 8).
  Id переиспользуется: `savedId` или `findBySelections`. Затем `JourneyCoordinator.onStoryCreated`.
  На этом прогоне: id `6b4478f9-3a3`, title «Тайна зачарованного дуба»,
  selections `{HERO=robi, WORLD=forest, OBJECT=key, CHOICE=door, ENDING=secret_library}`.
  В store ровно 1 запись с этими выборами.
- **Replay** повторяет ту же историю (Роби → лес → ключ → дверь → библиотека → Ending).
  Не пишет новый Save. Возврат на Ending не ставит `visitCompleted` повторно.
- **New Story** очищает transient snapshot (`HERO` / `hero:ROBI`) и **не** удаляет сохранённую запись.

## History / Journey policy (факт)

| Событие | Когда | Что пишется |
|---|---|---|
| open | `CREATE_HOME` → `HERO` один раз за визит (`visitOpened`) | `journey.onContentOpened(lumi-story)`, trial `STORY_STARTED` |
| completion | `CONSEQUENCE` → `ENDING` один раз (`visitCompleted`) | `ActivityHistory` kind `INTERACTIVE`, title каталога «Придумать историю», topics `[story,create]`; `journey.onContentCompleted`; trial `STORY_COMPLETED` |
| save | отдельная кнопка Ending | только `StoryStore` + `onStoryCreated` → Journal «Наши истории» |

Pause / resume / Replay / process restore **не** вызывают `recordVisit` / `persistSave`.
Debug harness (`recordsActivity=false`) ничего не пишет.

Journey `forest-secrets` node `story-optional` использует `CompletionPolicy.CONTENT_COMPLETED`
и тот же catalog id `lumi-story`. XP / achievements не добавлялись.

## Tests / build / lint / release boundary

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
          :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest
```

`BUILD SUCCESSFUL` — `logs/validation.log`.

- JVM: **294** теста, **0** падений. Из них 23 `StoryProductionTest` + 20 `StoryPreviewStateTest`.
- Lint: **0 errors**, 252 warnings (существующие в проекте).
- Release: `CreativeStudioActivity` есть; `StitchStoryBuilderPreviewActivity` **нет**.
- Release assets: `create-story-v1.json` + `create_story_v1/*.jpg` + `provenance.json`.
- Production Kotlin в `app/src/main` не ссылается на `creative.stitch` / `src/debug`.
- Runtime Stitch URL не грузятся (URL только как provenance source).

`logs/release-boundary.txt`.

## H728 production acceptance

`PASS 20 checks` — `logs/device-acceptance.txt`, `logs/checks.json`.

Запуск **только** через `HomeActivity`. Top activity на всём каноническом пути:
`CreativeStudioActivity`. `StitchStoryBuilderPreviewActivity` в logcat нет.
FATAL / ANR / Fatal signal — 0.

Проверено на устройстве:

- Create Home 1920×1080, scale=1.0
- Роби / Лес / Ключ / Дверь / Consequence (не skip) / Ending
- Тим → честное `soon=hero:TIM` («Скоро»), экран не меняется
- Save → 1 запись StoryStore
- Replay с теми же выборами (`hero=ROBI world=FOREST object=KEY action=DOOR`)
- New Story очищает selections
- Back → Home, focus снова на «Придумать историю»
- Pause/resume на Object и Story Choice, pid сохранён
- Gallery: история видна в production `DiscoveryJournalActivity`

Limitation Gallery: chip «Наши истории» на Home оказался выше восстановленной карточки Create,
поэтому Journal открыт production-route `DiscoveryJournalActivity --es focus stories`
(тот же UI, что у chip). Не fixture.

## PSS / multi-story / AI

| Поверхность | PSS KiB | .gguf/.onnx | AI |
|---|---|---|---|
| Home | 114 731 | нет | — |
| Create Home | 155 033 | нет | 0 |
| Hero | 159 143 | нет | 0 |
| Forest | 162 444 | нет | 0 |
| Story Choice | 173 036 | нет | 0 |
| Ending | 179 048 | нет | 0 |
| multi-before | 171 397 | нет | 0 |
| Ending 1 | 182 685 | нет | 0 |
| Ending 2 | 185 213 | нет | 0 |
| Ending 3 | 185 261 | нет | 0 |

Три канонических истории подряд: рост **+2 576 KiB** между 1-м и 3-м Ending
(+2 528 после первого, +48 после второго). Монотонного runaway нет.
`VoiceRuntime.peek()` only; микрофон / STT / Qwen не запускались. Все state-строки `AI=0`.

## Не проверено

- Физический пульт (только ADB keyevent)
- Живой голос на устройстве (parity D-pad/voice — в JVM)
- Полная process death (device pause/resume сохранил pid; encode/decode — JVM)
- Дети 4–6 лет; качество TTS на слух
- Другие устройства, кроме H728
- Вторые сюжетные ветки (намеренно «Скоро»)

## Evidence

- Video: `docs/v1/create-story-production/recordings/create-story-production-full.mp4`
- Screenshots: `docs/v1/create-story-production/screenshots/01_home_create_focus.png` … `14_story_in_gallery.png`
- Multi-story: `logs/multi-story-memory.json`, `logs/pss-multi-*.txt`
- Logs: `logs/device-acceptance.txt`, `logs/checks.json`, `logs/performance.json`,
  `logs/state.log`, `logs/logcat.txt`, `logs/stores-delta.json`, `logs/stores-after.json`,
  `logs/validation.log`, `logs/release-boundary.txt`
