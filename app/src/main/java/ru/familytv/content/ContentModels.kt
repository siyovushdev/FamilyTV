package ru.familytv.content

/** Optional educational metadata — extensible, not a closed enum in UI. */
data class ContentMetadata(
    val ageMin: Int? = null,
    val ageMax: Int? = null,
    val skills: List<String> = emptyList(),
    val difficulty: String? = null,
    val estimatedMinutes: Int? = null,
    val topics: List<String> = emptyList(),
    val interactionModes: List<String> = emptyList(),
    val locale: String? = null,
    val seriesId: String? = null,
    val episodeOrder: Int? = null,
    val contentPurpose: String? = null,
    /** Optional package-relative WebVTT/SRT caption asset. */
    val captionRef: String? = null,
)

/** Supported catalog entry kinds. */
enum class ContentType {
    VIDEO,
    GAME,
    INTERACTIVE,
    SESSION,
    ADVENTURE,
}

/**
 * Device/family scoped catalog entry. UI never binds Activities directly —
 * navigation uses [route] / type-specific refs resolved by ContentNavigator.
 */
data class ContentItem(
    val id: String,
    val title: String,
    val description: String = "",
    val type: ContentType,
    val ageRange: String? = null,
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val thumbnail: String? = null,
    val durationMs: Long? = null,
    val mediaRef: String? = null,
    val route: String? = null,
    val experienceRef: String? = null,
    val sessionRef: String? = null,
    val adventureRef: String? = null,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
    val version: Int = 1,
    val homeSections: List<String> = emptyList(),
    val metadata: ContentMetadata = ContentMetadata(),
    /** Provenance when item comes from an installed package. */
    val packageId: String? = null,
    val packageVersion: Int? = null,
)

data class ContentCatalog(
    val schemaVersion: Int,
    val catalogVersion: Int,
    val source: ContentSourceKind,
    val items: List<ContentItem>,
    val validationWarnings: List<String> = emptyList(),
)

enum class ContentSourceKind {
    BUNDLED,
    LOCAL_IMPORT,
    MERGED,
}

data class ManifestValidationResult(
    val ok: Boolean,
    val catalog: ContentCatalog?,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

interface ContentRepository {
    fun getCatalog(): ContentCatalog
    fun getItem(id: String): ContentItem?
    fun getEnabledItems(): List<ContentItem>
    fun itemsForSection(sectionId: String): List<ContentItem>
    fun setEnabled(id: String, enabled: Boolean)
    fun reload(): ContentCatalog
    fun validateActiveManifest(): ManifestValidationResult
    fun importFromImportDirectory(): ManifestValidationResult
    fun catalogVersion(): Int
    fun activeSource(): ContentSourceKind
    fun resolveMedia(item: ContentItem): ResolvedMedia?
    fun openExperienceJson(item: ContentItem): String?
    fun openSessionJson(item: ContentItem): String?
    fun openAdventureJson(item: ContentItem): String?
}

sealed interface ResolvedMedia {
    data class AssetPath(val assetPath: String) : ResolvedMedia
    data class FilePath(val file: java.io.File) : ResolvedMedia
}
