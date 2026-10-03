package ru.familytv.games.world

import org.junit.Assert.*
import org.junit.Test
import ru.familytv.games.GameColor
import ru.familytv.games.Verdict

class GamesStateTest {
    @Test fun wrongMathPreservesApplesAndExplicitRetry() {
        val s = GamesState().apply { enterMath() }
        s.beginCombine(); s.finishCombine()
        val original = s.apples
        assertEquals(Verdict.WRONG, s.answerMath(4))
        assertEquals(original, s.apples)
        assertEquals(4, s.mathChoice)
        s.nextMath()
        assertEquals(original, s.apples)
        s.help()
        assertEquals(original, s.apples)
        assertEquals(4, s.mathChoice)
        s.finishCounting()
        assertEquals(Verdict.CORRECT, s.answerMath(5))
        assertTrue(s.combined)
        assertEquals(original, s.apples)
        s.nextMath()
        assertNotEquals(original.id, s.apples.id)
        assertFalse(s.combined)
    }
    @Test fun wrongPaintChangesTheWingAndCannotLaunch() {
        val s = GamesState().apply { enter(GameWorld.COLOR) }
        assertNull(s.painted)
        assertEquals(Verdict.WRONG, s.paint(GameColor.RED))
        assertEquals(GameColor.RED, s.painted)
        assertFalse(s.launch())
        s.help()
        assertEquals(GameColor.RED, s.painted)
        assertEquals(Verdict.CORRECT, s.paint(GameColor.BLUE))
        assertTrue(s.launch())
        assertFalse(s.launch())
        assertEquals(GameColor.BLUE, s.painted)
    }
    @Test fun appleWorldHasFiniteCalmEndingAndNoAutoAdvance() {
        val s = GamesState().apply { enterMath() }
        repeat(3) {
            val q = s.apples
            s.beginCombine(); s.finishCombine()
            s.answerMath(q.left + q.right)
            assertEquals(GameWorld.MATH, s.world)
            s.nextMath()
        }
        assertEquals(GameWorld.HOME, s.world)
    }

    @Test fun numbersAreUnavailableUntilCombineFinishes() {
        val s = GamesState().apply { enterMath() }
        assertFalse(s.answersVisible)
        assertFalse(s.help())
        assertNull(s.answerMath(5))
        assertNull(s.mathChoice)
        assertTrue(s.beginCombine())
        assertFalse(s.beginCombine())
        assertFalse(s.answersVisible)
        assertNull(s.answerMath(5))
        s.finishCombine()
        assertTrue(s.canAnswerMath)
        assertEquals(0, s.mathFocus)
        assertEquals("?", s.equationResult)
    }

    @Test fun repeatedHelpDoesNotRevealSelectOrSubmitAnAnswer() {
        val s = GamesState().apply { enterMath(); beginCombine(); finishCombine() }
        s.answerMath(4)
        repeat(2) {
            assertTrue(s.help())
            assertFalse(s.help())
            assertFalse(s.canAnswerMath)
            assertNull(s.answerMath(5))
            assertEquals("?", s.equationResult)
            assertEquals(4, s.mathChoice)
            assertEquals(0, s.mathFocus)
            assertEquals("Посчитаем вместе. Один, два, три, четыре, пять.", s.countingNarration())
            s.finishCounting()
            assertEquals(GameFeedback.HELP, s.feedback)
            assertEquals("?", s.equationResult)
            assertTrue(s.canAnswerMath)
            assertEquals(0, s.mathRound)
        }
        assertEquals(Verdict.CORRECT, s.answerMath(5))
        assertEquals("5", s.equationResult)
    }

    @Test fun pauseDuringCombineReturnsToWorldAndPauseDuringHelpKeepsQuestion() {
        val s = GamesState().apply { enterMath(); beginCombine() }
        s.stopTransientActions()
        s.finishCombine() // a stale completion cannot unlock answers
        assertFalse(s.answersVisible)
        assertTrue(s.beginCombine())
        s.finishCombine()
        s.help()
        s.stopTransientActions()
        assertEquals("?", s.equationResult)
        assertTrue(s.canAnswerMath)
        assertTrue(s.help())
    }

    @Test fun everyNewMathRoundStartsWithObjectsAndCountsOnlyItsOwnApples() {
        val s = GamesState().apply { enterMath() }
        val spoken = listOf(
            "Посчитаем вместе. Один, два, три, четыре, пять.",
            "Посчитаем вместе. Один, два, три.",
            "Посчитаем вместе. Один, два, три, четыре.",
        )
        spoken.forEach {
            assertFalse(s.answersVisible)
            assertEquals(0, s.mathFocus)
            assertNull(s.mathChoice)
            assertEquals(it, s.countingNarration())
            s.beginCombine(); s.finishCombine()
            s.answerMath(s.apples.left + s.apples.right)
            s.nextMath()
        }
        assertEquals(GameWorld.HOME, s.world)
    }
}
