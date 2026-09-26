package ru.familytv.games.stitch

import ru.familytv.games.world.GamesActivity

/** Design regression harness. Shares production presentation, without history/Journey writes. */
class StitchGamesPreviewActivity : GamesActivity() {
    override val recordsActivity = false
}
