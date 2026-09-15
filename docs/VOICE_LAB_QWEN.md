# Voice Lab — Milestone 2

> Milestone 3: model ownership is now process-scoped and shared by games and
> VoiceLab. See [Voice Games](VOICE_GAMES.md) for current lifecycle and launch
> instructions; measurements below remain the historical milestone evidence.

This milestone extends the existing H340 microphone → Silero → Russian STT
pipeline with local Qwen answer extraction. Milestone 1 is accepted as complete
based on the user's verified live test with Logitech H340. The VAD/STT model,
parameters, and audio capture implementation are retained.

## Build and launch

```sh
./gradlew assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n ru.familytv/.voicelab.VoiceLabActivity
adb logcat -s FamilyVoiceLab:I AndroidRuntime:E
```

Requires the configured Android SDK 37 / Gradle Java 25, NDK 29.0.14033849,
CMake 3.22.1, and an arm64 Android device. adb is used only for deployment and
testing; application inference has no command-line process dependency.

## Ownership and execution

`VoiceLabController` retains its capture/VAD worker. Its bounded inference queue
now processes each utterance in order: existing STT, then `QwenAnswerEngine`.
The capture worker continues reading audio during both stages. No native model
operation runs on the main thread.

`QwenAnswerEngine` owns one llama.cpp model/context per Activity lifetime, reused
across utterances and Stop/Start. The existing STT recognizer is likewise reused.
There is no chat history: Milestone 2.1 evaluates the constant 162-token prefix
once during initialization. Each extraction removes sequence-0 positions
`[162, infinity)` with `llama_memory_seq_rm`, retaining the immutable prefix KV.
Only the new transcript/template suffix is tokenized and evaluated. No serialized
state copy or growing conversation is used. On H728, each short request uses
four threads on CPU 4–7; the caller’s affinity is restored afterward. Other SoCs
retain the generic scheduler; unavailable affinity falls back to 8/4 threads. The bounded
512-token context and existing model allocation are retained.

Stop invalidates the session and increments a native cancellation epoch. Old
queued requests and in-flight results cannot update the UI. llama.cpp's abort
callback checks cancellation and a 20-second request deadline. Destruction waits
asynchronously for capture and inference workers before freeing native pointers.

## Native integration

`app/src/main/cpp/family_qwen.cpp` provides a small JNI adapter, built by CMake.
It links the supplied Android `libllama.so`, `libggml.so`, and `libggml-base.so`.
Only CPU backend variants are packaged. GGML selects the supported CPU variant
from `applicationInfo.nativeLibraryDir`; legacy native-library extraction makes
these files available to its loader. No GPU/NPU backend is packaged.

The libraries and matching public headers come from
`/home/siovush/FireflyAI/llama.cpp`; the library hash matches the working local
FireflyVoiceAssistant integration. That project's in-process dynamic-backend
approach was reused without its LLM tools, command logic, or prompt cache.
See `qwen-artifacts.sha256` and `LLAMA_CPP_LICENSE`.

Configuration: CPU only, zero GPU layers, mmap loading, context 512 tokens,
batch 256, microbatch 128, initial prefix threads 8; H728 warm suffix/generation threads 4 on CPU 4–7,
greedy sampling, at most 16 output tokens. UTF-8 crosses JNI as byte arrays,
including generated token fragments, rather than modified-UTF-8 strings.

## Model deployment

The exact supplied GGUF is packaged uncompressed in
`app/src/main/assets/voice-lab/llm/` with a size/version/SHA-256 manifest.
Actual supplied file size: **396,705,472 bytes (378.33 MiB)**.

On first use `QwenModelStore` streams the asset to
`noBackupFilesDir/qwen/<version>/Qwen3-0.6B-Q4_K_M.gguf.partial`, verifies its size
and SHA-256, fsyncs, and renames it atomically. A lock serializes deployment.
Subsequent launches reuse the versioned file after checking its size; they do
not copy or hash the complete model again. Interrupted copies are never loaded.
An APK with a new model version creates a different private path. Old versions
are retained; remove obsolete versions in a later deployment-management feature.
Models are excluded from Android backup by using `noBackupFilesDir`.

## Answer semantics and limits

The short Russian system instruction asks for the child's answer, numbers as
digits, and the final choice after self-correction. It explicitly forbids solving
or correcting. Six short user/assistant demonstrations establish the format;
with the transcript, the tested requests use 176–184 prompt tokens. Smaller
prompts failed self-correction/unknown-answer cases on this 0.6B model.
No answer key or game context is supplied by the engine. Tests
also include a spoken arithmetic question with a wrong answer that must remain 4.

`AnswerPrompt` uses Qwen3 ChatML with an already closed empty think block, the
template's hard non-thinking mode. Transcript template delimiters are escaped.
No generated reasoning is consumed or hidden. Incomplete/token-limited output
and malformed responses are rejected as errors instead of guessed answers.
Only harmless outer whitespace/quotes are removed; there is no arithmetic or
rule-based answer fallback. Input is bounded to 500 characters and checked
against the native context limit.

The model is probabilistic in capability even with deterministic decoding:
passing the fixed cases does not guarantee correct extraction of arbitrary
speech, ambiguity, or prompt-like text. Preserve the STT text for diagnosis.
There is no scoring, educational question logic, TTS, wake word, or cloud AI.

## UI and timing

The existing technical screen shows STT and LLM results separately, their
latencies, prompt/generated token counts, initialization times, and total
speech-end → LLM-result latency. Statuses are IDLE, LISTENING, SPEECH,
DECODING_STT, INFERENCING_LLM, RESULT, and ERROR. It returns to LISTENING after
RESULT while keeping the result visible. Stop and Clear retain loaded models.

STT timing keeps its Milestone 1 definition. LLM latency includes UTF-8 encoding/tokenization, prefill, generation and output
validation; it starts after the small prompt string is constructed. Total
latency includes endpoint silence, audio/VAD processing, queue delay, STT and
LLM, measured against the same VAD-estimated speech boundary as Milestone 1.
Model deployment and native initialization are logged separately. Qwen init now
includes the once-only prefix evaluation. `QWEN_PREFIX` reports that cost; each
`QWEN_PROFILE` reports tokenization, prefix/user evaluation, generation, KV reset,
native total, request number, token counts and queried llama.cpp thread counts.
The screen’s prompt token count includes cached tokens; `prefixEvaluatedTokens`
in the log distinguishes actual repeated work.

## Tests

```sh
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class ru.familytv.voicelab.QwenAnswerInstrumentedTest,ru.familytv.voicelab.VoicePipelineInstrumentedTest \
  ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
```

Qwen tests run 11 cases twice with one engine: all six required cases,
“мне кажется пять”, an arithmetic trap, and three additional correction/color/
unknown-answer cases. They check deployed-file reuse, non-thinking formatting,
output validation, stale-ticket and in-flight cancellation, and context reuse
after native abort.
The original VAD/STT fixture and microphone lifecycle tests remain included.
Actual live speech still needs H340 connected and two spoken phrases without
restarting the Activity; text/fixture tests must not be reported as live results.

Historical Milestone 2 results are recorded in
[VOICE_LAB_QWEN_VALIDATION.md](VOICE_LAB_QWEN_VALIDATION.md). The user subsequently
confirmed live speech works. Current prefix caching, scheduling experiments and
latency results are in [QWEN_LATENCY.md](QWEN_LATENCY.md).
