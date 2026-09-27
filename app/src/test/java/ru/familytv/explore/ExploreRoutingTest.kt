package ru.familytv.explore

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType
import ru.familytv.interaction.*

class ExploreRoutingTest {
    private val main = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private fun source(path: String) = File(main, "java/ru/familytv/$path").readText()
    private val catalog by lazy {
        val items = JSONObject(File(main, "assets/catalog/manifest.json").readText()).getJSONArray("items")
        (0 until items.length()).map { items.getJSONObject(it) }.associateBy { it.getString("id") }
    }
    private fun select(destination: ExploreDestination) = WeatherExploreState().apply { focus("destination:${destination.name}"); semantic(FamilyAction.Select) }
    private fun catalogItem(id: String): ContentItem {
        val json = requireNotNull(catalog[id]) { "catalog item $id" }
        return ContentItem(id, json.getString("title"), type = ContentType.valueOf(json.getString("type")), adventureRef = json.optString("adventureRef"),
            enabled = json.optBoolean("enabled", true))
    }
    private fun assertOpensExistingAdventure(destination: ExploreDestination, contentId: String, adventureRef: String) {
        assertEquals(contentId, destination.contentId)
        val item = catalogItem(contentId)
        assertEquals(ContentType.ADVENTURE, item.type); assertEquals(adventureRef, item.adventureRef); assertTrue(item.enabled)
        assertFalse("existing adventure keeps AdventureActivity", ExploreRoutes.opensWeather(item))
        assertTrue(File(main, "assets/catalog/adventures/$adventureRef").isFile)
        val s = select(destination)
        assertEquals(destination, s.takeLaunch()); assertNull("launch is consumed once", s.takeLaunch())
        assertEquals(WeatherScreen.HOME, s.value.screen); assertFalse(s.value.unavailable)
        assertEquals("destination:${destination.name}", s.value.focus)
        assertTrue(source("explore/ExploreActivity.kt").contains("ContentNavigator.open(this, item)"))
    }

    @Test fun weatherOpensWeatherV1() {
        val s = select(ExploreDestination.WEATHER)
        assertEquals(WeatherScreen.INTRO, s.value.screen)
        assertNull(s.takeLaunch())
        assertEquals(ExploreRoutes.CONTENT_ID, ExploreDestination.WEATHER.contentId)
        assertTrue(ExploreRoutes.opensWeather(catalogItem(ExploreRoutes.CONTENT_ID)))
    }

    @Test fun spaceOpensExistingSpaceAdventure() = assertOpensExistingAdventure(ExploreDestination.SPACE, "adv-space-expedition", "space-expedition.json")

    @Test fun forestOpensExistingForestAdventure() = assertOpensExistingAdventure(ExploreDestination.FOREST, "adv-forest-trail", "forest-trail.json")

    @Test fun howItWorksStaysUnavailable() {
        val s = select(ExploreDestination.HOW)
        assertNull(ExploreDestination.HOW.contentId); assertFalse(ExploreDestination.HOW.available)
        assertNull(s.takeLaunch()); assertTrue(s.value.unavailable); assertEquals(WeatherScreen.HOME, s.value.screen)
    }

    @Test fun experimentsStaysUnavailable() {
        val s = select(ExploreDestination.EXPERIMENTS)
        assertNull(ExploreDestination.EXPERIMENTS.contentId); assertFalse(ExploreDestination.EXPERIMENTS.available)
        assertNull(s.takeLaunch()); assertTrue(s.value.unavailable); assertEquals(WeatherScreen.HOME, s.value.screen)
    }

    @Test fun voiceSpaceAndDpadSpaceResolveToTheSameDestination() {
        val dpad = WeatherExploreState().apply { move(Direction.LEFT); move(Direction.LEFT); semantic(FamilyAction.Select) }
        for (phrase in listOf("космос", "покажи космос", "открой космос")) {
            val action = (VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), phrase) as RouteResult.Action).action as FamilyAction.OpenWorld
            assertEquals(phrase, ExploreDestination.SPACE, ExploreDestination.fromRoute(action.worldId))
            assertTrue(ExploreRoutes.handles(action.worldId))
            val voice = WeatherExploreState(); assertTrue(voice.semantic(action))
            assertEquals(dpad.checkpoint(), voice.checkpoint())
            assertEquals(ExploreDestination.SPACE, voice.takeLaunch())
        }
        assertEquals(ExploreDestination.SPACE, dpad.takeLaunch())
        // "Исследуй" still opens Explore Home itself; a route never hijacks a running Weather loop.
        val explore = (VoiceCommandRouter.route(RouteContext(InteractionSurface.HOME), "исследуй") as RouteResult.Action).action as FamilyAction.OpenWorld
        assertNull(ExploreDestination.fromRoute(explore.worldId)); assertTrue(ExploreRoutes.handles(explore.worldId))
        val weather = select(ExploreDestination.WEATHER)
        assertFalse(weather.semantic(FamilyAction.OpenWorld(ExploreDestination.SPACE.route)))
        assertEquals(WeatherScreen.INTRO, weather.value.screen); assertNull(weather.takeLaunch())
        val home = source("home/HomeActivity.kt")
        assertTrue(home.contains("if (ExploreRoutes.handles(action.worldId))"))
        assertTrue(home.contains("startActivity(ExploreRoutes.intent(this, destination))"))
        assertTrue(source("explore/ExploreActivity.kt").contains("handle(FamilyAction.OpenWorld(it.route))"))
    }

    @Test fun backFromSpaceForestAndWeatherRestoresTheirLocationFocus() {
        for (destination in listOf(ExploreDestination.SPACE, ExploreDestination.FOREST)) {
            val s = select(destination); s.takeLaunch()
            s.pause(); s.resume() // host Activity pauses under the adventure and resumes on Back
            assertEquals("destination:${destination.name}", s.value.focus)
            val recreated = WeatherExploreState(s.checkpoint())
            assertEquals("destination:${destination.name}", recreated.value.focus)
            assertEquals(WeatherScreen.HOME, recreated.value.screen)
        }
        val weather = select(ExploreDestination.WEATHER); assertTrue(weather.back())
        assertEquals("destination:WEATHER", weather.value.focus)
        val activity = source("explore/ExploreActivity.kt")
        assertTrue(activity.substringAfter("override fun onResume()").substringBefore("override fun onPause()")
            .contains("controls[state.value.focus]?.requestFocus()"))
        assertTrue("debug harness never opens production adventures", activity.contains("if (!recordsActivity) { log(\"open_skipped_preview"))
    }
}
