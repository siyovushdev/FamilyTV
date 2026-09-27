# Create / Story Builder V1 — native Android TV debug preview

Статус: **debug-only preview, НЕ production promotion, без commit.**
Activity: `ru.familytv/.creative.stitch.StitchStoryBuilderPreviewActivity` (только `app/src/debug`).
Устройство: H728 / ADT-3, Android 14, 1920×1080, density 320, animator_duration_scale=0.25 (не менялся).
Ввод: ADB keyevent. **Физический пульт — NOT VERIFIED.**

## Canonical path

Create Home → «Придумать историю» → Роби → Волшебный лес → Золотой ключ → Открыть дверь →
визуальное следствие (ключ в скважине, створка открывается, тёплый свет, тайная библиотека) →
Ending «Тайна зачарованного дуба» → Посмотреть ещё раз (5 моментов своей истории) → Ending →
В наши истории (только preview) → Новая история → Back-цепочка → выход.

## Состав

| Файл | Роль |
|---|---|
| `app/src/debug/java/ru/familytv/creative/stitch/StoryPreviewDomain.kt` | адаптер к production `StoryGrammarParser` / `StoryBuilderRuntime` (select, retell, titleFor, mapVoice) |
| `.../StoryPreviewState.kt` | явная тестируемая state machine (`StorySnapshot` + encode/decode для state restoration) |
| `.../StoryPreviewViews.kt` | мир на утверждённом арте + native Canvas для интерактивных предметов |
| `.../StitchStoryBuilderPreviewActivity.kt` | 1920×1080 `ReferenceStage`, D-pad → `FamilyActionDispatcher`, `NarrationCoordinator` |
| `app/src/debug/assets/create_story_v1/` | 6 Stitch JPEG (байты без изменений), `story.json`, `provenance.json` |
| `app/src/testDebug/.../StoryPreviewStateTest.kt` | 20 JVM-тестов |
| `tools/qa/create_story_preview_h728.py` | H728 acceptance (49 проверок) |

## Проверки

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` — BUILD SUCCESSFUL (`logs/validation.log`).
  271 тест, 0 падений; из них 20 новых `StoryPreviewStateTest`. Lint: 0 errors; в новом коде 0 warnings
  (256 warnings — существующие в проекте).
- `:app:compileReleaseKotlin` — успешно (`logs/release-compile.log`); `git diff app/src/main` пуст.
- H728: `PASS 49 checks` (`logs/device-acceptance.txt`, `logs/checks.json`).

## PSS / AI

| Экран | PSS KiB | .gguf/.onnx | AI |
|---|---|---|---|
| Create Home | 140 243 | нет | 0 |
| Choose Hero | 150 168 | нет | 0 |
| Forest | 157 923 | нет | 0 |
| Story Choice | 167 263 | нет | 0 |
| Ending | 174 291 | нет | 0 |

PSS растёт по ходу пути примерно на 34 MB; утечку это не доказывает, но длинную сессию (несколько историй подряд) не мерили.
Все 141 строка состояния содержат `AI=0`; `VoiceRuntime` только `peek()`. Микрофон, STT, Qwen не запускались.
Холодный старт Activity 1473 мс. FATAL / ANR / Fatal signal — 0.

## Хранилища

`logs/stores-delta.json`: все файлы `shared_prefs` до/после совпадают (`changed: []`).
`familytv_stories` не создан; «В наши истории» логирует `save_preview_only persisted=false`.

## Visual acceptance (честно, по скриншотам и видео)

1. **Robi одинаковый на Hero и Ending?** Частично. Одно семейство дизайна (голубой робот, экран-лицо,
   антенны), но каждый Stitch-арт нарисован отдельно: на Hero у Роби звезда на груди, в лесу и финале — другие детали.
   Нужен art pass с единым character sheet перед production.
2. **Forest ощущается лесом?** Да: светящиеся грибы, тропинка, старый дуб, после выбора лес становится сценой.
3. **Key — физический предмет?** Да: золотой ключ в сфере, после OK сфера растворяется и ключ светится в руке Роби.
4. **Key остаётся частью истории?** Да: блестит на Story Choice, летит в скважину на Consequence, поднят Роби в финале, звучит в пересказе.
5. **Door/Path — действия в мире?** Да: фокус подсвечивает саму арку двери и искры вдоль тропинки, без карточек.
6. **Мир меняется после Door?** Да: ключ поворачивается, створка открывается, в проёме тёплая библиотека, затем кроссфейд к арту тайной библиотеки. Реплика звучит после изменения.
7. **Ending показывает историю, а не metadata?** Да: Роби с ключом, тайная библиотека, книга, название истории; без XP/звёзд/счёта.
8. **Понятно ребёнку 4–6 лет без чтения?** Скорее да на основном пути (всё озвучено, одна подсвеченная цель, крупные предметы).
   Не проверено на детях. Ярлык «Скоро» на недоступных вариантах требует чтения; его дублирует голосовая реплика Lumi.

## Альтернативные ветки

Реально поддержаны: Роби, Волшебный лес, Золотой ключ, Открыть дверь → тайная библиотека.
Честно «Скоро» (фокусируемы, Lumi говорит короткую реплику, история не продвигается): Театр, Рисунки,
Тим, Пушистик, Звёздная станция, Таинственный остров, Светящаяся карта, Звёздный фонарик, Пойти по тропинке.

## Не проверено

Физический пульт; реальный голос (preview намеренно без микрофона, parity D-pad/voice проверена только в JVM-тестах);
дети 4–6 лет; качество TTS-озвучки на слух; поведение при смене языка/шрифта системы; других устройствах, кроме H728.

## Evidence

- `recordings/create-story-v1-flow.mp4` — 1920×1080, ~116 с, непрерывно по всему пути.
- `screenshots/01_create_home.png` … `12_back_focus_restored.png`, `extra_pause_resume_*.png`.
- `logs/` — validation, device-acceptance, checks, state, logcat, PSS/maps, accessibility dumps, stores delta.
- `reference/` — исходные Stitch JPEG + provenance.
