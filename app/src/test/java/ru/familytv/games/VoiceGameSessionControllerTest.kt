package ru.familytv.games

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.voice.*

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceGameSessionControllerTest {
    private class FakeVoice : VoiceAnswerSource {
        override val state = MutableStateFlow(VoiceAnswerState(VoicePhase.READY))
        var requests = 0L
        var stops = 0
        override fun activate() { state.value = VoiceAnswerState(VoicePhase.READY) }
        override fun startListening(): Long {
            requests++
            state.value = VoiceAnswerState(VoicePhase.LISTENING)
            return requests
        }
        override fun stopListening() { stops++; state.value = VoiceAnswerState(VoicePhase.STOPPED) }
        override fun retryInitialization() { state.value = VoiceAnswerState(VoicePhase.INITIALIZING) }
        override fun close() = stopListening()
        fun answer(text: String, id: Long = requests) {
            state.value = VoiceAnswerState(VoicePhase.RESULT, VoiceAnswer(id, text, text))
        }
    }
    @Test fun wrongAndUnknownRetrySameProblemAndCorrectWaitsBeforeNext() = runTest {
        val voice = FakeVoice()
        val question = AppleQuestion(1, 2, 3)
        val game = VoiceGameSessionController(voice, backgroundScope, GameSession(GameKind.MATH, QuestionGenerator(3), question))
        runCurrent(); game.resume(); runCurrent()
        assertEquals(GamePhase.LISTENING, game.state.value.phase)
        voice.answer("4"); runCurrent()
        assertEquals(GamePhase.WRONG, game.state.value.phase)
        assertEquals(question, game.state.value.question)
        advanceTimeBy(1499); runCurrent(); assertEquals(1, voice.requests)
        advanceTimeBy(1); runCurrent(); assertEquals(2, voice.requests)
        assertEquals(question, game.state.value.question)
        voice.answer("не знаю"); runCurrent()
        assertEquals(GamePhase.NOT_UNDERSTOOD, game.state.value.phase)
        advanceTimeBy(1500); runCurrent(); assertEquals(question, game.state.value.question)
        voice.answer("5"); runCurrent(); assertEquals(GamePhase.CORRECT, game.state.value.phase)
        advanceTimeBy(1500); runCurrent(); assertTrue(game.state.value.question.id > question.id)
        assertEquals(4, voice.requests)
        game.close()
    }
    @Test fun pauseCancelsRestartAndLateAnswerCannotChangeNewQuestionSession() = runTest {
        val voice = FakeVoice()
        val question = AppleQuestion(1, 2, 3)
        val game = VoiceGameSessionController(voice, backgroundScope, GameSession(GameKind.MATH, QuestionGenerator(3), question))
        runCurrent(); game.resume(); runCurrent()
        voice.answer("4"); runCurrent(); game.pause()
        advanceTimeBy(2000); runCurrent(); assertEquals(1, voice.requests)
        game.resume(); runCurrent(); assertEquals(2, voice.requests)
        voice.answer("5", 1); runCurrent()
        assertEquals(GamePhase.LISTENING, game.state.value.phase)
        assertEquals(question, game.state.value.question)
        voice.state.value = VoiceAnswerState(VoicePhase.ERROR, problem = VoiceProblem.MICROPHONE)
        runCurrent(); assertEquals(GamePhase.ERROR, game.state.value.phase)
        advanceTimeBy(5000); runCurrent(); assertEquals(2, voice.requests)
        game.retry(); runCurrent(); assertEquals(3, voice.requests)
        game.close()
    }
    @Test fun colorUsesTheSameVoiceSessionWithoutSupplyingAnExpectedAnswer() = runTest {
        val voice = FakeVoice() // Interface accepts no question/expectedAnswer argument.
        val q = ColorQuestion(2, GameColor.BLUE, listOf(GameColor.RED, GameColor.BLUE, GameColor.GREEN))
        val logged = mutableListOf<Verdict>()
        val game = VoiceGameSessionController(voice, backgroundScope,
            GameSession(GameKind.COLOR, QuestionGenerator(2), q), telemetry = { _, _, verdict -> logged += verdict })
        runCurrent(); game.resume(); runCurrent()
        voice.answer("красный"); runCurrent(); assertEquals(GamePhase.WRONG, game.state.value.phase)
        advanceTimeBy(1500); runCurrent(); assertEquals(q, game.state.value.question)
        voice.answer("синий"); runCurrent(); assertEquals(GamePhase.CORRECT, game.state.value.phase)
        advanceTimeBy(1500); runCurrent(); assertTrue(game.state.value.question.id > q.id)
        assertEquals(listOf(Verdict.WRONG, Verdict.CORRECT), logged)
        game.close()
    }
}
