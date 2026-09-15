# Voice Games — Milestone 3

## Architecture and ownership

`games/GameHubActivity` is the launcher. `MathVoiceGameActivity` and
`ColorVoiceGameActivity` share `BaseVoiceGameActivity`, pure `GameSession` rules
and `VoiceGameSessionController`. Child screens contain no model/JNI calls.

`voice/VoiceRuntime` is a lazy process singleton retaining only application
context. Entering the hub or VoiceLab prewarms **one** `VoiceLabController`,
whose existing capture and inference workers load VAD/STT and Qwen off the main
thread. Readiness is `AI_INITIALIZING → AI_READY`, or `AI_ERROR` with retry.
The hub disables game cards during preparation. Cold loading remains about
14 seconds; moving between games reuses the loaded models and prefix cache.

Each screen receives a lightweight `VoiceAnswerPipeline` lease implementing
`VoiceAnswerSource` with `StateFlow`. Only one foreground lease owns capture.
Acquiring another lease cancels the previous generation; late native results
and queued UI snapshots cannot answer the next question. Pause/Back releases
capture, cancels inference and discards unfinished speech. Models remain loaded
until process death, deliberately trading retained memory for instant game
switches. There are no Activity references in the singleton. Initialization
retry first closes both old native owners on their workers, then creates the
replacement; it never overlaps model stacks.

`voicelab/VoiceLabController` remains the shared backend. Games use one utterance
per listening session: after a VAD endpoint, recording stops while STT/Qwen
finish. VoiceLab retains continuous diagnostic listening. Native inference,
model assets, STT/VAD settings, Qwen prompt, cached prefix and CPU strategy are
unchanged from Milestone 2.1. Capture stopping during initialization does not
clear the queued model-loading task.

## State flow and rules

Voice: `INITIALIZING → READY → LISTENING → SPEECH → STT_PROCESSING →
LLM_PROCESSING → RESULT`; explicit `ERROR` and `STOPPED` paths.

Game: `LOADING → QUESTION → LISTENING → SPEECH → PROCESSING →
CORRECT / WRONG / NOT_UNDERSTOOD`. Feedback lasts 1.5 seconds. Correct advances;
wrong/unknown retains the same question. Pause cancels the feedback timer.
Current questions survive Activity recreation; question IDs increase.

* **Apple Addition:** two groups of local vector apples, each 1–5; sum at most
  10. Only an entire normalized integer string 0–10 is accepted.
* **Color Choice:** three distinct circles from красный, синий, зелёный, жёлтый.
  A dark outline and arrow identify the target. Only the four canonical strings
  are accepted; another recognized color is wrong, other text is unknown.

`expectedAnswer` exists only in game rules and telemetry. The voice interface
has no question/answer-key argument; Qwen receives only the STT transcript.
The real-model regression sends “сколько будет два плюс три мой ответ четыре”,
asserts Qwen returns `4`, then asserts the 2+3 game verdict is `WRONG`.
Seeded JVM tests cover both games and shared retry/advance/cancellation behavior.

## Errors and hardware

Games on H728 (`sun55iw3p1`) require an available USB microphone; VoiceLab keeps
its original generic input behavior for diagnostics. Missing/removed USB input
stops the session and shows “Микрофон не подключён”. Reconnect the H340 and press
“Ещё раз”; the question is retained. Android's existing MIC routing is retained;
inspect `FamilyVoiceLab listening ... route=` to confirm the actual device.
Other devices can use their normal microphone.

Permission denial, capture timeout, STT failure, Qwen failure and initialization
failure show child-safe errors with manual retry; no fabricated answer is
submitted. Permanent permission denial opens app settings. Back/background
never keeps recording. A disconnected/zero-signal built-in `ble_audio` route
is not a substitute for the USB H340. Silence produces no answer.

## Launch and validation commands

From the repository root, with the SDK configured in `local.properties`:

```sh
./gradlew assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am start -n ru.familytv/.games.GameHubActivity
adb shell am start -n ru.familytv/.games.MathVoiceGameActivity
adb shell am start -n ru.familytv/.games.ColorVoiceGameActivity
adb shell am start -n ru.familytv/.voicelab.VoiceLabActivity
adb shell am instrument -w -e class ru.familytv.games.VoiceGamesInstrumentedTest ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class ru.familytv.voicelab.QwenAnswerInstrumentedTest ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class ru.familytv.voicelab.VoicePipelineInstrumentedTest ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class ru.familytv.voicelab.QwenLatencyInstrumentedTest -e withCapture true -e passes 2 ru.familytv.test/androidx.test.runner.AndroidJUnitRunner
```

Use `adb -s DEVICE` if more than one device is attached. Instrumentation is
serial: standalone model tests release an idle shared runtime first. The H728
benchmark enforces warm median <1200 ms and p95 <1500 ms, independently of UX.
The original 162-token prefix, four warm threads and CPU4–7 strategy remain.

VoiceLab is also accessible by long-pressing “FamilyTV” on a **debug** hub.
Legacy `.MainActivity` remains available explicitly. Production code uses no
shell commands and introduces no network service.

Logs: `FamilyVoiceGame` records runtime initialization/release and benchmark;
`FamilyMathGame` / `FamilyColorGame` record game, questionId, speechDurationMs,
endpointDelayMs, sttLatencyMs, sttText, llmLatencyMs, normalizedAnswer,
expectedAnswer, correct and totalPostSpeechLatencyMs. These logs may contain
speech text; keep personal recordings and logs out of commits.

## Scope and remaining live acceptance

This prototype has no TTS, scoring persistence, profiles, wake word or cloud.
Questions are random and may repeat; a restored question is preserved, but the
entire random-generator history is not persisted. Long/free-form answers may
be rejected by the bounded normalizer. Canonical color spelling includes `ё`.

UI screenshots labeled `question`, `correct` and `wrong` are controlled
presentation states from instrumentation, **not evidence of live speech**.
Real-model text tests and prerecorded STT tests likewise do not establish live
child microphone accuracy. The validation report below records actual results.

## Five-minute live acceptance tomorrow

1. **0:00–0:45:** connect Logitech H340; open FamilyTV. Wait for preparation once.
   Enter Math; confirm “Я слушаю...” and the H340 route in diagnostic logs.
2. **0:45–2:00:** count the apples. Say a deliberately wrong number, then
   “не знаю”: both must keep the same apples. Say the correct number; after
   “Правильно!” and 1.5 seconds a new task appears. Try truncated “пят” on a
   suitable five-answer question. Observe roughly 1.5–1.9 s after speech ends.
3. **2:00–3:00:** Back → Colors: no model-loading wait. Name a different color,
   then the outlined circle's color. Wrong keeps the target; correct advances.
   Try “я выбираю синий” when the blue circle is outlined.
4. **3:00–4:00:** start speaking and press Back. Return: no stale answer or
   crash. Disconnect H340 while listening; confirm the safe error. Reconnect
   and press “Ещё раз”; the same question resumes.
5. **4:00–5:00:** open VoiceLab through debug long-press, verify a real phrase
   and route/latency. Return to the hub. If any failure occurs, save logcat
   with the game and VoiceLab tags plus the spoken phrase and displayed task.

## Recorded validation

See [Milestone 3 delivery report](VOICE_GAMES_VALIDATION.md) for the complete
15-point report, screenshots, real device results, benchmark and memory figures.
All 7 JVM and 7 instrumented tests passed. The expanded 40-answer benchmark was
exact, with warm median 693 ms and p95 1120 ms. H340 was disconnected; physical
reconnection and live speech remain the acceptance checklist above.
