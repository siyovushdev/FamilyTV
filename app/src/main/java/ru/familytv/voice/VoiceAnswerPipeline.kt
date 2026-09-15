package ru.familytv.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.familytv.voicelab.VoiceLabState
import ru.familytv.voicelab.VoiceStatus

/** Lightweight foreground lease over the process runtime. No Activity/model references. */
internal class VoiceAnswerPipeline internal constructor(private val runtime: VoiceRuntime,
    internal val singleAnswer: Boolean, internal val requireUsb: Boolean) : VoiceAnswerSource {
    private val _state = MutableStateFlow(VoiceAnswerState())
    override val state: StateFlow<VoiceAnswerState> = _state.asStateFlow()
    private var closed = false
    private var inputError = false

    override fun activate() {
        if (closed) return
        inputError = false
        runtime.acquire(this)
    }
    override fun startListening(): Long {
        if (closed) return -1
        inputError = false
        return runtime.start(this)
    }
    override fun stopListening() {
        runtime.stop(this, detach = true)
        _state.value = VoiceAnswerState(VoicePhase.STOPPED)
    }
    override fun retryInitialization() = runtime.retryInitialization()
    override fun close() { stopListening(); closed = true }
    fun clear() = runtime.clear(this)
    fun permissionDenied() = runtime.permissionDenied(this)
    internal fun revoked() { _state.value = VoiceAnswerState(VoicePhase.STOPPED) }
    internal fun initializing() { _state.value = VoiceAnswerState(VoicePhase.INITIALIZING) }
    internal fun inputMissing() {
        inputError = true
        _state.value = VoiceAnswerState(VoicePhase.ERROR, problem = VoiceProblem.MICROPHONE)
    }

    internal fun accept(value: VoiceLabState) {
        if (closed || !runtime.isOwner(this) || inputError) return
        val phase = when {
            value.problem == VoiceProblem.INITIALIZATION -> VoicePhase.ERROR
            !value.ready -> VoicePhase.INITIALIZING
            value.status == VoiceStatus.ERROR -> VoicePhase.ERROR
            value.status == VoiceStatus.RESULT -> VoicePhase.RESULT
            value.status == VoiceStatus.LISTENING -> VoicePhase.LISTENING
            value.status == VoiceStatus.SPEECH -> VoicePhase.SPEECH
            value.status == VoiceStatus.DECODING_STT -> VoicePhase.STT_PROCESSING
            value.status == VoiceStatus.INFERENCING_LLM -> VoicePhase.LLM_PROCESSING
            !value.captureBusy && !value.active -> VoicePhase.READY
            else -> VoicePhase.STOPPED
        }
        _state.value = VoiceAnswerState(phase, if (phase == VoicePhase.RESULT) VoiceAnswer(
            value.session, value.text, value.llmText, VoiceMetrics(value.speechDurationMs,
                value.endpointDelayMs, value.sttLatencyMs, value.llmLatencyMs, value.totalPostSpeechLatencyMs)
        ) else null, value.problem)
    }
}
