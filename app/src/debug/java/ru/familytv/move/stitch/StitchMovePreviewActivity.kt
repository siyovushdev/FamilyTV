package ru.familytv.move.stitch

import ru.familytv.move.MoveActivity

/** Debug harness for the production Move world. It never writes History, Journey or trial events. */
class StitchMovePreviewActivity : MoveActivity() {
    override val recordsActivity = false
    override val logTag = "StitchMove"
}
