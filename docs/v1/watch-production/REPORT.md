# FamilyTV Watch — production promotion

Дата проверки: 2026-09-25. Утверждённая native Android TV реализация Cosmic Hearth V2 перенесена из debug preview в основной source set. Production entry: `ru.familytv/.home.HomeActivity` → **Смотреть**. Проверка выполнена установленным debug APK через production Activities, без запуска preview и без импорта fixture catalog.

## Что перенесено

- Watch Home, Browse, Details, safe area 1920×1080, cyan focus/scale, D-pad, подсказки и custom player HUD используют утверждённые native Views.
- Общие примитивы Stage/Drawing выделены из debug Home C в `ui/ReferenceScene.kt`; Home C использует aliases к тем же примитивам. Его экран не продвигался в production.
- `WatchUi.kt` и `WatchPlayerUi.kt` перемещены в main. Debug Watch Activities теперь являются небольшими адаптерами к production экрану и существующему `VideoPlayerActivity`; второго ExoPlayer stack нет.
- Debug catalog, 25 fixture items, artwork и длинные тестовые media остались в debug. Production assets, ContentRepository, WatchProgressStore, package protocol и VoiceRuntime не изменялись.

## Production files

| Файл (от корня репозитория) | Изменение |
|---|---|
| `app/src/main/java/ru/familytv/home/watch/WatchActivity.kt` | Home/Browse/Details, реальный Continue, D-pad/Back/focus, постраничный список, настоящее подмножество серий, FamilyAction |
| `app/src/main/java/ru/familytv/home/watch/WatchCatalog.kt` | UI-проекция существующего репозитория, age/enabled фильтр, категории, bounded artwork cache; не новый ContentRepository |
| `app/src/main/java/ru/familytv/home/watch/WatchUi.kt` | Перенесённые утверждённые карточки, Details, кнопки и фон |
| `app/src/main/java/ru/familytv/home/watch/WatchPlayerUi.kt` | Перенесённые HUD, timeline, root key handling |
| `app/src/main/java/ru/familytv/ui/ReferenceScene.kt` | Общие неизменённые по геометрии примитивы из preview |
| `app/src/main/java/ru/familytv/player/VideoPlayerActivity.kt` | Утверждённый один HUD, существующий Media3 engine, captions, progress и completion hooks |
| `app/src/main/java/ru/familytv/home/HomeActivity.kt` | Только вход «Смотреть» в существующей полосе кнопок, video routing, обновление Continue и повторное подключение FamilyAction после возврата |
| `app/src/main/java/ru/familytv/home/ContentDetailsActivity.kt` | VIDEO перенаправляется в Watch Details; остальные типы сохраняют прежний экран |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/themes.xml` | Неэкспортируемая Watch Activity и fullscreen тема Watch/Player |

Полный аудит относительно SHA256 baseline до начала этой задачи: [changed-files.json](logs/changed-files.json). Изменений в Space/Forest/Weather/Story Builder/Journey/Journal/Math/Color/Parent Mode/VoiceRuntime, signing/distribution нет. Существующие completion вызовы activity history и journey coordinator в плеере сохранены.

## Каталог и artwork

Используется `PlatformServices.contentRepository()` / `getEnabledItems()`: VIDEO, непустой mediaRef, текущий age filter, enabled state. Явные `dev_only` / `experimental` исключены из child Watch; установленным пакетам не требуется искусственный тег `v1`.

Детерминированный приоритет группировки: categories → topics → tags → contentPurpose. Словарь покрывает природу, космос, устройство вещей, истории, творчество/музыку/движение; неизвестная тема попадает в «Другие видео». Пустые категории не показываются. Более пяти категорий/видео дают предыдущую/следующую страницу. Серии показываются только для двух и более доступных VIDEO с общим непустым seriesId, в episodeOrder.

Artwork читается из реального package/bundled thumbnail; при символическом или отсутствующем thumbnail извлекается кадр соответствующего видео, затем предусмотрен нативный значок. Декодирование вне main thread, только для текущей страницы; cache ограничен 12 MiB. Debug illustrations не подставляются в production.

На H728 production catalog содержит **два доступных ролика по 3 секунды**: «Тёплый свет» и «Разминка». Их реальные кадры однотонные — именно они видны на карточках и в плеере. Встроенные «Голубое небо» / «Зелёный лес» помечены dev_only и исключены. Установленных пакетов на устройстве нет. Все найденные видео в `lessons/` также короткие (2–4 секунды). Новые media или искусственное состояние Continue не создавались.

## Progress, player и lifecycle

- Используется тот же `WatchProgressStore`: save на Back/onPause, resume position при новом запуске, markCompleted при STATE_ENDED. Правило `< 8 секунд` и near-end policy сохранены.
- Watch и старый Home пересекают Continue с текущими доступными VIDEO. Пустой каталог возвращает пустой Continue, хотя исходный store трактует пустой knownIds как «все». Старые debug progress keys на устройстве сохранены, но в production не показываются.
- Production плеер — прежний `VideoPlayerActivity` с одним ExoPlayer и `PlayerView.useController=false`. Один custom HUD: Back, −10, Play/Pause, +10, timeline, текущая/полная длительность. CC и звук доступны только при соответствующем track.
- CaptionRef разрешается существующим repository для VTT/SRT, locale берётся из metadata. Один SubtitleView располагается над HUD с безопасным нижним отступом; внутренний subtitle renderer PlayerView отключён.
- Auto-hide остаётся 5000 ms inactivity во время playback; paused HUD виден. На pause ExoPlayer освобождается; возвращение пересоздаёт его с позицией и play/pause состоянием. Catalog eligibility проверяется заново при возвращении. Ошибка media даёт выход Back.
- Автоматического VoiceRuntime/STT/Qwen init нет. Подсказка Луми — текстовое control hint; новый голосовой pipeline в Watch не добавлялся.

## Tests

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — PASS. **166 tests, 0 failures, 0 errors**, включая 8 новых production regressions. Lint: **0 errors, 232 warnings** (существующие и стилистические предупреждения, включая programmatic View constructors). Дополнительно `:app:compileReleaseKotlin` — PASS: main компилируется без debug source set.

Новые проверки: реальный LocalContentRepository + enabled/age; текущий bundled manifest; установка существующего apple-adventure пакета во временную папку с проверкой локального media resolution; metadata grouping/fallback; Continue с удалёнными/недоступными ID и пустым каталогом; completion/short clips; порядок серий/страница выбранного эпизода; seek clamp. Файлы и synthetic values unit tests не входят в production assets.

Логи: [build-tests-lint.txt](logs/build-tests-lint.txt), [compile-release.txt](logs/compile-release.txt), [test-summary.txt](logs/test-summary.txt).

## H728 evidence

Устройство: ADT-3 / Allwinner **h728**, Android 14, 1920×1080, density 320. APK установлен через `adb install -r`, данные сохранены. Навигация ADB key events, touch не использовался; физический пульт отдельно не проверялся. Local bundled media работают без сетевого источника; Wi-Fi для ADB оставался включён.

Непрерывная запись: [watch_production_full.mp4](recordings/watch_production_full.mp4). Начинается с настоящего production Home. Скриншоты:

| Evidence | Что действительно подтверждает |
|---|---|
| [01_home_to_watch.png](screenshots/01_home_to_watch.png) | Старый Home, фокус на добавленном «Смотреть» |
| [02_watch_home.png](screenshots/02_watch_home.png) | Только реальные непустые категории |
| [03_watch_focus.png](screenshots/03_watch_focus.png) | Cyan focus второй категории |
| [04_browse.png](screenshots/04_browse.png), [05_browse_focus.png](screenshots/05_browse_focus.png) | Реальное видео, длительность, возврат фокуса с Back |
| [06_details.png](screenshots/06_details.png) | Краткое описание, Смотреть; Parent/фиктивных серий нет |
| [07_player_controls.png](screenshots/07_player_controls.png) | Один HUD поверх реального видео |
| `08_player_hidden.png` — не снят | Реальные ролики заканчиваются раньше 5s auto-hide; поддельного кадра нет |
| [09_continue.png](screenshots/09_continue.png) | Continue корректно отсутствует после короткого ролика; это НЕ демонстрация long-video Continue |
| [10_resume.png](screenshots/10_resume.png) | Повторный запуск короткого ролика с нуля; это НЕ доказательство nonzero resume |
| [11_home_return.png](screenshots/11_home_return.png) | Возврат в production Home |
| [12_player_audio_muted.png](screenshots/12_player_audio_muted.png) | Применимое audio action для AAC track |

## Результат device acceptance

PASS: production Home → Смотреть → Watch Home → Browse → Details → реальный Media3 playback → Back → Home. 24 проверки фокуса вместе с проверкой resumed Activity; возврат фокуса на категорию, видео и кнопку «Смотреть». Работают pause/play, seek с clamp к началу/концу, audio mute/unmute (реальный AAC track), short-clip completion и повторный запуск с 0. Один HUD; CC скрыт, поскольку caption track отсутствует. Логи: [acceptance-run.txt](logs/acceptance-run.txt), [acceptance_logcat.txt](logs/acceptance_logcat.txt).

Дополнительная lifecycle-проверка: приостановленное видео на **1012 ms** → временный системный экран настроек без изменения настроек → Back в тот же production Player. ExoPlayer release подтверждён; новый player запросил **1012 ms** и стал READY на **1012 ms**, остался paused. Затем Back вернул production Home. Это внутриэкранное восстановление после background, а не long-video Continue. [lifecycle-result.json](logs/lifecycle-result.json), [lifecycle-logcat.txt](logs/lifecycle-logcat.txt), [13_player_lifecycle_paused.png](screenshots/13_player_lifecycle_paused.png).

| Экран | TOTAL PSS, KiB | MiB |
|---|---:|---:|
| Watch Home | 117525 | 114.8 |
| Browse | 118059 | 115.3 |
| Details | 118415 | 115.6 |
| Player (paused, READY) | 127185 | 124.2 |

Замеры: [pss.json](logs/pss.json) и полные `meminfo_*.txt` в logs. Дополнительная оптимизация не проводилась. Обнаруженная повторная регистрация focus observer при render устранена; один observer на Activity снимается при destroy.

**AI=0**: Watch `runtimePresent=false`, player resume `AI=0`, initialization marker отсутствует. В process maps до и после lifecycle нет `.gguf`/`.onnx` моделей. **Crashes/ANR: не обнаружены** в process и системном logcat за финальный прогон + lifecycle; catalog/load/playback errors также отсутствуют. [crash-ai-audit.txt](logs/crash-ai-audit.txt), [system_logcat.txt](logs/system_logcat.txt).

Recording: **49,109 s**, native Android screenrecord, 1920×1080 H.264, 367 кадров с переменной частотой, без аудиодорожки. Полный decode ffmpeg без ошибок; первый/последний кадр проверены — production Home с фокусом на «Смотреть». [recording-probe.json](logs/recording-probe.json), [recording-decode.txt](logs/recording-decode.txt). Звуковой action подтверждён UI/логами, физическое прослушивание динамиков не проверялось.

Source/APK идентификаторы: [source-sha256.txt](logs/source-sha256.txt), [apk-sha256.txt](logs/apk-sha256.txt). APK установлен через install -r после финальной успешной сборки. Все итоговые screenshots и запись относятся к нему. Повторение проверки: `python3 tools/qa/watch_production_acceptance.py --device <H728 serial>`, затем `python3 tools/qa/watch_production_lifecycle.py --device <H728 serial>`; скрипты ожидают текущий bundled catalog и не создают content/progress fixtures.

## Ограничения приемки

**NOT VERIFIED на production H728:** playing auto-hide через 5s, длительный Continue/nonzero resume, отображение/переключение captions (у доступных production роликов нет caption track), installed-package playback на устройстве (пакетов нет), multi-page/series UI с реальным большим каталогом. Store/mapping/series/pagination boundary проверены JVM-тестами; прежняя debug-проверка не выдаётся за production evidence. Для оставшейся device-приёмки нужен реальный доступный длинный ролик с captions и соответствующее содержимое каталога. Политика short clips не ослаблялась.

Первый поиск входа в QA script шёл вниз от восстановленной нижней карточки Home и дошёл до края; скрипт исправлен для перехода к верху. Это не падение приложения. Финальная проверка подтверждает не только log focus, но и текущую resumed Activity, чтобы исключить старые записи фокуса из предыдущего экрана.
