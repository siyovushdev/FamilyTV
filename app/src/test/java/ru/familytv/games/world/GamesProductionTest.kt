package ru.familytv.games.world

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.content.memoryPrefs
import ru.familytv.games.GameColor
import ru.familytv.games.GamesDestination
import ru.familytv.interaction.*
import ru.familytv.parent.ActivityHistoryStore
import ru.familytv.parent.ActivityKind

class GamesProductionTest {
    @Test fun homeAndCatalogRoutesKeepProductionActivityContracts() {
        val voice = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "хочу поиграть") as RouteResult.Action
        val route = (voice.action as FamilyAction.OpenWorld).worldId
        assertEquals(GamesDestination.HOME, GamesDestination.fromRoute(route))
        assertEquals("ru.familytv.games.GameHubActivity", GamesDestination.fromRoute(route)?.activityName)
        assertEquals(GamesDestination.MATH, GamesDestination.fromRoute("game/math"))
        assertEquals(GamesDestination.COLOR, GamesDestination.fromRoute("game-color"))
        assertNull(GamesDestination.fromRoute("game/weather"))
        assertNull(GamesDestination.fromRoute("game/maze"))
    }

    @Test fun voiceAnswerCannotSkipWorldActionAndHelpCannotSubmitIt() {
        val s = GamesState().apply { enterMath() }
        val answer = FamilyAction.Answer("5")
        assertNull(GamesActions.resolve(s, answer))
        s.beginCombine()
        assertNull(GamesActions.resolve(s, answer))
        s.finishCombine()
        assertEquals(GamesCommand.Number(5), GamesActions.resolve(s, answer))
        s.help()
        assertNull(GamesActions.resolve(s, answer))
        assertNull(s.mathChoice)
        assertEquals("?", s.equationResult)
        s.finishCounting()
        assertEquals(GamesCommand.Number(5), GamesActions.resolve(s, answer))
        assertNull(GamesActions.resolve(s, FamilyAction.Answer("не знаю")))
    }

    @Test fun nextIsExplicitProgressionNeverAnAliasForSelectedAnswer() {
        val s = GamesState().apply { enterMath(); beginCombine(); finishCombine() }
        assertNull(GamesActions.resolve(s, FamilyAction.Next))
        s.answerMath(4)
        assertNull(GamesActions.resolve(s, FamilyAction.Next))
        s.answerMath(5)
        assertEquals(0, s.mathRound)
        assertEquals(GamesCommand.Next, GamesActions.resolve(s, FamilyAction.Next))
        assertNull(GamesActions.resolve(s, FamilyAction.Answer("5")))
        assertEquals(GamesCommand.Back, GamesActions.resolve(s, FamilyAction.Back, busy = true))
    }

    @Test fun voicePaintUsesSameRealWingAndRequiresSeparateLaunch() {
        val s = GamesState().apply { enterColor() }
        val route = VoiceCommandRouter.route(RouteContext(InteractionSurface.GAME, awaitingAnswer = true), "красный") as RouteResult.Action
        assertEquals(GamesCommand.Paint(GameColor.RED), GamesActions.resolve(s, route.action))
        s.paint(GameColor.RED)
        assertEquals(GameColor.RED, s.painted)
        assertNull(GamesActions.resolve(s, FamilyAction.Next))
        assertFalse(s.launch())
        s.paint(GameColor.BLUE)
        assertFalse(s.launched)
        assertEquals(GamesCommand.Next, GamesActions.resolve(s, FamilyAction.Next))
        assertTrue(s.launch())
        assertFalse(s.launch())
        assertNull(s.paint(GameColor.RED))
        assertEquals(GameColor.BLUE, s.painted)
    }

    @Test fun backRestoresWorldCardAndRoundStartRestoresWorldAction() {
        val s = GamesState()
        for ((world, card) in listOf(GameWorld.MATH to "game:0", GameWorld.COLOR to "game:1")) {
            if (world == GameWorld.MATH) s.bindMath(CountCombineFixtures.apples())
            if (world == GameWorld.COLOR) s.bindColor(AttributeApplyFixtures.rocket())
            s.enter(world)
            s.backToGames()
            assertEquals(card, s.defaultFocus())
            assertEquals(GameWorld.HOME, s.world)
        }
        s.enterMath()
        assertEquals("world", s.defaultFocus())
        s.beginCombine(); s.finishCombine()
        assertEquals("choice:0", s.defaultFocus())
        s.answerMath(5)
        assertEquals("next", s.defaultFocus())
        s.nextMath()
        assertEquals("world", s.defaultFocus())
    }

    @Test fun recreatedVisitRecordsOnceUsingExistingDeviceHistorySchema() {
        val visit = GameVisit()
        visit.begin(GameWorld.MATH, 1000)
        visit.begin(GameWorld.MATH, 2000) // resume is not another visit
        val restored = GameVisit(visit.world, visit.startedAt)
        val store = ActivityHistoryStore(memoryPrefs()) { 1_700_000_000_000L }
        val end = requireNotNull(restored.finish(9000))
        store.record(ActivityKind.GAME, end.title, end.contentId, end.topics, end.durationMs)
        assertNull(restored.finish(9500))
        val event = store.todaySummary().events.single()
        assertEquals("game-math", event.contentId)
        assertEquals(ActivityKind.GAME, event.kind)
        assertEquals(listOf("math", "counting"), event.topics)
        assertEquals(8000L, event.durationMs)
        restored.begin(GameWorld.COLOR, 10000)
        assertEquals("game-color", restored.finish(12000)?.contentId)
    }

    @Test fun productionEntryCannotDependOnPreviewOrInitializeAi() {
        val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
        val world = File(main, "java/ru/familytv/games/world").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val hub = File(main, "java/ru/familytv/games/GameHubActivity.kt").readText()
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", ".prewarm(", "startListening(", "StitchGamesPreview", "stitch_games/", "reference_1")) {
            assertFalse(forbidden, (world + hub).contains(forbidden))
        }
        val activity = File(main, "java/ru/familytv/games/world/GamesActivity.kt").readText()
        assertTrue(activity.contains("PlatformServices.activityHistory(this).record("))
        assertTrue(activity.contains(".onContentCompleted(end.contentId)"))
        // Both finite completion and Back return direct catalog/session launches to their caller.
        assertEquals(2, Regex("if \\(initialGame != null\\) \\{ finish\\(\\); return }").findAll(activity).count())
        val session = File(main, "java/ru/familytv/session/LearningSessionActivity.kt").readText()
        assertTrue(session.substringAfter("override fun onResume()").substringBefore("private fun render()")
            .contains("PlatformServices.actionDispatcher().attach(actionHandler)"))
        val home = File(main, "java/ru/familytv/home/HomeActivity.kt").readText()
        assertFalse(home.contains("chip(\"Играть\")"))
        assertTrue(home.contains("ContentNavigator.gameIntent(this, \"play\")"))
        assertTrue(home.contains("FamilyAction.OpenWorld(\"play\")"))
        assertEquals(listOf("Смотреть", "Играть", "Исследовать", "Создавать", "Двигаться"), ru.familytv.home.HomeActivity.CHILD_WORLDS)
        for (asset in listOf("math_card", "orchard_background", "workshop_background")) {
            assertTrue(File(main, "assets/games_v2/$asset.webp").isFile)
        }
    }
}
