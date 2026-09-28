# Move V1 / «Разбудим звёзды» — native Android TV debug preview

Статус: **debug-only preview, НЕ production promotion, без commit.**
Activity: `ru.familytv/.move.stitch.StitchMovePreviewActivity` (только `app/src/debug`).
Устройство: H728 / ADT-3, Android 14, 1920×1080, density 320, animator_duration_scale=0.25.
Ввод: ADB keyevent. **Физический пульт — NOT VERIFIED.**

## Canonical path

Move Home → Ready → Start → (пульт можно отложить) → Reach → Comets → Freeze → Balance →
Calm Ending → Replay → Ready / выход.

После Ready основной путь идёт **без OK** между movement beats.

## Что переиспользовано / почему production нетронут

- `FamilyActionDispatcher`, `FamilyAction` (Start/Pause/Resume/Repeat/Help/Back/Home)
- `NarrationCoordinator` + `PresentationMessage` (общий TTS, без второго pipeline)
- `VoiceCommandRouter` mapping (микрофон не запускается)
- `ReferenceStage` 1920×1080
- Debug Activity pattern как у Story/Explore preview

Отдельная Move state machine нужна: production Move runtime нет.
Preview живёт в `app/src/debug/java/ru/familytv/move/stitch/` + `app/src/debug/assets/move_v1/`.
`app/src/main`, production Home и catalog routing **не менялись**.

## State machine

Явный `MovePreviewState` / `MoveSnapshot` (не View-таймеры):

| Screen | Phases |
|---|---|
| MOVE_HOME | NONE |
| READY | NONE |
| REACH | INTRO → ACTIVE → CONSEQUENCE → (auto) |
| COMETS | INTRO → LEFT → RIGHT → CONSEQUENCE → (auto) |
| FREEZE | MOVE → FREEZE → COUNTDOWN → (auto) |
| BALANCE | INTRO → ACTIVE → CONSEQUENCE → (auto) |
| ENDING | STORY_RESULT → BREATH_IN → BREATH_OUT → ACTIONS |

`tick(delta)` двигает только если не `paused` и не `helping`.
Activity `onPause` останавливает ticker (часы не прыгают), semantic pause не сбрасывается.
`encode`/`decode` для recreation.

## Canonical Lumi — честно

Stitch-арт одного семейства (светло-голубой округлый робот), но **лица/антенны плывут**:

- Home — две антенны, простой рот
- Ready — более «3D» циан-компаньон
- Reach / Freeze / Ending — ближе друг к другу
- Comets — самый большой дрейф: LED-глаза `0-0`

Нового персонажа не генерировали. На обычном пути дети смотрят **иллюстрированного Lumi в approved art**
(поза уже в кадре) + native cues (стрелки, светлячки, кометы, мост).
Native FamilyTV-orb Lumi рисуется **только на Help**, чтобы ещё раз показать движение
одним силуэтом (teal orb, одна антенна, тёмное лицо).

## Assets / provenance

7 JPEG из `code.html`, fetch `=s0`, без перекодирования, без runtime HTTP.

`app/src/debug/assets/move_v1/{move_home,ready,reach,comets,freeze,balance,ending}.jpg`
+ `provenance.json` (URL, SHA-256, size 1376×768).
Копии: `android-preview/reference/`.
`screen.png` не использовались как арт (чёрный/битый export). Freeze — полная космическая поляна, не чёрный кадр.

## Hands-free / Pause / Repeat / Help

После Ready `Select` на movement **не** переключает этап.
Pause (кнопка / MEDIA_PAUSE): overlay «Продолжить / Закончить», elapsed заморожен.
Repeat: пересказывает beat, state не двигает.
Help: 2800 мс показа позы, elapsed не растёт.

## Tests / build / lint / release

```
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`BUILD SUCCESSFUL`. JVM **316** тестов, **0** падений; из них **22** `MovePreviewStateTest`.
Lint: 0 errors.
Release manifest: `StitchMovePreviewActivity` **отсутствует**.
`logs/validation.log`, `logs/release-boundary.txt`.

## H728 acceptance

`PASS 19 checks`. Запуск только preview Activity. Home не открывался.
FATAL / ANR — 0. Stores preview не писал (`stores-delta` unchanged).

Visual answers:

1. **Поза без чтения?** Да: руки вверх / шаг в сторону / замереть / руки в стороны / спокойные руки.
2. **Один Lumi?** Частично — одно семейство, Comets-лицо отличается (см. выше).
3. **Пульт после Start?** Да, auto-progression до Ending.
4. **Мир меняется?** Да: звёзды загораются, путь открывается, светлячки замирают, мост достраивается, финал светится.
5. **Freeze ≠ fake sensing?** Да: нет «распознано/засчитано», только story countdown.
6. **Pause держит beat?** Да: elapsed 2904 заморожен на 3+ с, затем тот же Comets beat.
7. **Ending спокойный?** Да: вдох/выдох, звёзды, без score/XP; кнопки после calm beat.

`05_comets_left.png` снят уже на pause overlay (чтобы успеть заморозить LEFT). Мир комет без overlay — `06` и `13`.

## PSS / AI

| Поверхность | PSS KiB | .gguf/.onnx | AI |
|---|---|---|---|
| Move Home | 145 925 | нет | 0 |
| Ready | 150 285 | нет | 0 |
| Reach | 157 321 | нет | 0 |
| Freeze | 171 385 | нет | 0 |
| Ending | 181 392 | нет | 0 |
| After Replay | 183 016 | нет | 0 |

После Replay +1.6 MB к Ending — не runaway.
Все state-строки `AI=0`. `VoiceRuntime.peek()` only.

## Не проверено

Физический пульт; живой голос; дети 4–8; TTS на слух; полная process death;
другие устройства кроме H728; production Home → Двигаться (намеренно не трогали).

## Evidence

- Video: `docs/design/stitch/move-v1/android-preview/recordings/move-v1-flow.mp4`
- Screenshots: `.../screenshots/01_move_home.png` … `14_replay.png`
- Logs: `.../logs/{device-acceptance,checks,performance,state,logcat,stores-delta,validation,release-boundary}.txt`
