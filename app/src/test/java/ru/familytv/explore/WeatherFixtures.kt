package ru.familytv.explore

import java.io.File

internal object WeatherFixtures {
    val main: File = File("src/main").takeIf { it.exists() } ?: File("app/src/main")
    private val cached by lazy {
        val json = File(main, "assets/catalog/adventures/weather-lab.json").readText()
        val loaded = WeatherParser.parse(json, ExploreRoutes.CONTENT_ID)
        check(loaded is WeatherLoad.Ready) { (loaded as WeatherLoad.Rejected).reason }
        loaded.definition
    }

    fun ice(): WeatherDefinition = cached
}

internal fun exploreState(snapshot: WeatherSnapshot = WeatherSnapshot(experiment = WeatherFixtures.ice())) =
    WeatherExploreState(snapshot)
