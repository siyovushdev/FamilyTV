package ru.familytv.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.adventure.BuildAndTestParams
import ru.familytv.adventure.BuildPart
import ru.familytv.adventure.BuildSlot
import ru.familytv.adventure.MechanicType
import ru.familytv.adventure.PredictAndTestParams
import ru.familytv.adventure.PredictOption
import ru.familytv.adventure.mechanics.BuildAndTestEngine
import ru.familytv.adventure.mechanics.MechanicRegistry
import ru.familytv.adventure.mechanics.MechanicVerdict
import ru.familytv.adventure.mechanics.PredictAndTestEngine

class DiscoveryMechanicTest {
    private val predictParams = PredictAndTestParams(
        options = listOf(
            PredictOption("sun", "На солнце", listOf("солнце")),
            PredictOption("shade", "В тени", listOf("тень")),
        ),
        outcomeByOptionId = mapOf(
            "sun" to "Растаял быстро",
            "shade" to "Тает медленно",
        ),
        correctPredictionId = "sun",
    )

    private val buildParams = BuildAndTestParams(
        slots = listOf(BuildSlot("cover", "Крыша"), BuildSlot("base", "Низ")),
        parts = listOf(
            BuildPart("umbrella", "Зонтик", listOf("зонт"), "cover"),
            BuildPart("lamp", "Лампа", listOf("лампа"), "cover"),
            BuildPart("ice", "Холод", listOf("холод"), "base"),
            BuildPart("heat", "Грелка", listOf("грелка"), "base"),
        ),
        successPlacements = mapOf("umbrella" to "cover", "ice" to "base"),
        successObservation = "Хорошо держит холод",
        failObservation = "Так теплее — лёд тает",
    )

    @Test
    fun predictWrongHypothesisStillObservedNotWrong() {
        var snap = PredictAndTestEngine.initial(predictParams)
        snap = PredictAndTestEngine.applySelect(snap, predictParams, "shade")
        assertEquals(MechanicVerdict.INCOMPLETE, PredictAndTestEngine.evaluate(snap, predictParams))
        snap = PredictAndTestEngine.applySelect(snap, predictParams, "__test__")
        assertEquals(MechanicVerdict.OBSERVED, PredictAndTestEngine.evaluate(snap, predictParams))
        assertEquals("Тает медленно", snap.observation)
        assertNotEquals(MechanicVerdict.WRONG, PredictAndTestEngine.evaluate(snap, predictParams))
    }

    @Test
    fun predictVoiceTestPhrase() {
        var snap = PredictAndTestEngine.initial(predictParams)
        val (s1, _) = PredictAndTestEngine.applyVoice(snap, predictParams, "солнце")
        snap = s1
        val (s2, v) = PredictAndTestEngine.applyVoice(snap, predictParams, "проверить")
        assertEquals(MechanicVerdict.OBSERVED, v)
        assertTrue(s2.observation!!.contains("быстро"))
    }

    @Test
    fun buildFailExplainsWhy() {
        var snap = BuildAndTestEngine.initial(buildParams)
        snap = BuildAndTestEngine.applySelect(snap, buildParams, "lamp")
        snap = BuildAndTestEngine.applySelect(snap, buildParams, "heat")
        snap = BuildAndTestEngine.applySelect(snap, buildParams, "__test__")
        assertEquals(MechanicVerdict.OBSERVED, BuildAndTestEngine.evaluate(snap, buildParams))
        assertEquals("Так теплее — лёд тает", snap.observation)
    }

    @Test
    fun buildSuccessCorrect() {
        var snap = BuildAndTestEngine.initial(buildParams)
        snap = BuildAndTestEngine.applySelect(snap, buildParams, "umbrella")
        snap = BuildAndTestEngine.applySelect(snap, buildParams, "ice")
        snap = BuildAndTestEngine.applySelect(snap, buildParams, "__test__")
        assertEquals(MechanicVerdict.CORRECT, BuildAndTestEngine.evaluate(snap, buildParams))
    }

    @Test
    fun discoveryHasNoScoreVerdictWrong() {
        assertTrue(MechanicRegistry.isDiscovery(MechanicType.PREDICT_AND_TEST))
        assertTrue(MechanicRegistry.isDiscovery(MechanicType.BUILD_AND_TEST))
        assertFalse(MechanicRegistry.isDiscovery(MechanicType.VISUAL_SEARCH))
    }

    @Test
    fun causeEffectRules() {
        val model = CauseEffectModel(
            variables = listOf(
                SimVariable("temp", listOf("COLD", "WARM", "HOT"), "COLD"),
                SimVariable("ice", listOf("FROZEN", "MELTING", "MELTED"), "FROZEN"),
            ),
            rules = listOf(
                SimRule(
                    id = "melt",
                    whenAll = mapOf("temp" to "HOT"),
                    effect = SimEffect(set = mapOf("ice" to "MELTED"), observation = "Лёд растаял"),
                ),
            ),
        )
        var state = CauseEffectEngine.initial(model)
        assertEquals("FROZEN", state.values["ice"])
        state = CauseEffectEngine.applyAction(model, state, mapOf("temp" to "HOT"))
        assertEquals("MELTED", state.values["ice"])
        assertEquals("Лёд растаял", state.lastObservation)
        assertTrue("melt" in state.explored)
    }
}
