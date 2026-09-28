# FamilyTV · Home C / Visual Playground / Cosmic Hearth V2 · production

Дата: 2026-09-28. Launcher `ru.familytv/.home.HomeActivity` сразу показывает утверждённый Home C. Stitch preview evidence в `docs/design/stitch/home-c/` не перезаписывался.

## Открыть

```sh
adb shell am start -n ru.familytv/.home.HomeActivity
```

Debug harness (не production, не launcher):

```sh
adb shell am start -n ru.familytv/.home.stitch.StitchHomePreviewActivity
```

## Что перенесено из preview

В `main` перенесены утверждённые native Views Cosmic Hearth V2:

- `HomeActivity` — production host, 1920×1080, landscape, `Theme.FamilyTV.HomeC`
- `HomeSceneViews` — backdrop, пять островов, chrome controls, mission dock
- `HomeWorldContent` — реальные Journey / Continue, без fixture-счётчиков
- vector assets `stitch_c_*.xml`

Debug `StitchHomePreviewActivity` стал thin subclass `HomeActivity()`. Production не зависит от debug source set.

## Пять production destinations

| Остров | FamilyAction | Activity |
|---|---|---|
| Смотреть | `OpenWorld("watch")` | `WatchActivity` |
| Играть | `OpenWorld("play")` | `GameHubActivity` (Games V2) |
| Исследовать | `OpenWorld("explore")` | `ExploreActivity` |
| Создавать | `OpenWorld("create")` | `CreativeStudioActivity` |
| Двигаться | `OpenWorld("move")` | `MoveActivity` |

D-pad и voice резолвятся в те же `FamilyAction.OpenWorld`. Stitch preview Activities не запускаются.

## Inventory: старый Home → новый Home

Старый Home имел эти entry points → в новом Home они доступны здесь:

| Старый Home | Новый Home C |
|---|---|
| Чип «Смотреть» | Остров 1, production Watch |
| Чип «Играть» | Остров 2, production Games Home |
| Чип «Исследовать» | Остров 3, production Explore |
| Catalog card Create (`lumi-story`) | Остров 4, production Story Builder |
| Catalog card Move (`move-stretch`) | Остров 5, production Move |
| Чип «Наши открытия» | Chrome «Открытия» → `DiscoveryJournalActivity` |
| Чип «Наши истории» | Тот же Journal (voice «наши истории» / «наши открытия»). Отдельный чип убран, чтобы Home не стал dashboard |
| Чип «Карта» | Mission dock при реальной Journey; voice «карта» |
| Continue Watching row | Mission / условный «Досмотреть», если `WatchCatalog.continueWatching` непустой |
| Journey «Сейчас в пути» | Mission dock из `JourneyEngine` / `journeyProgress` |
| Parent (кнопка + long-press на бренд) | Chrome «Родителям» → PIN `ParentModeActivity` |
| Screen-time reminder | Текстовая строка над островами |
| Opt-in микрофон / VoiceRuntime на Home | Убран. Chrome «Луми, давай играть!» только подсказка. AI=0 |
| VoiceLab long-press | Не с child Home. Остаётся внутри Parent Mode |

Не создавались child profiles. Catalog/direct routes (`OpenContent`, `StartAdventure`, `OpenGame`) сохранены в `handleHomeAction`.

## Focus / Back

`lastFocusId` в `familytv_home` + `onSaveInstanceState(lastWorld, focus)`. После Watch / Games / Explore / Create / Move Back на H728 вернул фокус на открытый остров.

## Current mission / Journal / Parent

На проверенном устройстве:

- `emptyMission=false`, `journey=space-grand-expedition`, узел «Старт», OK → `JourneyMapActivity`
- Continue video = null, «Досмотреть» скрыт
- Journal → `DiscoveryJournalActivity`
- Parent → PIN gate «Введите PIN» (на устройстве PIN ещё не задан, поэтому UI сохранения PIN; это существующий gate, не child world)

Empty/neutral mission покрыт unit-тестами (`Сегодня вместе` / без «15 минут» / без «12»). Визуально empty state на этом устройстве не снимался, потому что реальная миссия есть.

## Tests / build / release

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest
```

Все команды BUILD SUCCESSFUL. 361 unit test. Lint debug без ошибок. Release compile не тянет debug Home C.

21 production regressions в `HomeProductionTest`.

## H728

Устройство: ADT-3, SoC h728, Android 14, 1920×1080 density 320.

Cold launcher: `HomeActivity` TotalTime 1614 ms, scene 1920×1080 scale=1.0, AI=0.

Непрерывная запись: `recordings/home-c-production-full.mp4`.

Все пять миров открыли production Activities и вернули фокус. Preview Activities не появлялись. Crash/ANR нет.

## PSS / AI

| Surface | TOTAL PSS | .gguf/.onnx |
|---|---:|---|
| cold Home | 115984 KiB (113.3 MiB) | none |
| after focus cycles | 119729 KiB | none |
| after Watch return | 119739 KiB | none |
| after Games return | 129553 KiB | none |
| after Explore return | 152544 KiB | none |
| after Create return | 149590 KiB | none |
| after Move return | 132970 KiB | none |

PID сохранился (20457). После Move PSS ниже Explore/Create — нет runaway bitmap growth на самом Home. VectorDrawable иконки кэшируются в `ReferenceDrawing`. AI runtime не поднимался: `VoiceRuntime.peek()=null`, в maps нет `.gguf`/`.onnx`.

## Не проверено

- Визуальный empty mission на устройстве без Journey (unit-тесты есть)
- Нажатие voice-hint toast на этой записи
- PIN digit-entry (gate показан, PIN не вводился и не менялся)
- Оценка ребёнком с 2–3 м
- Отдельный чип «Наши истории» (намеренно схлопнут в Journal)

## Пути

- Report: `docs/v1/home-c-production/REPORT.md`
- Screenshots: `docs/v1/home-c-production/screenshots/`
- Video: `docs/v1/home-c-production/recordings/home-c-production-full.mp4`
- Logs: `docs/v1/home-c-production/logs/`
