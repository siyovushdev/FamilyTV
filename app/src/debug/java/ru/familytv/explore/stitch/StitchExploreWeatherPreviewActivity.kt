package ru.familytv.explore.stitch

import ru.familytv.explore.ExploreActivity

/** Debug harness for the production Explore world. It never writes History, Journey or trial events. */
class StitchExploreWeatherPreviewActivity : ExploreActivity() {
    override val recordsActivity = false
}
