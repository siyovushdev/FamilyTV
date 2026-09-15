package ru.familytv.voicelab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import ru.familytv.voicelab.llm.QwenAnswerEngine
import ru.familytv.voice.VoiceProblem
import ru.familytv.voicelab.VoiceEngine.Companion.SAMPLE_RATE
import ru.familytv.voicelab.VoiceEngine.Companion.TAG
import ru.familytv.voicelab.VoiceEngine.Companion.WINDOW_SIZE
import ru.familytv.voicelab.VoiceEngine.Companion.elapsedMs
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal enum class VoiceStatus { IDLE, LISTENING, SPEECH, DECODING_STT, INFERENCING_LLM, RESULT, ERROR }

internal data class VoiceLabState(
    val session: Long = 0,
    val problem: VoiceProblem? = null,
    val endpointDelayMs: Long? = null,
    val status: VoiceStatus = VoiceStatus.IDLE,
    val active: Boolean = false,
    val captureBusy: Boolean = false,
    val ready: Boolean = false,
    val detail: String = "Инициализация моделей…",
    val vadStatus: String = "Ожидание",
    val text: String = "",
    val speechDurationMs: Long? = null,
    val sttLatencyMs: Long? = null,
    val endToTextMs: Long? = null,
    val endpointToTextMs: Long? = null,
    val timestampSource: String = "",
    val microphone: String = "—",
    val peakPcm16: Int? = null,
    val rmsPcm16: Int? = null,
    val vadInitMs: Long? = null,
    val sttInitMs: Long? = null,
    val llmInitMs: Long? = null,
    val llmText: String = "",
    val llmLatencyMs: Long? = null,
    val promptTokens: Long? = null,
    val generatedTokens: Long? = null,
    val totalPostSpeechLatencyMs: Long? = null,
    val utterance: Long = 0
)

/** No native call or blocking recorder operation executes on the main thread. */
internal class VoiceLabController(context: Context, private val render: (VoiceLabState) -> Unit) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val capture = Executors.newSingleThreadExecutor { Thread(it, "FamilyVoiceCapture") }
    private val decoder = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(2),
        { Thread(it, "FamilyVoiceInference") }, ThreadPoolExecutor.AbortPolicy()
    )
    private var state = VoiceLabState()
    private var engine: VoiceEngine? = null
    @Volatile private var qwen: QwenAnswerEngine? = null
    private var generation = 0L
    private var nextUtterance = 0L
    private var speechDetected = false
    private var pendingDecodes = 0
    @Volatile private var closed = false

    init {
        publish()
        capture.execute {
            try {
                val loaded = VoiceEngine(app.assets)
                engine = loaded
                update { copy(ready = llmInitMs != null,
                    detail = if (llmInitMs != null) "Готово" else "Загрузка Qwen…", vadInitMs = loaded.vadInitMs,
                    sttInitMs = loaded.sttInitMs) }
            } catch (error: Exception) {
                failInitialization(error)
            } catch (error: LinkageError) {
                failInitialization(error)
            }
        }
        decoder.execute {
            try {
                val loaded = QwenAnswerEngine(app)
                qwen = loaded
                update { copy(ready = sttInitMs != null, llmInitMs = loaded.initMs,
                    detail = if (sttInitMs != null) "Готово" else "Загрузка VAD/STT…") }
            } catch (error: Exception) {
                failInitialization(error)
            } catch (error: LinkageError) {
                failInitialization(error)
            }
        }
    }

    private fun failInitialization(error: Throwable) {
        Log.e(TAG, "initializationFailed", error)
        update { copy(status = VoiceStatus.ERROR, problem = VoiceProblem.INITIALIZATION, detail = "Модели не загружены: ${error.message}") }
    }

    val currentSession: Long get() = synchronized(lock) { generation }
    val currentState: VoiceLabState get() = synchronized(lock) { state }

    fun start(singleAnswer: Boolean = false, requireUsb: Boolean = false) {
        synchronized(lock) {
            if (closed || !state.ready || state.active || state.captureBusy) return
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                permissionDenied()
                return
            }
            val session = ++generation
            pendingDecodes = 0
            speechDetected = false
            update { copy(active = true, captureBusy = true, problem = null, status = VoiceStatus.LISTENING,
                detail = "Запуск микрофона…", vadStatus = "Ожидание речи",
                peakPcm16 = null, rmsPcm16 = null) }
            capture.execute { record(session, singleAnswer, requireUsb) }
        }
    }

    fun stop() {
        synchronized(lock) {
            if (closed) return
            generation++
            qwen?.cancel()
            if (state.ready) decoder.queue.clear()
            pendingDecodes = 0
            speechDetected = false
            update { copy(active = false, status = VoiceStatus.IDLE,
                problem = problem.takeIf { it == VoiceProblem.INITIALIZATION },
                detail = if (captureBusy) "Остановка…" else "Остановлено",
                vadStatus = "Остановлен; незавершённая фраза отброшена") }
            // READ_NON_BLOCKING + a 5 ms worker sleep bounds the capture stop delay.
            // Never race AudioRecord.stop/release against read on another thread.
        }
    }

    fun clear() = update { copy(text = "", speechDurationMs = null, sttLatencyMs = null,
        endToTextMs = null, endpointToTextMs = null, timestampSource = "", utterance = 0,
        llmText = "", llmLatencyMs = null, promptTokens = null, generatedTokens = null,
        totalPostSpeechLatencyMs = null,
        status = if (status == VoiceStatus.RESULT) when {
            speechDetected -> VoiceStatus.SPEECH
            pendingDecodes > 0 -> VoiceStatus.DECODING_STT
            active -> VoiceStatus.LISTENING
            else -> VoiceStatus.IDLE
        } else status) }

    fun permissionDenied() = update { copy(status = VoiceStatus.ERROR, problem = VoiceProblem.PERMISSION,
        detail = "Нет разрешения на микрофон. Разрешите его в настройках приложения и нажмите Start listening.") }

    private fun current(session: Long) = synchronized(lock) {
        !closed && state.active && generation == session
    }

    private fun record(session: Long, singleAnswer: Boolean, requireUsb: Boolean) {
        var recorder: AudioRecord? = null
        val runtime = checkNotNull(engine)
        try {
            if (!current(session)) return
            if (requireUsb) {
                val inputs = app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_INPUTS)
                check(inputs.any { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }) {
                    "USB microphone missing"
                }
            }
            runtime.vad.reset()
            val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "Неподдерживаемый формат микрофона: $minBuffer" }
            if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) throw SecurityException("RECORD_AUDIO denied")
            recorder = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, SAMPLE_RATE * 2))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord не инициализирован" }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Микрофон не запущен" }
            Log.i(TAG, "listening session=$session rate=$SAMPLE_RATE route=${recorder.routedDevice?.productName}")
            val input = recorder.routedDevice
            sessionUpdate(session) { copy(detail = "Говорите по-русски",
                microphone = "${input?.productName} (type=${input?.type}, address=${input?.address})") }
            val pcm = ShortArray(WINDOW_SIZE)
            val timestamp = AudioTimestamp()
            var filled = 0
            var processed = 0L
            var levelFrames = 0
            var levelPeak = 0
            var levelSquareSum = 0.0
            var speechStartSample: Long? = null
            var lastAudioNs = System.nanoTime()
            var answered = false
            while (current(session) && !answered) {
                val count = recorder.read(pcm, filled, pcm.size - filled, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "AudioRecord.read error=$count" }
                if (count == 0) {
                    check(elapsedMs(lastAudioNs) < 3000) { "Микрофон не отдаёт данные 3 секунды" }
                    Thread.sleep(5)
                    continue
                }
                lastAudioNs = System.nanoTime()
                filled += count
                if (filled < WINDOW_SIZE) continue
                filled = 0
                processed += WINDOW_SIZE
                for (sample in pcm) {
                    val value = sample.toInt()
                    levelPeak = maxOf(levelPeak, kotlin.math.abs(value))
                    levelSquareSum += value.toDouble() * value
                }
                levelFrames += WINDOW_SIZE
                if (levelFrames >= SAMPLE_RATE * 5) {
                    val rms = kotlin.math.sqrt(levelSquareSum / levelFrames).toInt()
                    Log.i(TAG, "audioLevel session=$session frames=$processed peakPcm16=$levelPeak " +
                        "rmsPcm16=$rms")
                    sessionUpdate(session) { copy(peakPcm16 = levelPeak, rmsPcm16 = rms) }
                    levelFrames = 0
                    levelPeak = 0
                    levelSquareSum = 0.0
                }
                runtime.vad.acceptWaveform(FloatArray(WINDOW_SIZE) { pcm[it] / 32768f })
                val detected = runtime.vad.isSpeechDetected()
                if (detected && speechStartSample == null) {
                    speechStartSample = processed
                    Log.i(TAG, "speechStart utterance=${nextUtterance + 1} session=$session sample=$processed")
                    synchronized(lock) {
                        if (current(session)) {
                            speechDetected = true
                            sessionUpdate(session) { copy(status = VoiceStatus.SPEECH, vadStatus = "Речь",
                                detail = "Речь обнаружена") }
                        }
                    }
                }
                check(speechStartSample == null || processed - speechStartSample < 25L * SAMPLE_RATE) {
                    "Речь дольше 25 секунд: сделайте паузу и запустите прослушивание снова"
                }
                while (!runtime.vad.empty() && !answered) {
                    val segment = runtime.vad.front()
                    runtime.vad.pop()
                    val endpointNs = System.nanoTime()
                    val endSample = segment.start.toLong() + segment.samples.size
                    val hardwareTime = recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) ==
                        AudioRecord.SUCCESS && timestamp.nanoTime > 0 && timestamp.framePosition >= endSample
                    val speechEndNs = if (hardwareTime) {
                        timestamp.nanoTime - (timestamp.framePosition - endSample) * 1_000_000_000 / SAMPLE_RATE
                    } else {
                        endpointNs - (processed - endSample) * 1_000_000_000 / SAMPLE_RATE
                    }
                    speechStartSample = null
                    synchronized(lock) {
                        if (current(session)) {
                            speechDetected = false
                            enqueue(session, segment.samples, speechEndNs, endpointNs,
                                if (hardwareTime) "AudioTimestamp + граница VAD" else "Оценка по числу семплов", singleAnswer)
                            if (singleAnswer) answered = true
                        }
                    }
                }
            }
        } catch (error: Exception) {
            fail(session, error, if (requireUsb && error.message == "USB microphone missing")
                VoiceProblem.MICROPHONE else VoiceProblem.CAPTURE)
        } finally {
            recorder?.let {
                try {
                    if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) it.stop()
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "recorderStop", error)
                } finally {
                    it.release()
                }
            }
            runtime.vad.reset()
            update { copy(captureBusy = false,
                detail = if (status == VoiceStatus.IDLE) "Остановлено" else detail) }
            Log.i(TAG, "captureStopped session=$session")
        }
    }

    private fun enqueue(session: Long, samples: FloatArray, speechEndNs: Long,
        endpointNs: Long, timestampSource: String, singleAnswer: Boolean) {
        val id = ++nextUtterance
        val durationMs = samples.size * 1000L / SAMPLE_RATE
        val llmTicket = checkNotNull(qwen).ticket()
        Log.i(TAG, "speechEnd utterance=$id speechDurationMs=$durationMs endpointDelayMs=${elapsedMs(speechEndNs)}")
        val endpointDelay = elapsedMs(speechEndNs)
        pendingDecodes++
        sessionUpdate(session) { copy(status = VoiceStatus.DECODING_STT, vadStatus = "Конец речи: тишина ≥ 0.5 с",
            detail = "Распознавание фразы $id; микрофон продолжает слушать") }
        try {
            decoder.execute {
                if (!current(session)) return@execute
                val started = System.nanoTime()
                Log.i(TAG, "sttStart utterance=$id queueLatencyMs=${elapsedMs(endpointNs)}")
                var phase = VoiceProblem.TRANSCRIPTION
                try {
                    val text = checkNotNull(engine).recognize(samples)
                    val latency = elapsedMs(started)
                    val endToText = elapsedMs(speechEndNs)
                    val endpointToText = elapsedMs(endpointNs)
                    Log.i(TAG, "sttEnd utterance=$id sttLatencyMs=$latency endToTextMs=$endToText " +
                        "endpointToTextMs=$endpointToText sttText=\"$text\"")
                    synchronized(lock) {
                        if (!current(session)) return@execute
                        sessionUpdate(session) { copy(status = VoiceStatus.INFERENCING_LLM, text = text,
                            utterance = id, speechDurationMs = durationMs, sttLatencyMs = latency,
                            endToTextMs = endToText, endpointToTextMs = endpointToText,
                            timestampSource = timestampSource, llmText = "", llmLatencyMs = null,
                            totalPostSpeechLatencyMs = null, promptTokens = null, generatedTokens = null,
                            detail = "Qwen извлекает ответ из фразы $id") }
                    }
                    check(text.isNotBlank()) { "STT вернул пустой текст; Qwen не запущен" }
                    if (!current(session)) return@execute
                    phase = VoiceProblem.UNDERSTANDING
                    Log.i(TAG, "llmStart utterance=$id")
                    val answer = checkNotNull(qwen).extract(text, llmTicket)
                    val total = elapsedMs(speechEndNs)
                    Log.i(TAG, "llmEnd utterance=$id llmLatencyMs=${answer.latencyMs} " +
                        "llmResult=\"${answer.text}\" promptTokens=${answer.promptTokens} " +
                        "generatedTokens=${answer.generatedTokens} totalPostSpeechLatencyMs=$total")
                    synchronized(lock) {
                        if (!current(session)) return@execute
                        pendingDecodes--
                        sessionUpdate(session) { copy(status = VoiceStatus.RESULT, active = !singleAnswer,
                            endpointDelayMs = endpointDelay,
                            llmText = answer.text, llmLatencyMs = answer.latencyMs,
                            promptTokens = answer.promptTokens, generatedTokens = answer.generatedTokens,
                            totalPostSpeechLatencyMs = total, detail = "Ответ из фразы $id извлечён") }
                    }
                    main.postDelayed({
                        synchronized(lock) {
                            if (current(session) && state.status == VoiceStatus.RESULT && state.utterance == id) {
                                sessionUpdate(session) { copy(status = when {
                                    speechDetected -> VoiceStatus.SPEECH
                                    pendingDecodes > 0 -> VoiceStatus.DECODING_STT
                                    else -> VoiceStatus.LISTENING
                                }, detail = "Готово к следующей фразе",
                                    vadStatus = if (speechDetected) "Речь" else "Ожидание речи; предыдущая фраза завершена") }
                            }
                        }
                    }, 250)
                } catch (error: Exception) {
                    fail(session, error, phase)
                }
            }
        } catch (error: RejectedExecutionException) {
            fail(session, IllegalStateException("Очередь обработки речи заполнена. Нажмите Start listening для повтора", error))
        }
    }

    private fun fail(session: Long, error: Exception, problem: VoiceProblem = VoiceProblem.CAPTURE) = synchronized(lock) {
        if (!current(session)) return@synchronized
        Log.e(TAG, "pipelineError session=$session", error)
        generation++
        qwen?.cancel()
        decoder.queue.clear()
        pendingDecodes = 0
        update { copy(active = false, status = VoiceStatus.ERROR, problem = problem, vadStatus = "Остановлен",
            detail = error.message ?: error.javaClass.simpleName) }
    }

    fun microphoneRemoved() = synchronized(lock) {
        fail(generation, IllegalStateException("USB microphone removed"), VoiceProblem.MICROPHONE)
    }

    private fun sessionUpdate(session: Long, transform: VoiceLabState.() -> VoiceLabState) = synchronized(lock) {
        if (current(session)) update(transform)
    }

    private fun update(transform: VoiceLabState.() -> VoiceLabState) = synchronized(lock) {
        if (!closed) {
            state = state.transform().copy(session = generation)
            publish()
        }
    }

    private fun publish() = synchronized(lock) {
        val snapshot = state
        main.post { if (!closed) render(snapshot) }
    }

    fun close(onReleased: () -> Unit = {}) = synchronized(lock) {
        if (closed) return@synchronized
        closed = true
        generation++
        qwen?.cancel()
        decoder.queue.clear()
        main.removeCallbacksAndMessages(null)
        // FIFO barriers: first let capture finish, then let any running decode finish.
        capture.execute {
            decoder.execute {
                engine?.close()
                engine = null
                qwen?.close()
                qwen = null
                main.post(onReleased)
            }
            decoder.shutdown()
        }
        capture.shutdown()
    }
}
