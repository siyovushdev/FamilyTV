# Explore / Weather V1 — UX polish (debug preview)

26 сентября 2026. Два точечных исправления поверх принятого [android-preview](../android-preview/REPORT.md). Не redesign, механики не менялись, production promotion и commit не выполнялись. Предыдущее evidence не заменялось.

## 1. One Lumi only

Второй маленький робот был «запечён» в фон `app/src/debug/assets/explore_weather/weather_intro.webp` (общий для Intro / Prediction / Placing ice). Основной Lumi — большой робот, вокруг которого native-код рисует halo.

- Кандидат без маленького робота получен reference-image edit'ом исходного `android-preview/reference/weather_intro_clean.png` ([prompt](../../../../../app/src/debug/assets/explore_weather/provenance.json)).
- В исходник вклеена **только** область робота: кандидат выровнен по полосе вокруг маски (scale 0.995, offset 8/−3 px, средняя разница 7.7/255), затем composite через feathered mask; у пальцев основного Lumi край жёсткий. Скрипт: [composite_one_lumi.py](reference/composite_one_lumi.py), маска: [weather_intro_one_lumi_mask.png](reference/weather_intro_one_lumi_mask.png), параметры: [composite-params.json](reference/composite-params.json).
- Scope: изменены 100 154 px (6.4 %) внутри bbox x1194..1535 / y297..731 кадра 1678×937. **Все пиксели вне bbox и вся область основного Lumi побайтно совпадают с базой** (проверяется тестом). Солнце, луч, стол, тарелки, композиция не тронуты.
- Сигнатура второго робота в его области: белые пиксели головы 4471 → 2, cyan-огни 2955 → 0.
- WebP quality 94, без crop/resize, 1678×937.

| | SHA-256 |
|---|---|
| новый `weather_intro.webp` (в APK) | `53e049200a6e438553602eb34df77323ad0d37e9c8437a2cb3cf1da60a9713a2` |
| источник `reference/weather_intro_one_lumi.png` | `0e698688fa28b0b4d5f32b14ee071379bce646af1f8aba84ba11fe836dd2be17` |
| прежний asset (сохранён: `reference/weather_intro_two_robots_previous.webp`) | `247ad1c49975c57d467625e90fcbc63aa3f31f87812f55f33703620055fb105d` |
| база `android-preview/reference/weather_intro_clean.png` (не изменена) | `b5bc8fa5995a2b2d87ad47c4f047277c27fcb57d5cf2bfaa999761336a631aaf` |

Provenance дополнен блоком `oneLumiEdit`; прежняя цепочка (оригинал Stitch → clean edit) сохранена.

## 2. Ask Lumi — explanation gating

- `WeatherSnapshot.canBuild = screen == ASK && cause == DONE`.
- Пока не DONE, `controls()` в ASK = `back, explanation:CHAIN, repeat`: кнопки «Спрятать лёд от солнца» нет ни в Views, ни в accessibility tree, она не focusable и не actionable. `activate("build")` дополнительно требует `canBuild`.
- Default focus объяснения — невидимый world target над карточкой «Солнце → Тепло → Лёд → Вода» (рамка карточки ярче при фокусе). OK на нём ничего не делает: не открывает Build, не пропускает и не перезапускает объяснение.
- На переходе в DONE появляется «Спрятать лёд от солнца» и получает focus (так же, как «Проверить» после установки укрытия). Auto-transition нет, ребёнок сам нажимает OK.
- Repeat (кнопка или `FamilyAction.Repeat`) сбрасывает clock на SUN; если focus был на «Спрятать…», он уходит на объяснение. До нового DONE кнопки снова нет.
- Lifecycle: gating — чистая функция `elapsedMs`, который в pause заморожен. Конструктор состояния нормализует устаревший focus, поэтому восстановленный snapshot не может получить `state != DONE` при доступном next.
- Back не менялся: ASK → Result; Build choose → ASK (DONE, focus на «Спрятать…»).

## 3. Tests / build / lint

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — **BUILD SUCCESSFUL** ([log](logs/validation.log), [summary](logs/validation-summary.json)).

- 224 JVM-теста, 0 failures/errors/skips; preview-класс: 44 (28 прежних + 16 новых) — [junit](logs/preview-junit.xml).
- Новые: Intro и Prediction используют one-Lumi фон (hash chain webp → provenance → PNG, пиксели); entry/SUN/HEAT/ICE/WATER — next недоступен, в т.ч. через D-pad и прямой focus; DONE — доступен и в focus, без auto-transition; premature Select на всех стадиях не открывает Build и не сдвигает clock; Select после DONE открывает Build; Repeat после DONE и во время sequence снова блокирует; повторный DONE снова открывает; pause/checkpoint/stale focus; Back; `VoiceRuntime.peek() == null`.
- Android Lint: 0 errors, 252 warnings — те же, что до polish, все в существующем коде; в Explore preview 0.
- Kotlin: 4 warnings, все в существующем `app/src/main` (deprecated API в `MainActivity`, `WatchPlayerUi`, `NarrationProvider`); в preview 0 ([log](logs/compile-debug-kotlin.log)).
- `app/src/main`: 268 файлов побайтово равны baseline из android-preview ([main-unchanged.json](logs/main-unchanged.json)).

## 4. H728

ADT-3 / Android 14, `192.168.68.105:5555`, 1920×1080, установлен APK `8351bdc6…` ([apk.json](logs/apk.json)). Сценарий: [tools/qa/stitch_explore_weather_polish.py](../../../../../tools/qa/stitch_explore_weather_polish.py), реальный ADB D-pad, **29 device checks** ([checks.json](logs/checks.json), [output](logs/device-acceptance.txt), [state log](logs/state.log)).

- Explore → Intro → Prediction → Experiment → Result → «Почему?» → Ask Lumi.
- Premature OK сразу на входе и на SUN, HEAT, ICE, WATER: в логе `action=Select screen=ASK canBuild=false`, BUILD не открывался.
- Порядок из лога: SUN → HEAT → ICE → WATER → DONE; `canBuild=true` только на DONE (оба прохода).
- DONE → «Спрятать лёд от солнца» в focus → OK → Build & Test → Back → ASK DONE.
- Repeat → next исчез (`canBuild=false`) → OK на объяснении отклонён → полная последовательность → DONE → next снова доступен.
- Accessibility: во время объяснения focus = «Луми объясняет…», «Спрятать лёд от солнца» отсутствует; на DONE — наоборот; все focusable в safe area.
- Pause (Settings → Back) на HEAT: PID сохранён, стадия и focus те же, `canBuild=false`, затем DONE.
- Back: Ask → Result → Intro → Explore.
- Intro и Prediction визуально содержат одного Lumi (screenshots 01/02).
- Crash/ANR нет; SHA-256 production preferences до/после совпали.

| Точка | PSS, KiB | AI |
|---|---:|---:|
| weather-intro | 143 646 | 0 |
| ask-lumi | 163 401 | 0 |

## 5. AI

AI=0 во всех log-строках. `.gguf` / `.onnx` в `/proc/PID/maps` отсутствуют. Микрофон, STT, Qwen не инициализируются. Source boundary test прежний, плюс runtime `VoiceRuntime.peek() == null`.

## 6. Evidence

- [screenshots](screenshots): `01_intro_one_lumi` … `08_repeat_done` + `extra_pause_resume_explanation`.
- [recording](recordings/explore-weather-polish-ask-lumi.mp4): 39.07 s, 1920×1080, 767 кадров, один непрерывный screenrecord: Result → «Почему?» → отклонённые OK → SUN/HEAT/ICE/WATER → DONE → Build & Test → Back → Repeat → DONE. Полный decode на native time base: 767/767, 0 ошибок ([verification](logs/video-verification.json)). 57 строк «non monotonically increasing dts» появляются только при CFR-конвертации null-муксера (так же у прежней записи) и ошибками декодирования не являются.
- [reference](reference): кандидат, маска, скрипт composite, итоговый PNG, прежний WebP.

## 7. Ограничения

- Удаление робота — generative fill, выровненный и вклеенный в пределах маски. При 3× zoom видна естественная дополнительная стойка перил у края стола; швов/дыр нет, но это не рука художника. Human visual approval остаётся отдельным шагом.
- Старые android-preview screenshots/видео Intro/Prediction по-прежнему показывают двух роботов (историческое evidence, не заменялось).
- Прежний `stitch_explore_weather.py` по анализу совместим с gating (он ждёт DONE перед «Спрятать…»), но в этом polish не перезапускался.
- ADB screenrecord без аудио; narration не проверялась на слух.
