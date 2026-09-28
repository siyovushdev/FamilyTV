package ru.familytv.home.watch

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.familytv.content.*
import ru.familytv.content.packages.LocalContentPackageStore
import ru.familytv.parent.FamilyContentFilter
import ru.familytv.progress.WatchProgressStore
import java.io.ByteArrayInputStream
import java.io.File

class WatchCatalogTest {
    @get:Rule val tmp = TemporaryFolder()
    private val manifest = """{"schemaVersion":1,"catalogVersion":1,"items":[
      {"id":"child","title":"Child","type":"VIDEO","mediaRef":"child.mp4","durationMs":90000,"categories":["nature"],"metadata":{"ageMin":3,"ageMax":7}},
      {"id":"older","title":"Older","type":"VIDEO","mediaRef":"older.mp4","metadata":{"ageMin":10,"ageMax":14}},
      {"id":"hidden","title":"Hidden","type":"VIDEO","mediaRef":"hidden.mp4","enabled":false},
      {"id":"dev","title":"Dev","type":"VIDEO","mediaRef":"dev.mp4","tags":["dev_only"]},
      {"id":"game","title":"Game","type":"GAME","route":"game/math"}
    ]}"""
    private fun repo() = LocalContentRepository(ContentOverrideStore(memoryPrefs()),
        { ByteArrayInputStream(manifest.toByteArray()) }, tmp.newFolder())

    @Test fun realRepositoryHonorsAgeEnabledStateAndContentType() {
        val repo = repo()
        assertEquals(listOf("child"), WatchCatalog.eligible(repo, FamilyContentFilter(4, 6)).map { it.id })
        repo.setEnabled("child", false)
        // getItem deliberately still returns disabled items: Watch must not use it to bypass eligibility.
        assertNotNull(repo.getItem("child"))
        assertTrue(WatchCatalog.eligible(repo, FamilyContentFilter(4, 6)).isEmpty())
        repo.setEnabled("child", true)
        assertEquals(listOf("child", "older"), WatchCatalog.eligible(repo, FamilyContentFilter()).map { it.id })
    }

    @Test fun bundledCatalogIsProjectedWithoutFixtureInjection() {
        val assets = File("src/main/assets")
        val repo = LocalContentRepository(ContentOverrideStore(memoryPrefs()),
            { File(assets, it).inputStream() }, tmp.newFolder())
        val videos = WatchCatalog.eligible(repo, FamilyContentFilter())
        assertEquals(setOf("video-warm"), videos.map { it.id }.toSet())
        assertEquals(setOf("stories"), WatchCatalog.groups(videos).keys.map { it.id }.toSet())
        assertTrue(videos.all { it in repo.getEnabledItems() })
    }

    @Test fun installedPackageUsesExistingRepositoryAndResolvesLocalMedia() {
        val store = LocalContentPackageStore(tmp.newFolder(), tmp.newFolder(), memoryPrefs())
        val installed = store.importDirectory(File("../content-packages/apple-adventure"))
        assertTrue(installed.errors.toString(), installed.ok)
        val repo = LocalContentRepository(ContentOverrideStore(memoryPrefs()),
            { ByteArrayInputStream(manifest.toByteArray()) }, tmp.newFolder(), store)
        val video = WatchCatalog.eligible(repo, FamilyContentFilter()).single { it.packageId == "apple-adventure" }
        assertEquals("pkg-apple-intro-video", video.id)
        val media = repo.resolveMedia(video)
        assertTrue(media is ResolvedMedia.FilePath && media.file.isFile)
        repo.setEnabled(video.id, false)
        assertFalse(WatchCatalog.eligible(repo, FamilyContentFilter()).any { it.id == video.id })
    }

    @Test fun deterministicGroupingUsesMetadataPriorityAndNeverDropsUnknownVideos() {
        fun video(id: String, categories: List<String> = emptyList(), topics: List<String> = emptyList()) =
            ContentItem(id, id, type = ContentType.VIDEO, mediaRef = "$id.mp4",
                categories = categories, metadata = ContentMetadata(topics = topics))
        val explicit = video("a", listOf("SPACE"), listOf("nature"))
        val topic = video("b", topics = listOf("forest"))
        val unknown = video("c", topics = listOf("unclassified"))
        val groups = WatchCatalog.groups(listOf(explicit, topic, unknown))
        assertEquals(listOf("nature", "space", "watch"), groups.keys.map { it.id })
        assertEquals(listOf(explicit), groups.entries.single { it.key.id == "space" }.value)
        assertEquals(3, groups.values.flatten().distinctBy { it.id }.size)
        assertTrue(WatchCatalog.groups(emptyList()).isEmpty())
    }

    @Test fun continueIsAlwaysIntersectedWithCurrentlyEligibleVideosIncludingEmptyCatalog() {
        val repo = repo()
        val progress = WatchProgressStore(memoryPrefs())
        progress.save("child", 12_345, 90_000)
        progress.save("older", 14_000, 90_000)
        progress.save("removed-or-debug-content", 11_000, 90_000)
        val visible = WatchCatalog.eligible(repo, FamilyContentFilter(4, 6))
        assertEquals(listOf("child"), WatchCatalog.continueWatching(visible, progress).map { it.id })
        assertEquals(12_345L, progress.resumePositionMs("child"))
        assertTrue(WatchCatalog.continueWatching(emptyList(), progress).isEmpty())
        repo.setEnabled("child", false)
        assertTrue(WatchCatalog.continueWatching(WatchCatalog.eligible(repo, FamilyContentFilter(4, 6)), progress).isEmpty())
    }

    @Test fun completionAndShortClipPolicyRemoveContinueWithoutChangingStoreRules() {
        val videos = WatchCatalog.eligible(repo(), FamilyContentFilter(4, 6))
        val progress = WatchProgressStore(memoryPrefs())
        progress.save("child", 11_000, 90_000)
        assertEquals(1, WatchCatalog.continueWatching(videos, progress).size)
        progress.markCompleted("child", 90_000)
        assertTrue(WatchCatalog.continueWatching(videos, progress).isEmpty())
        assertEquals(0L, progress.resumePositionMs("child"))
        progress.save("child", 1_200, 3_000)
        assertTrue(WatchCatalog.continueWatching(videos, progress).isEmpty())
        assertEquals(0L, progress.resumePositionMs("child"))
    }

    @Test fun seriesContainOnlyMatchingEligibleEpisodesAndSelectionRestoresItsPage() {
        val videos = (1..12).reversed().map { n -> ContentItem("episode-$n", "Episode $n", type = ContentType.VIDEO,
            metadata = ContentMetadata(seriesId = if (n <= 11) "series" else "other", episodeOrder = n)) }
        val series = WatchCatalog.series(videos, "series")
        assertEquals((1..11).map { "episode-$it" }, series.map { it.id })
        assertEquals(0, WatchCatalog.pageFor(series, "episode-5"))
        assertEquals(1, WatchCatalog.pageFor(series, "episode-6"))
        assertEquals(2, WatchCatalog.pageFor(series, "episode-11"))
        assertEquals(0, WatchCatalog.pageFor(series, "removed"))
        assertTrue(WatchCatalog.series(videos, null).isEmpty())
    }

    @Test fun seekClampsToKnownMediaAndUnknownDuration() {
        assertEquals(0L, seekWatchPosition(2_000, -10_000, 3_000))
        assertEquals(3_000L, seekWatchPosition(2_000, 10_000, 3_000))
        assertEquals(24_000L, seekWatchPosition(14_000, 10_000, 90_000))
        assertEquals(0L, seekWatchPosition(2_000, 10_000, -1))
    }
}
