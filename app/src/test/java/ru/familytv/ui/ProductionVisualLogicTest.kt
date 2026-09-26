package ru.familytv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.adventure.GuideEmotion
import ru.familytv.narration.CommonVoicePack
import ru.familytv.narration.NarrationQuality
import ru.familytv.narration.NarrationReadiness

class ProductionVisualLogicTest {
    @Test
    fun commonVoicePackIncludesNewDevPhrases() {
        assertTrue(CommonVoicePack.allKeys().containsAll(
            listOf("hello", "lets_start", "good_thought", "take_break", "repeat_task", "explore", "want_repeat"),
        ))
        assertEquals("narration/common/hello.wav", CommonVoicePack.pathFor("hello"))
    }

    @Test
    fun narrationQualityGateRejectsPlaceholderForRelease() {
        assertFalse(
            NarrationQuality.meetsAudioFirstRelease(
                NarrationQuality.readinessFromMarker("DEV_PLACEHOLDER"),
            ),
        )
        assertTrue(
            NarrationQuality.meetsAudioFirstRelease(
                NarrationQuality.readinessFromMarker("DEV_VOICE"),
            ),
        )
        assertEquals(
            NarrationReadiness.PRODUCTION_READY,
            NarrationQuality.readinessFromMarker("FINAL_VOICE"),
        )
    }

    @Test
    fun narrationQualityGateAcceptsInterim() {
        assertTrue(
            NarrationQuality.meetsAudioFirstRelease(
                NarrationQuality.readinessFromMarker("INTERIM_VOICE"),
            ),
        )
        assertFalse(
            NarrationQuality.meetsAudioFirstRelease(
                NarrationQuality.readinessFromMarker("DEV_PLACEHOLDER"),
            ),
        )
    }

    @Test
    fun lumiAssetSlotsAreStable() {
        assertTrue(LumiAssets.allSlotIds().contains("lumi_listening"))
        assertEquals("lumi_helping", LumiAssets.slotFor(GuideEmotion.HELPING))
    }
}
