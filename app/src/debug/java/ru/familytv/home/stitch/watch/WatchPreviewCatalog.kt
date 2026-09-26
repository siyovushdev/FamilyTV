package ru.familytv.home.stitch.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONArray
import ru.familytv.content.CatalogManifestParser
import ru.familytv.content.ContentCatalog
import ru.familytv.content.ContentItem
import ru.familytv.content.ContentRepository
import ru.familytv.content.ContentSourceKind
import ru.familytv.content.ContentType
import ru.familytv.content.PlatformServices
import ru.familytv.content.ResolvedMedia
import ru.familytv.content.V1ContentShelf
import ru.familytv.home.stitch.color
import ru.familytv.home.watch.WatchCategory
import ru.familytv.home.watch.WatchCatalogSnapshot

/** Debug projection only. Production repository, imports and package protocol stay untouched. */
internal class WatchPreviewCatalog private constructor(
    private val base: ContentRepository,
    private val samples: List<ContentItem>,
    val categories: List<WatchCategory>,
    val visibleVideos: List<ContentItem>,
    val artwork: Map<String, Bitmap>,
) : ContentRepository by base {
    private val sampleIds = samples.map { it.id }.toSet()

    override fun getCatalog(): ContentCatalog = base.getCatalog().let {
        it.copy(items = it.items + samples, source = ContentSourceKind.MERGED)
    }
    override fun getEnabledItems(): List<ContentItem> = visibleVideos
    override fun getItem(id: String): ContentItem? = visibleVideos.find { it.id == id }
    override fun itemsForSection(sectionId: String): List<ContentItem> = visibleVideos.filter { sectionId in it.homeSections }
    override fun resolveMedia(item: ContentItem): ResolvedMedia? =
        if (item.id in sampleIds) item.mediaRef?.let(ResolvedMedia::AssetPath) else base.resolveMedia(item)

    fun caption(context: Context, item: ContentItem): ResolvedMedia? = item.metadata.captionRef?.let {
        if (item.id in sampleIds) ResolvedMedia.AssetPath(it)
        else PlatformServices.contentRepository(context).resolveMediaRef(item, it)
    }

    fun videos(category: WatchCategory): List<ContentItem> = visibleVideos.filter { item ->
        val words = (item.categories + item.metadata.topics + item.tags).map { it.lowercase() }.toSet()
        category.id in words || words.any { it in category.keywords }
    }

    fun art(item: ContentItem, category: WatchCategory): Bitmap? =
        artwork[item.thumbnail] ?: artwork["stitch_watch/art/${category.art}.webp"]

    fun snapshot(): WatchCatalogSnapshot = WatchCatalogSnapshot(categories, visibleVideos,
        groups = categories.associate { it.id to videos(it) },
        imageLoader = { item -> artwork[item.thumbnail] },
        categoryImageLoader = { category -> artwork["stitch_watch/art/${category.art}.webp"] })

    companion object {
        /** Call off the UI thread. Ten 512x286 artworks occupy ~5.6 MiB decoded in total. */
        fun load(context: Context): WatchPreviewCatalog {
            val base = PlatformServices.contentRepository(context)
            val parsed = CatalogManifestParser.parse(
                context.assets.open("stitch_watch/catalog.json").bufferedReader().use { it.readText() },
                ContentSourceKind.BUNDLED,
            )
            check(parsed.ok) { "Invalid preview catalog: ${parsed.errors}" }
            val samples = requireNotNull(parsed.catalog).items
            val json = JSONArray(context.assets.open("stitch_watch/categories.json").bufferedReader().use { it.readText() })
            val categories = (0 until json.length()).map { index ->
                val o = json.getJSONObject(index)
                val keywords = o.getJSONArray("keywords")
                WatchCategory(o.getString("id"), o.getString("title"), o.getString("line1"),
                    o.getString("line2"), o.getString("art"), color(o.getString("accent")),
                    (0 until keywords.length()).map { keywords.getString(it) }.toSet())
            }
            val filter = PlatformServices.familySettings(context).contentFilter()
            val real = V1ContentShelf.filterForHome(base.getEnabledItems(), false)
            val videos = (samples + real).filter { item ->
                item.type == ContentType.VIDEO && item.enabled &&
                    (filter.ageMin == null || item.metadata.ageMax == null || item.metadata.ageMax >= filter.ageMin) &&
                    (filter.ageMax == null || item.metadata.ageMin == null || item.metadata.ageMin <= filter.ageMax)
            }.distinctBy { it.id }
            val paths = (samples.mapNotNull { it.thumbnail } + categories.map { "stitch_watch/art/${it.art}.webp" }).distinct()
            val artwork = paths.associateWith { path ->
                context.assets.open(path).use { requireNotNull(BitmapFactory.decodeStream(it)) }
            }
            return WatchPreviewCatalog(base, samples, categories, videos, artwork)
        }
    }
}
