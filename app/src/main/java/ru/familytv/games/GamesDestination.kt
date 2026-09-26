package ru.familytv.games

/** Stable catalog routes and Activity names, also used by Home's FamilyAction path. */
enum class GamesDestination(val activityName: String) {
    HOME("ru.familytv.games.GameHubActivity"),
    MATH("ru.familytv.games.MathVoiceGameActivity"),
    COLOR("ru.familytv.games.ColorVoiceGameActivity");

    companion object {
        fun fromRoute(route: String?): GamesDestination? = when (route) {
            "play", "games", "game" -> HOME
            "game/math", "game-math" -> MATH
            "game/color", "game-color" -> COLOR
            else -> null
        }
    }
}
