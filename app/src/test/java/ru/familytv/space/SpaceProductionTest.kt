package ru.familytv.space

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.adventure.AdventureParser
import ru.familytv.adventure.AdventurePhase
import ru.familytv.adventure.ChallengeScene
import ru.familytv.adventure.MechanicType
import ru.familytv.adventure.PathMazeParams
import ru.familytv.adventure.PatternSequenceParams
import ru.familytv.adventure.VisualSearchParams
import ru.familytv.interaction.FamilyAction
import ru.familytv.interaction.InteractionSurface
import ru.familytv.interaction.RouteContext
import ru.familytv.interaction.RouteResult
import ru.familytv.interaction.VoiceCommandRouter
import ru.familytv.journey.JourneyEngine
import ru.familytv.journey.JourneyParser
import ru.familytv.journey.JourneyProgress
import ru.familytv.voice.VoiceRuntime

class SpaceProductionTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val debug = File(main.parentFile, "debug")
    private fun source(path: String) = File(main, "java/ru/familytv/$path").readText()
    private val spaceSources by lazy {
        File(main, "java/ru/familytv/space").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }
    private val journeyJson by lazy { File(main, "assets/catalog/journeys/space-grand-expedition.json").readText() }
    private val adventureJson by lazy { File(main, "assets/catalog/adventures/space-expedition.json").readText() }
    private val journey by lazy { JourneyParser.parse(journeyJson) }
    private val adventure by lazy { AdventureParser.parse(adventureJson) }

    private fun progress(
        completed: Set<String> = emptySet(),
        open: Set<String> = setOf("launch"),
        last: String? = null,
        started: Boolean = false,
        done: Boolean = false,
    ) = JourneyProgress(
        journeyId = SpaceRoutes.JOURNEY_ID,
        openNodeIds = open,
        completedNodeIds = completed,
        lastVisitedNodeId = last,
        started = started,
        completed = done,
        journeyVersion = journey.version,
        updatedAt = 1L,
    )

    private fun space(
        completed: Set<String> = emptySet(),
        open: Set<String> = setOf("launch"),
        last: String? = null,
        started: Boolean = false,
        resumeScene: String? = null,
    ): SpaceExpeditionState {
        val p = JourneyEngine.sanitize(journey, progress(completed, open, last, started))
        val views = JourneyEngine.views(journey, p, { true })
        return SpaceExpeditionState(
            journey, p, adventure, resumeScene, views,
            JourneyEngine.resumeNodeId(journey, p),
        )
    }

    private fun openResumeChallenge(s: SpaceExpeditionState) {
        if (s.value.screen == SpaceScreen.ENTRY) s.activate("continue")
        if (s.value.screen == SpaceScreen.JOURNEY) s.activate("continue")
        if (s.value.screen == SpaceScreen.INTRO) s.activate("continue")
    }

    @Test fun homeMissionRoutesToSpaceExpedition() {
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("SpaceRoutes.handlesJourney(d.journeyId)"))
        assertTrue(home.contains("SpaceRoutes.intent(this, d.journeyId)"))
        assertTrue(home.contains("openJourneyIntent"))
        assertEquals(SpaceRoutes.JOURNEY_ID, journey.id)
        assertEquals("Большая космическая экспедиция", journey.title)
        val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "давай продолжим космос")
        assertTrue(routed is RouteResult.Action)
        assertEquals(FamilyAction.ContinueJourney(SpaceRoutes.JOURNEY_ID), (routed as RouteResult.Action).action)
    }

    @Test fun exploreSpaceUsesSameExpeditionDomain() {
        assertTrue(SpaceRoutes.opens(ru.familytv.content.ContentItem(SpaceRoutes.CONTENT_ID, "Космос", type = ru.familytv.content.ContentType.ADVENTURE, adventureRef = SpaceRoutes.ADVENTURE_ASSET)))
        assertTrue(source("home/ContentNavigator.kt").contains("SpaceRoutes.opens(item)"))
        assertTrue(source("explore/ExploreActivity.kt").contains("ContentNavigator.open(this, item)"))
        assertEquals("adv-space-expedition", ru.familytv.explore.ExploreDestination.SPACE.contentId)
        assertEquals(SpaceRoutes.JOURNEY_ID, journey.id)
        assertEquals("space-expedition", adventure.id)
    }

    @Test fun freshJourneyShowsFirstStart() {
        val s = space()
        assertEquals(SpaceScreen.ENTRY, s.value.screen)
        assertFalse(s.value.started)
        assertEquals("Старт", s.value.currentLabel)
        assertNull(s.value.lastCompletedLabel)
        s.activate("journey")
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        val launch = s.value.waypoints.first { it.nodeId == "launch" }
        assertEquals(SpaceNodeVisual.CURRENT, launch.visual)
        assertTrue(s.value.waypoints.filter { it.nodeId != "launch" && !it.optional }.all { it.visual == SpaceNodeVisual.LOCKED || it.visual == SpaceNodeVisual.AVAILABLE })
    }

    @Test fun partialJourneyShowsRealResume() {
        val s = space(completed = setOf("light-lab", "temp-lab"), open = setOf("launch", "stretch", "calm-end"), last = "temp-lab", started = true)
        assertTrue(s.value.started)
        assertEquals("Тепло", s.value.lastCompletedLabel)
        assertEquals("Старт", s.value.currentLabel)
        val byId = s.value.waypoints.associateBy { it.nodeId }
        assertEquals(SpaceNodeVisual.CURRENT, byId.getValue("launch").visual)
        assertEquals(SpaceNodeVisual.COMPLETED, byId.getValue("light-lab").visual)
        assertEquals(SpaceNodeVisual.COMPLETED, byId.getValue("temp-lab").visual)
        assertEquals(SpaceNodeVisual.LOCKED, byId.getValue("moon-hub").visual)
        assertEquals(SpaceNodeVisual.AVAILABLE, byId.getValue("stretch").visual)
    }

    @Test fun completedCurrentAndLockedNodesAreDistinct() {
        val s = space(completed = setOf("launch"), open = setOf("moon-hub"), last = "launch", started = true)
        val byId = s.value.waypoints.associateBy { it.nodeId }
        assertEquals(SpaceNodeVisual.COMPLETED, byId.getValue("launch").visual)
        assertEquals(SpaceNodeVisual.CURRENT, byId.getValue("moon-hub").visual)
        assertEquals(SpaceNodeVisual.LOCKED, byId.getValue("light-lab").visual)
        assertEquals(SpaceNodeVisual.LOCKED, byId.getValue("final-mission").visual)
        assertNotEquals(byId.getValue("launch").visual, byId.getValue("moon-hub").visual)
        assertNotEquals(byId.getValue("moon-hub").visual, byId.getValue("light-lab").visual)
    }

    @Test fun dpadJourneyFocusIsDeterministic() {
        val s = space(started = true)
        s.activate("journey")
        assertEquals("node:launch", s.value.focus)
        s.move(SpacePad.RIGHT)
        assertTrue(s.value.focus.startsWith("node:"))
        s.move(SpacePad.LEFT)
        assertEquals("node:launch", s.value.focus)
        s.move(SpacePad.DOWN)
        assertEquals("continue", s.value.focus)
        s.move(SpacePad.UP)
        assertEquals("node:launch", s.value.focus)
    }

    @Test fun currentStageOpensRealLaunchAdventure() {
        val s = space(started = true)
        s.semantic(FamilyAction.Select)
        assertEquals(SpaceScreen.INTRO, s.value.screen)
        assertEquals("intro", s.runtime()!!.snapshot().sceneId)
        assertTrue(s.takeOpened())
        assertTrue(s.visitWasOpened())
    }

    @Test fun activeChallengeUsesAuthoredValues() {
        val maze = adventure.scenes.filterIsInstance<ChallengeScene>().first { it.challenge.mechanic == MechanicType.PATH_MAZE }
        val params = maze.challenge.params as PathMazeParams
        assertEquals(4, params.width)
        assertEquals(3, params.height)
        assertEquals("EVEN_NUMBERS", params.allowedLabelRule.name)
        assertEquals("4", params.cellLabels[ru.familytv.adventure.GridPos(1, 1)])
        assertFalse(params.cellLabels.values.containsAll(listOf("3", "6", "5", "7")) && params.cellLabels.size == 4)
        val pattern = adventure.scenes.filterIsInstance<ChallengeScene>().first { it.challenge.mechanic == MechanicType.PATTERN_SEQUENCE }
        val seq = pattern.challenge.params as PatternSequenceParams
        assertEquals(listOf("5", "6", "7"), seq.options)
        assertEquals("6", seq.correctOption)
        val s = space(started = true, resumeScene = "nav_maze")
        openResumeChallenge(s)
        assertEquals("PATH_MAZE", s.value.mechanicType)
        assertTrue(s.value.choices.any { it.label == "2" })
        assertTrue(s.value.choices.any { it.label == "4" })
        assertTrue(s.value.choices.any { it.label == "8" })
        assertEquals(params.width * params.height, s.value.choices.size)
    }

    @Test fun correctRuleIsDomainDriven() {
        val src = spaceSources
        assertFalse(src.contains("correctOption = \"6\""))
        assertFalse(src.contains("listOf(\"3\", \"6\", \"5\", \"7\")"))
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        val seq = (s.runtime()!!.currentScene() as ChallengeScene).challenge.params as PatternSequenceParams
        s.activate("opt:${seq.correctOption}")
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertTrue(s.value.worldReacted)
        assertEquals(true, s.runtime()!!.snapshot().feedbackCorrect)
    }

    @Test fun wrongAttemptDoesNotCompleteAndKeepsState() {
        val s = space(started = true, resumeScene = "nav_maze")
        openResumeChallenge(s)
        val before = s.runtime()!!.snapshot()
        s.activate("cell:0,0") // authored odd 3, adjacent to start
        assertFalse(s.runtime()!!.isCompleted())
        assertEquals(AdventurePhase.CHALLENGE_ACTIVE, s.runtime()!!.snapshot().phase)
        assertEquals(before.sceneId, s.runtime()!!.snapshot().sceneId)
        assertEquals(before.mechanic?.path, s.runtime()!!.snapshot().mechanic?.path)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertTrue(s.value.wrongMs > 0)
        assertFalse(s.takeCompleted())
    }

    @Test fun helpDoesNotAutoComplete() {
        val s = space(started = true, resumeScene = "nav_maze")
        openResumeChallenge(s)
        val path = s.runtime()!!.snapshot().mechanic?.path
        s.semantic(FamilyAction.Help)
        assertFalse(s.runtime()!!.isCompleted())
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertTrue(s.value.helping)
        assertFalse(s.takeCompleted())
        assertEquals(path, s.runtime()!!.snapshot().mechanic?.path)
    }

    @Test fun correctCompletionTriggersWorldConsequence() {
        val s = space(started = true, resumeScene = "nav_maze")
        openResumeChallenge(s)
        s.activate("cell:1,1")
        s.activate("cell:2,1")
        s.activate("cell:3,1")
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertTrue(s.value.worldReacted)
        assertTrue(s.value.engineOn || s.value.flying || s.value.routeLit)
        assertEquals(true, s.runtime()!!.snapshot().feedbackCorrect)
    }

    @Test fun progressUsesExistingJourneyStoreAndUpdatesAfterComplete() {
        val before = progress(open = setOf("launch"), started = true)
        val afterLaunch = JourneyEngine.completeNode(journey, before, "launch")
        assertTrue("launch" in afterLaunch.completedNodeIds)
        assertTrue("moon-hub" in afterLaunch.openNodeIds)
        val s = space(completed = afterLaunch.completedNodeIds, open = afterLaunch.openNodeIds, last = "launch", started = true)
        assertEquals(SpaceNodeVisual.COMPLETED, s.value.waypoints.first { it.nodeId == "launch" }.visual)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("PlatformServices.journeyCoordinator"))
        assertFalse(source("space/SpaceExpeditionActivity.kt").contains("JourneyProgressStore("))
    }

    @Test fun backDoesNotComplete() {
        val s = space(started = true, resumeScene = "nav_maze")
        openResumeChallenge(s)
        s.semantic(FamilyAction.Back)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertFalse(s.takeCompleted())
        assertFalse(s.runtime()!!.isCompleted())
    }

    @Test fun pauseResumeDoesNotDuplicateOpened() {
        val s = space(started = true)
        openResumeChallenge(s)
        assertTrue(s.takeOpened())
        s.semantic(FamilyAction.Pause)
        assertTrue(s.value.paused)
        s.semantic(FamilyAction.Resume)
        assertFalse(s.value.paused)
        assertFalse(s.takeOpened())
        s.semantic(FamilyAction.Pause)
        s.activate("continue")
        assertFalse(s.takeOpened())
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("restoreOpenedVisit"))
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("visitOpened"))
    }

    @Test fun homeAndExploreFocusRestorationStayWired() {
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("rememberFocus(\"mission\")"))
        assertTrue(source("explore/ExploreActivity.kt").contains("controls[state.value.focus]?.requestFocus()"))
        assertTrue(source("explore/ExploreActivity.kt").contains("openExperience"))
    }

    @Test fun forestJourneyMapIsUnchanged() {
        val map = source("journey/JourneyMapActivity.kt")
        assertTrue(map.contains("JourneyMapStyle.FOREST_TRAIL"))
        assertTrue(map.contains("if (SpaceRoutes.handlesJourney(journeyId))"))
        assertTrue(map.contains("node.branches.isNotEmpty() && node.type == JourneyNodeType.HUB"))
        assertTrue(map.contains("showBranchPicker"))
        assertFalse(map.contains("SpaceWorldView"))
        assertFalse(map.contains("space_expedition_v1"))
        val forest = File(main, "assets/catalog/journeys/forest-secrets.json")
        assertTrue(forest.isFile)
        assertTrue(source("home/ContentNavigator.kt").contains("AdventureActivity::class.java"))
    }

    @Test fun aiIsNotInitialized() {
        assertFalse(spaceSources.contains("ensureVoice"))
        assertFalse(spaceSources.contains("VoiceAnswerPipeline"))
        assertFalse(spaceSources.contains("MicPermission"))
        assertFalse(spaceSources.contains(".gguf"))
        assertFalse(spaceSources.contains(".onnx"))
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("VoiceRuntime.peek()"))
        assertEquals(null, VoiceRuntime.peek())
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("AI=\${if (VoiceRuntime.peek() == null) 0 else 1}"))
    }

    @Test fun productionDoesNotUseWebView() {
        assertFalse(spaceSources.contains("android.webkit"))
        assertFalse(spaceSources.contains("WebView"))
        assertFalse(spaceSources.contains("WebViewClient"))
        assertFalse(spaceSources.contains("loadUrl"))
        assertFalse(spaceSources.contains("evaluateJavascript"))
        assertFalse(source("space/SpaceExpeditionActivity.kt").contains(".html"))
        assertFalse(File(main, "java/ru/familytv/space/SpacePatternBoard.kt").exists())
        assertFalse(File(main, "java/ru/familytv/space/SpaceHearthBoard.kt").exists())
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("SpaceWorldView"))
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("RGB_565"))
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("clearExcept"))
        assertEquals(1, source("space/SpaceExpeditionActivity.kt").split("setContentView").size - 1)
        SpaceRoutes.BACKGROUNDS.values.forEach { name ->
            assertTrue(name, File(main, "assets/space_expedition_v1/$name").isFile)
        }
        assertFalse(SpaceRoutes.BACKGROUNDS.containsKey("journey_updated"))
        assertFalse(SpaceRoutes.BACKGROUNDS.containsValue("journey_updated_bg.jpg"))
        assertFalse(source("space/SpaceNativeWorld.kt").contains("journey_updated"))
        assertTrue(source("space/SpaceNativeWorld.kt").contains("SpaceScreen.JOURNEY, SpaceScreen.HUB -> \"journey\""))
        assertTrue(File(main, "assets/space_expedition_v1/journey_bg.jpg").isFile)
        assertTrue(File(main, "assets/space_expedition_v1/journey_updated_bg.original.jpg").isFile)
        assertTrue(File(main, "assets/space_expedition_v1/fonts/PlusJakartaSans.ttf").isFile)
        assertFalse(spaceSources.contains("Stitch"))
        assertFalse(spaceSources.contains("stitch"))
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains(".space.SpaceExpeditionActivity"))
        val entry = manifest.substringAfter("android:name=\".space.SpaceExpeditionActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        if (debug.exists()) {
            assertFalse(source("space/SpaceExpeditionActivity.kt").contains("debug/"))
        }
        assertFalse(source("space/SpaceExpeditionActivity.kt").contains("StitchHome"))
    }

    @Test fun nativeEntryControlsStayWired() {
        val s = space()
        assertEquals(listOf("home", "continue", "journey", "journal"), s.controls())
        assertEquals("continue", s.value.focus)
        s.move(SpacePad.LEFT)
        assertEquals("home", s.value.focus)
        s.move(SpacePad.RIGHT)
        s.move(SpacePad.RIGHT)
        assertEquals("journey", s.value.focus)
        s.activate("journey")
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
    }

    @Test fun helpAndWrongStayOnChallengeWithoutReload() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        s.semantic(FamilyAction.Help)
        assertTrue(s.value.helping)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        s.activate("help")
        assertFalse(s.value.helping)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        s.activate("opt:5")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertTrue(s.value.wrongMs > 0)
        assertFalse(s.runtime()!!.isCompleted())
    }

    @Test fun successConsequenceHasVisiblePhasesThenJourney() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        s.activate("opt:6")
        assertEquals(SpaceBeat.INSTALL, s.value.beat)
        s.tick(600)
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertEquals(SpaceBeat.IGNITE, s.value.beat)
        s.tick(700)
        assertEquals(SpaceBeat.TRAVEL, s.value.beat)
        assertTrue(s.value.flying)
        while (s.value.screen == SpaceScreen.SUCCESS) s.tick(200)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertTrue(s.value.worldReacted)
        assertEquals(SpaceBeat.SETTLE, s.value.beat)
    }

    @Test fun allSpaceMechanicsStayPlayable() {
        val mechanics = adventure.scenes.filterIsInstance<ChallengeScene>().map { it.challenge.mechanic }.toSet()
        assertEquals(
            setOf(MechanicType.VISUAL_SEARCH, MechanicType.PATH_MAZE, MechanicType.PATTERN_SEQUENCE, MechanicType.SORT_AND_CLASSIFY),
            mechanics,
        )
        for (scene in adventure.scenes.filterIsInstance<ChallengeScene>()) {
            val s = space(started = true, resumeScene = scene.id)
            openResumeChallenge(s)
            assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
            assertEquals(scene.challenge.mechanic.name, s.value.mechanicType)
            assertTrue(s.value.choices.isNotEmpty() || s.value.sequenceDisplay.isNotEmpty())
            s.semantic(FamilyAction.Help)
            assertFalse(s.runtime()!!.isCompleted())
            s.semantic(FamilyAction.Back)
            assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        }
    }

    @Test fun visualSearchUsesAuthoredBatteriesNotHardcodedQuiz() {
        val scene = adventure.scenes.filterIsInstance<ChallengeScene>().first { it.challenge.id == "ship-power" }
        val params = scene.challenge.params as VisualSearchParams
        assertEquals(setOf("cell_a", "cell_b", "cell_c"), params.requiredIds)
        val s = space(started = true, resumeScene = "launch_repair")
        openResumeChallenge(s)
        params.requiredIds.forEach { s.activate("obj:$it") }
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertTrue(s.value.engineOn || s.value.worldReacted)
    }

    @Test fun journeyNodesMatchProductionCatalog() {
        assertEquals(listOf("launch", "moon-hub", "light-lab", "temp-lab", "stretch", "final-mission", "calm-end", "story-side"), journey.nodes.map { it.id })
        val s = space()
        assertEquals(journey.nodes.map { it.id }.toSet(), s.value.waypoints.map { it.nodeId }.toSet())
        assertFalse(s.value.guideLine.contains("85%"))
        assertFalse(s.value.guideLine.contains("Этап 3 из 5"))
        assertFalse(spaceSources.contains("Звёздная туманность"))
    }

    @Test fun patternPhysicalOptionTransitionsWithoutQuizSubmit() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertEquals("PATTERN_SEQUENCE", s.value.mechanicType)
        assertEquals(listOf("2", "4", "?", "8"), s.value.sequenceDisplay)
        assertTrue(s.controls().any { it.startsWith("opt:") })
        assertFalse(s.controls().contains("check"))
        s.activate("opt:5")
        assertEquals(SpaceBeat.REJECT, s.value.beat)
        assertEquals("opt:5", s.value.flyingKey)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
    }

    @Test fun patternWrongReturnsOptionWithoutCompletion() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        val beforeScene = s.runtime()!!.snapshot().sceneId
        s.activate("opt:5")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertFalse(s.runtime()!!.isCompleted())
        assertEquals(AdventurePhase.CHALLENGE_ACTIVE, s.runtime()!!.snapshot().phase)
        assertEquals(beforeScene, s.runtime()!!.snapshot().sceneId)
        assertTrue(s.value.wrongMs > 0)
        assertFalse(s.takeCompleted())
        s.activate("opt:7")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertFalse(s.runtime()!!.isCompleted())
    }

    @Test fun patternHelpDoesNotAutoAnswer() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        val before = s.runtime()!!.snapshot().mechanic?.chosenOption
        s.semantic(FamilyAction.Help)
        assertTrue(s.value.helping)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertFalse(s.runtime()!!.isCompleted())
        assertEquals(before, s.runtime()!!.snapshot().mechanic?.chosenOption)
        assertNotEquals("6", s.runtime()!!.snapshot().mechanic?.chosenOption ?: "")
        assertFalse(s.takeCompleted())
    }

    @Test fun patternCorrectEntersConsequenceThenJourney() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        s.activate("opt:6")
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertEquals(SpaceBeat.INSTALL, s.value.beat)
        assertTrue(s.value.worldReacted)
        assertTrue(s.value.routeLit)
        assertEquals(true, s.runtime()!!.snapshot().feedbackCorrect)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "launch" }.visual)
        assertNotEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        repeat(20) { s.tick(200) }
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "launch" }.visual)
        assertEquals(SpaceNodeVisual.LOCKED, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        assertFalse(s.takeCompleted())
        assertFalse(s.runtime()!!.isCompleted())
    }

    @Test fun consequenceCompletesBeforeReturningToJourney() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        s.activate("opt:6")
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        s.tick(400)
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertTrue(s.value.successMs < SPACE_CONSEQUENCE_MS)
        while (s.value.screen == SpaceScreen.SUCCESS) s.tick(200)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertTrue(s.value.worldReacted)
    }

    @Test fun updatedJourneyUsesSameWorldAndRealProgress() {
        val s = space(started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        val beforeDone = s.value.requiredDone
        val beforeCurrent = s.value.waypoints.first { it.nodeId == "launch" }.visual
        s.activate("opt:6")
        while (s.value.screen == SpaceScreen.SUCCESS) s.tick(200)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertTrue(s.value.worldReacted)
        assertEquals(beforeDone, s.value.requiredDone)
        assertEquals(beforeCurrent, s.value.waypoints.first { it.nodeId == "launch" }.visual)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "launch" }.visual)
        assertEquals(SpaceNodeVisual.LOCKED, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        s.move(SpacePad.RIGHT)
        assertTrue(s.value.focus.startsWith("node:"))
        s.semantic(FamilyAction.Back)
        assertEquals(SpaceScreen.ENTRY, s.value.screen)
    }

    @Test fun realDomainProgressUnchangedByPresentation() {
        val before = progress(open = setOf("launch"), started = true)
        val s = space(open = before.openNodeIds, last = before.lastVisitedNodeId, started = true, resumeScene = "repair_pattern")
        openResumeChallenge(s)
        s.activate("opt:6")
        repeat(20) { s.tick(200) }
        assertEquals(setOf("launch"), before.openNodeIds)
        assertTrue(s.value.waypoints.first { it.nodeId == "launch" }.visual == SpaceNodeVisual.CURRENT)
        assertFalse("moon-hub" in before.completedNodeIds)
        assertFalse(spaceSources.contains("Путь открылся"))
        assertTrue(spaceSources.contains("drawEnergyOrb") || spaceSources.contains("sequenceOrb"))
    }

    @Test fun sortPlacesIntoWorldZonesAndWrongStaysInWorld() {
        val s = space(started = true, resumeScene = "planet_sort")
        openResumeChallenge(s)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertEquals("SORT_AND_CLASSIFY", s.value.mechanicType)
        assertTrue(s.value.choices.any { it.key == "item:whale" })
        assertTrue(s.value.categories.any { it.key == "cat:water" })
        assertEquals("whale", s.value.sortHold)
        assertTrue(s.value.focus.startsWith("cat:"))
        assertFalse(s.controls().any { it.startsWith("item:") })
        s.move(SpacePad.RIGHT)
        assertEquals("cat:land", s.value.focus)
        s.move(SpacePad.LEFT)
        assertEquals("cat:water", s.value.focus)
        s.activate("cat:land")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertFalse(s.runtime()!!.isCompleted())
        assertTrue(s.value.wrongMs > 0)
        assertEquals("whale", s.value.sortHold)
        assertEquals(SpaceBeat.REJECT, s.value.beat)
        s.tick(1500)
        assertEquals("whale", s.value.sortHold)
        s.activate("cat:water")
        assertEquals("tree", s.value.sortHold)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        s.activate("cat:land")
        assertEquals("cloud", s.value.sortHold)
        s.activate("cat:air")
        assertEquals("fish", s.value.sortHold)
        s.activate("cat:water")
        assertEquals(SpaceScreen.SUCCESS, s.value.screen)
        assertTrue(s.value.worldReacted)
        repeat(20) { s.tick(200) }
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "launch" }.visual)
    }

    @Test fun sortHelpHighlightsWorldWithoutAutoSubmitOrAnswerText() {
        val s = space(started = true, resumeScene = "planet_sort")
        openResumeChallenge(s)
        val before = s.value.sortHold
        s.semantic(FamilyAction.Help)
        assertTrue(s.value.helping)
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertFalse(s.runtime()!!.isCompleted())
        assertEquals(before, s.value.sortHold)
        assertEquals("cat:water", s.value.hintTarget)
        assertFalse(s.value.guideLine.contains("Кит — вода"))
        assertFalse(s.takeCompleted())
        assertTrue(s.value.focus.startsWith("cat:"))
    }

    @Test fun sortRendererIsGenericAndHasIllustratedAssets() {
        val world = source("space/SpaceNativeWorld.kt")
        val sortFn = world.substringAfter("private fun drawSort").substringBefore("private fun drawWorld")
        assertFalse(sortFn.contains("Кит"))
        assertFalse(sortFn.contains("Дерево"))
        assertFalse(sortFn.contains("Облако"))
        assertFalse(sortFn.contains("Рыба"))
        assertFalse(sortFn.contains("#22d3ee"))
        assertTrue(world.contains("item_\$"))
        assertTrue(world.contains("world_\$"))
        assertTrue(world.contains("drawWorld"))
        assertTrue(File(main, "assets/space_expedition_v1/sort/item_whale.jpg").isFile)
        assertTrue(File(main, "assets/space_expedition_v1/sort/world_water.jpg").isFile)
        assertTrue(File(main, "assets/space_expedition_v1/sort/PROVENANCE.txt").isFile)
        assertTrue(source("space/SpaceExpeditionActivity.kt").contains("SORT_ART_DIR"))
        assertTrue(source("space/SpaceNativeWorld.kt").contains("private fun drawPattern"))
        assertTrue(source("space/SpaceNativeWorld.kt").contains("drawSpacePath"))
    }

    @Test fun currentPlayableLeafNodeOpensDirectly() {
        val s = space(started = true)
        assertEquals("launch", s.value.resumeNodeId)
        s.activate("continue")
        assertEquals(SpaceScreen.INTRO, s.value.screen)
        assertEquals("intro", s.runtime()!!.snapshot().sceneId)
        assertTrue(s.takeOpened())
    }

    @Test fun currentHubNodeResolvesToCurrentPlayableScene() {
        val playable = adventure.scenes.filterIsInstance<ChallengeScene>().first()
        val s = space(
            completed = setOf("launch"),
            open = setOf("moon-hub"),
            last = "launch",
            started = true,
            resumeScene = playable.id,
        )
        assertEquals("moon-hub", s.value.resumeNodeId)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        s.activate("continue")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertEquals(playable.id, s.runtime()!!.snapshot().sceneId)
        assertEquals(playable.challenge.mechanic.name, s.value.mechanicType)
        assertFalse(spaceSources.contains("resumeScene = \"planet_sort\""))
        assertFalse(source("space/SpaceExpeditionState.kt").contains("planet_sort"))
    }

    @Test fun selectOnActionableHubIsNotNoOp() {
        val playable = adventure.scenes.filterIsInstance<ChallengeScene>().first()
        val live = space(
            completed = setOf("launch"),
            open = setOf("moon-hub"),
            last = "launch",
            started = true,
            resumeScene = playable.id,
        )
        live.activate("journey")
        assertEquals(SpaceScreen.JOURNEY, live.value.screen)
        assertEquals("node:moon-hub", live.value.focus)
        live.semantic(FamilyAction.Select)
        assertEquals(SpaceScreen.CHALLENGE, live.value.screen)
        assertEquals(playable.id, live.runtime()!!.snapshot().sceneId)

        val picker = space(completed = setOf("launch"), open = setOf("moon-hub"), last = "launch", started = true)
        picker.activate("journey")
        picker.activate("node:moon-hub")
        assertEquals(SpaceScreen.HUB, picker.value.screen)
        assertTrue(picker.value.branches.isNotEmpty())
        assertTrue(picker.controls().any { it.startsWith("branch:") })
        val branch = picker.value.branches.first()
        picker.activate("branch:${branch.id}")
        val chosen = picker.takeBranch()
        assertNotNull(chosen)
        assertEquals("moon-hub", chosen!!.fromNodeId)
        assertEquals(branch.id, chosen.branchId)
    }

    @Test fun lockedHubStaysSafe() {
        val s = space(started = true)
        s.activate("journey")
        assertEquals(SpaceNodeVisual.LOCKED, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        s.activate("node:moon-hub")
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertTrue(s.value.wrongMs > 0)
        assertFalse(s.takeOpened())
        assertNull(s.takeExternal())
        assertNull(s.takeBranch())
        assertNull(s.takeManual())
        assertNotEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertNotEquals(SpaceScreen.HUB, s.value.screen)
    }

    @Test fun backFromResolvedHubSceneRestoresJourneyFocusThenEntry() {
        val playable = adventure.scenes.filterIsInstance<ChallengeScene>().first()
        val s = space(
            completed = setOf("launch"),
            open = setOf("moon-hub"),
            last = "launch",
            started = true,
            resumeScene = playable.id,
        )
        s.activate("continue")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        s.semantic(FamilyAction.Back)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertEquals("node:moon-hub", s.value.focus)
        assertEquals(SpaceNodeVisual.CURRENT, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        assertEquals(SpaceNodeVisual.COMPLETED, s.value.waypoints.first { it.nodeId == "launch" }.visual)
        assertFalse(s.takeCompleted())
        s.semantic(FamilyAction.Back)
        assertEquals(SpaceScreen.ENTRY, s.value.screen)
        assertEquals("continue", s.value.focus)
        assertEquals("moon-hub", s.value.resumeNodeId)
    }

    @Test fun currentHubStillOpensHostedSceneWhenHubLooksLocked() {
        val playable = adventure.scenes.filterIsInstance<ChallengeScene>().first()
        val s = space(
            open = setOf("moon-hub"),
            last = "moon-hub",
            started = true,
            resumeScene = playable.id,
        )
        assertEquals("moon-hub", s.value.resumeNodeId)
        assertEquals(SpaceNodeVisual.LOCKED, s.value.waypoints.first { it.nodeId == "moon-hub" }.visual)
        s.activate("continue")
        assertEquals(SpaceScreen.CHALLENGE, s.value.screen)
        assertEquals(playable.id, s.runtime()!!.snapshot().sceneId)
        s.semantic(FamilyAction.Back)
        assertEquals(SpaceScreen.JOURNEY, s.value.screen)
        assertEquals("node:moon-hub", s.value.focus)
    }

    @Test fun completedHubResolvesToPlayableChildNotNoOp() {
        var p = JourneyEngine.start(journey, progress(open = setOf("launch"), started = true))
        p = JourneyEngine.completeNode(journey, p, "launch")
        p = JourneyEngine.completeNode(journey, p, "moon-hub", branchId = "to-temp")
        val views = JourneyEngine.views(journey, p, { true })
        assertEquals("temp-lab", JourneyEngine.playableChildNodeId(journey, p, views, "moon-hub"))
        val s = SpaceExpeditionState(
            journey, p, adventure, null, views,
            JourneyEngine.resumeNodeId(journey, p),
        )
        s.activate("journey")
        s.activate("node:moon-hub")
        assertEquals("adv-weather-lab", s.takeExternal())
        assertNull(s.takeBranch())
        assertNull(s.takeManual())
        assertFalse(s.takeCompleted())
    }
}
