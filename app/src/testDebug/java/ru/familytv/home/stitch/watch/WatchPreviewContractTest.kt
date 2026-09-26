package ru.familytv.home.stitch.watch

import org.junit.Assert.*
import org.junit.Test
import ru.familytv.home.watch.seekWatchPosition
import org.json.JSONArray
import ru.familytv.content.CatalogManifestParser
import ru.familytv.content.ContentSourceKind
import ru.familytv.progress.WatchProgressStore
import java.io.File

class WatchPreviewContractTest {
    @Test fun sampleVideosResolveToPlayableFilesAndHaveResumableDurations() {
        val assets = File("src/debug/assets")
        val parsed = CatalogManifestParser.parse(File(assets, "stitch_watch/catalog.json").readText(), ContentSourceKind.BUNDLED)
        assertTrue(parsed.errors.toString(), parsed.ok)
        val items = requireNotNull(parsed.catalog).items
        assertEquals(25, items.size)
        assertEquals(25, items.map { it.id }.toSet().size)
        val categories = JSONArray(File(assets, "stitch_watch/categories.json").readText())
        for (i in 0 until categories.length()) {
            val category = categories.getJSONObject(i)
            assertEquals(5, items.count { category.getString("id") in it.categories })
            assertTrue(File(assets, "stitch_watch/art/${category.getString("art")}.webp").isFile)
        }
        items.forEach {
            assertTrue(File(assets, requireNotNull(it.mediaRef)).length() > 100_000)
            assertTrue(File(assets, requireNotNull(it.thumbnail)).isFile)
            assertTrue(File(assets, requireNotNull(it.metadata.captionRef)).readText().startsWith("WEBVTT"))
            assertFalse(WatchProgressStore.isNonResumable(requireNotNull(it.durationMs)))
            assertFalse(WatchProgressStore.isNearEnd(10_000, it.durationMs!!))
        }
    }
    @Test fun remoteSeekingClampsBothEndsAndHandlesUnknownDuration() {
        assertEquals(0L, seekWatchPosition(5_000, -10_000, 90_000))
        assertEquals(90_000L, seekWatchPosition(85_000, 10_000, 90_000))
        assertEquals(25_000L, seekWatchPosition(15_000, 10_000, 90_000))
        assertEquals(0L, seekWatchPosition(5_000, 10_000, -1))
    }
}
