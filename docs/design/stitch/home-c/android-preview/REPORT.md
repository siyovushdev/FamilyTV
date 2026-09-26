# FamilyTV · Stitch C · Android TV debug preview

Дата: 2026-09-24. Реализовано и установлено на реальный H728. Production Home и его launcher entry не менялись. Preview доступен только в debug-сборке.

## Открыть

```sh
adb shell am start -n ru.familytv/.home.stitch.StitchHomePreviewActivity
```

Обычный Home по-прежнему открывается командой:

```sh
adb shell am start -n ru.familytv/.home.HomeActivity
```

BACK закрывает preview. LEFT/RIGHT переключают пять островов; UP ведёт к голосовому control или «Родителям»; DOWN — к текущей миссии. Возврат из верхней/нижней зоны сохраняет последний остров. На краях фокус остаётся на крайнем элементе. В существующем Parent Mode BACK возвращает в production Home — этот контракт сохранён. PIN не вводился и не менялся.

## Reference и перенос

Использован только `docs/design/stitch/home-c/screen.png` и его `code.html`. Исходный PNG имеет размер **1280×1080**, хотя HTML описывает сцену **1920×1080**: картинка обрезана справа, а не является уменьшенной версией полного экрана. Видимая часть PNG определяет композицию; правая часть с Create/Move и controls восстановлена по HTML Variant C.

Сохранены ночной градиент, слои атмосферного фона и дальние силуэты, верхняя зона FamilyTV, пять отдельных островов с разными landmarks, выбранный «Играть» по умолчанию, cyan/teal ring, Lumi над выбранным островом, нижний mission dock и remote legend. Никаких shelves, RecyclerView или бесконечной ленты нет.

Нативные адаптации:

- Android Views и аппаратный Canvas для сцены; восемь небольших VectorDrawable для landmarks, ракеты и controls. SVG-геометрия перенесена из локального Variant C HTML; внешние картинки и новые зависимости не добавлялись.
- Базовые станции: примерно 330×356 px, начало x=80/y=315; mission dock y=810. Единая система координат 1920×1080 масштабируется с сохранением пропорций. На H728 scale=1 при системной density=320 dpi. Важные элементы размещены в 80 px safe area; при фокусе крайние станции сдвигаются внутрь. Мягкий halo может выходить за safe area, оставаясь внутри кадра.
- Focus scale=1.08, подъём 14 px, переход 260 ms, расширение верхней части выбранной станции и контрастное кольцо. CSS blur заменён радиальными градиентами/полупрозрачными контурами без программного blur и больших bitmap. Нативный Roboto отличается от браузерного шрифта.
- Использован существующий `LumiView`, включая его текущую форму, лицо и цвета. Его position следует за островом; idle — движение на 6 px. CSS-персонаж не копировался и production Lumi не переделывался. Анимации останавливаются при уходе с экрана.
- Статические сюжетные названия reference и «сохранено 15 минут назад» заменены данными устройства. Длинных описаний не добавлено.

## Данные и действия

Каталог читается через `PlatformServices.contentRepository()` в `Dispatchers.IO`. Применяются V1 visibility, enabled flags и возрастной фильтр; preview не записывает тестовый прогресс.

| Остров | Данные/переход на проверенном H728 |
|---|---|
| Смотреть | `video-warm` / «Тёплый свет», существующий ContentDetailsActivity |
| Играть | `game-math` / «Математика», существующий ContentDetailsActivity |
| Исследовать | `adv-weather-lab` / «Лаборатория погоды», существующий ContentDetailsActivity |
| Создавать | Существующая CreativeStudioActivity, подпись «История с Луми» |
| Двигаться | `move-stretch` / «Разминка», существующий ContentDetailsActivity |

Для Create потребовался локальный compatibility fallback: текущий production `CatalogManifestParser` пропускает bundled `lumi-story`, поскольку INTERACTIVE-запись не содержит `experienceRef`. Preview открывает существующую студию только при этой конкретной validation warning, с учётом enabled override и возраста 3–9. Сам parser, manifest каталога и Story Builder не менялись.

Dock показывает реальную активную Journey «Большая космическая экспедиция» и текущий узел «Старт». Кнопка открывает существующую карту. При отсутствии активной Journey предлагается новое незавершённое путешествие с подписью «Новое путешествие», без фиктивного сохранения. Если доступно Continue Watching, появляется отдельное «Досмотреть»; оно получает контент из `WatchProgressStore`. На этом устройстве продолжаемого видео не было, поэтому этот условный control скрыт и его video-resume ветка на H728 **NOT VERIFIED**.

Voice control показывает короткую подсказку. Ни preview startup, ни focus, ни нажатие этого control не запускают микрофон, VoiceRuntime, Qwen или STT. «Родителям» открывает существующий PIN entry.

## Проверка на устройстве

Устройство: reported model **ADT-3**, SoC **Allwinner h728**, hardware **sun55iw3p1**, Android **14**, физический экран **1920×1080**, density **320**. Идентификаторы сохранены в `logs/device.txt`.

| Проверка | Результат |
|---|---|
| Все пять островов, верхняя зона, Lumi, mission dock | PASS, реальные PNG 1920×1080 |
| Начальный Play focus, в том числе после touch-mode | PASS |
| 22 состояния LEFT/RIGHT/UP/DOWN и границы | PASS |
| Возврат к последнему острову, отсутствие focus trap | PASS |
| Пять переходов в существующий контент/студию и обратно | PASS |
| Parent entry и его штатный выход в Home | PASS |
| Journey entry и возврат в preview | PASS |
| BACK из preview | PASS |
| Production Home после проверки | PASS, `14_production_home_unchanged.png` |
| Voice control без AI initialization | PASS |
| Видимые clipping/выход важных элементов за экран | Не обнаружены при просмотре пяти фокусов и controls |

Фокус подтверждён событиями нативных View, действиями ADB, скриншотами и непрерывным video. Дополнительный UiAutomator dump на этой прошивке не создал XML; ошибки чтения сохранены как `*_unavailable.txt`, а не как успешные hierarchy dumps. Физическая оценка ребёнком с расстояния 2–3 м остаётся для внешнего review.

## Память, запуск, AI

Три **process-cold** запуска через `am force-stop` + `am start -W`; устройство не перезагружалось, данные и системный файловый кеш не очищались. PSS снят после появления каталога, без screenrecord. `TotalTime` — измерение ActivityManager, а не собственный таймер приложения.

| Запуск | TotalTime | TOTAL PSS | MiB |
|---|---:|---:|---:|
| 1 | 1458 ms | 114301 KiB | 111.62 |
| 2 | 1562 ms | 114020 KiB | 111.35 |
| 3 | 1523 ms | 114036 KiB | 111.36 |

Медиана TotalTime: **1523 ms**. Каталог привязан через 492 / 567 / 564 ms от начала `onCreate`.

Во всех трёх запусках: **AI=0, runtimePresent=false**. В process logs нет `runtimeInitialization`, `llmInitMs`, `NativeQwen.create`; в `/proc/<pid>/maps` нет отображённых `.onnx`/`.gguf` моделей. Нажатие voice control также зафиксировано с AI=0. Это проверка отсутствия инициализации моделей, не ASR-тест.

Контрольный production Home на той же сборке: TotalTime **2236 ms**, PSS **115906 KiB / 113.19 MiB**, без AI init в process log. Один контрольный замер не является сравнительным benchmark.

## Build / isolation

- `:app:assembleDebug` — PASS.
- `:app:testDebugUnitTest` — 156 tests, 0 failures, 0 errors; итоговый прогон сохранён отдельно.
- `:app:lintDebug` — PASS, 0 errors / 217 warnings всего проекта; среди предупреждений preview — программно создаваемые View constructors и KTX suggestions.
- `:app:processReleaseMainManifest` — PASS; `StitchHomePreviewActivity` отсутствует в merged release manifest.
- Установка `adb install -r` сохранила app data. Не выполнялись очистка данных, изменение signing или замена production launcher.

Изменён только debug manifest; добавлены:

```text
app/src/debug/java/ru/familytv/home/stitch/StitchHomePreviewActivity.kt
app/src/debug/java/ru/familytv/home/stitch/StitchSceneViews.kt
app/src/debug/java/ru/familytv/home/stitch/StitchPreviewContent.kt
app/src/debug/res/values/stitch_preview.xml
app/src/debug/res/drawable/stitch_c_{watch,play,explore,create,move,rocket,mic,parent}.xml
tools/qa/stitch_c_preview_acceptance.py
```

Остальные изменения, уже имевшиеся в рабочем дереве до этой задачи, не включались в работу над preview. Production UI/theme, Adventures, Journey, Journal, Parent, VoiceRuntime и content packages не редактировались.

## Review evidence

- [comparison.png](comparison.png): слева исходный PNG, справа полный реальный H728 кадр. Изображения имеют исходный размер, не растянуты.
- [comparison_left_crop.png](comparison_left_crop.png): дополнительное сравнение совпадающей левой области 1280×1080, 1:1.
- [focus_contact_sheet.png](focus_contact_sheet.png): пять world focus и Parent control.
- [screenshots/](screenshots/): восемь обязательных кадров `01_stitch_c_default.png` … `08_stitch_c_lumi.png`, плюс 09–14 для mission/voice/Parent/Journey/production Home. `08` — полный реальный кадр с Lumi, не сгенерированный крупный план.
- [recordings/stitch_c_home_navigation.mp4](recordings/stitch_c_home_navigation.mp4): одна непрерывная запись H728, 1920×1080 H.264, около 45 секунд, без монтажа. Начинается с production Home, затем открывает preview, проходит пять островов, верхние controls и mission dock; видны focus animation и Lumi idle.
- [logs/](logs/): замеры, process logs/maps, build/lint/tests, acceptance console и metadata записи.

ZIP: `/tmp/familytv-stitch-c-android-preview.zip`. Содержит evidence, исходный Variant C reference, debug source/resources и acceptance script. APK и generated build directories в ZIP не включены.

Повторить аппаратную проверку:

```sh
python3 tools/qa/stitch_c_preview_acceptance.py --device DEVICE_SERIAL --phase measure
python3 tools/qa/stitch_c_preview_acceptance.py --device DEVICE_SERIAL --phase capture
python3 tools/qa/stitch_c_preview_acceptance.py --device DEVICE_SERIAL --phase verify
```

Preview оставлен для внешнего review. Другие экраны FamilyTV не перерабатывались.
