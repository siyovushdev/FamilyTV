package ru.familytv.voice

import kotlinx.coroutines.flow.StateFlow

internal enum class AiReadiness { AI_INITIALIZING, AI_READY, AI_ERROR }
internal enum class VoicePhase { INITIALIZING, READY, LISTENING, SPEECH, STT_PROCESSING, LLM_PROCESSING, RESULT, ERROR, STOPPED }
internal enum class VoiceProblem { INITIALIZATION, PERMISSION, MICROPHONE, CAPTURE, TRANSCRIPTION, UNDERSTANDING }

internal data class VoiceMetrics(
    val speechDurationMs: Long? = null,
    val endpointDelayMs: Long? = null,
    val sttLatencyMs: Long? = null,
    val llmLatencyMs: Long? = null,
    val totalPostSpeechLatencyMs: Long? = null
)

/** Answer understanding has no question, game type, answer key or expected-answer input. */
internal data class VoiceAnswer(val requestId: Long, val transcript: String,
    val normalizedText: String, val metrics: VoiceMetrics = VoiceMetrics())
internal data class VoiceAnswerState(val phase: VoicePhase = VoicePhase.INITIALIZING,
    val answer: VoiceAnswer? = null, val problem: VoiceProblem? = null)

/** UI/session contract; games never own or call model/JNI implementations. */
internal interface VoiceAnswerSource : AutoCloseable {
    val state: StateFlow<VoiceAnswerState>
    fun activate()
    fun startListening(): Long
    fun stopListening()
    fun retryInitialization()
    override fun close()
}
