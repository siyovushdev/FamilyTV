package ru.familytv.explore.stitch

import org.junit.Assert.*
import org.junit.Test
import ru.familytv.adventure.mechanics.MechanicVerdict
import ru.familytv.adventure.mechanics.PredictAndTestEngine
import ru.familytv.interaction.FamilyAction
import ru.familytv.voice.VoiceRuntime
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO

class WeatherPreviewStateTest {
    private fun prediction() = WeatherPreviewState().apply { activate("destination:WEATHER"); activate("start") }
    private fun experiment(place: IcePlace = IcePlace.SUN) = prediction().apply { activate("place:$place"); tick(WeatherRules.PLACE_MS) }
    private fun result(place: IcePlace = IcePlace.SUN) = experiment(place).apply { tick(WeatherRules.EXPERIMENT_MS) }
    private fun ask() = result().apply { activate("why") }
    private fun askAt(cause: Cause) = ask().apply { tick(WeatherRules.CAUSE_MS * cause.ordinal) }
    private fun build() = askAt(Cause.DONE).apply { activate("build") }
    private fun assertNextLocked(state: WeatherPreviewState, cause: Cause) {
        assertEquals(WeatherScreen.ASK, state.value.screen)
        assertEquals(cause, state.value.cause)
        assertFalse(state.value.canBuild)
        assertFalse("build" in state.controls())
        assertNotEquals("build", state.value.focus)
        repeat(20) { Direction.entries.forEach { state.move(it); assertNotEquals("build", state.value.focus) } }
        state.focus("build"); assertNotEquals("build", state.value.focus)
        assertFalse(state.activate("build"))
        assertEquals(WeatherScreen.ASK, state.value.screen)
    }
    private fun assertNextAvailable(state: WeatherPreviewState) {
        assertEquals(WeatherScreen.ASK, state.value.screen)
        assertEquals(Cause.DONE, state.value.cause)
        assertTrue(state.value.canBuild)
        assertTrue("build" in state.controls())
        assertFalse(EXPLANATION_FOCUS in state.controls())
        assertEquals("build", state.value.focus)
    }
    private fun placed(cover: Shelter = Shelter.CANOPY) = build().apply { activate("shelter:$cover"); tick(WeatherRules.PLACE_MS) }

    @Test fun weatherIsTheOnlyActiveWorldAndEntersTheLab() {
        assertEquals(listOf(Destination.WEATHER), Destination.entries.filter { it.available })
        val state = WeatherPreviewState(); state.activate("destination:WEATHER")
        assertEquals(WeatherScreen.INTRO, state.value.screen)
        assertEquals("start", state.value.focus)
    }
    @Test fun futureLocationsRemainHonestUnavailableWorldTargets() {
        Destination.entries.filterNot { it.available }.forEach {
            val state = WeatherPreviewState(); state.focus("destination:$it"); state.activate()
            assertEquals(WeatherScreen.HOME, state.value.screen)
            assertEquals("destination:$it", state.value.focus)
            assertTrue(state.value.unavailable)
        }
    }
    @Test fun bothPredictionsAreHypothesesWithoutJudgment() {
        IcePlace.entries.forEach { place ->
            val state = prediction(); state.activate("place:$place")
            assertEquals(place, state.value.hypothesis)
            assertEquals(MechanicVerdict.INCOMPLETE, PredictAndTestEngine.evaluate(state.predictionSnapshot(), WeatherRules.prediction))
            state.tick(WeatherRules.PLACE_MS); state.tick(WeatherRules.EXPERIMENT_MS)
            assertEquals(MechanicVerdict.OBSERVED, PredictAndTestEngine.evaluate(state.predictionSnapshot(), WeatherRules.prediction))
            assertEquals(WeatherRules.OBSERVATION, state.value.prompt())
        }
    }
    @Test fun chosenHypothesisSurvivesEverySubsequentStage() {
        val state = result(IcePlace.SHADE)
        state.activate("why"); state.tick(9600); state.activate("build"); state.activate("shelter:CANOPY"); state.tick(1800); state.activate("check"); state.tick(10000)
        assertEquals(IcePlace.SHADE, state.value.hypothesis)
    }
    @Test fun placeAnimationCompletesBeforeComparisonStarts() {
        val state = prediction(); state.activate("place:SUN"); state.tick(1799)
        assertEquals(WeatherScreen.PLACING_ICE, state.value.screen)
        state.tick(1); assertEquals(WeatherScreen.EXPERIMENT, state.value.screen)
    }
    @Test fun experimentAlwaysContainsTwoInitiallyIdenticalCubes() {
        val state = experiment()
        assertEquals(2, state.value.samples.size)
        assertEquals(setOf(IcePlace.SUN, IcePlace.SHADE), state.value.samples.map { it.place }.toSet())
        assertEquals(listOf(1f, 1f), state.value.samples.map { it.ice })
        assertEquals(listOf(0f, 0f), state.value.samples.map { it.water })
    }
    @Test fun experimentReachesFiniteResultWithoutAnyInput() {
        val state = experiment()
        repeat(280) { state.tick(50) }
        assertEquals(WeatherScreen.RESULT, state.value.screen)
        val end = state.checkpoint(); state.tick(60000)
        assertEquals(end, state.checkpoint())
    }
    @Test fun experimentIsDeterministicAndIndependentOfHypothesis() {
        assertEquals(result(IcePlace.SUN).value.samples, result(IcePlace.SHADE).value.samples)
        val a = experiment(); val b = experiment()
        repeat(100) { a.tick(50) }; b.tick(5000)
        assertEquals(a.checkpoint(), b.checkpoint())
    }
    @Test fun sunGraduallyShrinksFasterAndMakesMoreWater() {
        val samples = (0..10).map { WeatherRules.samples(it / 10f) }
        samples.zipWithNext().forEach { (before, after) ->
            for (i in 0..1) { assertTrue(after[i].ice < before[i].ice); assertTrue(after[i].water > before[i].water) }
        }
        val (sun, shade) = samples.last()
        assertTrue(sun.ice < shade.ice / 2)
        assertTrue(sun.water > shade.water * 3)
    }
    @Test fun timeLapseOnlyAdvancesTheSameExperiment() {
        val a = experiment(); val b = experiment()
        a.activate("speed"); b.tick(2500)
        assertEquals(a.value.samples, b.value.samples)
        assertEquals(WeatherScreen.EXPERIMENT, a.value.screen)
    }
    @Test fun askLumiCauseOrderUsesFiniteReadableBeats() {
        val state = ask(); val seen = mutableListOf(state.value.cause)
        repeat(4) { state.tick(WeatherRules.CAUSE_MS); seen += state.value.cause }
        assertEquals(listOf(Cause.SUN, Cause.HEAT, Cause.ICE, Cause.WATER, Cause.DONE), seen)
        assertEquals(WeatherScreen.ASK, state.value.screen)
        assertEquals(2, state.value.samples.size)
    }
    @Test fun repeatRestartsExplanationWithoutChangingResultOrHypothesis() {
        val state = ask(); state.tick(WeatherRules.CAUSE_MS * 3)
        val samples = state.value.samples; val hypothesis = state.value.hypothesis
        repeat(3) {
            state.semantic(FamilyAction.Repeat)
            assertEquals(Cause.SUN, state.value.cause)
            assertEquals(samples, state.value.samples)
            assertEquals(hypothesis, state.value.hypothesis)
            state.tick(WeatherRules.CAUSE_MS * 4)
        }
    }
    @Test fun buildHasExactlyTwoPhysicalChoices() {
        assertEquals(listOf("shelter:CANOPY", "shelter:LEAVES"), build().controls().filter { it.startsWith("shelter:") })
    }
    @Test fun checkIsAbsentUntilARealShelterFinishesPlacement() {
        val state = build()
        assertFalse(state.activate("check")); assertFalse(state.value.canCheck)
        state.activate("shelter:CANOPY"); state.tick(1799)
        assertFalse(state.activate("check")); assertFalse("check" in state.controls())
        state.tick(1)
        assertTrue(state.value.canCheck); assertEquals("check", state.value.focus)
    }
    @Test fun placedCoverNeverRevealsResultWithoutExplicitCheck() {
        val state = placed(); repeat(10) { state.tick(60000) }
        assertEquals(WeatherScreen.BUILD_PLACED, state.value.screen)
        assertEquals(0f, state.value.buildFraction)
        assertFalse(state.value.prompt().contains("медленнее"))
        state.activate("check")
        assertEquals(WeatherScreen.BUILD_RUNNING, state.value.screen)
        assertEquals(0f, state.value.buildFraction)
        state.tick(WeatherRules.BUILD_MS)
        assertEquals(WeatherScreen.BUILD_RESULT, state.value.screen)
        assertTrue(state.value.prompt().contains("медленнее"))
    }
    @Test fun eitherShelterProducesTheSameUnscoredDiscovery() {
        val prompts = Shelter.entries.map { placed(it).apply { activate("check"); tick(WeatherRules.BUILD_MS) }.value.prompt() }
        assertEquals(1, prompts.toSet().size)
        assertEquals("Под укрытием лёд тает медленнее.", prompts.first())
    }
    @Test fun replacingCoverResetsAllExperimentResults() {
        val state = placed(); state.activate("check"); state.tick(10000); state.activate("change")
        assertNull(state.value.shelter); assertEquals(0f, state.value.buildFraction)
        state.activate("shelter:LEAVES"); state.tick(1800)
        assertEquals(Shelter.LEAVES, state.value.shelter); assertEquals(0f, state.value.buildFraction)
    }
    @Test fun backRestoresWorldZoneAndShelterFocus() {
        val state = experiment(IcePlace.SHADE); state.back()
        assertEquals("place:SHADE", state.value.focus)
        state.back(); state.back(); assertEquals("destination:WEATHER", state.value.focus)
        val b = build(); b.focus("shelter:LEAVES"); b.activate(); b.tick(1800); b.back()
        assertEquals("shelter:LEAVES", b.value.focus)
    }
    @Test fun pauseFreezesMeaningfulStateAndResumeContinuesSameExperiment() {
        val state = experiment(IcePlace.SHADE); state.tick(5000); state.pause()
        val checkpoint = state.checkpoint(); state.tick(60000); assertEquals(checkpoint, state.checkpoint())
        val restored = WeatherPreviewState(checkpoint); restored.resume(); restored.tick(9000)
        assertEquals(WeatherScreen.RESULT, restored.value.screen)
        assertEquals(IcePlace.SHADE, restored.value.hypothesis)
    }
    @Test fun allTimedScreensSurviveCheckpointRecreation() {
        val states = listOf(prediction().apply { activate("place:SUN") }, experiment(), ask(), build().apply { activate("shelter:LEAVES") }, placed().apply { activate("check") })
        states.forEach { state ->
            state.tick(650); state.pause()
            val copy = WeatherPreviewState(state.checkpoint())
            copy.resume(); state.resume(); copy.tick(750); state.tick(750)
            assertEquals(state.checkpoint(), copy.checkpoint())
        }
    }
    @Test fun navigationAlwaysLandsOnAnExistingVisibleControl() {
        val states = listOf(WeatherPreviewState(), prediction(), experiment(), result(), ask(), build(), placed())
        states.forEach { state ->
            repeat(20) { Direction.entries.forEach { direction -> state.move(direction); assertTrue(state.controls().contains(state.value.focus)) } }
        }
    }
    @Test fun everyVisibleControlIsReachableIncludingDuringPlacement() {
        val states = listOf(WeatherPreviewState(), prediction(), prediction().apply { activate("place:SUN") },
            experiment(), result(), ask(), build(), build().apply { activate("shelter:CANOPY") },
            placed(), placed().apply { activate("check") }, placed().apply { activate("check"); tick(10000) })
        states.forEach { initial ->
            val queue = ArrayDeque<WeatherSnapshot>(); queue.add(initial.checkpoint())
            val visited = mutableSetOf<WeatherSnapshot>(); val reached = mutableSetOf<String>()
            while (queue.isNotEmpty()) {
                val snapshot = queue.removeFirst()
                if (!visited.add(snapshot)) continue
                reached += snapshot.focus
                Direction.entries.forEach { direction ->
                    val next = WeatherPreviewState(snapshot); next.move(direction); queue.add(next.checkpoint())
                }
            }
            assertTrue("${initial.value.screen}: unreachable ${initial.controls() - reached}", reached.containsAll(initial.controls()))
        }
    }
    @Test fun repeatIsReachableAndCanReturnToWorldObjects() {
        for (state in listOf(prediction(), build())) {
            state.move(Direction.DOWN); assertEquals("repeat", state.value.focus)
            state.move(Direction.UP); assertTrue(':' in state.value.focus)
        }
    }
    @Test fun normalizedVoiceUsesTheSameHypothesisActionsWithoutListening() {
        for ((phrase, target) in listOf("На солнце" to IcePlace.SUN, "В тень" to IcePlace.SHADE)) {
            val state = prediction(); assertTrue(state.semantic(FamilyAction.Answer(phrase)))
            assertEquals(target, state.value.hypothesis)
            assertEquals(WeatherScreen.PLACING_ICE, state.value.screen)
        }
    }
    @Test fun unrelatedVoiceCannotResubmitAnOldHypothesis() {
        val state = experiment(); state.back()
        assertFalse(state.semantic(FamilyAction.Answer("привет")))
        assertFalse(state.semantic(FamilyAction.Answer("проверить")))
        assertEquals(WeatherScreen.PREDICTION, state.value.screen)
    }
    @Test fun buildVoiceCannotCheckBeforePlacement() {
        val state = build()
        assertFalse(state.semantic(FamilyAction.Answer("проверить")))
        assertTrue(state.semantic(FamilyAction.Answer("навес")))
        assertFalse(state.semantic(FamilyAction.Answer("проверить")))
        state.tick(1800)
        assertTrue(state.semantic(FamilyAction.Answer("проверить")))
    }
    @Test fun previewBoundaryCannotMutateProductionStoresOrInitializeAi() {
        val sources = File("src/debug/java/ru/familytv/explore/stitch").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (forbidden in listOf("activityHistory(", "journeyCoordinator(", "adventureProgress(", "TrialEventStore(", "WatchProgressStore(", "startActivity(", "VoiceRuntime.get(", "newPipeline(", "prewarm(", "acquireLease(", "AudioRecord(", "GroundedQwenMatcher(", "VoiceCommandRouter(")) {
            assertFalse("Forbidden preview boundary: $forbidden", sources.contains(forbidden))
        }
        assertTrue(sources.contains("VoiceRuntime.peek()"))
        assertTrue(sources.contains("NarrationCoordinator("))
        assertTrue(sources.contains("PredictAndTestEngine.applySelect("))
        assertTrue(sources.contains("CauseEffectEngine.applyAction("))
    }
    @Test fun entryExistsOnlyInDebugAndProductionDoesNotReferenceIt() {
        val activity = "StitchExploreWeatherPreviewActivity"
        assertTrue(File("src/debug/AndroidManifest.xml").readText().contains(activity))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains(activity))
        assertFalse(File("src/main/java").walkTopDown().filter { it.extension == "kt" }.any { it.readText().contains(activity) })
        assertTrue(File("src/debug/assets/explore_weather/provenance.json").isFile)
    }

    // ---- UX polish: one Lumi on the Intro / Prediction background ----
    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun secondRobotPixels(image: BufferedImage): Int {
        var count = 0
        for (y in 330 until 640) for (x in 1300 until 1510) {
            val rgb = image.getRGB(x, y); val r = rgb shr 16 and 255; val g = rgb shr 8 and 255; val b = rgb and 255
            val white = minOf(r, g, b) > 200 && maxOf(r, g, b) - minOf(r, g, b) < 40
            val cyanLight = g > 200 && b > 200 && r < 170
            if (white || cyanLight) count++
        }
        return count
    }
    private fun assertIntroArtHasOneLumi() {
        val provenance = File("src/debug/assets/explore_weather/provenance.json").readText()
        val entry = Regex(""""asset": "app/src/debug/assets/explore_weather/weather_intro\.webp",\s*"source": "([^"]+)",\s*"sha256": "([0-9a-f]{64})",\s*"sourceSha256": "([0-9a-f]{64})"""").find(provenance)
        assertNotNull("weather_intro provenance entry", entry)
        val (source, assetSha, sourceSha) = entry!!.destructured
        assertEquals(assetSha, sha256(File("src/debug/assets/explore_weather/weather_intro.webp")))
        val previous = Regex(""""previousAssetSha256": "([0-9a-f]{64})"""").find(provenance)!!.groupValues[1]
        assertNotEquals("two-robot asset must not ship", previous, assetSha)
        val edited = File("../$source"); assertEquals(sourceSha, sha256(edited))
        val baseFile = File("../" + Regex(""""base": "([^"]+)"""").find(provenance)!!.groupValues[1])
        val base = ImageIO.read(baseFile); val oneLumi = ImageIO.read(edited)
        assertEquals(base.width to base.height, oneLumi.width to oneLumi.height)
        assertTrue("the second robot existed in the approved base", secondRobotPixels(base) > 1000)
        assertTrue("the second robot is gone", secondRobotPixels(oneLumi) < 50)
        // Main Lumi, sun, table, plates and all other scenery are pixel-identical to the approved base.
        var outside = 0; var mainLumi = 0
        for (y in 0 until base.height) for (x in 0 until base.width) {
            if (base.getRGB(x, y) == oneLumi.getRGB(x, y)) continue
            if (x !in 1194..1535 || y !in 297..731) outside++
            if (x in 1000 until 1291 && y in 220 until 466) mainLumi++
        }
        assertEquals("pixels changed outside the edit", 0, outside)
        assertEquals("main Lumi pixels changed", 0, mainLumi)
    }
    @Test fun introVisualUsesOneLumi() {
        val intro = WeatherPreviewState().apply { activate("destination:WEATHER") }
        assertEquals(WeatherScreen.INTRO, intro.value.screen)
        assertEquals("weather_intro", intro.value.background)
        assertIntroArtHasOneLumi()
    }
    @Test fun predictionVisualUsesOneLumi() {
        val prediction = prediction()
        assertEquals(WeatherScreen.PREDICTION, prediction.value.screen)
        assertEquals("weather_intro", prediction.value.background)
        prediction.activate("place:SHADE")
        assertEquals("weather_intro", prediction.value.background)
        assertIntroArtHasOneLumi()
    }

    // ---- UX polish: Ask Lumi explanation gates the next experiment ----
    @Test fun askEntryKeepsNextExperimentUnavailable() {
        val state = ask()
        assertEquals(EXPLANATION_FOCUS, state.value.focus)
        assertNextLocked(state, Cause.SUN)
    }
    @Test fun sunStageKeepsNextUnavailable() = assertNextLocked(askAt(Cause.SUN).apply { tick(WeatherRules.CAUSE_MS - 1) }, Cause.SUN)
    @Test fun heatStageKeepsNextUnavailable() = assertNextLocked(askAt(Cause.HEAT), Cause.HEAT)
    @Test fun iceStageKeepsNextUnavailable() = assertNextLocked(askAt(Cause.ICE), Cause.ICE)
    @Test fun waterStageKeepsNextUnavailable() = assertNextLocked(askAt(Cause.WATER).apply { tick(WeatherRules.CAUSE_MS - 1) }, Cause.WATER)
    @Test fun doneRevealsNextAsNaturalFocusWithoutAutoTransition() {
        val state = askAt(Cause.WATER); state.tick(WeatherRules.CAUSE_MS)
        assertNextAvailable(state)
        state.tick(60000); state.tick(60000)
        assertNextAvailable(state)
    }
    @Test fun prematureSelectBeforeDoneNeverOpensBuild() {
        val state = ask()
        for (cause in listOf(Cause.SUN, Cause.HEAT, Cause.ICE, Cause.WATER)) {
            assertEquals(cause, state.value.cause)
            val elapsed = state.value.elapsedMs
            repeat(5) { assertTrue(state.semantic(FamilyAction.Select)) }
            assertEquals(WeatherScreen.ASK, state.value.screen)
            assertEquals("OK on the explanation neither skips nor restarts it", elapsed, state.value.elapsedMs)
            assertFalse(state.value.canBuild)
            state.tick(WeatherRules.CAUSE_MS)
        }
        assertNextAvailable(state)
    }
    @Test fun explicitSelectAfterDoneOpensBuild() {
        val state = askAt(Cause.DONE)
        assertTrue(state.semantic(FamilyAction.Select))
        assertEquals(WeatherScreen.BUILD_CHOOSE, state.value.screen)
        assertEquals("shelter:CANOPY", state.value.focus)
    }
    @Test fun repeatAfterDoneLocksNextAgain() {
        val viaAction = askAt(Cause.DONE); viaAction.semantic(FamilyAction.Repeat)
        assertEquals(EXPLANATION_FOCUS, viaAction.value.focus)
        assertNextLocked(viaAction, Cause.SUN)
        val viaButton = askAt(Cause.DONE); viaButton.move(Direction.RIGHT)
        assertEquals("repeat", viaButton.value.focus)
        viaButton.activate()
        assertEquals("repeat", viaButton.value.focus)
        assertNextLocked(viaButton, Cause.SUN)
    }
    @Test fun repeatedDoneMakesNextAvailableAgain() {
        val state = askAt(Cause.DONE)
        repeat(3) {
            state.semantic(FamilyAction.Repeat)
            state.semantic(FamilyAction.Select)
            assertEquals(WeatherScreen.ASK, state.value.screen)
            for (cause in listOf(Cause.SUN, Cause.HEAT, Cause.ICE, Cause.WATER)) {
                assertEquals(cause, state.value.cause); assertFalse(state.value.canBuild)
                state.tick(WeatherRules.CAUSE_MS)
            }
            assertNextAvailable(state)
        }
        state.semantic(FamilyAction.Select)
        assertEquals(WeatherScreen.BUILD_CHOOSE, state.value.screen)
    }
    @Test fun repeatDuringSequenceRestartsWithoutUnlocking() {
        val state = askAt(Cause.ICE); state.semantic(FamilyAction.Repeat)
        assertNextLocked(state, Cause.SUN)
        state.tick(WeatherRules.CAUSE_MS * 3); assertNextLocked(state, Cause.WATER)
        state.tick(WeatherRules.CAUSE_MS); assertNextAvailable(state)
    }
    @Test fun pauseResumeAndRecreationNeverExposeNextBeforeDone() {
        val state = askAt(Cause.ICE); state.pause(); state.tick(60000)
        val checkpoint = state.checkpoint()
        assertEquals(Cause.ICE, checkpoint.cause); assertEquals(EXPLANATION_FOCUS, checkpoint.focus)
        assertFalse("paused OK only resumes in the host", state.activate())
        assertNextLocked(state, Cause.ICE)
        val restored = WeatherPreviewState(checkpoint); restored.resume()
        assertEquals(EXPLANATION_FOCUS, restored.value.focus)
        assertNextLocked(restored, Cause.ICE)
        // A stale focus from a previous DONE cannot leak into a restored, unfinished explanation.
        val stale = WeatherPreviewState(checkpoint.copy(focus = "build", paused = false))
        assertEquals(EXPLANATION_FOCUS, stale.value.focus)
        assertNextLocked(stale, Cause.ICE)
        restored.tick(WeatherRules.CAUSE_MS * 2)
        assertNextAvailable(restored)
        val doneCopy = WeatherPreviewState(restored.checkpoint())
        assertNextAvailable(doneCopy)
    }
    @Test fun backBehaviorAroundAskIsUnchanged() {
        for (cause in Cause.entries) {
            val state = askAt(cause); assertTrue(state.back())
            assertEquals(WeatherScreen.RESULT, state.value.screen); assertEquals("why", state.value.focus)
        }
        val fromBuild = build(); assertTrue(fromBuild.back())
        assertNextAvailable(fromBuild)
        val result = result(); result.back(); assertEquals(WeatherScreen.INTRO, result.value.screen)
    }
    @Test fun fullGatedFlowKeepsAiUninitialized() {
        val state = ask(); state.semantic(FamilyAction.Select); state.tick(WeatherRules.CAUSE_MS * 4)
        state.semantic(FamilyAction.Repeat); state.tick(WeatherRules.CAUSE_MS * 4); state.semantic(FamilyAction.Select)
        assertEquals(WeatherScreen.BUILD_CHOOSE, state.value.screen)
        assertNull(VoiceRuntime.peek())
    }
}
