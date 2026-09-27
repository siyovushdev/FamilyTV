package ru.familytv.creative.stitch

import ru.familytv.creative.CreativeStudioActivity

/** Debug harness for the production Story Builder. It never writes History, Journey or StoryStore. */
class StitchStoryBuilderPreviewActivity : CreativeStudioActivity() {
    override val recordsActivity = false
    override val logTag = "StitchStory"
}
