# Milestone 1 validation — 2026-09-14

## Scope and device

Implemented in `/home/siovush/AndroidStudioProjects/FamilyTV`, package `ru.familytv`.
Device: `192.168.68.109:5555`, reports ADT_3 / ADT-3, Android 14, arm64-v8a,
hardware `sun55iw3p1`, eight ARM Cortex-A55 cores (CPU part `0xd05`). These match
the supplied H728 configuration despite the firmware's model name.

The original `./gradlew assembleDebug` passed before edits. Git status was
attempted before and after edits, but this workspace's `.git` is not a usable
Git repository; no Git diff, commit history, or clean-tree claim is available.
No existing source file was removed. Launcher and player behavior are retained.

## Changes

- Added `voicelab/VoiceLabActivity.kt`, `VoiceLabController.kt`, and `VoiceEngine.kt`
  under `app/src/main/java/ru/familytv/`.
- Added six matching Kotlin JNI binding files under `com/k2fsa/sherpa/onnx/`,
  native arm64 libraries, VAD/STT assets, and `keepRules/sherpa.keep`.
- Registered the isolated Activity in `AndroidManifest.xml`; RECORD_AUDIO
  permission already existed.
- Updated `app/build.gradle.kts` and `gradle/libs.versions.toml` for packaging
  and AndroidJUnitRunner. Added device tests and a test-only Russian WAV asset.
- Added a permission recheck before AudioRecord construction in the existing
  `MainActivity.kt`, fixing its previously reported MissingPermission Lint error.
- Added documentation, artifact hashes, upstream wrapper license, and updated
  `AGENTS.md` to describe the new tests and voice package.

## Measured device results

- Final `./gradlew assembleDebug :app:assembleDebugAndroidTest :app:lintDebug`:
  **BUILD SUCCESSFUL**; Lint reports 0 errors and 55 warnings (including existing
  dependency updates, UI localization warnings and arm64-only packaging).
- `adb install -r -t …/app-debug.apk`: **Success**, existing app data retained.
- Final mandatory instrumentation run: **OK (2 tests)** in **29.285 s**.
- `adb shell am start -n ru.familytv/.voicelab.VoiceLabActivity`: successful;
  the screen was visually inspected on the device at 1920×1080.
- Debug APK: approximately **88 MiB** at
  `app/build/outputs/apk/debug/app-debug.apk`.

The fixture is the supplied model directory's `test_wavs/1.wav`. Streaming VAD
produced segments of 6918 ms and 6886 ms on successive runs with the same native
runtime. Both recognized:

> родион потапыч высчитывал каждый новый вершок углубления и давно определил про себя

Observed STT times across measured runs: **649–739 ms**, RTF approximately
**0.094–0.107**. These are Android JNI results on H728, not desktop measurements.
The final run measured **681 ms** and **649 ms** for the two segments.
The test asserts the exact transcription, silence rejection, natural endpoints
without flushing, and repeatability with the same recognizer.

VAD initialization: **68–207 ms** in instrumented runs (304 ms on the first
standalone launch). STT initialization: approximately **11.3–11.7 seconds** after
setting `modelType = "transducer"`. Auto-detecting the type previously took
approximately 22.5–23.3 seconds because it constructed an additional encoder
session. All initialization runs off the UI thread.

Start/Stop/Start, Clear, and Activity teardown passed on actual AudioRecord.
Logcat confirms the same models remain alive across the three capture sessions
and are released only when the Activity closes. No application crash or ANR
was observed during these runs.

## Acceptance limitation

The optional speaker-to-microphone test played the fixture through the device's
current HDMI output. It timed out after 30 seconds with no VAD speech segment.
Further live diagnostics found **all-zero PCM** (`peakPcm16=0`, `rmsPcm16=0`)
over repeated five-second windows. Android reported recording `silenced:false`
and the correct mono 16000 Hz PCM16 format. Audio policy listed only the
`ble_audio` built-in-microphone port (address `bottom`) and Remote Submix;
no USB microphone was available. Thus the current input route provides no
usable audio. The physical microphone must be connected/activated or its route
corrected before testing live speech. The UI now displays the route, levels,
and an explicit all-zero PCM message.

This does **not** pass the requested live-speech acceptance test. Two consecutive
spoken phrases and speech-end-to-text latency remain unverified. No live
end-to-end latency is claimed from the fixture test.

`audioLevel` log entries report PCM16 peak/RMS every five seconds for diagnosing
silent or low-level microphone input. See `VOICE_LAB.md` for launch/test commands,
timestamp definitions, model ownership, and runtime limits.

Local run artifacts are saved in `app/build/reports/voice-lab/`:
`instrumentation.txt`, `acoustic.txt`, and `device-logcat.txt`.
`screen.png` shows the final screen with the all-zero PCM diagnostic. At handoff
the Activity is open in IDLE and microphone capture has been stopped.

## Next milestone

First finish live speech acceptance on the actual microphone at the intended
distance, then collect repeatability and latency measurements in quiet and TV
noise. Tune capture routing/levels only from that evidence. After VAD/STT is
stable, agree the next interaction milestone; no LLM, wake word, TTS, or game
logic is included here.
