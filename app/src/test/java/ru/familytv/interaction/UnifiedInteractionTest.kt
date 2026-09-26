package ru.familytv.interaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.narration.CommonVoicePack

class UnifiedInteractionTest {
    @Test
    fun commandVsAnswerArbitration() {
        val ask = RouteContext(InteractionSurface.INTERACTIVE, awaitingAnswer = true)
        assertTrue((VoiceCommandRouter.route(ask, "шесть") as RouteResult.Action).action is FamilyAction.Answer)
        assertEquals(FamilyAction.Repeat, (VoiceCommandRouter.route(ask, "повтори") as RouteResult.Action).action)
        assertEquals(FamilyAction.Help, (VoiceCommandRouter.route(ask, "помоги") as RouteResult.Action).action)
        assertEquals(FamilyAction.Back, (VoiceCommandRouter.route(ask, "назад") as RouteResult.Action).action)
        assertTrue((VoiceCommandRouter.route(ask, "я думаю шесть") as RouteResult.Action).action is FamilyAction.Answer)
    }

    @Test
    fun gameSurfaceRoutesHome() {
        val r = VoiceCommandRouter.route(RouteContext(InteractionSurface.GAME, awaitingAnswer = true), "домой")
        assertEquals(FamilyAction.Home, (r as RouteResult.Action).action)
    }

    @Test
    fun presentationDisplayVsSpoken() {
        val msg = PresentationMessage(
            text = "Найди 3 круглых предмета",
            spokenText = "Найди три круглых предмета.",
            narrationRef = "space-expedition/find-float.wav",
        )
        assertEquals("Найди 3 круглых предмета", msg.displayText())
        assertEquals("Найди три круглых предмета.", msg.effectiveSpoken())
        assertEquals("space-expedition/find-float.wav", msg.effectiveNarrationRef())
    }

    @Test
    fun commonVoicePackKeys() {
        assertTrue(CommonVoicePack.allKeys().contains("repeat_task"))
        assertEquals("narration/common/well_done.wav", CommonVoicePack.pathFor("well_done"))
    }

    @Test
    fun audioFirstValidatorReleaseStyle() {
        val ok = AudioFirstValidator.validateAdventureScene(
            "s", "CHALLENGE", "Найди три", "Найди три", "space/a.wav",
            ReadingSupportMode.AUDIO_FIRST,
        )
        assertTrue(ok.none { it.level == AudioFirstValidator.Level.ERROR })
    }

    @Test
    fun dispatcherParityRemoteAndVoice() {
        val seen = mutableListOf<Pair<InputSource, FamilyAction>>()
        val d = FamilyActionDispatcher()
        d.attach(FamilyActionHandler { src, act -> seen += src to act; true })
        d.dispatch(InputSource.REMOTE, FamilyAction.Repeat)
        d.dispatch(InputSource.VOICE, FamilyAction.Help)
        assertEquals(InputSource.REMOTE, seen[0].first)
        assertEquals(FamilyAction.Repeat, seen[0].second)
        assertEquals(InputSource.VOICE, seen[1].first)
        assertEquals(FamilyAction.Help, seen[1].second)
    }
}
