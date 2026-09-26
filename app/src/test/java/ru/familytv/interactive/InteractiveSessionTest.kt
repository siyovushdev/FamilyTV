package ru.familytv.interactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InteractiveSessionTest {
    private val experience = InteractiveExperienceParser.parse(
        """
        {
          "id":"apple-adventure",
          "title":"Demo",
          "schemaVersion":3,
          "startStepId":"intro",
          "steps":[
            {"type":"message","id":"intro","text":"Hi","transitions":{"next":"choice"}},
            {"type":"choice","id":"choice","prompt":"Q","options":[
              {"id":"fish","text":"Рыба"},{"id":"cat","text":"Кошка"}
            ],"correctOptionId":"fish",
             "feedback":{"correct":"Yes","wrong":"No","hint":"water","retryLimit":2},
             "transitions":{"onCorrect":"tf","onWrong":"choice"}},
            {"type":"true_false","id":"tf","prompt":"Water?","correct":true,
             "feedback":{"correct":"Ok","wrong":"Nope"},
             "transitions":{"onCorrect":"move","onWrong":"tf"}},
            {"type":"move","id":"move","prompt":"Jump","durationSeconds":10,"continueLabel":"Done",
             "transitions":{"next":"voice"}},
            {"type":"question","id":"voice","prompt":"How many?","questionType":"VOICE_NUMBER","expected":"5",
             "visual":{"kind":"APPLES","left":2,"right":3},
             "feedback":{"correct":"Y","wrong":"N"},
             "transitions":{"onCorrect":"out","onWrong":"voice","onUnknown":"voice"}},
            {"type":"message","id":"out","text":"Done","transitions":{"next":"__end__"}}
          ]
        }
        """.trimIndent(),
    )

    @Test
    fun choiceWrongKeepsStep() {
        val session = InteractiveSession(experience)
        session.advanceFromNonQuestion()
        assertEquals(InteractiveVerdict.WRONG, session.answerChoice("cat"))
        session.finishFeedback()
        assertEquals("choice", session.currentStepId)
    }

    @Test
    fun choiceCorrectAdvancesToTrueFalse() {
        val session = InteractiveSession(experience)
        session.advanceFromNonQuestion()
        assertEquals(InteractiveVerdict.CORRECT, session.answerChoice("fish"))
        session.finishFeedback()
        assertTrue(session.currentStep is TrueFalseStep)
    }

    @Test
    fun trueFalseAndMoveAdvance() {
        val session = InteractiveSession(experience, "tf")
        assertEquals(InteractiveVerdict.CORRECT, session.answerTrueFalse(true))
        session.finishFeedback()
        assertTrue(session.currentStep is MoveStep)
        session.advanceFromNonQuestion()
        assertTrue(session.currentStep is QuestionStep)
    }

    @Test
    fun wrongVoiceAnswerKeepsQuestion() {
        val session = InteractiveSession(experience, "voice")
        assertEquals(InteractiveVerdict.WRONG, session.answerVoice("4"))
        session.finishFeedback()
        assertEquals("voice", session.currentStepId)
    }

    @Test
    fun expectedAnswerIsolatedInJudge() {
        val step = experience.steps.filterIsInstance<QuestionStep>().first()
        assertEquals(InteractiveVerdict.CORRECT, InteractiveJudge.evaluateVoice(step, "5"))
        assertEquals("5", step.expected)
    }

    @Test
    fun feedbackComesFromData() {
        val choice = experience.steps.filterIsInstance<ChoiceStep>().first()
        assertEquals("Yes", choice.feedback.correct)
        assertEquals("water", choice.feedback.hint)
    }

    @Test
    fun retryLimitSoftAdvances() {
        val session = InteractiveSession(experience, "choice")
        repeat(2) {
            session.answerChoice("cat")
            session.finishFeedback()
        }
        // After retryLimit=2 wrongs, finishFeedback soft-advances via onCorrect
        assertEquals("tf", session.currentStepId)
    }

    @Test
    fun videoStepTransition() {
        val exp = InteractiveExperienceParser.parse(
            """
            {"id":"v","title":"v","schemaVersion":3,"steps":[
              {"type":"video","id":"vid","mediaRef":"media/a.mp4","transitions":{"next":"m"}},
              {"type":"message","id":"m","text":"ok","transitions":{"next":"__end__"}}
            ]}
            """.trimIndent(),
        )
        val session = InteractiveSession(exp)
        session.onVideoCompleted()
        assertEquals("m", session.currentStepId)
    }

    @Test
    fun invalidTargetRejected() {
        val bad = """
          {"id":"x","title":"x","schemaVersion":3,"steps":[
            {"type":"message","id":"a","text":"t","transitions":{"next":"missing"}}
          ]}
        """.trimIndent()
        assertFalse(ExperienceValidator.validate(InteractiveExperienceParser.parse(bad)).ok)
    }
}
