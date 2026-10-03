package ru.familytv.games.world

import ru.familytv.games.AttributeApplyDefinition
import ru.familytv.games.CountCombineDefinition

/** Per-visit accounting, not a progress store. Restored across Activity recreation. */
internal class GameVisit(
    var world: GameWorld = GameWorld.HOME,
    var startedAt: Long = 0,
) {
    data class End(val contentId: String, val title: String, val topics: List<String>, val durationMs: Long)

    private var contentId: String? = null
    private var title: String? = null
    private var topics: List<String>? = null

    fun begin(
        world: GameWorld,
        now: Long,
        contentId: String? = null,
        title: String? = null,
        topics: List<String>? = null,
    ) {
        if (this.world != GameWorld.HOME || world == GameWorld.HOME) return
        this.world = world
        startedAt = now
        this.contentId = contentId
        this.title = title
        this.topics = topics
    }

    fun finish(now: Long): End? {
        if (world == GameWorld.HOME) return null
        val math = world == GameWorld.MATH
        val end = End(
            contentId ?: if (math) "game-math" else "game-color",
            title ?: if (math) "Математика" else "Цвета",
            topics ?: if (math) listOf("math", "counting") else listOf("colors"),
            (now - startedAt).coerceAtLeast(0),
        )
        world = GameWorld.HOME
        startedAt = 0
        contentId = null
        title = null
        topics = null
        return end
    }
}

internal data class VisitIdentity(val contentId: String, val title: String, val topics: List<String>)

/** Visits record the bound dataset id. They do not assume the first apples or blue-wing set. */
internal fun visitIdentity(
    world: GameWorld,
    math: CountCombineDefinition?,
    color: AttributeApplyDefinition? = null,
): VisitIdentity {
    if (world == GameWorld.MATH) {
        val set = checkNotNull(math) { "COUNT_COMBINE dataset is not bound" }
        return VisitIdentity(set.contentId, set.title, listOf("math", "counting"))
    }
    if (world == GameWorld.COLOR) {
        val set = checkNotNull(color) { "ATTRIBUTE_APPLY dataset is not bound" }
        return VisitIdentity(set.contentId, set.title, listOf("colors"))
    }
    return VisitIdentity("game-color", "Цвета", listOf("colors"))
}
