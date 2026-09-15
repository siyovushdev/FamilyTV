package ru.familytv.games

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.familytv.voice.VoiceAnswer
import ru.familytv.voice.VoiceAnswerSource
import ru.familytv.voice.VoicePhase
import ru.familytv.voice.VoiceProblem

internal data class GameViewState(val question: GameQuestion, val phase: GamePhase,
    val problem: VoiceProblem? = null)

/** Shared two-game orchestration. Delays and collectors are owned by the screen lifecycle. */
internal class VoiceGameSessionController(
    private val source: VoiceAnswerSource,
    private val scope: CoroutineScope,
    private val session: GameSession,
    private val feedbackMs: Long = 1500,
    private val telemetry: (GameQuestion, VoiceAnswer, Verdict) -> Unit = { _, _, _ -> }
) : AutoCloseable {
    private val _state = MutableStateFlow(GameViewState(session.question, session.phase))
    val state = _state.asStateFlow()
    private var visible = false
    private var awaitingReady = false
    private var request = -1L
    private var consumed = -1L
    private var feedback: Job? = null
    private val collector = scope.launch {
        source.state.collect { voice ->
            if (!visible) return@collect
            when (voice.phase) {
                VoicePhase.INITIALIZING -> show(GamePhase.LOADING)
                VoicePhase.READY -> if (awaitingReady) listen()
                VoicePhase.LISTENING -> show(GamePhase.LISTENING)
                VoicePhase.SPEECH -> show(GamePhase.SPEECH)
                VoicePhase.STT_PROCESSING, VoicePhase.LLM_PROCESSING -> show(GamePhase.PROCESSING)
                VoicePhase.ERROR -> {
                    feedback?.cancel()
                    awaitingReady = false
                    show(GamePhase.ERROR, voice.problem)
                }
                VoicePhase.RESULT -> voice.answer?.let { answer ->
                    if (answer.requestId != request || answer.requestId == consumed) return@let
                    consumed = answer.requestId
                    val verdict = session.answer(answer.normalizedText)
                    telemetry(session.question, answer, verdict)
                    publish()
                    source.stopListening()
                    feedback?.cancel()
                    feedback = scope.launch {
                        delay(feedbackMs)
                        if (visible) {
                            session.finishFeedback()
                            publish()
                            awaitingReady = true
                            source.activate()
                        }
                    }
                }
                VoicePhase.STOPPED -> Unit // Does not overwrite answer feedback on intentional stop.
            }
        }
    }

    fun resume() {
        if (visible) return
        visible = true
        awaitingReady = true
        source.activate()
        // StateFlow need not emit if READY remained equal across a pause.
        if (source.state.value.phase == VoicePhase.READY && awaitingReady) listen()
    }
    fun pause() {
        visible = false
        awaitingReady = false
        feedback?.cancel()
        if (session.phase in listOf(GamePhase.CORRECT, GamePhase.WRONG, GamePhase.NOT_UNDERSTOOD))
            session.finishFeedback()
        request = -1
        source.stopListening()
        show(GamePhase.STOPPED)
    }
    fun retry() {
        if (!visible || state.value.phase !in listOf(GamePhase.ERROR, GamePhase.STOPPED, GamePhase.LOADING)) return
        feedback?.cancel()
        awaitingReady = true
        if (state.value.problem == VoiceProblem.INITIALIZATION) source.retryInitialization()
        source.activate()
        if (source.state.value.phase == VoicePhase.READY && awaitingReady) listen()
    }
    private fun listen() {
        awaitingReady = false
        show(GamePhase.QUESTION)
        request = source.startListening()
    }
    private fun show(phase: GamePhase, problem: VoiceProblem? = null) {
        session.transition(phase)
        _state.value = GameViewState(session.question, phase, problem)
    }
    private fun publish() { _state.value = GameViewState(session.question, session.phase) }
    override fun close() { pause(); collector.cancel(); source.close() }
}
