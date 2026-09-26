package ru.familytv.games.world

import ru.familytv.games.AnswerJudge
import ru.familytv.games.AppleQuestion
import ru.familytv.games.ColorQuestion
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
    val rounds = listOf(2 to 3, 1 to 2, 2 to 2)
    val apples: AppleQuestion get() = rounds[mathRound.coerceIn(rounds.indices)].let { (a, b) -> AppleQuestion(mathRound + 1L, a, b) }
    val answers: List<Int> get() = (apples.left + apples.right).let { listOf(it - 1, it, it + 1) }
    val paints = listOf(GameColor.RED, GameColor.BLUE, GameColor.YELLOW)
    val rocketQuestion = ColorQuestion(1, GameColor.BLUE, paints)
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
    fun countingNarration(): String {
        val words = listOf("Один", "два", "три", "четыре", "пять")
        return "Посчитаем вместе. ${words.take(apples.left + apples.right).joinToString(", ")}."
    }

    fun enter(next: GameWorld) {
        world = next; feedback = GameFeedback.READY; combined = false
        stopTransientActions(); mathFocus = 0; mathChoice = null
        painted = null; paintChoice = GameColor.BLUE; launched = false
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
        return AnswerJudge.evaluate(rocketQuestion, value.answer).also {
            feedback = if (it == Verdict.CORRECT) GameFeedback.CORRECT else GameFeedback.WRONG
        }
    }
    fun launch(): Boolean {
        if (world != GameWorld.COLOR || feedback != GameFeedback.CORRECT || painted != GameColor.BLUE || launched) return false
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
        GameWorld.MATH -> when (feedback) {
            GameFeedback.READY -> if (combined) "Сколько яблок получилось вместе?" else "Соединим яблоки из двух корзин!"
            GameFeedback.HELP -> "Считаем яблоки вместе — по одному."
            GameFeedback.WRONG -> "Посмотрим ещё раз. Все яблоки остаются с нами."
            GameFeedback.CORRECT -> "Все яблоки вместе! Корзина собрана."
        }
        GameWorld.COLOR -> when {
            launched -> "Ракета полетела! Синее крыло готово к путешествию."
            feedback == GameFeedback.WRONG -> "Попробуем синий — как звёздное небо."
            feedback == GameFeedback.CORRECT -> "Крыло стало синим. Ракета готова к полёту!"
            feedback == GameFeedback.HELP -> "Синяя краска — для крыла. Посмотри на баночку."
            else -> "Покрась крыло ракеты в синий цвет!"
        }
    }
}
