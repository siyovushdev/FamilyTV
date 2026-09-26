package ru.familytv.home.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentRepository
import ru.familytv.content.ContentType
import ru.familytv.content.PlatformServices
import ru.familytv.content.ResolvedMedia
import ru.familytv.parent.FamilyContentFilter
import ru.familytv.progress.WatchProgressStore

/** Presentation metadata only; content and enabled state still belong to ContentRepository. */
data class WatchCategory(
    val id: String, val title: String, val line1: String, val line2: String,
    val art: String = "", val accent: Int, val keywords: Set<String> = emptySet(),
)

object WatchCatalog {
    private val categories = listOf(
        WatchCategory("nature", "Животные и природа", "Животные", "и природа", accent = 0xFF34D399.toInt(),
            keywords = setOf("animals", "animal", "nature", "forest", "природа", "животные")),
        WatchCategory("space", "Космос и наука", "Космос", "и наука", accent = 0xFF38D9D2.toInt(),
            keywords = setOf("space", "science", "sky", "космос", "наука")),
        WatchCategory("how", "Как всё устроено", "Как всё", "устроено", accent = 0xFFFBBF24.toInt(),
            keywords = setOf("how", "machines", "technology", "устройство", "техника")),
        WatchCategory("stories", "Истории и сказки", "Истории", "и сказки", accent = 0xFFC084FC.toInt(),
            keywords = setOf("stories", "story", "сказка", "истории", "calm")),
        WatchCategory("creativity", "Творчество и движение", "Творчество", "и движение", accent = 0xFFFB7185.toInt(),
            keywords = setOf("creative", "creativity", "music", "move", "творчество", "музыка", "движение")),
    )
    private val other = WatchCategory("watch", "Все истории", "Другие", "видео", accent = 0xFF38D9D2.toInt())

    fun eligible(repository: ContentRepository, filter: FamilyContentFilter): List<ContentItem> =
        repository.getEnabledItems().filter {
            it.type == ContentType.VIDEO && it.enabled && !it.mediaRef.isNullOrBlank() &&
                it.tags.none { tag -> tag in setOf("dev_only", "experimental") } &&
                (filter.ageMin == null || it.metadata.ageMax == null || it.metadata.ageMax >= filter.ageMin) &&
                (filter.ageMax == null || it.metadata.ageMin == null || it.metadata.ageMin <= filter.ageMax)
        }.distinctBy { it.id }.sortedWith(compareBy({ it.sortOrder }, { it.title }, { it.id }))

    /** One stable group per video. Explicit categories take precedence over topics, tags and purpose. */
    fun category(item: ContentItem): WatchCategory {
        for (words in listOf(item.categories, item.metadata.topics, item.tags, listOfNotNull(item.metadata.contentPurpose))) {
            val normalized = words.map { it.lowercase(java.util.Locale.ROOT) }.toSet()
            categories.firstOrNull { it.id in normalized || it.keywords.any(normalized::contains) }?.let { return it }
        }
        return other
    }

    fun groups(videos: List<ContentItem>): Map<WatchCategory, List<ContentItem>> {
        val grouped = videos.groupBy(::category)
        return (categories + other).mapNotNull { category -> grouped[category]?.let { category to it } }.toMap()
    }

    fun continueWatching(videos: List<ContentItem>, progress: WatchProgressStore): List<ContentItem> {
        // WatchProgressStore's empty set means "all"; an empty child catalog must mean none.
        if (videos.isEmpty()) return emptyList()
        val byId = videos.associateBy { it.id }
        return progress.continueWatching(byId.keys).mapNotNull { byId[it.contentId] }
    }

    fun series(videos: List<ContentItem>, seriesId: String?): List<ContentItem> =
        if (seriesId.isNullOrBlank()) emptyList() else videos.filter { it.metadata.seriesId == seriesId }
            .sortedWith(compareBy({ it.metadata.episodeOrder ?: Int.MAX_VALUE }, { it.sortOrder }, { it.title }))

    fun pageFor(items: List<ContentItem>, contentId: String): Int =
        items.indexOfFirst { it.id == contentId }.coerceAtLeast(0) / 5

    fun load(context: Context): WatchCatalogSnapshot {
        val repo = PlatformServices.contentRepository(context)
        val videos = eligible(repo, PlatformServices.familySettings(context).contentFilter())
        val groups = groups(videos)
        return WatchCatalogSnapshot(groups.keys.toList(), videos, groups.mapKeys { it.key.id },
            imageLoader = { item -> loadArtwork(context, item) })
    }

    /** Decode only artwork requested by the visible page; never decode full-size package posters. */
    private fun loadArtwork(context: Context, item: ContentItem): Bitmap? {
        val repo = PlatformServices.contentRepository(context)
        val thumbnail = item.thumbnail?.let { repo.resolveMediaRef(item, it) }
        fun open(media: ResolvedMedia) = when (media) {
            is ResolvedMedia.AssetPath -> context.assets.open(media.assetPath)
            is ResolvedMedia.FilePath -> media.file.inputStream()
        }
        if (thumbnail != null) runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(thumbnail).use { BitmapFactory.decodeStream(it, null, bounds) }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = 1
                while (bounds.outWidth / inSampleSize > 1024 || bounds.outHeight / inSampleSize > 1024) inSampleSize *= 2
            }
            open(thumbnail).use { BitmapFactory.decodeStream(it, null, opts) }
        }.getOrNull()?.let { return it }
        // Existing bundled thumbnails are symbolic names. Use the actual video frame as artwork.
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                when (val media = repo.resolveMedia(item)) {
                    is ResolvedMedia.AssetPath -> context.assets.openFd(media.assetPath).use {
                        retriever.setDataSource(it.fileDescriptor, it.startOffset, it.length)
                    }
                    is ResolvedMedia.FilePath -> retriever.setDataSource(media.file.absolutePath)
                    null -> return null
                }
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 360)
            } finally { retriever.release() }
        }.getOrNull()
    }
}

/** A bounded UI snapshot, not a repository or another source of catalog truth. */
class WatchCatalogSnapshot(
    val categories: List<WatchCategory>,
    val visibleVideos: List<ContentItem>,
    private val groups: Map<String, List<ContentItem>>,
    private val imageLoader: (ContentItem) -> Bitmap?,
    private val categoryImageLoader: ((WatchCategory) -> Bitmap?)? = null,
) {
    private val artwork = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missingArt = mutableSetOf<String>()
    fun getItem(id: String): ContentItem? = visibleVideos.firstOrNull { it.id == id }
    fun videos(category: WatchCategory): List<ContentItem> = groups[category.id].orEmpty()
    fun categoryArt(category: WatchCategory): Bitmap? = categoryImageLoader?.invoke(category)
        ?: videos(category).firstOrNull()?.let { artwork.get(it.id) }
    fun art(item: ContentItem, category: WatchCategory): Bitmap? = artwork.get(item.id) ?: categoryArt(category)
    @Synchronized
    fun loadArtwork(items: List<ContentItem>) {
        items.distinctBy { it.id }.forEach { item ->
            if (artwork.get(item.id) == null && item.id !in missingArt) {
                val bitmap = imageLoader(item)
                if (bitmap != null) artwork.put(item.id, bitmap) else missingArt.add(item.id)
            }
        }
    }
}

internal fun watchTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000
    return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}
internal fun watchDuration(ms: Long?): String = ms?.takeIf { it > 0 }?.let(::watchTime) ?: "Видео"
internal fun seekWatchPosition(current: Long, delta: Long, duration: Long): Long =
    (current + delta).coerceIn(0L, duration.coerceAtLeast(0L))
