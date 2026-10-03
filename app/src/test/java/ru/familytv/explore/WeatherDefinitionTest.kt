package ru.familytv.explore

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.content.ContentOverrideStore
import ru.familytv.content.LocalContentRepository
import ru.familytv.content.memoryPrefs
import ru.familytv.interaction.FamilyAction
import ru.familytv.journey.CompletionPolicy
import ru.familytv.journey.JourneyParser

/** Proves the illustrated ice experiment is the opened definition, not a leftover WeatherRules copy. */
class WeatherDefinitionTest {
    private val ice by lazy { WeatherFixtures.ice() }
    private val main = WeatherFixtures.main

    @Test fun productionContentLoadsTheIllustratedDefinition() {
        val loaded = WeatherCatalog.load(repository(), ExploreRoutes.CONTENT_ID)
        assertTrue(loaded.toString(), loaded is WeatherLoad.Ready)
        val definition = (loaded as WeatherLoad.Ready).definition
        assertEquals(ExploreRoutes.CONTENT_ID, definition.contentId)
        assertEquals(ExploreRoutes.EXPERIMENT_ID, definition.experimentId)
        assertEquals(listOf("На солнце", "В тень"), definition.places.map { it.label })
        assertEquals(listOf("солнце", "на солнышко"), definition.place(IcePlace.SUN).voice)
        assertEquals(1800L, definition.timing.placeMs)
        assertEquals(14000L, definition.timing.experimentMs)
        assertEquals(2400L, definition.timing.causeMs)
        assertEquals(10000L, definition.timing.buildMs)
        assertEquals("На солнце лёд растаял быстрее.", definition.lines.observation)
        assertEquals("Под укрытием лёд тает медленнее.", definition.lines.sheltered)
        assertEquals(listOf("Навес", "Зонтик из листьев"), definition.shelters.map { it.label })
        assertEquals("Луми объясняет: солнце, тепло, лёд, вода", definition.lines.explanation)
        assertNull(definition.narrationRef)
        assertNull(definition.toPrediction().correctPredictionId)
        // The old tiled adventure copy is still in the file and is not what the screen says.
        assertNotEquals(
            "Смотри: на солнце кубик быстро стал лужицей. Тепло ускоряет таяние!",
            definition.lines.observation,
        )
        val journey = JourneyParser.parse(File(main, "assets/catalog/journeys/space-grand-expedition.json").readText())
        val node = journey.nodes.single { it.contentId == ExploreRoutes.CONTENT_ID }
        assertEquals(CompletionPolicy.CONTENT_COMPLETED, node.completionPolicy)
    }

    @Test fun predictionTimingObservationBuildAndHelpComeFromTheDefinition() {
        val definition = other()
        val state = WeatherExploreState(WeatherSnapshot(experiment = definition)).apply {
            activate("destination:WEATHER")
        }
        assertEquals("Где кубик пропадёт раньше?", state.value.prompt())
        state.activate("start")
        assertEquals("Куда положим кубик?", state.value.prompt())
        assertNotEquals(ice.lines.prediction, state.value.prompt())
        assertEquals(listOf("У окна", "В ящике"), definition.toPrediction().options.map { it.label })
        assertTrue(state.semantic(FamilyAction.Answer("окно")))
        assertEquals(IcePlace.SUN, state.value.hypothesis)
        state.tick(definition.timing.placeMs)
        assertEquals(WeatherScreen.EXPERIMENT, state.value.screen)
        state.tick(definition.timing.experimentMs - 1)
        assertEquals(WeatherScreen.EXPERIMENT, state.value.screen)
        state.tick(1)
        assertEquals("Окно растопило кубик.", state.value.prompt())
        assertNotEquals(ice.lines.observation, state.value.prompt())
        state.activate("why")
        assertEquals("Свет греет кубик.", state.value.prompt())
        assertFalse(state.value.canBuild)
        state.semantic(FamilyAction.Help)
        assertEquals(WeatherScreen.ASK, state.value.screen)
        assertEquals("Свет греет кубик.", state.value.prompt())
        assertFalse(state.activate("build"))
        state.tick(definition.timing.causeMs * 4)
        assertTrue(state.value.canBuild)
        state.activate("build")
        assertEquals(listOf("Крышка", "Коробка"), definition.shelters.map { it.label })
        assertEquals("Чем накроем кубик?", state.value.prompt())
        state.activate("shelter:CANOPY")
        state.tick(definition.timing.placeMs)
        state.activate("check")
        state.tick(definition.timing.buildMs - 1)
        assertEquals(WeatherScreen.BUILD_RUNNING, state.value.screen)
        state.tick(1)
        assertEquals("Под крышкой кубик остался.", state.value.prompt())
        assertNotEquals(ice.lines.sheltered, state.value.prompt())
        assertTrue(state.back())
        assertEquals(WeatherScreen.BUILD_CHOOSE, state.value.screen)
    }

    @Test fun malformedDefinitionDoesNotBecomeTheIceExperiment() {
        assertTrue(WeatherParser.parse("{", ExploreRoutes.CONTENT_ID) is WeatherLoad.Rejected)
        val file = File(main, "assets/catalog/adventures/weather-lab.json").readText()
        assertTrue(WeatherParser.parse(file.replace("На солнце лёд растаял быстрее.", ""), ExploreRoutes.CONTENT_ID) is WeatherLoad.Rejected)
        val graded = file.replace("\"template\": \"weather_explore\"", "\"template\": \"weather_explore\", \"correctPredictionId\": \"SUN\"")
        val gradedLoad = WeatherParser.parse(graded, ExploreRoutes.CONTENT_ID)
        assertTrue(gradedLoad.toString(), gradedLoad is WeatherLoad.Rejected)
        val mismatch = WeatherParser.parse(file, "adv-forest-trail")
        assertTrue(mismatch is WeatherLoad.Rejected)
        assertFalse((mismatch as WeatherLoad.Rejected).reason.contains(ice.lines.observation))
        val sources = File(main, "java/ru/familytv/explore").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        assertFalse(sources.contains("object WeatherRules"))
        assertFalse(sources.contains("На солнце лёд растаял быстрее"))
        assertFalse(sources.contains("Как думаешь, где лёд растает быстрее"))
    }

    @Test fun foreignContentDoesNotOpenTheIceDefinition() {
        val forest = WeatherCatalog.load(repository(), "adv-forest-trail")
        assertTrue(forest is WeatherLoad.Rejected)
        assertFalse((forest as WeatherLoad.Rejected).reason.contains("растаял"))
        val space = WeatherCatalog.load(repository(), "adv-space-expedition")
        assertTrue(space is WeatherLoad.Rejected)
        val manifest = File(main, "assets/catalog/manifest.json").readText()
        assertFalse(manifest.contains("weather-fixture-b"))
        val navigator = File(main, "java/ru/familytv/home/ContentNavigator.kt").readText()
        assertTrue(navigator.contains("putExtra(ExploreRoutes.EXTRA_CONTENT_ID, item.id)"))
        assertTrue(navigator.contains("AdventureActivity::class.java"))
        val activity = File(main, "java/ru/familytv/explore/ExploreActivity.kt").readText()
        assertTrue(activity.contains("WeatherCatalog.load"))
        assertFalse(activity.contains("catalog/adventures/weather-lab.json"))
    }

    @Test fun adventureLinksStillResolve() {
        val adventure = JSONObject(File(main, "assets/catalog/adventures/weather-lab.json").readText())
        val scenes = adventure.getJSONArray("scenes")
        val ids = (0 until scenes.length()).map { scenes.getJSONObject(it).getString("id") }.toSet()
        assertTrue(ids.contains(adventure.getString("startSceneId")))
        for (index in 0 until scenes.length()) {
            val scene = scenes.getJSONObject(index)
            for (key in listOf("next", "onMappedNext")) {
                if (scene.has(key)) assertTrue(scene.getString(key), ids.contains(scene.getString(key)))
            }
            val challenge = scene.optJSONObject("challenge") ?: continue
            if (challenge.has("successNext")) assertTrue(ids.contains(challenge.getString("successNext")))
        }
        val explore = adventure.getJSONObject("explore")
        assertEquals(ExploreRoutes.CONTENT_ID, explore.getString("contentId"))
        assertEquals(ExploreRoutes.EXPERIMENT_ID, explore.getString("experimentId"))
    }

    private fun repository(): LocalContentRepository {
        val root = Files.createTempDirectory("weather-m4").toFile()
        return LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { path -> File(main, "assets/$path").inputStream() },
            catalogRoot = root,
        )
    }

    private fun other(): WeatherDefinition {
        val json = """
            {
              "schemaVersion": 1,
              "template": "weather_explore",
              "contentId": "weather-fixture-b",
              "experimentId": "fixture-melt",
              "title": "Проверочный кубик",
              "timing": { "placeMs": 400, "experimentMs": 900, "causeMs": 150, "buildMs": 700 },
              "places": [
                { "id": "SUN", "label": "У окна", "caption": "У окна", "voice": ["окно"], "description": "Положить у окна" },
                { "id": "SHADE", "label": "В ящике", "caption": "В ящике", "voice": ["ящик"], "description": "Положить в ящик" }
              ],
              "shelters": [
                { "id": "CANOPY", "label": "Крышка", "voice": ["крышка"], "description": "Поставить крышку" },
                { "id": "LEAVES", "label": "Коробка", "voice": ["коробка"], "description": "Поставить коробку" }
              ],
              "chain": ["Свет", "Тепло", "Кубик", "Вода"],
              "lines": {
                "home": "Куда теперь?",
                "homeSoon": "Этот угол скоро откроется.",
                "intro": "Где кубик пропадёт раньше?",
                "prediction": "Куда положим кубик?",
                "placing": "Кладём и смотрим.",
                "watching": "Смотри на кубик.",
                "observation": "Окно растопило кубик.",
                "askWarm": "Свет греет кубик.",
                "askMelt": "Кубик стал водой.",
                "buildChoose": "Чем накроем кубик?",
                "buildPlaced": "Крышка на месте. Проверим?",
                "buildWatching": "Смотрим под крышкой.",
                "sheltered": "Под крышкой кубик остался.",
                "coveredCaption": "Под крышкой",
                "sunCaption": "У окна",
                "explanation": "Луми показывает: свет, тепло, кубик, вода"
              }
            }
        """.trimIndent()
        val loaded = WeatherParser.parse(json, "weather-fixture-b")
        assertTrue(loaded.toString(), loaded is WeatherLoad.Ready)
        return (loaded as WeatherLoad.Ready).definition
    }
}
