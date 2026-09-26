package ru.familytv.home.stitch.watch

import ru.familytv.player.VideoPlayerActivity

/** Same production Media3 engine/HUD, with debug-only media resolution. */
class StitchWatchPlayerActivity : VideoPlayerActivity() {
    override fun loadVideo(contentId: String): PlaybackContent {
        val library = WatchPreviewCatalog.load(this)
        val item = requireNotNull(library.getItem(contentId))
        return PlaybackContent(item, requireNotNull(library.resolveMedia(item)), library.caption(this, item))
    }
    override fun recordCompletion(content: PlaybackContent, durationMs: Long) = Unit
}
