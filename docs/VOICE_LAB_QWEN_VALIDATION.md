# Milestone 2 — H728 validation, 2026-09-14

## Scope and device

Milestone 1 remains accepted from the user's live Logitech H340 test. Its
`VoiceEngine.kt`, model files, capture parameters and VAD/STT algorithms were
retained. Milestone 2 adds sequential STT → Qwen processing on the inference
worker while AudioRecord/VAD continue on the capture worker.

Target: H728, reported Android model ADT-3, Android 14, sun55iw3p1, ARM64.
Testing used the actual CPU JNI runtime and packaged GGUF on the device.
No external inference process, cloud service, GPU or NPU was used.

## Implementation and changed files

- `app/src/main/java/ru/familytv/voicelab/llm/`: persistent Qwen engine, JNI
  declarations, versioned private model deployment and deterministic prompt.
- `app/src/main/cpp/`: minimal JNI adapter, CMake and matching llama.cpp/GGML
  public headers from the supplied local build.
- `app/src/main/jniLibs/arm64-v8a/`: supplied llama/GGML libraries and CPU variants.
- `app/src/main/assets/voice-lab/llm/`: supplied GGUF and integrity manifest.
- `VoiceLabController.kt`, `VoiceLabActivity.kt`: LLM stage, session cancellation,
  separate results/statuses and latency/token metrics on the existing screen.
- `app/build.gradle.kts`, `app/src/main/keepRules/qwen.keep`: native build,
  uncompressed GGUF, extracted backend libraries and JNI retention.
- `app/src/androidTest/`: real-model answer tests and retained VAD/STT/lifecycle
  coverage; initialization wait accommodates both models.
- `AGENTS.md`, `docs/VOICE_LAB.md`, `docs/VOICE_LAB_QWEN.md`, this report,
  `docs/qwen-artifacts.sha256`, `docs/LLAMA_CPP_LICENSE`: contributor guidance,
  operation, provenance and validation.

The engine retains model/context until Activity destruction; Stop/Start does
not reload it. Each request clears previous KV contents. Settings are CPU-only,
8 prefill / 4 generation threads, context 512, greedy sampling, 16-token cap.
Qwen's non-thinking ChatML prefix closes an empty think block before generation.
The short system instruction plus six format demonstrations produces about
180 prompt tokens. Smaller prompts failed correction/unknown-answer cases;
no rule-based answer mapping or mathematical correction was substituted.

The 396,705,472-byte model is copied once from assets into versioned
`noBackupFilesDir`, SHA-256 checked, fsynced and atomically renamed. Later launches
reuse the existing version/size. Initial deployment measured 4,343 ms; reuse
requires only milliseconds. Source and packaged artifact checksums are recorded.

## Build and device results

`./gradlew assembleDebug :app:assembleDebugAndroidTest :app:lintDebug` passed.
Android Lint reported 61 warnings and no errors. Both APKs installed successfully;
VoiceLab launched and displayed all metrics without clipping at 1920×1080.
The final instrumentation run completed in 122.127 seconds: **OK (4 tests)**.
No crash, ANR or OOM was observed in this run.

- All 11 Qwen cases passed twice using one engine (22/22 exact outputs).
- A stale cancelled ticket was rejected; an active prefill was cancelled after
  100 ms, and the same native context successfully answered again afterward.
- Model-file reuse preserved its modification time.
- The unchanged VAD/Russian STT runtime decoded the WAV fixture twice, identically.
- Activity microphone Start/Stop/restart/Clear/background checks passed.

The following are **text → actual Qwen on H728**, not live microphone results.

| Input | Output, both passes | LLM latency pass 1 / 2 (ms) |
| --- | --- | --- |
| четыре | 4 | 2697 / 2693 |
| думаю это пять | 5 | 2745 / 2757 |
| не пять а шесть | 6 | 10181 / 2755 |
| сначала семь нет восемь | 8 | 2819 / 2827 |
| не знаю | не знаю | 3343 / 2809 |
| я выбираю синий | синий | 2887 / 2872 |
| мне кажется пять | 5 | 2758 / 2777 |
| сколько будет два плюс три мой ответ четыре | 4 | 2820 / 3004 |
| сначала три нет девять | 9 | 2769 / 2781 |
| я не знаю | не знаю | 12188 / 4844 |
| я выбираю красный | красный | 2979 / 2869 |

For 22 requests: median **2,819.5 ms**, mean **3,689.7 ms**, range
**2,693–12,188 ms**. Two slow requests exceeded 10 seconds; their cause has not
been isolated. Prompt tokens: 176–184; generated answer tokens: 1–2.

Final foreground app model initialization: VAD **244 ms**, STT **11,911 ms**,
Qwen **5,175 ms**. Qwen test-engine initialization: **5,000 ms**. Repeated requests
and Stop/Start did not reinitialize models.

STT fixture latency was **676 / 645 ms**, for VAD segments **6,918 / 6,886 ms**.
These are recorded-file measurements, not the child's live phrase latency.
**Live speech-end → final LLM latency is unavailable** without the H340 test.

After launching the final app with both models ready, `dumpsys meminfo` reported
**TOTAL PSS 1,063,419 KB (~1,038 MiB)** and **TOTAL RSS 1,151,848 KB (~1,125 MiB)**.
Native heap PSS was 574,396 KB. This is a single idle snapshot, not peak memory
or a guarantee of long-session stability.

Raw evidence is under `app/build/reports/voice-lab-qwen/`: `instrumentation.txt`,
`device-logcat.txt`, `final-combined-memory.txt`, `audio-devices.txt`, and
`final-screen.png`. Earlier `prompt-probe*` files contain rejected prompt trials;
only the final instrumentation run above is the acceptance test suite.

## Live acceptance boundary

**Two consecutive live utterances have not been verified in this session.**
Android's audio-device log records H340 being disconnected at 13:38:35; subsequent
capture uses the ADT-3 built-in route (type 15, address bottom). The final
foreground capture check recorded peak=0 and RMS=0 in repeated 5-second windows;
see `live-input-check.png`. Listening was stopped after this check. The user was asked to reconnect H340 and
speak the two phrases. Text-input Qwen tests and WAV VAD/STT tests are explicitly
not live end-to-end acceptance, and no total live latency is inferred from them.

Before Milestone 3, connect H340, open VoiceLab, say “мне кажется пять”, pause,
then say “сначала семь нет восемь” in the same listening session. Check STT,
LLM `5` / `8`, automatic return to LISTENING, per-stage and total timings,
and absence of extra model initialization. Also characterize latency spikes and
memory under sustained live capture. The 0.6B extractor remains a prototype:
passing these cases does not guarantee arbitrary language accuracy. The APK is
large, model copies consume private storage, and obsolete model versions are
not yet pruned.
