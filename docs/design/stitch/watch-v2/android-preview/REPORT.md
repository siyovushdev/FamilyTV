# FamilyTV Watch Flow — Android TV preview

2026-09-25. Реализован отдельный **debug-only** маршрут на Android Views:
**Stitch Home C → Смотреть → Watch Home → Browse → Details → Media3 Player**.
Проверен на реальном **ADT-3 / Allwinner h728**, Android 14, 1920×1080. Это материалы для внешнего design review, без решения о production-внедрении.

## Запуск

Из корня проекта:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb -s 192.168.68.105:5555 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s 192.168.68.105:5555 shell am start -n ru.familytv/.home.stitch.StitchHomePreviewActivity
# LEFT → OK: Смотреть → Watch Home
adb -s 192.168.68.105:5555 shell am start -n ru.familytv/.home.stitch.watch.StitchWatchPreviewActivity
# Можно также открыть Browse/Details напрямую:
adb -s 192.168.68.105:5555 shell am start -n ru.familytv/.home.stitch.watch.StitchWatchPreviewActivity --es screen details --es category space --es contentId stitch-watch-space-1
```

Gradle daemon использует установленную Java 25. Player Activity не экспортирована и открывается через Details. Production launcher остаётся доступен как `ru.familytv/.home.HomeActivity`.

## Исходники и границы изменений

Новые файлы в `app/src/debug/java/ru/familytv/home/stitch/watch/`:

- `StitchWatchPreviewActivity.kt` — стек Home/Browse/Details, D-pad, восстановление фокуса, Continue.
- `StitchWatchPlayerActivity.kt` — один ExoPlayer, lifecycle/release, один собственный HUD, прогресс.
- `WatchPreviewCatalog.kt` — debug-проекция существующего ContentRepository и резолвинг локальных образцов.
- `WatchUi.kt` — native Canvas/View-компоненты, типографика, иллюстрации, фокус.
- `WatchPlayerUi.kt` — прозрачный HUD, timeline и корневой обработчик пульта.

Изменены debug `AndroidManifest.xml` и переход станции «Смотреть» в `StitchHomePreviewActivity.kt`. Добавлены `app/src/debug/assets/stitch_watch/`, контрактные тесты `app/src/testDebug/java/ru/familytv/home/stitch/watch/WatchPreviewContractTest.kt`, скрипты `tools/qa/stitch_watch_preview_acceptance.py` и `tools/qa/generate_stitch_watch_media.py`.

Production Home/Video, VoiceRuntime, Parent Mode, adventures, протокол пакетов и signing в этой задаче не изменялись. В репозитории уже были другие изменения до начала работы. Снимок файлов для review находится в `source/`; это дополнение к исходному проекту, не отдельный проект сборки.

## Соответствие источникам

Имена каталогов в задании расходятся с фактическим содержимым HTML/PNG. Использовано назначение, подтверждённое заголовком и содержимым каждого файла:

| Поверхность | Фактический каталог Stitch |
|---|---|
| Watch Home | `familytv_android_tv_4` |
| Browse | `familytv_android_tv_1` |
| Details | `familytv_android_tv_2` |
| Player | `familytv_android_tv_3` |

Основные tokens взяты из Cosmic Hearth V2 `DESIGN.md`: navy `#080d1e`, cyan `#38d9d2`, world accents, reference canvas 1920×1080 и safe area 88×72. PNG имеют обрезанный справа экспорт 1280×1080. Android показывает полный 16:9; сравнения сохраняют обе исходные пропорции и не растягивают обрезанный PNG на широкий экран. Копии источников находятся в `reference/`.

Нативные адаптации и отличия:

- Пять карточек шириной 320 px с промежутками 36 px; scale 1.06, кнопки 1.05. Крайние карточки слегка смещаются внутрь при фокусе. Контент помещается по ширине; screen capture подтверждает отсутствие обрезки соседних карточек.
- Системный Roboto с кириллицей вместо загружаемого Plus Jakarta Sans. Файлы шрифтов не загружались и не добавлялись. Основные размеры — 44/32/24, вторичные подписи 16–20 px.
- Иконки и вторичный знак Луми нарисованы native Canvas; это не буквальный перенос HTML SVG. Луми здесь показывает подсказку, не обещает озвучку.
- В Details одна короткая фраза, artwork, реальная длительность, Смотреть/Продолжить, Серии и короткое «К видео». Parent-кнопки на Details нет.
- Continue в шапке — компактное действие с позицией; thumbnail и видимое название, как в HTML, в эту кнопку не перенесены. Полное название есть в accessibility description и Details.
- В плеере нет FamilyTV header, второй панели Media3, фиктивного sleep timer и постоянной центральной кнопки поверх видео. Центральный значок появляется на паузе. Меню звука управляет mute единственной звуковой дорожки, не выбором языков. Единственный видимый SubtitleView расположен над HUD-затемнением: нижний отступ области cues 280 px при панели и 88 px без панели; текст 34 px. Это учитывает явное позиционирование VTT и исключает перекрытие субтитров контролами.

## Данные и воспроизведение

`WatchPreviewCatalog` делегирует существующему ContentRepository, добавляя только в debug-представление 25 VIDEO-образцов (пять на категорию) из JSON. Доступные реальные VIDEO также проецируются по метаданным и возрастному фильтру. При числе карточек больше пяти предусмотрены страницы; production-каталог и пакетный протокол не переписываются.

Используются **существующий WatchProgressStore** и общие FamilyAction для pause/resume/back/home. Нового progress store или декодера нет. PlayerView имеет `useController=false`; один Media3 ExoPlayer воспроизводит H.264/AAC и VTT. На уходе с экрана позиция сохраняется, player освобождается. Continue не заполняется фиктивными значениями.

Порог resumable-видео в существующем store не менялся: ролики короче 8 секунд считаются завершёнными. Поэтому для проверки Continue использованы длинные debug-образцы.

Образцы — пять настоящих локальных MP4 по 90 секунд, 1280×720/24 fps: медленное движение по предоставленным Stitch-иллюстрациям, тихий тестовый тон и русские VTT. Это **тестовые видеозаготовки, не готовые эпизоды**. Часть названий в разных категориях ссылается на один и тот же ролик. Их назначение — проверить Media3, дорожки, seek и resume. Размер debug-assets около 12 MiB. Десять WebP 512×286 занимают около 5.6 MiB после декодирования; крупных фоновых bitmap/video у навигационных экранов нет. URL исходных иллюстраций сохранены в `art-sources.json`; runtime-сеть для них не нужна.

## Проверки и H728

- `assembleDebug`, `testDebugUnitTest`, `lintDebug`: PASS. **158 tests, 0 failures, 0 skipped; Lint 0 errors, 229 warnings.** Первоначальный сбой анализатора Lifecycle исчез при повторном прогоне после завершения редактирования. Финальные результаты: `logs/gradle_verified.txt`, `logs/build_results.json`.
- Непрерывный реальный маршрут от Home C записан в `recordings/watch_flow_full.mp4`: **72.85 s, 1920×1080, без монтажа**. `ffprobe` не обнаружил audio stream: запись H728 без звука; сами тестовые MP4 содержат AAC. `logs/recording_probe.json`.
- Зафиксированы все пять category focus states и три разных video focus states, Details, видимый/скрытый player, Continue, возврат фокуса и resume. Дополнительный автоматизированный проход прошёл 55 проверок фокуса и проверяет все категории, границы ряда, UP/DOWN, Серии, timeline, BACK и PIN-entry. Использовались только ADB keyevents; физический пульт не использовался.
- Seek ±10 s подтверждён реальными from/target позициями в playback-логах; caption track и audio track обнаружены, CC и mute переключены. Auto-hide сработал спустя **5.025 s** после возобновления и последнего действия; скрыты весь HUD и его затемнение. Пауза сохраняет управление видимым.
- Первый выход сохранил **11,576 ms**; запрос resume — **11,576 ms**; READY — **11,611 ms**, отклонение 35 ms. Details и Watch Home получили Continue из того же store. `logs/capture_logcat.txt`.
- При открытии/focus **AI=0, runtimePresent=false**. Вход Луми — только управляющая подсказка; ASR/LLM он не запускает. Дополнительная проверка сверяет отсутствие mapped `.gguf`/`.onnx` в процессе. Логи и maps сохранены локально.

| Экран | TOTAL PSS, KiB | MiB |
|---|---:|---:|
| Watch Home | 122,867 | 120.0 |
| Browse | 126,638 | 123.7 |
| Details | 126,493 | 123.5 |
| Player (paused, decoder prepared) | 147,131 | 143.7 |

Это единичные PSS-снимки одного последовательного маршрута с Home C/предыдущими Activities в back stack и активным screenrecord. Они не являются изолированной стоимостью каждого экрана или пиком памяти. Метод и устройство: `DEVICE_STATE.md`; исходные dumpsys: `logs/meminfo_*.txt`.

В интервале итогового acceptance в process-logcat и system/crash buffers **не обнаружены crash/ANR FamilyTV** (`logs/runtime_safety.json`). Это результат ограниченного device-прогона, не гарантия отсутствия ошибок при дальнейшем использовании. Дополнительный D-pad-прогон завершился PASS, включая PIN-entry без ввода/изменения PIN и возврат к станции Смотреть в Home C.

## Ограничения

- Иллюстрации 512×286 становятся мягкими при увеличении в Details и видео. Во входных Stitch-иллюстрациях темы некоторых миров пересекаются; подтверждения распознавания категорий детьми без чтения нет.
- Голосовой inference и полноценная озвучка Луми намеренно не подключены в preview. Физический пульт, фактическое звучание через TV и детская usability-сессия не проверены.
- Запись имеет native variable frame rate; средняя частота кадров screenrecord не используется как измерение FPS интерфейса. Извлечённые кадры просмотрены для проверки содержимого; формальная оценка FPS/frame pacing и длительный playback/memory soak не проводились. На старте/возобновлении возможен короткий Media3 loading state, он остаётся в непрерывной записи.
- Native gradients, glow, иконки, шрифт, ширина промежутков и некоторые вторичные подписи отличаются от HTML. Визуальное качество и пригодность для production оценивает внешний reviewer.

## Материалы

- `screenshots/01_watch_home_default.png` … `10_return_focus.png` — обязательные состояния.
- `screenshots/11_category_how_focus.png` … `15_resumed_player.png` — дополнительные фокусы и resume.
- `screenshots/16_existing_parent_pin.png`, `17_player_captions.png`, `18_home_c_return_focus.png` — дополнительные проверки.
- `comparison/watch_home_stitch_vs_h728.png`, `browse_stitch_vs_h728.png`, `details_stitch_vs_h728.png`, `player_stitch_vs_h728.png` — источник слева, H728 справа; пропорции сохранены.
- `logs/` — устройство, PSS, playback/focus, сборка и acceptance; `source/` — review snapshot; `reference/` — исходный Stitch.
- Архив всей папки: `/tmp/familytv-stitch-watch-preview.zip`.

APK SHA-256 (сборка в workspace, не включена в ZIP): `5af6e2239415f065e73ca6ead89b9c70e334c1fbff2ae7b2c8da3e174d11b9d1`.
