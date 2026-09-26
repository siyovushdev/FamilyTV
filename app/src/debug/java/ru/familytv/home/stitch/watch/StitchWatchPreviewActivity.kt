package ru.familytv.home.stitch.watch

import android.content.Intent
import ru.familytv.home.watch.WatchActivity
import ru.familytv.home.watch.WatchCatalogSnapshot

/** Fixtures are available only through this debug entry. Production owns the presentation. */
class StitchWatchPreviewActivity : WatchActivity() {
    override fun loadCatalog(): WatchCatalogSnapshot = WatchPreviewCatalog.load(this).snapshot()
    override fun playerIntent(contentId: String): Intent =
        Intent(this, StitchWatchPlayerActivity::class.java).putExtra("contentId", contentId)
}
