# FamilyTV Games V2 — production promotion

Дата: 2026-09-26. Commit не создавался.

## Результат

Настоящий путь: `ru.familytv/.home.HomeActivity` → **Играть** → `GameHubActivity` → Math / Color → Games Home → Home. Единственная кнопка «Играть» использует FamilyAction и запоминает фокус. Старые catalog routes `game/math`, `game/color` и Activity names сохранены; прямой запуск из каталога/сессии возвращается вызывающему экрану.

Принятый debug preview + UX polish перенесён в `main`. Общая presentation implementation находится в `games/world`; debug Activity стала тонким design harness без записи истории/Journey. Production не открывает и не импортирует debug Activity. Weather/Maze остаются «Скоро».

Math сохраняет world → combine → answers; Help считает реальные яблоки 1…N, сохраняет `?`, не выбирает и не отправляет ответ. Ошибка сохраняет коллекцию. Три раунда заканчиваются по явному действию. Color действительно красит крыло, сохраняет неправильный цвет, разрешает Launch только после синего крыла и отдельного OK. Сохранены idle/help/wrong/success/launch реакции Lumi.

## Production files

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/ru/familytv/games/GameHubActivity.kt`
- `app/src/main/java/ru/familytv/games/BaseVoiceGameActivity.kt`
- `app/src/main/java/ru/familytv/home/HomeActivity.kt`
- `app/src/main/java/ru/familytv/home/ContentNavigator.kt`
- `app/src/main/java/ru/familytv/session/LearningSessionActivity.kt`
- `app/src/main/res/values/games.xml`
- `app/src/main/assets/games_v2/workshop_background.webp`
- `app/src/main/assets/games_v2/math_card.webp`
- `app/src/main/assets/games_v2/orchard_background.webp`
- `app/src/main/assets/games_v2/provenance.json`
- `app/src/main/java/ru/familytv/games/GamesDestination.kt`
- `app/src/main/java/ru/familytv/games/world/GameVisit.kt`
- `app/src/main/java/ru/familytv/games/world/GamesActions.kt`
- `app/src/main/java/ru/familytv/games/world/GamesWorldView.kt`
- `app/src/main/java/ru/familytv/games/world/GamesDrawing.kt`
- `app/src/main/java/ru/familytv/games/world/GamesState.kt`
- `app/src/main/java/ru/familytv/games/world/GamesActivity.kt`
- `app/src/main/java/ru/familytv/games/world/LumiReaction.kt`

Точный аудит этой задачи: [source-audit.json](logs/source-audit.json). Репозиторий уже содержал другие незакоммиченные изменения; они не сбрасывались.

Из `BaseVoiceGameActivity` перенесены только два concrete entry класса. Старые voice controller/domain code сохранены. `GameSession.kt`, содержащий AnswerJudge/AppleQuestion/ColorQuestion/GameColor/Verdict, не изменён.

## Архитектура, история, голос

- Используются существующие AnswerJudge, FamilyActionDispatcher, VoiceCommandRouter semantics, NarrationCoordinator/PlatformServices и ReferenceStage. Новый параллельный game engine или progress store не создан.
- `GameVisit` — только состояние текущего визита для однократной записи в существующий `ActivityHistoryStore`: kind GAME, IDs `game-math`/`game-color`, прежние topics/title/duration. Оно сохраняется в Bundle; pause/resume не создаёт дополнительное событие.
- Сохранена **прежняя policy explicit exit** из BaseVoiceGameActivity: при завершении/явном выходе вызывается `JourneyCoordinator.onContentCompleted(contentId)`, в том числе при Back. Это отметка посещения по существующей policy, не новая оценка знаний. Добавлен обычный `onContentOpened` при входе. Полное прохождение отдельного Journey не заявляется.
- После игры из Learning Session вызывающий экран снова получает управление; Session повторно подключает свой FamilyAction handler в onResume. Существующие session progress/advance/store сохранены.
- Math/Color semantic Answer, Help, Repeat, Next, Back/Home обрабатываются общей production policy. Next не может отправить выбранный ответ или пропустить combine. D-pad Select и normalized voice Answer используют одну и ту же проверку ответа. Автоматического STT/Qwen/microphone startup нет. В UI нет фиктивного состояния слушания.
- Главный записанный проход добавил ровно **две** реальные GAME-записи (Math и Color): [history-main-flow.json](logs/history-main-flow.json).

## Дизайн и assets

Перенесены только три нужных изображения: orchard_background, workshop_background, math_card. Байты совпадают с утверждённым preview; источники, метод подготовки и SHA-256 сохранены в [production provenance](../../../app/src/main/assets/games_v2/provenance.json). Новая графика не генерировалась. Остальные reference assets остались debug-only; старые design evidence не удалены.

Пять статичных production состояний **пиксель в пиксель совпадают** с принятым UX polish: Games Home, Math world-only, Math combined, Help finished, Color idle. [Visual parity](logs/visual-parity.json).

Большой Lumi остаётся baked artwork с native halo/pulse/rays; текущая иконка в шапке даёт tilt/bounce. Это прежнее принятое поведение.

## Tests / build / release boundary

Выполнено с Java 25:

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest
```

**180 tests, 0 failures/errors/skips.** Перенесены семь preview regressions в main tests и добавлены семь production regressions: routes, staged answers, Help, wrong state, finite progression, paint/explicit launch, Back/focus, normalized voice actions, history schema/deduplication и архитектурная граница no-AI/no-debug. [Tests](logs/unit-tests.json), [final build](logs/build-final.log).

Lint: **0 errors, 251 warnings**; warning-free результат не заявляется. Включает существующие предупреждения и programmatic View / orientation / KTX рекомендации. [Lint](logs/lint.txt).

Release/main compile прошёл. В merged release manifest нет StitchGamesPreviewActivity; в merged release assets нет `stitch_games`, присутствуют production `games_v2`. [Release boundary](logs/release-boundary.json). Release APK не устанавливался: H728 acceptance выполнена на debug APK с production entry и main implementation.

Установленный APK: `app/build/outputs/apk/debug/app-debug.apk`; SHA-256 `8e46d75a473e43b01e62d0eaf1f7541c94a8820ded70e965249db749ea7582ca`. [Install](logs/install.txt), [APK metadata](logs/apk.json).

## H728 acceptance

H728: ADT-3, sun55iw3p1, Android 14, arm64, 1920×1080. Реальные ADB D-pad keyevents; только production Home как начальный entry. Никаких injected/seeded game states, ответов или progress. App data не очищалась.

**40 проверок passed**: основной continuous flow; accessibility focus до/после combine; два Help 1…5; ребёнок сам выбирает 5; все три Math rounds; реальная красная/синяя краска и отдельный launch; сохранение focus; Back; Repeat; Weather/Maze unavailable; pause/resume во время Math Help и с красным крылом Color; отсутствие лишней истории при pause. [Checks](logs/checks.json), [acceptance](logs/acceptance.txt), [logcat](logs/logcat.txt).

Дополнительно **3 проверки** прямого production catalog entry: `MathVoiceGameActivity`, возврат по Back и после трёх раундов сохраняют вызывающий Home и фокус Math, затем D-pad возвращает фокус на Играть. [Direct catalog checks](logs/direct-catalog-checks.json), [log](logs/direct-catalog-logcat.txt), [Back](screenshots/22_catalog_back_focus.png), [finite return](screenshots/23_catalog_finite_returns_home.png). Всего **43 device checks**.

Непрерывная запись: [games-production-full.mp4](recordings/games-production-full.mp4), **91.27 s**, 1920×1080 H.264, без монтажа, без аудиодорожки. Home → Играть → Games Home → Math/combine/wrong/Help/repeat Help/correct/rounds 2–3 → Games Home → Color/red/blue/explicit Launch → Games Home → production Home. Full decode с исходным time base, VFR passthrough и `-xerror` прошёл без ошибок; исходный MP4 не изменён.

Видео SHA-256: `643c4606c5f64d37ce8bd643f115c60b8ce64bdb3b5095170d83f6cead7eed48`.

Для читаемых реакций system animator duration временно установлен в 1×, затем восстановлен исходный **0.25×**. [Setting audit](logs/animation-setting.json). Нативная скорость counting beats не менялась. Кадры счёта 1…5 извлечены целиком из того же непрерывного видео; без композитинга. [Frame times](logs/count-frame-times.json). Остальные screenshots — device screencap.

## Ключевые screenshots

| Состояние | Evidence |
|---|---|
| Production Home, focus Играть | [01](screenshots/01_home_games_focus.png) |
| Games Home | [02](screenshots/02_games_home.png) |
| Только объекты, answers отсутствуют | [03](screenshots/03_math_world.png) |
| После combine, focus 4, equation ? | [04](screenshots/04_math_combined.png) |
| Help закончился, focus Help, equation ? | [05](screenshots/05_math_help.png) |
| Counting 1 → 5 | [1](screenshots/05_help_count_1.png), [2](screenshots/05_help_count_2.png), [3](screenshots/05_help_count_3.png), [4](screenshots/05_help_count_4.png), [5](screenshots/05_help_count_5.png) |
| Math wrong | [06](screenshots/06_math_wrong.png) |
| Math correct, явное продолжение | [07](screenshots/07_math_correct.png) |
| Color | [08](screenshots/08_color.png) |
| Красное крыло остаётся | [09](screenshots/09_color_wrong.png) |
| Синее крыло, Launch ещё не выполнен | [10](screenshots/10_color_correct.png) |
| Физический launch + Lumi | [11](screenshots/11_rocket_launch.png) |
| Возврат Home, focus Играть | [12](screenshots/12_home_return.png) |
| Math pause/resume без reveal | [15](screenshots/15_help_pause_resume.png) |
| Color pause/resume сохраняет red | [18](screenshots/18_color_pause_keeps_red.png) |

## PSS / AI

Замеры одного холодного процесса, без принудительного GC. Это снимки состояния, не долговременный memory benchmark.

| Экран | PSS KiB | PSS MiB | AI | .gguf/.onnx mappings |
|---|---:|---:|---:|---|
| home | 111,447 | 108.8 | 0 | нет |
| games-home | 121,467 | 118.6 | 0 | нет |
| math | 125,908 | 123.0 | 0 | нет |
| color | 142,937 | 139.6 | 0 | нет |

[Performance JSON](logs/performance.json); сырые `pss-*.txt` и `maps-*.txt` в [logs](logs/). VoiceRuntime.peek остаётся null (AI=0); .gguf/.onnx не загружены. В acceptance log нет app crash/ANR.

## Что не проверено

- Physical remote — **NOT VERIFIED**; использованы ADB keyevents.
- Live microphone/STT/Qwen и качество звучания TTS не проверялись; semantic actions проверены unit tests, видео без звука.
- Полная отдельная Learning Session/Journey, process death/reboot и другие устройства/разрешения не проходились.
- Это локальный production promotion исходников; release distribution/store publication не выполнялись.

Повтор основного QA: `python3 tools/qa/games_production.py --normal-animation`. Предварительный прогон сохранён отдельно в `logs/preflight.txt`; его unsupported ADB flag исправлен в harness, окончательная acceptance прошла полностью.
