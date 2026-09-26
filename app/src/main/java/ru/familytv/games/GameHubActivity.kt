package ru.familytv.games

import ru.familytv.games.world.GamesActivity

/** Production Games Home. D-pad access never depends on microphone or AI readiness. */
class GameHubActivity : GamesActivity()

/** Preserve existing catalog and external Activity contracts. */
class MathVoiceGameActivity : GamesActivity() {
    override val initialGame = GameKind.MATH
}

class ColorVoiceGameActivity : GamesActivity() {
    override val initialGame = GameKind.COLOR
}
