package ru.familytv.content

/**
 * V1 Home shelf curation — tags only, no new catalog protocol.
 * `v1` → child Home; `dev_only` / `experimental` → hidden from V1 Home.
 */
object V1ContentShelf {
    const val TAG_V1 = "v1"
    const val TAG_DEV = "dev_only"
    const val TAG_EXPERIMENTAL = "experimental"

    fun isV1HomeVisible(item: ContentItem): Boolean {
        val tags = item.tags.map { it.lowercase() }
        if (TAG_DEV in tags || TAG_EXPERIMENTAL in tags) return false
        return TAG_V1 in tags
    }

    fun filterForHome(items: List<ContentItem>, showDevShelf: Boolean): List<ContentItem> {
        if (showDevShelf) return items
        return items.filter { isV1HomeVisible(it) }
    }
}
