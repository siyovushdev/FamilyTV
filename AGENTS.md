# Repository Guidelines

## Project Structure & Module Organization
FamilyTV is a single-module Kotlin Android app (`:app`), supporting API 28+.
Source lives in `app/src/main/java/ru/familytv/`:
- `games/` contains the launcher hub, Apple Addition, Color Choice and shared game rules.
- `voice/` provides the process-scoped AI runtime and foreground voice leases.
- `voicelab/` owns microphone/VAD/STT/Qwen workers and the retained diagnostic screen.
- `player/` loads playlists and local Media3 media; `MainActivity.kt` is the legacy recorder.
- `ui/theme/` contains Compose themes; current game screens use Android Views.

Resources are in `app/src/main/res/`, models in `app/src/main/assets/`, JNI in `app/src/main/cpp/`. Dependency versions belong in `gradle/libs.versions.toml`; module configuration belongs in `app/build.gradle.kts`. Keep generated `build/` directories out of changes.

## Build, Test, and Development Commands
Use the checked-in wrapper. Configure the SDK in local `local.properties`.
The project specifies SDK 37 and Java 25 for the Gradle daemon; Java source compatibility is 11.

- `./gradlew assembleDebug` — build the debug APK.
- `./gradlew :app:installDebug` — install on a connected device.
- `adb shell am start -n ru.familytv/.games.GameHubActivity` — open the hub.
- `./gradlew :app:lintDebug` — run Android Lint.
- `./gradlew :app:testDebugUnitTest` — run pure game/session tests.
- `./gradlew :app:connectedDebugAndroidTest` — run real-model/device tests on arm64.

## Coding Style & Naming Conventions
Use four-space indentation and Android Studio formatting. Use `PascalCase` for classes/files, `camelCase` for functions/properties, `UPPER_SNAKE_CASE` for constants and `snake_case` for resources. Keep packages under `ru.familytv`. No dedicated formatter is configured.

## Testing & Voice Architecture
JUnit4 JVM tests live in `app/src/test/`; AndroidJUnitRunner tests in `app/src/androidTest/`. Use deterministic seeds and descriptive `*Test` names. No coverage threshold is enforced. See `docs/VOICE_GAMES.md` for device commands and live acceptance.

Reuse `VoiceRuntime`; never load models per Activity/question. Keep inference off the main thread. Never pass questions or expected answers into Qwen: compare normalized transcripts in game rules. Preserve prefix caching and H728 CPU settings. Test cancellation, same-question retries and wrong-answer preservation.

## Commit & Pull Request Guidelines
Git history is unavailable here. Use concise imperative subjects, such as `Handle missing USB microphone`. Keep changes focused. PRs should describe behavior, link issues, record tests/device details and include screenshots for UI changes.

## Local Data & Configuration
Keep SDK paths, credentials, recordings and personal speech logs out of commits. Playlists use app-specific external `FamilyTV/program.json`, shaped `{"id":"demo","items":["clip.mp4"]}`, with files in `FamilyTV/media/`.
