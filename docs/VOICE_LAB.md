# FamilyTV Voice Lab — Milestone 1

> Milestone 3: model ownership is now process-scoped and shared by games and
> VoiceLab. See [Voice Games](VOICE_GAMES.md) for current lifecycle and launch
> instructions; measurements below remain the historical milestone evidence.

For the current STT → Qwen extension, see [Milestone 2](VOICE_LAB_QWEN.md).
Milestone 1 was subsequently confirmed working with Logitech H340 by the user.

## Build and launch

Use the repository Gradle wrapper, Android SDK 37 and the configured Java 25
Gradle toolchain. This prototype packages native libraries for **arm64-v8a only**.

```sh
./gradlew assembleDebug :app:lintDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ru.familytv/.voicelab.VoiceLabActivity
adb logcat -s FamilyVoiceLab:I AndroidRuntime:E
```

Select **Start listening**, grant microphone access if requested, and say
«один два три четыре пять». Pause for at least 0.5 seconds. The result stays on
screen while the system returns to LISTENING. Try a second phrase without
restarting. **Stop** discards unfinished speech and queued results; **Clear**
clears displayed recognition metrics without reloading models. The existing
FamilyTV launcher still opens `MainActivity`.

## Architecture

- `VoiceLabActivity`: isolated Android Views screen, permission request, metrics,
  TV remote focus, and lifecycle ownership. Leaving the screen stops recording;
  destroying the Activity releases models asynchronously.
- `VoiceLabController`: one capture worker for AudioRecord and VAD; one STT
  worker with a bounded queue of two waiting utterances. Capture continues while
  STT runs. Session identifiers reject stale results after Stop. Native resources
  are released only after both workers finish; the UI never joins a worker.
- `VoiceEngine`: one Silero instance and one offline recognizer per Activity
  lifetime. Each utterance gets only a temporary recognizer stream, released in
  `finally`. Stop/Start reuses both models. Activity recreation loads them again.

Capture uses MIC, 16000 Hz, mono PCM16, accumulated into complete 512-sample
frames and converted to floats by dividing by 32768. Silero uses CPU, one thread,
threshold 0.5, minimum speech 0.25 s and silence 0.5 s. STT uses the supplied
Russian small Zipformer INT8 transducer, CPU, four threads, and `greedy_search`.
Only completed VAD segments reach STT; no console executable is invoked.

## Models and native API

The checked-in models live in `app/src/main/assets/voice-lab/`; ONNX files are
uncompressed APK assets. JNI supports `AssetManager` directly, so there is no
first-run extraction, repeated copying, external model directory, or use of
`/data/local/tmp`. Installing an updated APK updates its models atomically.

`app/src/main/jniLibs/arm64-v8a/` contains the supplied `libonnxruntime.so` and
`libsherpa-onnx-jni.so`. `docs/voice-lab-artifacts.sha256` records their exact hashes
and model hashes. Kotlin JNI bindings were taken from the working local
FireflyVoiceAssistant integration and match the supplied sherpa source. Unused
model configuration fields remain because native JNI reads this full schema;
they do not load or integrate Qwen, an LLM, TTS, or any other pipeline.

See `SHERPA_ONNX_LICENSE` for the upstream wrapper license and the
[upstream Android documentation](https://k2-fsa.github.io/sherpa/onnx/android/index.html).
When replacing native libraries, update their matching bindings and rerun device
tests. Preserve upstream model/library licensing when distributing the APK.

## Metrics and limits

Logcat records model initialization once per runtime, `speechStart`, `speechEnd`,
`speechDurationMs`, `sttStart`, `sttEnd`, `sttLatencyMs`, and `recognizedText`.
The UI retains the latest result and initialization times.
It also shows the microphone route and PCM16 peak/RMS, updated every five seconds.
All-zero PCM is explicitly identified; check microphone connection/activation
before interpreting this condition as a VAD failure.

`sttLatencyMs` includes stream creation, feature extraction, decoding and result
retrieval. `endpointToTextMs` additionally includes decoder queue delay.
`endToTextMs` starts at the last sample of the VAD segment, mapped to the
monotonic clock using AudioRecord's hardware timestamp when available; otherwise
it is an estimate from sample counts. It includes endpoint silence, capture/VAD
processing, queue delay, and STT. This is a VAD-estimated speech boundary, not an
independently measured human speech ending. Segment duration includes Silero's
small boundary padding.

Continuous speech beyond approximately 25 seconds, an overloaded decoder queue,
or three seconds without audio data produce an explicit ERROR and stop capture.
The user can restart without reloading models. Listening is foreground-only.
Initialization failure requires reopening the Activity. Clear may be followed
by a new result if recognition was already in progress.

## Device tests

```sh
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class ru.familytv.voicelab.VoicePipelineInstrumentedTest \
  ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
```

Grant microphone permission through the screen before running lifecycle tests.
The WAV fixture is the supplied model's `test_wavs/1.wav` and is packaged only
in the test APK. Automated fixture tests exercise real JNI VAD/STT, silence,
natural endpoints (without `flush()`), and recognizer reuse. Microphone tests
exercise actual capture start/stop/restart, Clear, and Activity teardown.
Fixture recognition alone does **not** prove live acoustic speech recognition;
the final acceptance check must include actual speech entering the microphone.

For an optional acoustic test, add `-e acoustic true` to the instrumentation
command. It plays the fixture twice through the current media output while
VoiceLabActivity captures the real microphone, then requires Russian text and
a return to LISTENING after each phrase. It does not change system volume.
This test needs an audible speaker close enough to the microphone; HDMI output
without an audible TV speaker will time out.
