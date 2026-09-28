package ru.familytv.move

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.memoryPrefs
import ru.familytv.interaction.*
import ru.familytv.journey.CompletionPolicy
import ru.familytv.journey.JourneyParser
import ru.familytv.parent.ActivityHistoryStore
import ru.familytv.parent.ActivityKind
import ru.familytv.voice.VoiceRuntime

class MoveProductionTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val debug = File(main.parentFile, "debug")
    private fun source(path: String) = File(main, "java/ru/familytv/$path").readText()
    private val moveSources by lazy {
        File(main, "java/ru/familytv/move").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }
    private val judgment = listOf("очк", "балл", "xp", "калори", "правильно", "неправ", "засчитан", "распознан", "камера", "camera", "поза верн")

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

    @Test fun homeMoveRoutesToProductionMoveActivity() {
        for (phrase in listOf("двигайся", "двигаться", "хочу двигаться", "открой двигаться")) {
            val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), phrase) as RouteResult.Action
            assertEquals(FamilyAction.OpenWorld(MoveRoutes.WORLD_ID), routed.action)
        }
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("MoveRoutes.handles(action.worldId)"))
        assertTrue(home.contains("startActivity(MoveRoutes.intent(this))"))
        assertTrue(home.contains("if (MoveRoutes.opens(item))"))
        assertFalse(home.contains("chip(\"Двигаться\")") || home.contains("chip(\"Двигайся\")"))
        assertEquals("exactly one catalog move entry", 1, Regex("\"id\": \"move-stretch\"").findAll(File(main, "assets/catalog/manifest.json").readText()).count())
        val manifest = File(main, "AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".move.MoveActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        assertFalse(manifest.contains("StitchMovePreviewActivity"))
        assertTrue(source("home/ContentNavigator.kt").contains("if (MoveRoutes.opens(item))"))
        assertTrue(MoveRoutes.opens(ContentItem("move-stretch", "Разбудим звёзды", type = ContentType.INTERACTIVE, route = "move/stars")))
        assertFalse(MoveRoutes.opens(ContentItem("other", "Другое", type = ContentType.INTERACTIVE, experienceRef = "x.json")))
    }

    @Test fun moveHomeActiveAdventure() {
        val s = home()
        assertEquals(MoveScreen.MOVE_HOME, s.value.screen)
        assertEquals("place:STARS", s.value.focus)
        assertTrue(s.isAvailable("place:STARS"))
        assertEquals("Подвигаемся вместе?", s.prompt())
        s.select()
        assertEquals(MoveScreen.READY, s.value.screen)
        assertEquals("Разбудим звёзды", MovePlace.STARS.label)
    }

    @Test fun futureDestinationsUnavailableSafely() {
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
        assertTrue(s.prompt().contains("Пульт можно положить рядом"))
        s.activate("start")
        assertEquals(MoveScreen.REACH, s.value.screen)
        assertEquals(MovePhase.INTRO, s.value.phase)
    }

    @Test fun noMandatorySelectBetweenMovementBeats() {
        val s = reach()
        while (!(s.value.screen == MoveScreen.ENDING && s.value.phase == MovePhase.ACTIONS)) {
            val before = s.value.screen to s.value.phase
            s.tick(20_000L)
            assertNotEquals(before, s.value.screen to s.value.phase)
        }
        assertEquals(MovePhase.ACTIONS, s.value.phase)
        assertFalse(s.semantic(FamilyAction.Next))
    }

    @Test fun reachIsDeterministic() {
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

    @Test fun reachWorldConsequenceLightsStars() {
        val intro = reach()
        val done = after(MoveScreen.REACH, MovePhase.CONSEQUENCE)
        assertTrue(done.value.starsGlow > intro.value.starsGlow)
        assertTrue(done.value.starsGlow > 0.8f)
        assertTrue(done.prompt().contains("прос"))
    }

    @Test fun cometsIsDeterministic() {
        val left = after(MoveScreen.COMETS, MovePhase.LEFT)
        assertEquals(LumiPose.STEP_LEFT, left.value.pose)
        val right = after(MoveScreen.COMETS, MovePhase.RIGHT)
        assertEquals(LumiPose.STEP_RIGHT, right.value.pose)
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
        count.tick(MoveRules.FREEZE_COUNT_MS)
        assertEquals(MoveScreen.BALANCE, count.value.screen)
    }

    @Test fun freezeHasNoVerdictOrScore() {
        val s = after(MoveScreen.FREEZE, MovePhase.COUNTDOWN)
        val text = s.prompt().lowercase()
        for (word in judgment) assertFalse(word, text.contains(word))
        val src = moveSources.lowercase()
        for (forbidden in listOf("поза засчитана", "движение распознано", "leaderboard", "achievement", "calories")) {
            assertFalse(forbidden, src.contains(forbidden))
        }
    }

    @Test fun balanceIsDeterministic() {
        val intro = after(MoveScreen.BALANCE, MovePhase.INTRO)
        val done = after(MoveScreen.BALANCE, MovePhase.CONSEQUENCE)
        assertTrue(done.value.bridge > intro.value.bridge)
        intro.tick(MoveRules.BALANCE_INTRO_MS)
        assertEquals(MovePhase.ACTIVE, intro.value.phase)
        assertTrue(done.prompt().contains("полу"))
        done.tick(MoveRules.BALANCE_CONSEQUENCE_MS)
        assertEquals(MoveScreen.ENDING, done.value.screen)
        assertEquals(MovePhase.STORY_RESULT, done.value.phase)
    }

    @Test fun endingIsFinite() {
        val s = after(MoveScreen.ENDING, MovePhase.ACTIONS)
        assertTrue(s.value.actionsReady)
        s.tick(10 * 60_000L)
        assertEquals(MovePhase.ACTIONS, s.value.phase)
        assertEquals(MoveScreen.ENDING, s.value.screen)
        assertFalse(s.semantic(FamilyAction.Next))
    }

    @Test fun calmBreathHappensBeforeEndingActions() {
        val story = after(MoveScreen.ENDING, MovePhase.STORY_RESULT)
        assertEquals("Получилось! Звёзды проснулись.", story.prompt())
        assertFalse(story.value.actionsReady)
        assertFalse("replay" in story.controls())
        story.tick(MoveRules.ENDING_STORY_MS)
        assertEquals(MovePhase.BREATH_IN, story.value.phase)
        assertEquals("Вдох…", story.prompt())
        assertEquals(LumiPose.BREATHE_IN, story.value.pose)
        story.tick(MoveRules.ENDING_BREATH_MS)
        assertEquals(MovePhase.BREATH_OUT, story.value.phase)
        assertEquals("…и медленный выдох.", story.prompt())
        story.tick(MoveRules.ENDING_BREATH_MS)
        assertEquals(MovePhase.ACTIONS, story.value.phase)
        assertTrue(story.value.actionsReady)
        assertTrue("replay" in story.controls())
    }

    @Test fun pauseFreezesProgression() {
        val s = after(MoveScreen.COMETS, MovePhase.LEFT)
        val elapsed = s.value.elapsedMs
        s.semantic(FamilyAction.Pause)
        assertTrue(s.value.paused)
        s.tick(8_000L)
        assertEquals(MoveScreen.COMETS, s.value.screen)
        assertEquals(MovePhase.LEFT, s.value.phase)
        assertEquals(elapsed, s.value.elapsedMs)
        assertEquals(listOf("continue", "finish"), s.controls())
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

    @Test fun repeatDoesNotAdvance() {
        val s = after(MoveScreen.REACH, MovePhase.ACTIVE)
        val before = s.value
        s.semantic(FamilyAction.Repeat)
        assertEquals(before, s.value)
    }

    @Test fun helpDoesNotAdvance() {
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

    @Test fun replayRestartsAdventure() {
        val s = after(MoveScreen.ENDING, MovePhase.ACTIONS)
        s.activate("replay")
        assertEquals(MoveScreen.READY, s.value.screen)
        assertFalse(s.value.paused)
        s.activate("start")
        assertEquals(MoveScreen.REACH, s.value.screen)
        val activity = source("move/MoveActivity.kt")
        assertTrue(activity.contains("previous.screen == MoveScreen.READY && s.screen == MoveScreen.REACH"))
        assertTrue(activity.contains("previous.screen == MoveScreen.BALANCE && s.screen == MoveScreen.ENDING"))
        assertTrue(activity.contains("if (!visitCompleted &&"))
    }

    @Test fun backAndFocusRestoration() {
        val s = home(); s.semantic(FamilyAction.Back); assertTrue(s.exitRequested)
        val r = ready(); r.semantic(FamilyAction.Back); assertEquals(MoveScreen.MOVE_HOME, r.value.screen)
        val m = after(MoveScreen.COMETS, MovePhase.RIGHT); m.semantic(FamilyAction.Back)
        assertTrue(m.value.paused)
        m.activate("finish")
        assertEquals(MoveScreen.MOVE_HOME, m.value.screen)
        val e = after(MoveScreen.ENDING, MovePhase.ACTIONS); e.activate("home"); assertTrue(e.exitRequested)
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("putString(\"lastFocusId\", MoveRoutes.CONTENT_ID)"))
        assertTrue(home.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
            .contains("PlatformServices.actionDispatcher().attach(homeActionHandler)"))
    }

    @Test fun pauseResumeDoesNotDuplicateHistory() {
        val activity = source("move/MoveActivity.kt")
        val lifecycle = activity.substringAfter("override fun onResume()").substringBefore("override fun onSaveInstanceState")
        for (write in listOf("recordVisit(", "activityHistory(", "onContentOpened(", "onContentCompleted(")) {
            assertFalse(write, lifecycle.contains(write))
        }
        assertTrue(activity.contains("if (!visitOpened && previous.screen == MoveScreen.READY && s.screen == MoveScreen.REACH)"))
        assertTrue(activity.contains("if (!visitCompleted && previous.screen == MoveScreen.BALANCE && s.screen == MoveScreen.ENDING)"))
        assertTrue(activity.contains("outState.putBoolean(\"visitCompleted\", visitCompleted)"))
        val restored = MoveSnapshot.decode(after(MoveScreen.FREEZE, MovePhase.FREEZE).value.encode())
        assertEquals(MoveScreen.FREEZE, restored.screen)
        assertEquals(MovePhase.FREEZE, restored.phase)
    }

    @Test fun completionHappensAtCalmEnding() {
        val activity = source("move/MoveActivity.kt")
        assertTrue(activity.contains("kind = ActivityKind.MOVE"))
        assertTrue(activity.contains("onContentOpened(MoveRoutes.CONTENT_ID)"))
        assertTrue(activity.contains("onContentCompleted(MoveRoutes.CONTENT_ID)"))
        assertTrue(activity.contains("if (!recordsActivity) return"))
        val history = ActivityHistoryStore(memoryPrefs()) { 1_700_000_000_000L }
        history.record(ActivityKind.MOVE, MoveRoutes.FALLBACK_TITLE, MoveRoutes.CONTENT_ID, MoveRoutes.HISTORY_TOPICS, 44_000)
        val event = history.todaySummary().events.single()
        assertEquals(ActivityKind.MOVE, event.kind)
        assertEquals("move-stretch", event.contentId)
        assertEquals(0, history.todaySummary().totalWatchMs)
        val catalog = JSONObject(File(main, "assets/catalog/manifest.json").readText()).getJSONArray("items")
        val item = (0 until catalog.length()).map { catalog.getJSONObject(it) }.single { it.getString("id") == MoveRoutes.CONTENT_ID }
        assertEquals("INTERACTIVE", item.getString("type"))
        assertEquals("catalog/move/wake-stars.json", item.getString("experienceRef"))
        assertEquals("move/stars", item.getString("route"))
        assertEquals("Разбудим звёзды", item.getString("title"))
        val journey = JourneyParser.parse(File(main, "assets/catalog/journeys/space-grand-expedition.json").readText())
        val node = journey.nodes.single { it.contentId == MoveRoutes.CONTENT_ID }
        assertEquals(CompletionPolicy.CONTENT_OPENED, node.completionPolicy)
        assertEquals("REST", node.type.name)
        assertTrue(File(main, "assets/catalog/move/wake-stars.json").isFile)
        assertTrue(activity.contains("previous.screen != MoveScreen.MOVE_HOME && s.screen == MoveScreen.MOVE_HOME"))
    }

    @Test fun noFakeSensingOrCamera() {
        val src = moveSources.lowercase()
        for (forbidden in listOf("android.hardware.camera", "androidx.camera", "posedetector", "mlkit", "bodyskeleton", "movement detected", "зона 100", "комната безопасна", "camerax", "camera2")) {
            assertFalse(forbidden, src.contains(forbidden))
        }
        assertTrue(ready().prompt().contains("свободно"))
        assertFalse(ready().prompt().contains("%"))
    }

    @Test fun productionFlowNeverInitializesAi() {
        after(MoveScreen.ENDING, MovePhase.ACTIONS).apply { activate("replay"); activate("start") }
        assertNull(VoiceRuntime.peek())
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", ".prewarm(", "startListening(", "acquireLease(", "AudioRecord(", "SpeechRecognizer", "GroundedQwenMatcher", ".gguf", ".onnx")) {
            assertFalse(forbidden, moveSources.contains(forbidden))
        }
        assertTrue(moveSources.contains("VoiceRuntime.peek()"))
        assertTrue(moveSources.contains("PlatformServices.narrationCoordinator(this@MoveActivity)"))
    }

    @Test fun productionDoesNotDependOnDebugPreview() {
        for (name in MoveRoutes.ART) {
            assertTrue(name, File(main, "assets/move_v1/$name.jpg").isFile)
        }
        assertTrue(File(main, "assets/move_v1/provenance.json").readText().contains("\"asset\": \"app/src/main/assets/move_v1/move_home.jpg\""))
        assertTrue(File(main, "assets/move_v1/provenance.json").readText().contains("sha256"))
        val mainKotlin = File(main, "java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (forbidden in listOf("StitchMovePreviewActivity", "move.stitch", "src/debug")) {
            assertFalse(forbidden, mainKotlin.contains(forbidden))
        }
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("Stitch"))
        val harness = File(debug, "java/ru/familytv/move/stitch/StitchMovePreviewActivity.kt").readText()
        assertTrue(harness.contains(": MoveActivity()"))
        assertTrue(harness.contains("override val recordsActivity = false"))
        assertFalse(File(debug, "java/ru/familytv/move/stitch").walkTopDown().filter { it.name != "StitchMovePreviewActivity.kt" && it.extension == "kt" }.any())
    }
}
