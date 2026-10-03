package ru.familytv.home

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.creative.StoryRoutes
import ru.familytv.explore.ExploreRoutes
import ru.familytv.interaction.*
import ru.familytv.move.MoveRoutes
import ru.familytv.voice.VoiceRuntime

class HomeProductionTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val debug = File(main.parentFile, "debug")
    private fun source(path: String) = File(main, "java/ru/familytv/$path").readText()
    private val homeSources by lazy {
        File(main, "java/ru/familytv/home").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }
    private val home = source("home/HomeActivity.kt")

    @Test fun homeContainsExactlyFiveChildWorlds() {
        assertEquals(listOf("Смотреть", "Играть", "Исследовать", "Создавать", "Двигаться"), HomeActivity.CHILD_WORLDS)
        val scenes = source("home/HomeSceneViews.kt")
        assertEquals(HomeActivity.CHILD_WORLDS, Regex("""HomeWorld\("([^"]+)"""").findAll(scenes).map { it.groupValues[1] }.toList())
        assertEquals(5, HomeActivity.WORLD_FOCUS.size)
        assertEquals(setOf("watch", "games", "explore", "create", "move"), HomeActivity.WORLD_FOCUS.toSet())
        assertFalse(home.contains("chip(\""))
        assertFalse(home.contains("GradientDrawable.Orientation.TL_BR"))
    }

    @Test fun watchRouteOpensProductionWatch() {
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "смотреть") as RouteResult.Action
        assertEquals(FamilyAction.OpenWorld("watch"), routed.action)
        assertTrue(home.contains("startActivity(Intent(this, WatchActivity::class.java))"))
        assertTrue(home.contains("FamilyAction.OpenWorld(\"watch\")"))
        assertFalse(home.contains("StitchWatchPreviewActivity"))
    }

    @Test fun gamesRouteOpensProductionGamesHome() {
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "хочу поиграть") as RouteResult.Action
        assertEquals(FamilyAction.OpenWorld("play"), routed.action)
        assertTrue(home.contains("ContentNavigator.gameIntent(this, \"play\")"))
        assertTrue(home.contains("FamilyAction.OpenWorld(\"play\")"))
        assertFalse(home.contains("StitchGamesPreviewActivity"))
    }

    @Test fun exploreRouteOpensProductionExplore() {
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "исследовать") as RouteResult.Action
        assertEquals(FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID), routed.action)
        assertTrue(home.contains("startActivity(ExploreRoutes.intent(this, destination))"))
        assertTrue(home.contains("dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID))"))
        assertFalse(home.contains("StitchExploreWeatherPreviewActivity"))
    }

    @Test fun createRouteOpensProductionStudio() {
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "создавай") as RouteResult.Action
        assertEquals(FamilyAction.OpenWorld(StoryRoutes.WORLD_ID), routed.action)
        assertTrue(home.contains("startActivity(StoryRoutes.intent(this))"))
        assertTrue(home.contains("if (StoryRoutes.opens(item))"))
        assertFalse(home.contains("StitchStoryBuilderPreviewActivity"))
    }

    @Test fun moveRouteOpensProductionMove() {
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "двигаться") as RouteResult.Action
        assertEquals(FamilyAction.OpenWorld(MoveRoutes.WORLD_ID), routed.action)
        assertTrue(home.contains("startActivity(MoveRoutes.intent(this))"))
        assertTrue(home.contains("MoveRoutes.handles(action.worldId)"))
        assertFalse(home.contains("StitchMovePreviewActivity"))
    }

    @Test fun backRestoresWatchFocus() {
        assertTrue(home.contains("putString(\"lastFocusId\", \"watch\")"))
        assertTrue(home.contains("cardViews[\"watch\"] = watch"))
    }

    @Test fun backRestoresGamesFocus() {
        assertTrue(home.contains("putString(\"lastFocusId\", \"games\")"))
        assertTrue(home.contains("cardViews[\"games\"] = games"))
    }

    @Test fun backRestoresExploreFocus() {
        assertEquals(2, Regex("putString\\(\"lastFocusId\", \"explore\"\\)").findAll(home).count())
        assertTrue(home.contains("cardViews[\"explore\"] = explore"))
    }

    @Test fun backRestoresCreateFocus() {
        assertTrue(home.contains("putString(\"lastFocusId\", StoryRoutes.CONTENT_ID)"))
        assertTrue(home.contains("cardViews[StoryRoutes.CONTENT_ID] = worlds[3]"))
    }

    @Test fun backRestoresMoveFocus() {
        assertTrue(home.contains("putString(\"lastFocusId\", MoveRoutes.CONTENT_ID)"))
        assertTrue(home.contains("cardViews[MoveRoutes.CONTENT_ID] = worlds[4]"))
    }

    @Test fun currentMissionUsesRealProductionData() {
        val content = source("home/HomeWorldContent.kt")
        assertTrue(content.contains("PlatformServices.journeyProgress(context)"))
        assertTrue(content.contains("WatchCatalog.continueWatching"))
        assertTrue(content.contains("JourneyEngine.resumeNodeId"))
        assertFalse(content.contains("15 минут"))
        assertFalse(content.contains("fixture"))
        assertFalse(content.contains("demo mission"))
        assertTrue(home.contains("d.journeyId != null && SpaceRoutes.handlesJourney(d.journeyId)"))
        assertTrue(home.contains("SpaceRoutes.intent(this, d.journeyId)"))
        assertTrue(home.contains("Intent(this, JourneyMapActivity::class.java)"))
        assertTrue(source("home/ContentNavigator.kt").contains("SpaceRoutes.opens(item)"))
    }

    @Test fun emptyMissionStateContainsNoFixtureData() {
        assertEquals("Сегодня вместе", HomeWorldContent.EMPTY_TITLE)
        assertEquals("Выбери остров стрелками. Луми рядом.", HomeWorldContent.EMPTY_CAPTION)
        val scenes = source("home/HomeSceneViews.kt")
        assertFalse(scenes.contains("\"12\""))
        assertFalse(scenes.contains("сохранено"))
        assertFalse(homeSources.contains("15 минут"))
        assertTrue(source("home/HomeWorldContent.kt").contains("emptyMission = true"))
        assertTrue(home.contains("else -> worlds[lastWorld].requestFocus()"))
    }

    @Test fun journalRemainsAccessible() {
        assertTrue(home.contains("openJournal()"))
        assertTrue(home.contains("DiscoveryJournalActivity"))
        assertTrue(home.contains("contentDescription = \"Наши открытия\""))
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "наши открытия")
        assertTrue(routed is RouteResult.Action)
        assertEquals(FamilyAction.OpenJournal, (routed as RouteResult.Action).action)
        assertTrue(home.contains("FamilyAction.OpenJournal"))
    }

    @Test fun parentModeRemainsAccessibleAndPinProtected() {
        assertTrue(home.contains("ParentModeActivity::class.java"))
        assertTrue(home.contains("contentDescription = \"Режим родителя. Нужен PIN\""))
        val parent = source("parent/ParentModeActivity.kt")
        assertTrue(parent.contains("Введите PIN"))
        assertTrue(parent.contains("showPinGate()"))
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "родителям") as RouteResult.Action
        assertEquals(FamilyAction.OpenParentGate, routed.action)
    }

    @Test fun noDuplicateChildWorldDestination() {
        assertEquals(5, HomeActivity.CHILD_WORLDS.distinct().size)
        assertEquals(5, HomeActivity.WORLD_FOCUS.distinct().size)
        val scenes = source("home/HomeSceneViews.kt")
        val titles = Regex("""HomeWorld\("([^"]+)"""").findAll(scenes).map { it.groupValues[1] }.toList()
        assertEquals(HomeActivity.CHILD_WORLDS, titles)
        assertTrue(home.contains("startActivity(Intent(this, WatchActivity::class.java))"))
        assertTrue(home.contains("ContentNavigator.gameIntent(this, \"play\")"))
        assertTrue(home.contains("startActivity(ExploreRoutes.intent(this, destination))"))
        assertTrue(home.contains("startActivity(StoryRoutes.intent(this))"))
        assertTrue(home.contains("startActivity(MoveRoutes.intent(this))"))
        assertFalse(home.contains("StitchHomePreviewActivity"))
    }

    @Test fun voiceAndDpadResolveSameDestinationSemantics() {
        fun world(phrase: String) = (VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), phrase) as RouteResult.Action).action as FamilyAction.OpenWorld
        assertEquals("watch", world("смотреть").worldId)
        assertEquals("play", world("поиграть").worldId)
        assertEquals(ExploreRoutes.WORLD_ID, world("исследуй").worldId)
        assertEquals(StoryRoutes.WORLD_ID, world("создавай").worldId)
        assertEquals(MoveRoutes.WORLD_ID, world("двигаться").worldId)
        assertTrue(home.contains("when (index) {"))
        assertTrue(home.contains("0 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(\"watch\"))"))
        assertTrue(home.contains("1 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(\"play\"))"))
        assertTrue(home.contains("2 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID))"))
        assertTrue(home.contains("3 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(StoryRoutes.WORLD_ID))"))
        assertTrue(home.contains("4 -> PlatformServices.actionDispatcher().dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(MoveRoutes.WORLD_ID))"))
    }

    @Test fun homeRecreationRestoresMeaningfulFocus() {
        assertTrue(home.contains("outState.putInt(\"lastWorld\", lastWorld)"))
        assertTrue(home.contains("outState.putString(\"focus\", requestedFocus)"))
        assertTrue(home.substringAfter("override fun onResume()").substringBefore("override fun onNewIntent")
            .contains("PlatformServices.actionDispatcher().attach(homeActionHandler)"))
        assertTrue(home.contains("restoreFocus()"))
        assertTrue(home.contains("cardViews[requestedFocus]"))
    }

    @Test fun aiIsNotInitializedOnHome() {
        assertNull(VoiceRuntime.peek())
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", ".prewarm(", "startListening(", "acquireLease(", "AudioRecord(", "SpeechRecognizer", "GroundedQwenMatcher", ".gguf", ".onnx")) {
            assertFalse(forbidden, homeSources.contains(forbidden))
        }
        assertTrue(homeSources.contains("VoiceRuntime.peek()"))
        assertTrue(home.contains("voice_control_only"))
    }

    @Test fun productionHasNoDependencyOnDebugHomeC() {
        val mainKotlin = File(main, "java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(mainKotlin.contains("StitchHomePreviewActivity"))
        assertFalse(mainKotlin.contains("home.stitch"))
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("StitchHome"))
        val harness = File(debug, "java/ru/familytv/home/stitch/StitchHomePreviewActivity.kt").readText()
        assertTrue(harness.contains(": HomeActivity()"))
        for (name in listOf("stitch_c_watch", "stitch_c_play", "stitch_c_explore", "stitch_c_create", "stitch_c_move")) {
            assertTrue(name, File(main, "res/drawable/$name.xml").isFile)
        }
    }

    @Test fun oldWhiteHomeIsNotLauncherVisible() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".home.HomeActivity\"").substringBefore("</activity>")
        assertTrue(entry.contains("android.intent.action.MAIN"))
        assertTrue(entry.contains("@style/Theme.FamilyTV.HomeC"))
        assertFalse(home.contains("Color.rgb(255, 250, 242)"))
        assertFalse(home.contains("Миры для семьи"))
        assertTrue(source("home/HomeSceneViews.kt").contains("color(\"#090D21\")"))
        assertEquals(5, HomeActivity.CHILD_WORLDS.size)
    }
}
