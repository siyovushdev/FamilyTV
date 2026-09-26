package ru.familytv.games.world

/** Per-visit accounting, not a progress store. Restored across Activity recreation. */
internal class GameVisit(
    var world: GameWorld = GameWorld.HOME,
    var startedAt: Long = 0,
) {
    data class End(val contentId: String, val title: String, val topics: List<String>, val durationMs: Long)

    fun begin(world: GameWorld, now: Long) {
        if (this.world != GameWorld.HOME || world == GameWorld.HOME) return
        this.world = world
        startedAt = now
    }

    fun finish(now: Long): End? {
        if (world == GameWorld.HOME) return null
        val math = world == GameWorld.MATH
        val end = End(
            if (math) "game-math" else "game-color",
            if (math) "Математика" else "Цвета",
            if (math) listOf("math", "counting") else listOf("colors"),
            (now - startedAt).coerceAtLeast(0),
        )
        world = GameWorld.HOME
        startedAt = 0
        return end
    }
}
