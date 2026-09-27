package ru.familytv.explore

import android.content.Context
import android.content.Intent
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentType

/** Production routes into Explore. The Weather location is the catalog's existing weather-lab content. */
object ExploreRoutes {
    const val WORLD_ID = "explore"
    const val EXTRA_START = "start"
    const val START_WEATHER = "weather"
    const val CONTENT_ID = "adv-weather-lab"
    const val ADVENTURE_ID = "weather-lab"
    const val EXPERIMENT_ID = "where-melts-faster"
    const val FALLBACK_TITLE = "Лаборатория погоды"
    /** Same history topics AdventureActivity wrote for the weather-lab adventure (`adventure`, its world). */
    val HISTORY_TOPICS = listOf("adventure", WORLD_ID)

    fun opensWeather(item: ContentItem): Boolean = item.type == ContentType.ADVENTURE &&
        (item.id == CONTENT_ID || item.adventureRef?.substringAfterLast('/')?.removeSuffix(".json") == ADVENTURE_ID)

    /** Home handles the Explore world and every [ExploreDestination.route]. */
    fun handles(worldId: String): Boolean = worldId == WORLD_ID || ExploreDestination.fromRoute(worldId) != null

    /**
     * [start] WEATHER opens Weather Intro directly and returns to the caller (catalog / Journey).
     * SPACE / FOREST open through Explore Home, so Back lands on that world's location.
     */
    fun intent(context: Context, start: ExploreDestination? = null): Intent = Intent(context, ExploreActivity::class.java)
        .apply { if (start != null) putExtra(EXTRA_START, start.name.lowercase()) }
}

internal sealed interface ExploreRecord {
    /** Weather opened: journey content-opened hook. */
    data object Opened : ExploreRecord
    /** The prediction test was observed for this hypothesis (`sun` / `shade`, the catalog option ids). */
    data class Observed(val variant: String, val outcome: String) : ExploreRecord
    /** The whole discovery loop reached its result after an explicit Check. */
    data class Completed(val durationMs: Long) : ExploreRecord
}

/**
 * One Weather visit. Mirrors the weather-lab adventure policy: open once, observe each tested
 * hypothesis once, complete (history + journey) once when Build & Test has a result.
 * Pause, resume and recreation restore these fields and never emit records again.
 */
internal class ExploreVisit(startedAt: Long = 0L, observed: Collection<String> = emptyList(), completed: Boolean = false) {
    var startedAt = startedAt
        private set
    private val tested = observed.toMutableSet()
    val observed: Set<String> get() = tested
    var completed = completed
        private set
    val active get() = startedAt > 0

    fun begin(now: Long): List<ExploreRecord> {
        if (active) return emptyList()
        startedAt = now.coerceAtLeast(1); tested.clear(); completed = false
        return listOf(ExploreRecord.Opened)
    }

    fun observe(s: WeatherSnapshot, now: Long): List<ExploreRecord> {
        if (!active) return emptyList()
        val hypothesis = s.hypothesis
        return when {
            s.screen == WeatherScreen.RESULT && hypothesis != null && tested.add(hypothesis.name.lowercase()) ->
                listOf(ExploreRecord.Observed(hypothesis.name.lowercase(), WeatherRules.OBSERVATION))
            s.screen == WeatherScreen.BUILD_RESULT && s.shelter != null && !completed -> {
                completed = true
                listOf(ExploreRecord.Completed((now - startedAt).coerceAtLeast(0)))
            }
            else -> emptyList()
        }
    }

    /** Leaves Weather. Returns false when no visit was open. Never records history by itself. */
    fun finish(): Boolean {
        val wasActive = active
        startedAt = 0L; tested.clear(); completed = false
        return wasActive
    }
}
