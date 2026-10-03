package ru.familytv.games.world

import ru.familytv.games.AnswerJudge
import ru.familytv.games.AppleQuestion
import ru.familytv.games.AttributeApplyDefinition
import ru.familytv.games.ColorQuestion
import ru.familytv.games.CountCombineDefinition
import ru.familytv.games.GameColor
import ru.familytv.games.Verdict

internal enum class GameWorld { HOME, MATH, COLOR }
internal enum class GameFeedback { READY, HELP, WRONG, CORRECT }

/** Finite world presentation state. Answer semantics stay in the shared AnswerJudge. */
internal class GamesState {
    var world = GameWorld.HOME
    var feedback = GameFeedback.READY
    var gameFocus = 0
    var mathRound = 0
    var combined = false
    var combining = false
    var counting = false
    var mathFocus = 0
    var mathChoice: Int? = null
    var painted: GameColor? = null
    var paintChoice = GameColor.BLUE
    var launched = false
    var mathSet: CountCombineDefinition? = null
        private set
    var colorSet: AttributeApplyDefinition? = null
        private set
    val hasMath get() = mathSet != null
    val hasColor get() = colorSet != null
    val mathContentId get() = mathSet?.contentId
    val colorContentId get() = colorSet?.contentId
    val rounds get() = math().rounds.map { it.left to it.right }
    val apples: AppleQuestion get() = rounds[mathRound.coerceIn(rounds.indices)].let { (a, b) -> AppleQuestion(mathRound + 1L, a, b) }
    val answers: List<Int> get() = (apples.left + apples.right).let { listOf(it - 1, it, it + 1) }
    val paints get() = color().paints
    val correctColor get() = color().correct
    val hintIndex get() = paints.indexOf(correctColor).coerceAtLeast(0)
    val answersVisible get() = world == GameWorld.MATH && combined
    val canAnswerMath get() = answersVisible && !counting && feedback != GameFeedback.CORRECT
    val canPaint get() = world == GameWorld.COLOR && feedback != GameFeedback.CORRECT && !launched
    val equationResult get() = if (feedback == GameFeedback.CORRECT) "${apples.left + apples.right}" else "?"

    fun beginCombine(): Boolean {
        if (world != GameWorld.MATH || combined || combining) return false
        combining = true
        return true
    }
    fun finishCombine() {
        if (!combining) return
        combining = false
        combined = true
        mathFocus = 0
    }
    fun stopTransientActions() { combining = false; counting = false }
    fun finishCounting() { counting = false }
    fun bindMath(definition: CountCombineDefinition) {
        mathSet = definition
        mathRound = 0
        combined = false
        stopTransientActions()
        mathFocus = 0
        mathChoice = null
        if (world == GameWorld.MATH) feedback = GameFeedback.READY
    }

    private fun math(): CountCombineDefinition =
        checkNotNull(mathSet) { "COUNT_COMBINE dataset is not bound" }

    private fun color(): AttributeApplyDefinition =
        checkNotNull(colorSet) { "ATTRIBUTE_APPLY dataset is not bound" }

    fun bindColor(definition: AttributeApplyDefinition) {
        colorSet = definition
        painted = null
        launched = false
        paintChoice = definition.correct
        if (world == GameWorld.COLOR) feedback = GameFeedback.READY
    }

    fun colorLabel(index: Int): String = color().options[index].label

    fun countingNarration(): String {
        val set = math()
        val n = apples.left + apples.right
        return "${set.lines.countingLead}. ${set.countingWords.take(n).joinToString(", ")}."
    }

    fun enter(next: GameWorld) {
        if (next == GameWorld.MATH && mathSet == null) {
            throw IllegalStateException("COUNT_COMBINE dataset is not bound")
        }
        if (next == GameWorld.COLOR && colorSet == null) {
            throw IllegalStateException("ATTRIBUTE_APPLY dataset is not bound")
        }
        world = next; feedback = GameFeedback.READY; combined = false
        stopTransientActions(); mathFocus = 0; mathChoice = null
        painted = null; launched = false
        if (next == GameWorld.COLOR) paintChoice = color().correct
        if (next == GameWorld.MATH) mathRound = 0
    }
    fun answerMath(value: Int): Verdict? {
        if (!canAnswerMath) return null
        mathChoice = value
        return AnswerJudge.evaluate(apples, value.toString()).also {
            feedback = if (it == Verdict.CORRECT) GameFeedback.CORRECT else GameFeedback.WRONG
        }
    }
    fun paint(value: GameColor): Verdict? {
        if (!canPaint) return null
        paintChoice = value; painted = value
        val question = ColorQuestion(1, color().correct, paints)
        return AnswerJudge.evaluate(question, value.answer).also {
            feedback = if (it == Verdict.CORRECT) GameFeedback.CORRECT else GameFeedback.WRONG
        }
    }
    fun launch(): Boolean {
        if (world != GameWorld.COLOR || feedback != GameFeedback.CORRECT || painted != color().correct || launched) return false
        launched = true
        return true
    }
    fun nextMath() {
        if (world != GameWorld.MATH || feedback != GameFeedback.CORRECT) return
        if (mathRound == rounds.lastIndex) { world = GameWorld.HOME; gameFocus = 0; return }
        mathRound++; combined = false; feedback = GameFeedback.READY; mathChoice = null; mathFocus = 0
    }
    fun help(): Boolean {
        if (world == GameWorld.HOME || feedback == GameFeedback.CORRECT || counting) return false
        if (world == GameWorld.MATH && !combined) return false
        feedback = GameFeedback.HELP
        counting = world == GameWorld.MATH
        return true
    }
    fun backToGames() {
        gameFocus = if (world == GameWorld.COLOR) 1 else 0
        enter(GameWorld.HOME)
    }
    fun defaultFocus(): String = when {
        world == GameWorld.HOME -> "game:$gameFocus"
        feedback == GameFeedback.CORRECT -> "next"
        world == GameWorld.MATH && !combined -> "world"
        world == GameWorld.MATH -> if (counting) "help" else "choice:$mathFocus"
        else -> "choice:${paints.indexOf(paintChoice).coerceAtLeast(0)}"
    }
    fun prompt(): String = when (world) {
        GameWorld.HOME -> "Выбирай мир стрелками. Нажми OK, чтобы играть!"
        GameWorld.MATH -> {
            val lines = math().lines
            when (feedback) {
                GameFeedback.READY -> if (combined) lines.ask else lines.separate
                GameFeedback.HELP -> lines.help
                GameFeedback.WRONG -> lines.wrong
                GameFeedback.CORRECT -> lines.success
            }
        }
        GameWorld.COLOR -> {
            val lines = color().lines
            when {
                launched -> lines.launched
                feedback == GameFeedback.WRONG -> lines.wrong
                feedback == GameFeedback.CORRECT -> lines.success
                feedback == GameFeedback.HELP -> lines.help
                else -> lines.prompt
            }
        }
    }
}
