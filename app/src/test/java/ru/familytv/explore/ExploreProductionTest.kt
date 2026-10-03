package ru.familytv.explore

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.adventure.mechanics.MechanicVerdict
import ru.familytv.adventure.mechanics.PredictAndTestEngine
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.content.memoryPrefs
import ru.familytv.interaction.*
import ru.familytv.journey.CompletionPolicy
import ru.familytv.journey.JourneyParser
import ru.familytv.parent.ActivityHistoryStore
import ru.familytv.parent.ActivityKind
import ru.familytv.voice.VoiceRuntime

class ExploreProductionTest {
    private val ice by lazy { WeatherFixtures.ice() }
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val debug = File(main.parentFile, "debug")
    private fun source(path: String) = File(main, "java/ru/familytv/$path").readText()
    private val exploreSources by lazy {
        File(main, "java/ru/familytv/explore").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
    }

    private fun weather() = exploreState().apply { activate("destination:WEATHER") }
    private fun result(place: IcePlace = IcePlace.SUN) = weather().apply {
        activate("start"); activate("place:$place"); tick(ice.timing.placeMs); tick(ice.timing.experimentMs)
    }
    private fun ask() = result().apply { activate("why") }
    private fun askDone() = ask().apply { tick(ice.timing.causeMs * 4) }
    private fun placed(cover: Shelter = Shelter.CANOPY) = askDone().apply { activate("build"); activate("shelter:$cover"); tick(ice.timing.placeMs) }
    private fun buildResult() = placed().apply { activate("check"); tick(ice.timing.buildMs) }
    private fun assertBuildLocked(s: WeatherExploreState) {
        assertFalse(s.value.canBuild); assertFalse("build" in s.controls()); assertNotEquals("build", s.value.focus)
        assertFalse(s.activate("build")); assertEquals(WeatherScreen.ASK, s.value.screen)
    }

    // 1
    @Test fun homeExploreRoutesToProductionExploreActivity() {
        for (phrase in listOf("исследовать", "хочу исследовать", "исследуй")) {
            val routed = VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), phrase) as RouteResult.Action
            assertEquals(FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID), routed.action)
        }
        val home = source("home/HomeActivity.kt")
        assertFalse(home.contains("chip(\"Исследовать\")"))
        assertTrue(home.contains("dispatch(InputSource.REMOTE, FamilyAction.OpenWorld(ExploreRoutes.WORLD_ID))"))
        assertTrue(home.contains("startActivity(ExploreRoutes.intent(this, destination))"))
        val manifest = File(main, "AndroidManifest.xml").readText()
        val entry = manifest.substringAfter("android:name=\".explore.ExploreActivity\"").substringBefore("/>")
        assertTrue(entry.contains("android:exported=\"false\""))
        // The catalog weather lab now opens the same Explore Weather loop, never the old quiz adventure.
        assertTrue(ExploreRoutes.opensWeather(ContentItem("adv-weather-lab", "Лаборатория погоды", type = ContentType.ADVENTURE, adventureRef = "weather-lab.json")))
        assertTrue(ExploreRoutes.opensWeather(ContentItem("pkg-weather", "Погода", type = ContentType.ADVENTURE, adventureRef = "adventures/weather-lab.json")))
        assertFalse(ExploreRoutes.opensWeather(ContentItem("adv-space-expedition", "Космос", type = ContentType.ADVENTURE, adventureRef = "space-expedition.json")))
        assertFalse(ExploreRoutes.opensWeather(ContentItem("adv-weather-lab", "Видео", type = ContentType.VIDEO)))
        assertTrue(source("home/ContentNavigator.kt").contains("if (ExploreRoutes.opensWeather(item)) {\n                ExploreRoutes.intent(activity, ExploreDestination.WEATHER)"))
    }

    // 2
    @Test fun weatherIsTheAvailableLocation() {
        assertEquals(5, ExploreDestination.entries.size)
        assertEquals(listOf(ExploreDestination.SPACE, ExploreDestination.FOREST, ExploreDestination.WEATHER), ExploreDestination.entries.filter { it.available })
        val s = exploreState()
        assertEquals("destination:WEATHER", s.value.focus)
        assertTrue(s.activate())
        assertEquals(WeatherScreen.INTRO, s.value.screen)
        assertEquals("weather_intro", s.value.background)
    }

    // 3
    @Test fun futureDestinationsNeverCreateContentOrProgress() {
        for (d in ExploreDestination.entries.filterNot { it.available }) {
            val s = exploreState(); val visit = ExploreVisit()
            s.focus("destination:${d.name}"); s.activate()
            assertEquals(WeatherScreen.HOME, s.value.screen)
            assertTrue(s.value.unavailable)
            assertEquals("Этот мир скоро откроется. Исследуем погоду!", s.value.prompt())
            assertTrue(visit.observe(s.value, 5000).isEmpty())
            assertFalse(visit.active)
        }
        for (forbidden in listOf("AdventureActivity::class", "forest-secrets", "JourneyMapActivity")) {
            assertFalse(forbidden, exploreSources.contains(forbidden))
        }
    }

    // 4
    @Test fun hypothesesAreNeverJudgedCorrectOrWrong() {
        assertNull(ice.toPrediction().correctPredictionId)
        for (place in IcePlace.entries) {
            val s = weather().apply { activate("start"); activate("place:$place") }
            val verdicts = mutableListOf(PredictAndTestEngine.evaluate(s.predictionSnapshot(), ice.toPrediction()))
            s.tick(ice.timing.placeMs); s.tick(ice.timing.experimentMs)
            verdicts += PredictAndTestEngine.evaluate(s.predictionSnapshot(), ice.toPrediction())
            assertEquals(listOf(MechanicVerdict.INCOMPLETE, MechanicVerdict.OBSERVED), verdicts)
            assertEquals(ice.lines.observation, s.value.prompt())
        }
        for (word in listOf("Правильно", "Неправильно", "Верно", "Неверно", "Ошибка", "CORRECT", "WRONG", "score", "mastery")) {
            assertFalse(word, exploreSources.contains(word))
        }
    }

    // 5
    @Test fun predictionIsPreservedThroughTheWholeLoop() {
        val s = result(IcePlace.SHADE)
        s.activate("why"); s.tick(ice.timing.causeMs * 4); s.activate("build"); s.activate("shelter:LEAVES"); s.tick(ice.timing.placeMs)
        s.activate("check"); s.tick(ice.timing.buildMs)
        assertEquals(WeatherScreen.BUILD_RESULT, s.value.screen)
        assertEquals(IcePlace.SHADE, exploreState(s.checkpoint()).value.hypothesis)
    }

    // 6
    @Test fun experimentIsDeterministic() {
        val a = weather().apply { activate("start"); activate("place:SUN") }
        val b = weather().apply { activate("start"); activate("place:SUN") }
        repeat(136) { a.tick(50) }; b.tick(ice.timing.placeMs); b.tick(2000); b.tick(3000)
        assertEquals(WeatherScreen.EXPERIMENT, a.value.screen); assertEquals(5000L, a.value.elapsedMs)
        assertEquals(a.checkpoint(), b.checkpoint())
        assertEquals(result(IcePlace.SUN).value.samples, result(IcePlace.SHADE).value.samples)
    }

    // 7
    @Test fun sunMeltsFasterThanShade() {
        for (f in listOf(.25f, .5f, 1f)) {
            val (sun, shade) = WeatherMotion.samples(f)
            assertEquals(IcePlace.SUN, sun.place)
            assertTrue(sun.ice < shade.ice); assertTrue(sun.water > shade.water)
        }
    }

    // 8
    @Test fun observationOffersWhyAndIsRecordedOncePerHypothesis() {
        val s = result(IcePlace.SUN)
        assertEquals(WeatherScreen.RESULT, s.value.screen)
        assertEquals("why", s.value.focus)
        assertEquals(ice.lines.observation, s.value.prompt())
        val visit = ExploreVisit(); visit.begin(1000)
        assertEquals(listOf(ExploreRecord.Observed("sun", ice.lines.observation)), visit.observe(s.value, 2000))
        assertTrue(visit.observe(s.value, 3000).isEmpty())
        assertEquals(listOf(ExploreRecord.Observed("shade", ice.lines.observation)), visit.observe(result(IcePlace.SHADE).value, 4000))
    }

    // 9
    @Test fun askLumiRunsSunHeatIceWaterDone() {
        val s = ask(); val seen = mutableListOf(s.value.cause)
        repeat(4) { s.tick(ice.timing.causeMs); seen += s.value.cause }
        assertEquals(listOf(Cause.SUN, Cause.HEAT, Cause.ICE, Cause.WATER, Cause.DONE), seen)
    }

    // 10
    @Test fun nextIsUnavailableUntilDone() {
        val s = ask()
        repeat(4) {
            assertBuildLocked(s)
            repeat(3) { s.semantic(FamilyAction.Select) }
            assertBuildLocked(s)
            s.tick(ice.timing.causeMs - 1); assertBuildLocked(s); s.tick(1)
        }
        assertEquals(Cause.DONE, s.value.cause)
    }

    // 11
    @Test fun nextIsAvailableOnlyOnDone() {
        val s = askDone()
        assertTrue(s.value.canBuild); assertEquals("build", s.value.focus)
        s.tick(60000); assertEquals(WeatherScreen.ASK, s.value.screen)
        assertTrue(s.semantic(FamilyAction.Select))
        assertEquals(WeatherScreen.BUILD_CHOOSE, s.value.screen)
    }

    // 12
    @Test fun repeatLocksNextAgain() {
        val s = askDone()
        s.semantic(FamilyAction.Repeat)
        assertEquals(Cause.SUN, s.value.cause); assertBuildLocked(s)
        s.tick(ice.timing.causeMs * 4); assertTrue(s.value.canBuild)
    }

    // 13
    @Test fun buildOffersExactlyTwoShelters() {
        val s = askDone().apply { activate("build") }
        assertEquals(listOf("shelter:CANOPY", "shelter:LEAVES"), s.controls().filter { it.startsWith("shelter:") })
        assertEquals(listOf("Навес", "Зонтик из листьев"), ice.shelters.map { it.label })
    }

    // 14
    @Test fun noResultBeforeExplicitCheck() {
        val s = placed(); repeat(5) { s.tick(60000) }
        assertEquals(WeatherScreen.BUILD_PLACED, s.value.screen)
        assertEquals(0f, s.value.buildFraction)
        val visit = ExploreVisit(); visit.begin(1)
        assertTrue(visit.observe(s.value, 10).isEmpty())
        s.activate("check"); s.tick(ice.timing.buildMs)
        assertEquals("Под укрытием лёд тает медленнее.", s.value.prompt())
    }

    // 15
    @Test fun placedShelterSurvivesPauseAndRecreation() {
        val s = askDone().apply { activate("build"); activate("shelter:LEAVES"); tick(900); pause() }
        val restored = exploreState(s.checkpoint()); restored.resume(); restored.tick(900)
        assertEquals(Shelter.LEAVES, restored.value.shelter)
        assertTrue(restored.value.canCheck); assertEquals("check", restored.value.focus)
    }

    // 16
    @Test fun backRestoresFocusAndHomeReattachesActions() {
        val s = weather(); assertTrue(s.back())
        assertEquals(WeatherScreen.HOME, s.value.screen); assertEquals("destination:WEATHER", s.value.focus)
        assertFalse("Back at Explore Home returns to FamilyTV Home", s.back())
        val home = source("home/HomeActivity.kt")
        assertEquals(2, Regex("putString\\(\"lastFocusId\", \"explore\"\\)").findAll(home).count())
        assertTrue(home.contains("cardViews[\"explore\"] = explore"))
        assertTrue(home.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
            .contains("PlatformServices.actionDispatcher().attach(homeActionHandler)"))
        val activity = source("explore/ExploreActivity.kt")
        assertTrue(activity.contains("dispatcher.attach(actionHandler)")); assertTrue(activity.contains("dispatcher.detach(actionHandler)"))
    }

    // 17
    @Test fun pauseAndResumeNeverOpenNextEarly() {
        val s = ask().apply { tick(ice.timing.causeMs * 2); pause(); tick(60000) }
        assertEquals(Cause.ICE, s.value.cause); assertFalse(s.activate()); assertBuildLocked(s)
        val restored = exploreState(s.checkpoint().copy(focus = "build", paused = false))
        assertBuildLocked(restored)
        restored.tick(ice.timing.causeMs * 2); assertTrue(restored.value.canBuild)
    }

    // 18
    @Test fun historyAndJourneyUseTheExistingWeatherLabPolicy() {
        val visit = ExploreVisit()
        assertEquals(listOf(ExploreRecord.Opened), visit.begin(10_000))
        assertTrue(visit.observe(ask().value, 11_000).isEmpty())
        val completed = visit.observe(buildResult().value, 70_000).single() as ExploreRecord.Completed
        assertEquals(60_000L, completed.durationMs)
        val history = ActivityHistoryStore(memoryPrefs()) { 1_700_000_000_000L }
        history.record(ActivityKind.INTERACTIVE, ExploreRoutes.FALLBACK_TITLE, ExploreRoutes.CONTENT_ID, ExploreRoutes.HISTORY_TOPICS, completed.durationMs)
        val event = history.todaySummary().events.single()
        assertEquals(ActivityKind.INTERACTIVE, event.kind)
        assertEquals("adv-weather-lab", event.contentId)
        assertEquals(listOf("adventure", "explore"), event.topics)
        // The real catalog: weather-lab adventure, its Journey node and its journal unlock.
        val catalog = JSONObject(File(main, "assets/catalog/manifest.json").readText()).getJSONArray("items")
        val item = (0 until catalog.length()).map { catalog.getJSONObject(it) }.single { it.getString("id") == ExploreRoutes.CONTENT_ID }
        assertEquals("ADVENTURE", item.getString("type")); assertEquals("${ExploreRoutes.ADVENTURE_ID}.json", item.getString("adventureRef"))
        assertEquals(ExploreRoutes.FALLBACK_TITLE, item.getString("title"))
        val journey = JourneyParser.parse(File(main, "assets/catalog/journeys/space-grand-expedition.json").readText())
        val node = journey.nodes.single { it.contentId == ExploreRoutes.CONTENT_ID }
        assertEquals(CompletionPolicy.CONTENT_COMPLETED, node.completionPolicy)
        val adventure = JSONObject(File(main, "assets/catalog/adventures/weather-lab.json").readText())
        assertEquals(ExploreRoutes.WORLD_ID, adventure.getString("world"))
        val scenes = adventure.getJSONArray("scenes")
        assertTrue((0 until scenes.length()).any { scenes.getJSONObject(it).optJSONObject("challenge")?.optString("id") == ExploreRoutes.EXPERIMENT_ID })
        assertTrue(File(main, "assets/catalog/journal/unlocks.json").readText().contains("\"exp-${ExploreRoutes.EXPERIMENT_ID}\""))
        val activity = source("explore/ExploreActivity.kt")
        for (hook in listOf("journey.onContentOpened(id)", "journey.onExperimentObserved(experimentId", "journey.onContentCompleted(id)", "PlatformServices.activityHistory(this).record(")) {
            assertEquals(hook, 1, activity.split(hook).size - 1)
        }
    }

    // 19
    @Test fun pauseResumeAndRecreationNeverDuplicateHistory() {
        val visit = ExploreVisit(); visit.begin(1_000)
        visit.observe(result().value, 2_000)
        assertTrue(visit.observe(buildResult().value, 9_000).single() is ExploreRecord.Completed)
        val restored = ExploreVisit(visit.startedAt, visit.observed, visit.completed)
        assertTrue(restored.begin(12_000).isEmpty())
        assertTrue(restored.observe(result().value, 13_000).isEmpty())
        assertTrue(restored.observe(buildResult().value, 14_000).isEmpty())
        assertTrue(restored.finish()); assertFalse(restored.finish())
        assertEquals(listOf(ExploreRecord.Opened), restored.begin(20_000))
        val activity = source("explore/ExploreActivity.kt")
        val lifecycle = activity.substringAfter("override fun onResume()").substringBefore("override fun onSaveInstanceState")
        for (write in listOf("record(", "beginVisit(", "endVisit(")) assertFalse(write, lifecycle.contains(write))
        assertTrue(activity.contains("outState.putLong(\"visitStarted\", visit.startedAt)"))
    }

    // 20
    @Test fun productionFlowNeverInitializesAi() {
        val s = askDone(); s.activate("build"); s.activate("shelter:CANOPY"); s.tick(ice.timing.placeMs); s.activate("check"); s.tick(ice.timing.buildMs)
        assertNull(VoiceRuntime.peek())
        for (forbidden in listOf("VoiceRuntime.get(", "newPipeline(", ".prewarm(", "startListening(", "acquireLease(", "AudioRecord(", "SpeechRecognizer", "GroundedQwenMatcher", ".gguf", ".onnx", "NarrationCoordinator(")) {
            assertFalse(forbidden, exploreSources.contains(forbidden))
        }
        assertTrue(exploreSources.contains("PlatformServices.narrationCoordinator(this@ExploreActivity)"))
        assertTrue(exploreSources.contains("VoiceRuntime.peek()"))
    }

    // 21
    @Test fun productionDoesNotDependOnDebugPreview() {
        for (name in listOf("explore_world", "weather_intro", "weather_comparison", "weather_build")) {
            assertTrue(name, File(main, "assets/explore_weather/$name.webp").isFile)
        }
        assertTrue(File(main, "assets/explore_weather/provenance.json").readText().contains("\"asset\": \"app/src/main/assets/explore_weather/weather_intro.webp\""))
        assertFalse(File(debug, "assets/explore_weather").exists())
        val mainKotlin = File(main, "java").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        for (forbidden in listOf("StitchExploreWeatherPreviewActivity", "explore.stitch", "src/debug")) {
            assertFalse(forbidden, mainKotlin.contains(forbidden))
        }
        assertFalse(File(main, "AndroidManifest.xml").readText().contains("Stitch"))
        val harness = File(debug, "java/ru/familytv/explore/stitch/StitchExploreWeatherPreviewActivity.kt").readText()
        assertTrue(harness.contains(": ExploreActivity()")); assertTrue(harness.contains("override val recordsActivity = false"))
        assertTrue(source("explore/ExploreActivity.kt").contains("if (!recordsActivity || records.isEmpty()) return"))
    }

    @Test fun catalogLaunchReturnsToItsCallerFromWeatherIntro() {
        val activity = source("explore/ExploreActivity.kt")
        assertTrue(activity.contains("old.screen == WeatherScreen.INTRO && startsInWeather"))
        val start = exploreState(WeatherSnapshot(screen = WeatherScreen.INTRO, focus = "start", experiment = ice))
        assertEquals("start", start.value.focus)
        assertEquals(WeatherScreen.PREDICTION, start.apply { activate() }.value.screen)
    }
}
