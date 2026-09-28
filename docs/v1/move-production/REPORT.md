# Move V1 / «Разбудим звёзды» — production promotion

Статус: **перенесено в production, без commit.**
Вход: только `ru.familytv/.home.HomeActivity` → карточка «Разбудим звёзды».
Activity: `ru.familytv/.move.MoveActivity`.
Debug `StitchMovePreviewActivity` **не запускался** на acceptance.
Устройство: H728 / ADT-3, Android 14, 1920×1080, density 320, animator_duration_scale=0.25.
Ввод: ADB keyevent. **Физический пульт — NOT VERIFIED.**

## Canonical path

Production Home → «Разбудим звёзды» → Move Home → Ready / Safe Space → Start →
Reach (auto) → Comets (auto) → Freeze MOVE→FREEZE→COUNTDOWN (auto) →
Balance (auto) → Calm Ending (story → breath in → breath out → actions) →
Replay (Ready) → Ending → «В мир Двигаться» → Move Home → Back → Home
(focus на карточке «Разбудим звёзды» в секции «Двигайся»).

После Start нет обязательного OK между movement beats.

## Принятые production-решения

Не создавался второй Move engine, camera stack или screen-time система.

| Слой | Решение |
|---|---|
| Catalog item | id остаётся `move-stretch` (Journey REST node `stretch` в `space-grand-expedition` не ломается) |
| Type | `VIDEO` → `INTERACTIVE` (больше не Watch-плейсхолдер) |
| Grammar | `app/src/main/assets/catalog/move/wake-stars.json` (routing metadata, не Interactive Engine) |
| Runtime | тот же `MovePreviewState` / `MovePreviewViews`, перенесённые в `app/src/main` |
| Art | `app/src/main/assets/move_v1/*.jpg` + `provenance.json` (SHA-256, без runtime URL) |
| Маршрут | `MoveRoutes` (`CONTENT_ID=move-stretch`, `WORLD_ID=move`, `route=move/stars`) |

`manifest.json` для `move-stretch`: title «Разбудим звёзды», `experienceRef` = новый JSON,
`homeSections` = `["move","today"]`, `route` = `move/stars`.
Вторая кнопка «Двигаться» на Home C **не** добавлялась.

Lumi artwork не унифицировался: различия между Stitch-иллюстрациями (особенно Comets) допустимы для V1.

## Production files

Добавлено в `app/src/main`:

- `java/ru/familytv/move/MovePreviewState.kt`
- `java/ru/familytv/move/MovePreviewViews.kt`
- `java/ru/familytv/move/MoveActivity.kt`
- `java/ru/familytv/move/MoveRoutes.kt`
- `assets/catalog/move/wake-stars.json`
- `assets/move_v1/` (7 JPEG + provenance)

Изменено:

- `HomeActivity.kt` — `MoveRoutes.opens` / `OpenWorld("move")` → `MoveActivity`; `lastFocusId=move-stretch`
- `ContentNavigator.kt` — catalog INTERACTIVE `move/stars` → тот же production intent
- `AndroidManifest.xml` — landscape + `Theme.FamilyTV.Games`, label «Двигаться», `exported=false`
- `catalog/manifest.json` — INTERACTIVE + experienceRef + title
- `VoiceCommandRouter.kt` — «двигаться» → `OpenWorld("move")` (рядом с «двигайся»)
- `TrialEventStore.kt` — `MOVE_STARTED` / `MOVE_COMPLETED`
- `WatchCatalogTest.kt` — `move-stretch` больше не VIDEO

Debug harness оставлен:

- `StitchMovePreviewActivity` наследует production `MoveActivity`, `recordsActivity=false`
- debug State/Views удалены (больше не дублируют engine)
- debug assets `app/src/debug/assets/move_v1/` оставлены как копии; production не зависит от них

Тесты / QA:

- `app/src/test/java/ru/familytv/move/MoveProductionTest.kt` (24 regressions)
- `app/src/testDebug/java/ru/familytv/move/stitch/MovePreviewStateTest.kt` (пакет сохранён, импорт production)
- `tools/qa/move_production_h728.py`

## State / runtime

`MovePreviewState` — единственный semantic clock. View-анимации читают snapshot
(`starsGlow`, `pathOpen`, `worldStill`, `bridge`, `pose`), не продвигают beats.

После Ready → Reach ticks сами ведут:

REACH INTRO→ACTIVE→CONSEQUENCE → COMETS INTRO→LEFT→RIGHT→CONSEQUENCE →
FREEZE MOVE→FREEZE→COUNTDOWN → BALANCE INTRO→ACTIVE→CONSEQUENCE →
ENDING STORY_RESULT→BREATH_IN→BREATH_OUT→ACTIONS.

Pause ставит `paused=true`; `tick()` не увеличивает `elapsedMs`. Continue возвращает тот же beat.
Repeat — no-op на snapshot. Help ставит `helpMs` и показывает позу, не двигая elapsed.
Replay с ACTIONS возвращает Ready (принятая preview-семантика), затем Start снова открывает Reach.

Нет camera / pose / movement detection / score / XP / energy / verdict.

## History / Journey / screen-time policy (факт)

Существующий Journey node `stretch` (`contentId=move-stretch`) остаётся
`CompletionPolicy.CONTENT_OPENED` / `REST`. Это каталожная policy экспедиции, не mastery.
Её не меняли.

| Событие | Когда | Что пишется |
|---|---|---|
| open | `READY` → `REACH` один раз за визит (`visitOpened`) | `journey.onContentOpened(move-stretch)` — для CONTENT_OPENED это же завершает REST-ноду; trial `MOVE_STARTED` (только если Trial Mode) |
| completion | `BALANCE` → `ENDING` один раз (`visitCompleted`) | `ActivityHistory` kind `MOVE`, title каталога «Разбудим звёзды», topics `[move]`; `journey.onContentCompleted` (no-op, если нода уже закрыта open-policy); trial `MOVE_COMPLETED` |
| Replay | Ready → Reach внутри того же визита | флаги `visitOpened`/`visitCompleted` остаются; **нет** второй history-записи |
| Pause/resume | overlay / lifecycle | не пишет history |
| «В мир Двигаться» | возврат на MOVE_HOME | `visit_reset`; следующий Start — новый визит |

На этом прогоне:

- visit_completed #1 durationMs=48535 (первый канонический проход + pause)
- Replay того же визита **не** дал вторую запись
- visit_reset на MOVE_HOME
- visit_completed #2 durationMs=37101 (новый визит multi-replay, первое Ending)
- Ending #2 и #3 multi-replay **не** писали completion

Screen time: отдельной Move-системы нет. `ActivityHistoryStore.totalWatchMs` считает только
`WATCH` и `SESSION`. Kind `MOVE` в дневные минуты просмотра не входит.

## Hands-free / Pause / Replay

- После Start D-pad Select не требуется до Calm Ending actions.
- Pause на Comets LEFT: elapsed заморожен (183 мс), phase=LEFT после 3 с ожидания.
- Continue вернул тот же LEFT beat.
- Repeat во время pause не сдвинул screen/phase/elapsed.
- Help на Freeze MOVE: `help=1`, screen остался FREEZE/MOVE.
- Replay → Ready → Start → полный auto-проход до Ending.
- Auto-restart нет: actions ждут явного выбора.

## Tests / build / lint / release boundary

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest
```

Все команды — BUILD SUCCESSFUL.

Release:

- `MoveActivity` есть в release manifest (`exported=false`) и в `compileReleaseKotlin`
- `move_v1/*.jpg` есть в `mergeReleaseAssets`
- `StitchMovePreviewActivity` есть только в debug class output / debug manifest
- production Kotlin не импортирует `move.stitch` и не содержит runtime URL
- камера/ML Kit этой задачей не добавлялись

## H728 production acceptance

Вход: `am start -n ru.familytv/.home.HomeActivity`.
ResumedActivity на всём flow: `ru.familytv/.move.MoveActivity` (не Stitch).
Возврат: `HomeActivity`, focus «Разбудим звёзды».
Render: `1920x1080 scale=1.0`.
AI=0 на всех state-строках. Crash/ANR нет.
`StitchMovePreviewActivity` в logcat нет.

## PSS (KiB)

| Surface | TOTAL PSS | .gguf/.onnx | AI |
|---|---:|---|---|
| Home | 117 039 | нет | — |
| Move Home | 158 947 | нет | 0 |
| Ready | 161 991 | нет | 0 |
| Reach | 166 868 | нет | 0 |
| Freeze | 178 680 | нет | 0 |
| Ending #1 | 189 030 | нет | 0 |
| Ending #2 (replay) | 191 754 | нет | 0 |
| multi-before | 184 039 | нет | 0 |
| Ending 1 | 207 744 | нет | 0 |
| Ending 2 | 208 256 | нет | 0 |
| Ending 3 | 208 320 | нет | 0 |

Три Ending подряд в одном визите: рост **+576 KiB** между 1-м и 3-м
(+512 после первого, +64 после второго). Монотонного runaway нет.
`System.gc()` не вызывался. `VoiceRuntime.peek()` only.

PID 13512 сохранён на всём прогоне.

## Не проверено

- Физический пульт (только ADB keyevent)
- Живой голос / микрофон на устройстве (family-action parity — JVM; AI runtime не поднимался)
- Полная process death (encode/decode snapshot — JVM; device pause/resume pid сохранился)
- Дети 4–6 лет; качество TTS на слух
- Другие устройства, кроме H728
- Будущие поляны (намеренно «Скоро», без fake routing)
- `05_comets.png` снят в момент Pause overlay на LEFT; чистый Comets — `12_resume.png`

## Evidence

- Video: `docs/v1/move-production/recordings/move-production-full.mp4`
- Screenshots: `docs/v1/move-production/screenshots/01_home_move_focus.png` … `14_home_return.png`
- Multi-replay: `logs/multi-replay-memory.json`, `logs/pss-multi-*.txt`, `logs/maps-multi-*.txt`
- Logs: `logs/device.txt`, `logs/launch.txt`, `logs/checks.json`, `logs/performance.json`,
  `logs/state.log`, `logs/logcat.txt`, `logs/stores-delta.json`
