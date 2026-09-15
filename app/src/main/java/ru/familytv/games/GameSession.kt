package ru.familytv.games

import kotlin.random.Random

enum class GameKind { MATH, COLOR }
internal enum class GamePhase { LOADING, QUESTION, LISTENING, SPEECH, PROCESSING, CORRECT, WRONG, NOT_UNDERSTOOD, ERROR, STOPPED }
internal enum class Verdict { CORRECT, WRONG, NOT_UNDERSTOOD }
internal enum class GameColor(val answer: String, val rgb: Int) {
    RED("красный", 0xE95051), BLUE("синий", 0x377BDD),
    GREEN("зелёный", 0x43A76C), YELLOW("жёлтый", 0xF1C344)
}

internal sealed interface GameQuestion {
    val id: Long
    val expectedAnswer: String
}
internal data class AppleQuestion(override val id: Long, val left: Int, val right: Int) : GameQuestion {
    init { require(left in 1..5 && right in 1..5 && left + right <= 10) }
    override val expectedAnswer: String get() = (left + right).toString()
}
internal data class ColorQuestion(override val id: Long, val target: GameColor,
    val choices: List<GameColor>) : GameQuestion {
    init { require(choices.size in 2..3 && choices.distinct().size == choices.size && target in choices) }
    override val expectedAnswer: String get() = target.answer
}

internal class QuestionGenerator(seed: Long = System.nanoTime()) {
    private val random = Random(seed)
    private var nextId = 0L
    fun next(kind: GameKind, previousId: Long = 0): GameQuestion {
        nextId = maxOf(nextId, previousId)
        return when (kind) {
            GameKind.MATH -> AppleQuestion(++nextId, random.nextInt(1, 6), random.nextInt(1, 6))
            GameKind.COLOR -> {
                val choices = GameColor.entries.shuffled(random).take(3)
                ColorQuestion(++nextId, choices.random(random), choices)
            }
        }
    }
}

/** Pure game logic. The expected answer is used here, never supplied to voice understanding. */
internal object AnswerJudge {
    fun evaluate(question: GameQuestion, normalized: String): Verdict {
        val answer = normalized.trim()
        val understood = when (question) {
            is AppleQuestion -> Regex("(?:[0-9]|10)").matches(answer)
            is ColorQuestion -> GameColor.entries.any { it.answer == answer }
        }
        return when {
            !understood -> Verdict.NOT_UNDERSTOOD
            question.expectedAnswer == answer -> Verdict.CORRECT
            else -> Verdict.WRONG
        }
    }
}

internal class GameSession(val kind: GameKind, private val generator: QuestionGenerator = QuestionGenerator(),
    firstQuestion: GameQuestion? = null) {
    var question: GameQuestion = firstQuestion ?: generator.next(kind)
        private set
    var phase: GamePhase = GamePhase.LOADING
        private set
    fun transition(next: GamePhase) { phase = next }
    fun answer(normalized: String): Verdict = AnswerJudge.evaluate(question, normalized).also {
        phase = when (it) {
            Verdict.CORRECT -> GamePhase.CORRECT
            Verdict.WRONG -> GamePhase.WRONG
            Verdict.NOT_UNDERSTOOD -> GamePhase.NOT_UNDERSTOOD
        }
    }
    fun finishFeedback() {
        if (phase == GamePhase.CORRECT) question = generator.next(kind, question.id)
        phase = GamePhase.QUESTION
    }
}
