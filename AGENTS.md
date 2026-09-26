# Repository Guidelines

## Project Structure & Module Organization
FamilyTV is a single-module Kotlin Android app (`:app`), supporting API 28+.
Source lives in `app/src/main/java/ru/familytv/`:
- `content/` owns the catalog domain model, manifest parsing, `LocalContentRepository`, and installable `packages/`.
- `adventure/` owns AdventureRuntime, mechanic engines (incl. PREDICT_AND_TEST / BUILD_AND_TEST), guide director, and `AdventureActivity` (package-defined adventures).
- `journey/` owns Learning Journeys (map, progress, Discovery Journal, story gallery continuity).
- `knowledge/` owns curated KnowledgeEntry packs, deterministic KnowledgeResolver, and Ask Lumi grounding (no free chatbot).
- `discovery/` owns limited cause/effect simulation helpers for discovery packages.
- `creative/` owns Creative Studio, Story Builder grammar/runtime, and local StoryStore (save/replay).
- `interaction/` owns ReadingSupportMode, PresentationMessage, NarrationCoordinator, NarrationAssets, FamilyAction, VoiceCommandRouter, ChildInteractionBinder, and ActionDispatcher (remote+voice parity across Home/Adventure/Interactive/Games/Session/Video).
- `progress/` stores device-scoped watch progress (Continue Watching).
- `interactive/` runs data-driven lessons (Interactive Engine v3) via `InteractiveExperienceActivity`.
- `session/` runs curated finite Learning Sessions with calm endings.
- `parent/` is PIN-gated Parent Mode (screen time, activity history, family settings, debug TRIAL_MODE / NON_READER_SIM).
- `trial/` owns local anonymous TrialEventStore summaries (no raw voice, no cloud).
- `narration/` provides package-audio → offline TTS → text-only prompts, plus common Lumi voice pack resolution (`DEV_VOICE` / `FINAL_VOICE` quality markers).
- `ui/` holds FamilyTV visual tokens, LumiView, AdventureWorldStage, and light SceneTransitions (Home 3.0 / flagship adventures).
- `audio/` holds offline FamilySfx (packaged WAV, lazy play).
- `player/` loads playlists/media and plays catalog videos (Media3).
- `admin/` is local Content Admin (debug entry only) including package import/activate/rollback.
- `home/` is the child-facing TV Home launcher, details screen, and content navigation.
- `games/` contains the legacy game hub, Apple Addition, Color Choice and shared game rules.
- `voice/` provides the process-scoped AI runtime and foreground voice leases.
- `voicelab/` owns microphone/VAD/STT/Qwen workers and the retained diagnostic screen.
- Debug-only `app/src/debug/` holds the Accuracy Lab recorder and ASR experiments.
- `MainActivity.kt` is the legacy recorder.
- `ui/theme/` contains Compose themes; current game/home screens use Android Views.

Resources are in `app/src/main/res/`, models in `app/src/main/assets/`, JNI in `app/src/main/cpp/`. Dependency versions belong in `gradle/libs.versions.toml`; module configuration belongs in `app/build.gradle.kts`. Keep generated `build/` directories out of changes.

See `docs/FAMILYTV_BETA.md` for Beta product philosophy and UX.

## Build, Test, and Development Commands
Use the checked-in wrapper. Configure the SDK in local `local.properties`.
The project specifies SDK 37 and Java 25 for the Gradle daemon; Java source compatibility is 11.

- `./gradlew assembleDebug` — build the debug APK.
- `./gradlew :app:installDebug` — install on a connected device.
- `adb shell am start -n ru.familytv/.home.HomeActivity` — open Home.
- `adb shell am start -n ru.familytv/.games.GameHubActivity` — open the legacy game hub.
- `./gradlew :app:lintDebug` — run Android Lint.
- `./gradlew :app:testDebugUnitTest` — run pure game/session tests.
- `./gradlew :app:connectedDebugAndroidTest` — run real-model/device tests on arm64.

## Coding Style & Naming Conventions
Use four-space indentation and Android Studio formatting. Use `PascalCase` for classes/files, `camelCase` for functions/properties, `UPPER_SNAKE_CASE` for constants and `snake_case` for resources. Keep packages under `ru.familytv`. No dedicated formatter is configured.

## Testing & Voice Architecture
JUnit4 JVM tests live in `app/src/test/`; AndroidJUnitRunner tests in `app/src/androidTest/`. Use deterministic seeds and descriptive `*Test` names. No coverage threshold is enforced. See `docs/VOICE_GAMES.md` for device commands and live acceptance.

Reuse `VoiceRuntime`; never load models per Activity/question. Keep inference off the main thread. Never pass questions or expected answers into Qwen: compare normalized transcripts in game rules. Preserve prefix caching and H728 CPU settings. Test cancellation, same-question retries and wrong-answer preservation.

See `docs/PLATFORM_ALPHA.md` for the content catalog, Home, watch progress, and interactive lesson architecture.

## Commit & Pull Request Guidelines
History currently contains `Initial commit`. Use concise imperative subjects, such as `Handle missing USB microphone`. Keep changes focused. PRs should describe behavior, link issues, record tests/device details and include screenshots for UI changes.

## Local Data & Configuration
Keep SDK paths, credentials and recordings out of commits. Store lab corpora in ignored `voice-benchmark/local-*/`. See `docs/VOICE_ACCURACY_LAB.md`; do not promote experimental ASR without measured accuracy gains. Playlists use app-specific external `FamilyTV/program.json`, shaped `{"id":"demo","items":["clip.mp4"]}`, with files in `FamilyTV/media/`. Demo packages live under `content-packages/` (see `docs/CONTENT_PACKAGES.md`).
