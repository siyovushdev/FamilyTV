# Milestone 2.1 — Qwen latency on H728

> Milestone 3: model ownership is now process-scoped and shared by games and
> VoiceLab. See [Voice Games](VOICE_GAMES.md) for current lifecycle and launch
> instructions; measurements below remain the historical milestone evidence.

## Scope and diagnosis

The user has confirmed the live AudioRecord → VAD → Russian STT → Qwen pipeline.
This investigation changes only Qwen execution and measurement. The accepted
VAD/STT models, parameters and audio capture algorithm remain unchanged.

The previous native adapter preserved the model/context allocation but called
`llama_memory_clear(..., true)` before every request. It tokenized and evaluated
the entire instruction, six examples, new transcript and assistant template.
That is 176–184 tokens for this test set, of which **162 are constant**.
The original user-reported live median was about 8 seconds.

Profiling was added before caching. A controlled no-cache run measured a warm
native median of 2,970 ms: prefix 2,374 ms, new suffix 400 ms, generation 127 ms,
tokenization 2 ms (stage medians do not sum exactly). Under active capture,
request 7 took 11,755 ms: **11,209 ms prefix**, 408 ms suffix, 122 ms generation.
Thus generating one answer token is not the principal bottleneck. A 20-second
prefill timeout was also reproduced in the no-cache capture control.

## Prefix implementation

`AnswerPrompt` separates an immutable `prefix` from a per-utterance `suffix`.
`QwenAnswerEngine` loads its model/context once, then calls JNI `preparePrefix`
before declaring itself ready. The prefix is tokenized/evaluated once.

Before each request the native adapter calls
`llama_memory_seq_rm(llama_get_memory(ctx), 0, prefix_count, -1)`. Sequence-0
positions `[0, 162)` remain intact; all prior user/generated positions are
removed. New tokens start at position 162. No conversation history grows and
no entire-state serialization/copy is needed. Only suffix bytes cross JNI and
only suffix tokens are evaluated. New logits come from the last suffix token.

The APIs and Qwen-compatible KV implementation were checked in the supplied
`include/llama.h`, `src/llama-context.cpp` and `src/llama-kv-cache.cpp`. Partial
removal failure is an error, not permission to continue with stale history.
Cancellation/timeout still abort native decode; subsequent requests remove its
partial tail. Model initialization failures release the native handle.

`QWEN_PREFIX` records the once-only prefill cost. Every successful `QWEN_PROFILE`
records request number, tokenization/prefix/suffix/generation/reset/native-total
milliseconds, logical/prefix/new/evaluated/generated token counts and actual
thread settings queried from llama.cpp. Timers use `steady_clock`; the local
header explicitly discourages third-party reliance on its internal perf helpers.
Tokenization timing includes small request/affinity setup and byte-copy costs;
reset includes KV-tail removal and batch allocation. Generation includes sampling
and decode needed to reach EOG. Native total ends before result-array allocation,
profile logging and affinity restoration. Formatting validation
and JNI return add small overhead to the Kotlin/wall metric. Failed benchmark
requests retain their wall time and error even when no native profile completes.

## Prompt accuracy

The six-example prompt is retained. It corrects obvious truncation (`пят`),
extracts the final answer, preserves unknown/color answers and never receives a
game answer key. A spoken `два плюс три ... мой ответ четыре` must remain `4`.
No rule-based extraction, arithmetic correction, or output lookup was added.

| Cached prompt trial | Constant tokens | Exact answers / 14 |
| --- | ---: | ---: |
| Short instruction, no examples | 54 | 2 |
| Short instruction, two examples | 71 | 7 |
| Original instruction, three examples | 118 | 10 |
| Original instruction, four examples | 133 | 11 |
| Original instruction, six examples | 162 | 14 |

The four-example version returned `7` instead of `8` and `3` instead of `9`
after self-correction. The shorter instruction also produced explanations and
words instead of digits. Reducing the prompt would therefore trade correctness
for size. Its logical token count stays unchanged, but repeated prefill falls
from **176–184 to 14–22 tokens**; prefix tokens evaluated per request become **0**.

## CPU strategy and the remaining long tail

The 8/4 thread settings were real, confirmed by `llama_n_threads_batch` and
`llama_n_threads`. The issue was not an accidentally single-threaded JNI call.
The same cached model with active capture still had occasional 4–12-second
**suffix prefill** stalls. Pinning only generation did not help. Four threads
on CPU 4–7 for the short suffix removed these tails in the measured run.

| Active capture configuration | Warm median | p95 | Maximum | Exact answers |
| --- | ---: | ---: | ---: | ---: |
| No KV cache, default placement, 8/4 | 6,454 ms | 11,757 ms | 20,063 ms timeout | 27/28 |
| KV cache, default placement, 8/4 | 664 ms | 4,120 ms | 6,914 ms | 28/28 |
| KV cache, default placement, repeat | 684 ms | 6,085 ms | 12,061 ms | 28/28 |
| KV cache, prefill CPU 0–7 / generation CPU 4–7, 8/4 | 660 ms | 8,928 ms | 11,113 ms | 28/28 |
| KV cache, suffix + generation CPU 4–7, 4/4 | 668 ms | 788 ms | 791 ms | 28/28 |

These are measured wall times, with the first request excluded from warm
statistics. The no-cache maximum is time to cancellation, not a completed answer.
The comparison localizes two issues: unnecessary constant prefill and unstable
short-prefill execution with eight threads sharing mixed-frequency cores during
capture. It does not isolate thread count from placement as separate causal
factors or claim a particular kernel/GGML bug. The selected combination is tested.

The once-only 162-token prefix still uses eight threads. For normal H728 requests
(`Build.HARDWARE == "sun55iw3p1"`), `AffinityScope` temporarily restricts only the
calling inference thread to CPUs 4–7, and llama.cpp uses four prompt/four generation
threads. The checked local GGML implementation creates its automatic workers for
each decode; they inherit that mask. Scope destruction restores the caller’s
original mask on success and exceptions, preserving subsequent STT scheduling.
Capture/VAD runs on its existing separate worker. No realtime priority, governor,
thermal limit, system affinity or CPU frequency is modified.

Other SoCs retain the original scheduler. If the original allowed mask does not
include all eight target CPUs, or setting prompt affinity fails, native inference falls
back to the existing 8/4 strategy. Applied-affinity flags and actual thread counts
are logged. The engine performs no affinity change on the UI thread.

CPU0–3 maximum frequency is 1.008 GHz; CPU4–7 maximum is 1.8 GHz. Both clusters use
`schedutil`. Sampling observed 408–1,008 MHz / 408–1,800 MHz across idle and active
periods. Temperatures rose during sustained no-cache capture toward 70°C; thermal
trip points are 70/90/115°C. Some late throttling is possible, but it does not
explain the earlier 11.76-second request at approximately 61–64°C. Fast-cluster
frequency was 1.8 GHz at that request’s boundaries. The four-fast-core run also
remained fast at roughly 65–68°C. Sampling cannot rule out sub-second changes.

A system snapshot showed ~2.25 GB available memory, zero recent memory-pressure
averages, and CPU pressure around 9–11%. There is no evidence here of memory
exhaustion causing the long tail. Worker policy was `SCHED_OTHER`, nice 0; no
realtime-priority workaround was needed. Raw CPU/frequency/thermal readings are
retained with the benchmarks rather than presented as a thermal-throttling proof.

## Final installed-APK acceptance run

The final production default was tested with **56 consecutive requests** (four
passes of the 14-case corpus), active AudioRecord/VAD and STT loaded, using one
native engine. All **56/56 exact answers passed**, including truncation,
self-correction, unknown answers, colors and the wrong-answer arithmetic trap.
The test completed in 54.801 seconds. There were no timeouts or native reloads
between requests, and no observed crash/ANR/OOM.

| Final measurement | Milliseconds |
| --- | ---: |
| First request after models ready, wall | 791.651 |
| Warm median, 55 requests | 671.424 |
| Warm p95, nearest rank | 767.704 |
| Warm maximum | 796.678 |
| Warm native total median | 669.724 |
| Warm tokenization/setup median | 0.359 |
| Warm constant-prefix evaluation | 0 |
| Warm new-suffix evaluation median | 485.228 |
| Warm generation median | 130.363 |

Per request: **0 prefix tokens re-evaluated**, **14–22 new tokens evaluated**,
**1–2 answer tokens generated**. Logical context remains 176–184 tokens.
Compared with the active-capture no-cache control, warm median improved from
6,454 to 671 ms (about 9.6×), and p95 from 11,757 to 768 ms. The preferred
sub-second warm target is met in this measured run; this is not a guarantee
for every future workload, transcript length or device temperature.

Cold initialization is not hidden in these request numbers. In this final run,
model/context plus once-only prefix initialization took **13,487 ms**, including
**8,148 ms** prefix evaluation while STT initialized concurrently. Existing-file
deployment took 74 ms. STT initialization took 13,928 ms. The first answer then
took 792 ms **after readiness**. An invocation that includes loading Qwen must
therefore budget roughly 14.3 seconds for Qwen init plus first answer, in addition
to other startup coordination. Startup remains a separate optimization opportunity;
these costs are not paid on subsequent utterances or Stop/Start.

Final sampled CPU temperatures were 56.1–63.8°C / 56.7–66.7°C; the earlier
four-fast-core comparison was also stable near 65–68°C. No system frequency or
thermal settings were changed. See `final-56.json`, `final-56-log.txt` and
`final-56-tests.txt` for the full phase/sensor records.

All 56 wall latencies (ms) are reproduced below; only the first cell is cold.

| STT text | Pass 1 | Pass 2 | Pass 3 | Pass 4 |
| --- | ---: | ---: | ---: | ---: |
| пят | 791.7 | 554.1 | 551.8 | 584.9 |
| мне кажется пят | 581.7 | 606.9 | 581.1 | 566.7 |
| не пят а шесть | 610.4 | 615.7 | 607.0 | 614.6 |
| сначала семь нет восемь | 687.1 | 690.0 | 692.6 | 678.7 |
| не знаю | 703.4 | 667.8 | 716.1 | 671.4 |
| я выбираю синий | 771.3 | 764.1 | 764.0 | 767.7 |
| четыре | 599.3 | 597.0 | 588.0 | 579.5 |
| сколько будет два плюс три мой ответ четыре | 754.1 | 751.1 | 760.0 | 796.7 |
| думаю это пять | 651.6 | 636.8 | 646.3 | 644.8 |
| не пять а шесть | 635.3 | 675.9 | 645.3 | 644.4 |
| мне кажется пять | 612.1 | 606.6 | 608.8 | 604.3 |
| сначала три нет девять | 686.5 | 678.4 | 679.6 | 678.2 |
| я не знаю | 700.5 | 705.6 | 742.5 | 695.4 |
| я выбираю красный | 733.5 | 733.9 | 760.8 | 733.3 |

## Reproduction and interpretation

Build/install both debug APKs, then run a real-model device benchmark:

```sh
./gradlew assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r \
  -e class ru.familytv.voicelab.QwenLatencyInstrumentedTest \
  -e withCapture true \
  ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -v epoch -s FamilyVoiceLab:I AndroidRuntime:E > run-log.txt
python3 tools/summarize_qwen_benchmark.py run-log.txt
```

Each benchmark runs 14 distinct cases twice, in order, with one native model.
`withCapture=true` submits requests to the actual VoiceLab inference executor
while AudioRecord/VAD run and STT stays loaded. Input to Qwen is controlled text,
not fabricated live speech or replayed PCM; STT decoding does not overlap Qwen
because the production queue is sequential. The current microphone route yielded
zero PCM during capture tests. No assertion about new live phrase latency is made.

Test-only arguments include `cachePrefix=false` (original clearing behavior),
`promptCandidate=short|correction|three|four`, `passes=1`, and `cpuStrategy=0|1|2`.
Do not speak during controlled runs. The benchmark uses reflection to access the
existing worker/engine; no product UI switch or inference bypass was added.

“Cold request” means the first request after model/prefix initialization in a
fresh process, with the already deployed model and OS file cache potentially warm.
Initialization is reported separately. Warm statistics exclude that first
request; p95 uses nearest rank `ceil(0.95*N)`. Timeout wall time stays in the
no-cache distribution and is not counted as a correct answer.

## Changed files and regression checks

- `app/src/main/cpp/family_qwen.cpp`: prefix KV retention, phase profiling,
  scoped CPU affinity, benchmark CPU controls and actual thread-count logging.
- `voicelab/llm/AnswerPrompt.kt`, `NativeQwen.kt`, `QwenAnswerEngine.kt`: prefix/
  suffix API, once-only initialization, H728 CPU policy and failure cleanup.
- `QwenLatencyInstrumentedTest.kt`: real-device controlled corpus, repeatable
  cache/prompt/CPU comparisons, optional active capture and sensor logging.
- `QwenAnswerInstrumentedTest.kt`: caller-affinity restoration checks in addition
  to existing real-model accuracy/cancellation/context-reuse checks.
- `tools/summarize_qwen_benchmark.py`, this report, `VOICE_LAB_QWEN.md` and
  `qwen-artifacts.sha256`: reproducible statistics, usage and updated provenance.

The final APK and test APK built and installed successfully. Android Lint had
zero errors and 61 warnings. JVM test task has no source tests. All four existing
instrumentation tests passed in 55.796 seconds, including cancelled-prefill
recovery and affinity restoration; no crash/ANR/OOM was observed. The original
`VoiceEngine.kt` SHA-256 remains
`adb68cb4817fdbc0876220ac5d7771cbe35f7c4e9f84bb8354a842aad888ccf9`.
No VAD/STT/audio capture code or model file was changed.

Raw logs, per-request JSON, thermal/frequency sampling and test output are under
`app/build/reports/qwen-latency/`. Experimental prompt tests intentionally failed;
they are not the selected production configuration. This milestone adds no games,
scoring, TTS, wake word or other Milestone 3 functionality.
