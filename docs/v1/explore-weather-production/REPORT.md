# Explore / Weather V1 — production promotion

Результат: утверждённый Explore / Weather V1 теперь работает в production-сборке. Настоящий маршрут: Home → «Исследовать» → Explore Home → Погода → Intro → Prediction → Experiment → Observation → «Почему?» → Ask Lumi → Build & Test → Result → «В мир открытий» → Back → Home. Весь flow пройден на H728 одной непрерывной записью: 42 проверки PASS, AI=0, без crash и ANR. Механика, тексты, геометрия и графика перенесены без изменений. Commit не делался.

## 1. Что перенесено

- State machine (`WeatherScreen`, `WeatherRules`, `WeatherSnapshot`), world-views и Activity перенесены из `app/src/debug` в `app/src/main` через `git mv`, поэтому история файлов сохранена. Правки только в пакете и именах (`WeatherPreviewState` → `WeatherExploreState`). Правила, тайминги, focus и gating не менялись.
- Explore Home: пять локаций, активна только «Погода». Космос, Лес, «Как устроено» и «Опыты» показывают «Скоро» и ничего не запускают: ни старых Activity, ни visit, ни истории. Это проверено на приставке, кадр `extra_future_forest.png`.
- Discovery loop без вердикта: SUN/SHADE — это гипотеза (`PredictAndTestEngine` даёт `INCOMPLETE` → `OBSERVED`). Два кубика с детерминированными часами, «Ускорить время» работает как time-lapse тех же часов. На Observation следующее действие — «Почему?». Ask Lumi идёт в порядке SUN → HEAT → ICE → WATER → DONE, кнопка «Спрятать лёд от солнца» появляется только на DONE, Repeat снова её блокирует. В Build & Test ровно два укрытия; «Проверить» доступна только после физического размещения, результат показывается только после Check.
- Assets: четыре `webp` и `provenance.json` лежат в `app/src/main/assets/explore_weather/`. Байты и SHA-256 не изменились; фон Intro — финальный one-Lumi (`53e04920…`). В `provenance.json` пути обновлены и добавлен блок `productionPromotion`. Новая графика не создавалась.
- Debug preview оставлен как тонкий harness: `StitchExploreWeatherPreviewActivity : ExploreActivity() { recordsActivity = false }`. Он ничего не пишет в History, Journey или trial.

## 2. Production-файлы

| Файл | Изменение |
|---|---|
| `app/src/main/java/ru/familytv/explore/ExploreActivity.kt` | `open class`, visit-учёт, History/Journey, возврат в Home, прямой вход из каталога |
| `app/src/main/java/ru/familytv/explore/ExploreVisit.kt` | новый: `ExploreRoutes` (маршруты и id каталога), чистый per-visit учёт |
| `app/src/main/java/ru/familytv/explore/WeatherExploreState.kt` | перенесён из debug |
| `app/src/main/java/ru/familytv/explore/WeatherExploreViews.kt` | перенесён из debug |
| `app/src/main/assets/explore_weather/*` | перенесены из debug |
| `app/src/main/AndroidManifest.xml` | `.explore.ExploreActivity`, `exported=false`, тема `Theme.FamilyTV.Games` |
| `app/src/main/java/ru/familytv/home/HomeActivity.kt` | один чип «Исследовать» → `FamilyAction.OpenWorld("explore")`, `lastFocusId=explore` |
| `app/src/main/java/ru/familytv/home/ContentNavigator.kt` | каталожный `adv-weather-lab` (`weather-lab.json`) открывает Explore Weather вместо старого `AdventureActivity` |

Debug- и тестовые файлы:
- `app/src/debug/.../StitchExploreWeatherPreviewActivity.kt` — harness;
- `app/src/test/java/ru/familytv/explore/WeatherExploreStateTest.kt` — перенесён из `testDebug`;
- `app/src/test/java/ru/familytv/explore/ExploreProductionTest.kt` — новые regression-тесты;
- `tools/qa/explore_weather_production.py`, `tools/qa/explore_weather_release_boundary.py` — QA-скрипты.

## 3. Переиспользованные Discovery-абстракции

Новый engine, TTS или progress store не создавались. Используются:
- `PredictAndTestEngine` / `PredictAndTestParams` для гипотезы и наблюдения (`correctPredictionId = null`);
- `CauseEffectEngine` / `CauseEffectModel` для результата укрытия;
- `FamilyActionDispatcher` / `FamilyActionHandler`: attach в `onResume`, detach в `onPause`; D-pad Select идёт через dispatcher;
- существующий `VoiceCommandRouter` («исследовать» / «исследуй» → `OpenWorld("explore")`);
- общий `PlatformServices.narrationCoordinator` (без второго coordinator и без второго TTS);
- `JourneyCoordinator`, `ActivityHistoryStore`, `AdventureProgressStore`, `AdventureEventStore`, `TrialEventStore`.

## 4. History / Journey

Weather в каталоге — это существующий `adv-weather-lab` (Journey-узел `temp-lab`, политика `CONTENT_COMPLETED`). Старая policy `AdventureActivity` для weather-lab сохранена один в один; она просто перенесена на новые моменты flow:

| Момент | Запись (один раз за visit) |
|---|---|
| Вход в Weather Intro | `journey.onContentOpened`, `adventure_started`, trial `EXPERIMENT_STARTED` |
| Observation (первый раз для каждой гипотезы) | `journey.onExperimentObserved("where-melts-faster", variant sun/shade)`, trial `EXPERIMENT_VARIANT_TESTED` |
| **Completion = BUILD_RESULT после явного Check** | `journey.onContentCompleted`, `ActivityHistory INTERACTIVE` («Лаборатория погоды», topics `adventure, explore`, duration), `adventureProgress(weather-lab).completed=true`, `adventure_completed`, trial `ADVENTURE_COMPLETE` |
| Выход до completion | без истории и без completion — как в старой adventure |

- Состояние visit (`startedAt`, наблюдённые гипотезы, `completed`) хранится в Bundle. Pause/resume и пересоздание Activity ничего не записывают повторно.
- Повторный вход из Explore Home считается новым visit.
- «Начать» в `ContentDetails` по-прежнему пишет свою start-запись: это существующее поведение каталога, я его не трогал.

На H728 (`logs/history-journey.json`, `logs/stores-delta.json`):
- два завершённых visit дали две записи `INTERACTIVE adv-weather-lab`;
- третья запись — «Начать» из `ContentDetails` при проверке каталожного маршрута;
- pause/resume и Back не добавили ни одной записи;
- `temp-lab` перешёл в `completed`;
- в журнале появились `exp-where-melts-faster`, `ice-melts-with-heat` и ADVENTURE_COMPLETED «Тепло»; наблюдения записаны с вариантами `shade` и `sun`;
- trial store не изменился: на устройстве выключен TRIAL_MODE.

Mastery и score нет.

## 5. Тесты, сборка, lint, release boundary

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — PASS; тесты и lint перезапущены с `--rerun`.
- Unit-тесты: 244 теста, 0 failures. Из них 22 в `ExploreProductionTest` (regressions 1–21 плюс возврат при входе из каталога) и 42 в `WeatherExploreStateTest`.
- Lint: 0 errors, 255 warnings против 252 в baseline ux-polish. Три новых предупреждения повторяют стиль существующего кода: `DiscouragedApi` на `screenOrientation="landscape"` (он стоит у всех Activity) и два `UseKtx` на `prefs.edit()` в `HomeActivity`.
- Kotlin: только 4 известных warning в старом коде.
- `./gradlew :app:compileReleaseKotlin :app:mergeReleaseAssets :app:processReleaseMainManifest` — PASS.
- Release boundary (`logs/release-boundary.json`, PASS):
  - в release manifest есть `ExploreActivity` (`exported=false`), записей Stitch нет;
  - release assets содержат `explore_weather` с хешами из provenance, debug-only каталогов нет;
  - 22 explore-класса, классов `stitch` нет;
  - в bytecode explore нет debug-, mic- и model-строк.

## 6. H728

ADT-3, Android 14, 1920×1080. Установлен debug APK (`674e920b…`). Вход только через `ru.familytv/.home.HomeActivity`; `am start` в Explore не используется, Activity не exported. Проверено `tools/qa/explore_weather_production.py`, 42 проверки:

- Непрерывная запись от Home (фокус на «Исследовать») до возврата в Home, где фокус восстановлен на «Исследовать». 104,8 с, 1915 кадров, ошибок декодирования 0.
- D-pad проходит весь flow. Focus traps нет: все focusable-элементы попадают в safe area, это проверено accessibility-дампами.
- Один Lumi на Intro/Prediction.
- Ask gating: на SUN, HEAT, ICE и WATER OK поглощается и не открывает Build; DONE → «Спрятать лёд от солнца»; Repeat → снова SUN и блокировка до DONE.
- Build: два укрытия, «Проверить» только после размещения. Через 2,5 с ожидания без Check результата нет.
- Pause/resume через Settings на Experiment, Ask (не DONE) и размещённом укрытии: pid тот же, состояние заморожено, укрытие сохранилось, `canBuild` остался false, история не изменилась.
- Цепочка Back: BUILD_RESULT → BUILD_CHOOSE → ASK(DONE) → RESULT → INTRO → Explore Home → Home, фокус везде восстанавливается.
- Каталожная карточка «Лаборатория погоды» → Details → «Начать» → Weather Intro; Back возвращает в Home. `AdventureActivity` не запускался.
- В logcat нет `StitchExploreWeatherPreviewActivity`, `FATAL`, `ANR`, `Fatal signal`.

## 7. PSS / AI

| Поверхность | PSS, KiB | Debug preview (android-preview), KiB |
|---|---|---|
| Home | 112 686 | — |
| Explore Home | 134 201 | 123 729 |
| Weather Intro | 148 299 | 141 515 |
| Experiment | 157 696 | 149 886 |
| Build & Test | 174 228 | 169 710 |

Разница с preview в 5–10 МБ объясняется тем, что под Explore в back stack остаётся Home; preview запускался отдельно.
- AI=0 во всех строках `FamilyExplore` и `FamilyHome`.
- В `/proc/<pid>/maps` на всех пяти точках нет `.gguf` и `.onnx`.
- Microphone, STT, Qwen и `VoiceRuntime.get()` не вызываются.

## 8. Что не проверено

- Физический пульт — **NOT VERIFIED**: использовались ADB keyevent.
- Голос на устройстве не проверялся: микрофон намеренно не запускается. Маршрутизация «исследовать» / «исследуй» → Explore покрыта unit-тестом.
- Изменение поведения голосовых фраз «космос» / «покажи космос». Существующий router переводит их в тот же `OpenWorld("explore")`, поэтому теперь они открывают Explore Home, где Космос показан как «Скоро». Раньше они прокручивали Home к секции «Исследуй». Router я не менял; решение за продуктом.
- Узел `temp-lab` на карте Journey на приставке не нажимался. Он идёт через тот же `ContentNavigator.open`, что и проверенная каталожная карточка.
- Озвучка на слух не оценивалась: проверен только вызов общего `NarrationCoordinator`.
- `FamilyAction.Home` (голосовое «домой») → `HomeActivity` проверен только по коду.
- На H728 `animator_duration_scale=0.25` — настройка устройства, она не менялась. Часы Weather не зависят от animator.

## 9. Evidence

- `screenshots/01_home_explore_focus.png` … `13_home_return.png` — все production-кадры с этого прогона.
- `screenshots/extra_*` — недоступная локация, pause/resume, каталожный маршрут.
- `recordings/explore-weather-production-full.mp4`.
- `logs/`:
  - `checks.json`, `device-acceptance.txt`, `state.log`, `home.log`, `logcat.txt`;
  - `pss-*.txt`, `maps-*.txt`, `performance.json`;
  - `accessibility-*.xml`;
  - `history-main-flow.json`, `history-journey.json`, `stores-delta.json`;
  - `validation.log`, `validation-summary.json`, `lint-results-debug.xml`, `TEST-*.xml`;
  - `release-boundary.log`, `release-boundary.json`;
  - `video-metadata.json`, `video-verification.json`.
- `MANIFEST.sha256`.

Stitch design source и evidence `android-preview` и `ux-polish` не тронуты.
