package ru.familytv.games

import org.junit.Assert.*
import org.junit.Test

class GameSessionTest {
    @Test fun mathUsesTheChildsAnswerRatherThanCorrectingIt() {
        val q = AppleQuestion(1, 2, 3)
        assertEquals(Verdict.CORRECT, AnswerJudge.evaluate(q, "5"))
        assertEquals(Verdict.WRONG, AnswerJudge.evaluate(q, "4"))
        assertEquals(Verdict.CORRECT, AnswerJudge.evaluate(AppleQuestion(2, 5, 5), "10"))
        for (answer in listOf("не знаю", "11", "-1", "5.0", "пять", "05", "4 или 5", "", "999999999999"))
            assertEquals(answer, Verdict.NOT_UNDERSTOOD, AnswerJudge.evaluate(q, answer))
        assertEquals(Verdict.WRONG, AnswerJudge.evaluate(q, "0"))
    }
    @Test fun mathRetriesKeepQuestionAndCorrectAdvances() {
        val original = AppleQuestion(17, 2, 3)
        val session = GameSession(GameKind.MATH, QuestionGenerator(42), original)
        session.answer("4"); session.finishFeedback(); assertEquals(original, session.question)
        session.answer("не знаю"); session.finishFeedback(); assertEquals(original, session.question)
        session.answer("5"); session.finishFeedback(); assertTrue(session.question.id > original.id)
    }
    @Test fun colorIsCheckedAgainstOnlyAllowedValues() {
        val q = ColorQuestion(1, GameColor.BLUE, listOf(GameColor.RED, GameColor.BLUE, GameColor.GREEN))
        assertEquals(Verdict.CORRECT, AnswerJudge.evaluate(q, "синий"))
        assertEquals(Verdict.WRONG, AnswerJudge.evaluate(q, "красный"))
        for (answer in listOf("фиолетовый", "не знаю", "5", "Синий", "я выбираю синий", ""))
            assertEquals(answer, Verdict.NOT_UNDERSTOOD, AnswerJudge.evaluate(q, answer))
        val session = GameSession(GameKind.COLOR, QuestionGenerator(7), q)
        session.answer("не знаю"); session.finishFeedback(); assertEquals(q, session.question)
        session.answer("красный"); session.finishFeedback(); assertEquals(q, session.question)
        session.answer("синий"); session.finishFeedback(); assertTrue(session.question.id > q.id)
    }
    @Test fun seededGenerationIsValidAndRepeatable() {
        val a = QuestionGenerator(1234)
        val b = QuestionGenerator(1234)
        repeat(1000) {
            val q = a.next(GameKind.MATH) as AppleQuestion
            assertEquals(q, b.next(GameKind.MATH))
            assertTrue(q.left in 1..5 && q.right in 1..5 && q.left + q.right <= 10)
            val color = a.next(GameKind.COLOR) as ColorQuestion
            assertEquals(color, b.next(GameKind.COLOR))
            assertTrue(color.target in color.choices)
            assertEquals(3, color.choices.distinct().size)
        }
    }
}
