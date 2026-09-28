package ru.familytv.move.stitch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.move.LumiPose
import ru.familytv.move.MovePhase
import ru.familytv.move.MovePreviewState
import ru.familytv.move.MoveRules
import ru.familytv.move.MoveScreen
import ru.familytv.move.MoveSnapshot
import ru.familytv.voice.VoiceRuntime
import java.io.File

class MovePreviewStateTest {
    private fun home() = MovePreviewState()
    private fun ready() = home().apply { activate("place:STARS") }
    private fun reach() = ready().apply { activate("start") }
    private fun after(screen: MoveScreen, phase: MovePhase): MovePreviewState {
        val s = reach()
        var guard = 0
        while ((s.value.screen != screen || s.value.phase != phase) && guard++ < 80) {
            val limit = when (s.value.screen to s.value.phase) {
                MoveScreen.REACH to MovePhase.INTRO -> MoveRules.REACH_INTRO_MS
                MoveScreen.REACH to MovePhase.ACTIVE -> MoveRules.REACH_ACTIVE_MS
                MoveScreen.REACH to MovePhase.CONSEQUENCE -> MoveRules.REACH_CONSEQUENCE_MS
                MoveScreen.COMETS to MovePhase.INTRO -> MoveRules.COMETS_INTRO_MS
                MoveScreen.COMETS to MovePhase.LEFT -> MoveRules.COMETS_LEFT_MS
                MoveScreen.COMETS to MovePhase.RIGHT -> MoveRules.COMETS_RIGHT_MS
                MoveScreen.COMETS to MovePhase.CONSEQUENCE -> MoveRules.COMETS_CONSEQUENCE_MS
                MoveScreen.FREEZE to MovePhase.MOVE -> MoveRules.FREEZE_MOVE_MS
                MoveScreen.FREEZE to MovePhase.FREEZE -> MoveRules.FREEZE_HOLD_MS
                MoveScreen.FREEZE to MovePhase.COUNTDOWN -> MoveRules.FREEZE_COUNT_MS
                MoveScreen.BALANCE to MovePhase.INTRO -> MoveRules.BALANCE_INTRO_MS
                MoveScreen.BALANCE to MovePhase.ACTIVE -> MoveRules.BALANCE_ACTIVE_MS
                MoveScreen.BALANCE to MovePhase.CONSEQUENCE -> MoveRules.BALANCE_CONSEQUENCE_MS
                MoveScreen.ENDING to MovePhase.STORY_RESULT -> MoveRules.ENDING_STORY_MS
                MoveScreen.ENDING to MovePhase.BREATH_IN, MoveScreen.ENDING to MovePhase.BREATH_OUT -> MoveRules.ENDING_BREATH_MS
                else -> 200L
            }
            s.tick(limit)
        }
        assertEquals(screen, s.value.screen)
        assertEquals(phase, s.value.phase)
        return s
    }
    private fun MovePreviewState.activate(key: String) { focus(key); select() }
    private fun production() = File("src/main/java/ru/familytv/move").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    private fun harness() = File("src/debug/java/ru/familytv/move/stitch").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    private val judgment = listOf("очк", "балл", "xp", "калори", "повторен", "правильно", "неправ", "засчитан", "распознан", "камера", "camera", "поза верн")

    @Test fun moveHomeActiveAdventure() {
        val s = home()
        assertEquals(MoveScreen.MOVE_HOME, s.value.screen)
        assertEquals("place:STARS", s.value.focus)
        assertTrue(s.isAvailable("place:STARS"))
        assertEquals("Подвигаемся вместе?", s.prompt())
        s.select()
        assertEquals(MoveScreen.READY, s.value.screen)
    }

    @Test fun futureDestinationsStayHonest() {
        for (key in listOf("place:MUSIC", "place:CLOUD", "place:TRAIL")) {
            val s = home(); s.activate(key)
            assertEquals(MoveScreen.MOVE_HOME, s.value.screen)
            assertEquals(key, s.value.soon)
            assertTrue(s.prompt().contains("скоро", ignoreCase = true))
            assertFalse(s.isAvailable(key))
        }
    }

    @Test fun readyStartsAdventure() {
        val s = ready()
        assertEquals(MoveScreen.READY, s.value.screen)
        assertTrue(s.prompt().contains("свободно"))
        s.activate("start")
        assertEquals(MoveScreen.REACH, s.value.screen)
        assertEquals(MovePhase.INTRO, s.value.phase)
    }

    @Test fun reachAdvancesDeterministically() {
        val s = reach()
        s.tick(MoveRules.REACH_INTRO_MS - 1)
        assertEquals(MovePhase.INTRO, s.value.phase)
        s.tick(1)
        assertEquals(MovePhase.ACTIVE, s.value.phase)
        s.tick(MoveRules.REACH_ACTIVE_MS)
        assertEquals(MovePhase.CONSEQUENCE, s.value.phase)
        s.tick(MoveRules.REACH_CONSEQUENCE_MS)
        assertEquals(MoveScreen.COMETS, s.value.screen)
    }

    @Test fun reachConsequenceLightsStars() {
        val intro = reach()
        val done = after(MoveScreen.REACH, MovePhase.CONSEQUENCE)
        assertTrue(done.value.starsGlow > intro.value.starsGlow)
        assertTrue(done.value.starsGlow > 0.8f)
    }

    @Test fun cometsHasLeftRightSequence() {
        val left = after(MoveScreen.COMETS, MovePhase.LEFT)
        assertEquals(LumiPose.STEP_LEFT, left.value.pose)
        val right = after(MoveScreen.COMETS, MovePhase.RIGHT)
        assertEquals(LumiPose.STEP_RIGHT, right.value.pose)
    }

    @Test fun cometsConsequenceOpensPath() {
        val intro = after(MoveScreen.COMETS, MovePhase.INTRO)
        val done = after(MoveScreen.COMETS, MovePhase.CONSEQUENCE)
        assertTrue(done.value.pathOpen > intro.value.pathOpen)
        assertTrue(done.value.pathOpen > 0.75f)
    }

    @Test fun freezeHasMoveFreezeCountdown() {
        val move = after(MoveScreen.FREEZE, MovePhase.MOVE)
        assertEquals(LumiPose.DANCE, move.value.pose)
        val hold = after(MoveScreen.FREEZE, MovePhase.FREEZE)
        assertEquals(LumiPose.FREEZE, hold.value.pose)
        val count = after(MoveScreen.FREEZE, MovePhase.COUNTDOWN)
        assertTrue(count.value.countdown in 1..3)
    }

    @Test fun freezeHasNoVerdictOrScore() {
        val s = after(MoveScreen.FREEZE, MovePhase.COUNTDOWN)
        val text = s.prompt().lowercase()
        for (word in judgment) assertFalse(word, text.contains(word))
        assertFalse(production().lowercase().contains("поза засчитана"))
        assertFalse(production().lowercase().contains("движение распознано"))
    }

    @Test fun balanceIsDeterministic() {
        val intro = after(MoveScreen.BALANCE, MovePhase.INTRO)
        val done = after(MoveScreen.BALANCE, MovePhase.CONSEQUENCE)
        assertTrue(done.value.bridge > intro.value.bridge)
        intro.tick(MoveRules.BALANCE_INTRO_MS)
        assertEquals(MovePhase.ACTIVE, intro.value.phase)
    }

    @Test fun endingIsFinite() {
        val s = after(MoveScreen.ENDING, MovePhase.ACTIONS)
        assertTrue(s.value.actionsReady)
        s.tick(10 * 60_000L)
        assertEquals(MovePhase.ACTIONS, s.value.phase)
        assertEquals(MoveScreen.ENDING, s.value.screen)
        assertFalse(s.semantic(FamilyAction.Next))
    }

    @Test fun noMandatorySelectBetweenMovementBeats() {
        val s = reach()
        while (!(s.value.screen == MoveScreen.ENDING && s.value.phase == MovePhase.ACTIONS)) {
            val before = s.value.screen to s.value.phase
            s.tick(20_000L)
            assertNotEquals(before, s.value.screen to s.value.phase)
        }
        assertEquals(MovePhase.ACTIONS, s.value.phase)
    }

    @Test fun pauseFreezesSemanticProgression() {
        val s = after(MoveScreen.COMETS, MovePhase.LEFT)
        val elapsed = s.value.elapsedMs
        s.semantic(FamilyAction.Pause)
        assertTrue(s.value.paused)
        s.tick(8_000L)
        assertEquals(MoveScreen.COMETS, s.value.screen)
        assertEquals(MovePhase.LEFT, s.value.phase)
        assertEquals(elapsed, s.value.elapsedMs)
    }

    @Test fun resumeContinuesSameBeat() {
        val s = after(MoveScreen.FREEZE, MovePhase.MOVE)
        s.semantic(FamilyAction.Pause)
        val beat = s.value.screen to s.value.phase
        s.semantic(FamilyAction.Resume)
        assertFalse(s.value.paused)
        assertEquals(beat, s.value.screen to s.value.phase)
        s.tick(MoveRules.FREEZE_MOVE_MS)
        assertEquals(MovePhase.FREEZE, s.value.phase)
    }

    @Test fun repeatDoesNotAdvanceState() {
        val s = after(MoveScreen.REACH, MovePhase.ACTIVE)
        val before = s.value
        s.semantic(FamilyAction.Repeat)
        assertEquals(before, s.value)
    }

    @Test fun helpDoesNotAdvanceState() {
        val s = after(MoveScreen.REACH, MovePhase.ACTIVE)
        val elapsed = s.value.elapsedMs
        s.semantic(FamilyAction.Help)
        assertTrue(s.value.helping)
        s.tick(MoveRules.HELP_MS - 1)
        assertEquals(MoveScreen.REACH, s.value.screen)
        assertEquals(MovePhase.ACTIVE, s.value.phase)
        assertEquals(elapsed, s.value.elapsedMs)
        s.tick(2)
        assertFalse(s.value.helping)
        assertEquals(elapsed, s.value.elapsedMs)
    }

    @Test fun endingReplayRestartsAdventure() {
        val s = after(MoveScreen.ENDING, MovePhase.ACTIONS)
        s.activate("replay")
        assertEquals(MoveScreen.READY, s.value.screen)
        assertFalse(s.value.paused)
        s.activate("start")
        assertEquals(MoveScreen.REACH, s.value.screen)
    }

    @Test fun exitAndBackAreSafe() {
        val s = home(); s.semantic(FamilyAction.Back); assertTrue(s.exitRequested)
        val r = ready(); r.semantic(FamilyAction.Back); assertEquals(MoveScreen.MOVE_HOME, r.value.screen)
        val m = after(MoveScreen.COMETS, MovePhase.RIGHT); m.semantic(FamilyAction.Back)
        assertTrue(m.value.paused)
        m.activate("finish")
        assertEquals(MoveScreen.MOVE_HOME, m.value.screen)
        val e = after(MoveScreen.ENDING, MovePhase.ACTIONS); e.activate("home"); assertTrue(e.exitRequested)
    }

    @Test fun previewNeverMutatesProductionStores() {
        val src = harness()
        for (forbidden in listOf("StoryStore(", "ActivityHistoryStore(", "activityHistory(", "journeyCoordinator(",
            "TrialEventStore(", "trialEvents(", "onContentOpened(", "onContentCompleted(", "onStoryCreated(")) {
            assertFalse(forbidden, src.contains(forbidden))
        }
        assertTrue(src.contains("override val recordsActivity = false"))
        assertTrue(production().contains("if (!recordsActivity) return"))
    }

    @Test fun noCameraOrMovementSensing() {
        val src = production().lowercase()
        for (forbidden in listOf("android.hardware.camera", "androidx.camera", "posedetector", "mlkit", "bodyskeleton", "movement detected", "зона 100", "комната безопасна", "camerax")) {
            assertFalse(forbidden, src.contains(forbidden))
        }
    }

    @Test fun noAiInitialization() {
        after(MoveScreen.ENDING, MovePhase.ACTIONS)
        assertNull(VoiceRuntime.peek())
        val src = production()
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", ".prewarm(", "startListening(", "acquireLease(", "AudioRecord(", "SpeechRecognizer", "GroundedQwenMatcher", ".gguf", ".onnx")) {
            assertFalse(forbidden, src.contains(forbidden))
        }
        assertTrue(src.contains("VoiceRuntime.peek()"))
        val routed = VoiceCommandRouter.route(ru.familytv.interaction.RouteContext(ru.familytv.interaction.InteractionSurface.OTHER), "повтори")
        assertTrue(routed is ru.familytv.interaction.RouteResult.Action)
        assertEquals(FamilyAction.Repeat, (routed as ru.familytv.interaction.RouteResult.Action).action)
        assertTrue(src.contains("PlatformServices.narrationCoordinator"))
    }

    @Test fun previewAbsentFromReleaseMainBoundary() {
        val main = File("src/main")
        val mainKotlin = File(main, "java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(mainKotlin.contains("StitchMovePreviewActivity"))
        assertFalse(mainKotlin.contains("move.stitch"))
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("StitchMove"))
        assertTrue(File(main, "AndroidManifest.xml").readText().contains(".move.MoveActivity"))
        assertTrue(File("src/main/assets/move_v1/move_home.jpg").isFile)
        assertTrue(File("src/main/assets/move_v1/provenance.json").readText().contains("sha256"))
        assertTrue(File("src/debug/AndroidManifest.xml").readText().contains("StitchMovePreviewActivity"))
        val restored = MoveSnapshot.decode(after(MoveScreen.FREEZE, MovePhase.FREEZE).value.encode())
        assertEquals(MoveScreen.FREEZE, restored.screen)
        assertEquals(MovePhase.FREEZE, restored.phase)
    }
}
