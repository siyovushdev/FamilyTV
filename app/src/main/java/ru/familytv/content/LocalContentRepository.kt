package ru.familytv.content

import android.content.Context
import ru.familytv.content.packages.ContentPackageManifest
import ru.familytv.content.packages.ContentPackageStore
import ru.familytv.content.packages.LocalContentPackageStore
import ru.familytv.content.packages.PackageContentEntry
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset

/**
 * Merges bundled baseline (+ optional legacy catalog import) with active installed packages.
 * Conflict policy: package content IDs must not silently override bundled or other packages
 * (enforced at package install). Merge is deterministic by sortOrder then title.
 */
class LocalContentRepository(
    private val overrides: ContentOverrideStore,
    private val assetOpener: (String) -> InputStream,
    private val catalogRoot: File,
    private val packageStore: ContentPackageStore? = null,
) : ContentRepository {
    constructor(context: Context, packageStore: ContentPackageStore? = null) : this(
        overrides = ContentOverrideStore(context),
        assetOpener = { context.assets.open(it) },
        catalogRoot = File(
            requireNotNull(context.getExternalFilesDir(null)) { "External storage unavailable" },
            "FamilyTV/catalog",
        ),
        packageStore = packageStore,
    )

    @Volatile private var cached: ContentCatalog? = null
    @Volatile private var bundledIdsCache: Set<String>? = null
    @Volatile private var reloadGeneration: Long = 0L

    fun packageStoreOrNull(): ContentPackageStore? = packageStore

    /** Monotonic generation bumped on [reload] — Home can skip rebuild when unchanged. */
    fun catalogGeneration(): Long = reloadGeneration

    override fun getCatalog(): ContentCatalog = cached ?: reload()

    override fun getItem(id: String): ContentItem? = getEnabledItems().find { it.id == id }
        ?: getCatalog().items.find { it.id == id }?.let { applyOverride(it) }

    override fun getEnabledItems(): List<ContentItem> =
        getEffectiveItems().filter { it.enabled }

    fun getEffectiveItems(): List<ContentItem> =
        getCatalog().items.map(::applyOverride)

    override fun itemsForSection(sectionId: String): List<ContentItem> =
        getEnabledItems().filter { sectionId in it.homeSections }

    override fun setEnabled(id: String, enabled: Boolean) {
        overrides.setEnabled(id, enabled)
        cached = cached?.let { cat ->
            cat.copy(items = cat.items.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    override fun catalogVersion(): Int = getCatalog().catalogVersion

    override fun activeSource(): ContentSourceKind = getCatalog().source

    fun bundledContentIds(): Set<String> {
        bundledIdsCache?.let { return it }
        val bundled = CatalogManifestParser.parse(readBundledManifest(), ContentSourceKind.BUNDLED)
        return (bundled.catalog?.items?.map { it.id }?.toSet() ?: emptySet()).also { bundledIdsCache = it }
    }

    override fun validateActiveManifest(): ManifestValidationResult {
        val active = activeManifestFile()
        return if (active != null && active.exists()) {
            CatalogManifestParser.parse(active.readText(Charsets.UTF_8), ContentSourceKind.LOCAL_IMPORT)
        } else {
            CatalogManifestParser.parse(readBundledManifest(), ContentSourceKind.BUNDLED)
        }
    }

    override fun reload(): ContentCatalog {
        val baseline = loadBaseline()
        val packageItems = mutableListOf<ContentItem>()
        val warnings = baseline.validationWarnings.toMutableList()
        packageStore?.activePackages()?.forEach { (manifest, _) ->
            packageItems += manifest.content.map { it.toContentItem(manifest) }
        }
        val mergedItems = (baseline.items + packageItems)
            .sortedWith(compareBy({ it.sortOrder }, { it.title }))
        val source = when {
            packageItems.isNotEmpty() -> ContentSourceKind.MERGED
            baseline.source == ContentSourceKind.LOCAL_IMPORT -> ContentSourceKind.LOCAL_IMPORT
            else -> ContentSourceKind.BUNDLED
        }
        val catalog = ContentCatalog(
            schemaVersion = baseline.schemaVersion,
            catalogVersion = baseline.catalogVersion,
            source = source,
            items = mergedItems,
            validationWarnings = warnings,
        )
        cached = catalog
        reloadGeneration++
        return catalog
    }

    override fun importFromImportDirectory(): ManifestValidationResult {
        val importDir = File(catalogRoot.parentFile, "import")
        val manifest = File(importDir, "manifest.json")
        if (!manifest.exists()) {
            return ManifestValidationResult(false, null, listOf("No manifest.json in ${importDir.absolutePath}"))
        }
        val text = manifest.readText(Charsets.UTF_8)
        val parsed = CatalogManifestParser.parse(text, ContentSourceKind.LOCAL_IMPORT)
        if (!parsed.ok || parsed.catalog == null) return parsed

        val staging = File(catalogRoot, "staging").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        try {
            manifest.copyTo(File(staging, "manifest.json"), overwrite = true)
            val mediaSrc = File(importDir, "media")
            if (mediaSrc.isDirectory) mediaSrc.copyRecursively(File(staging, "media"), overwrite = true)
            val expSrc = File(importDir, "experiences")
            if (expSrc.isDirectory) expSrc.copyRecursively(File(staging, "experiences"), overwrite = true)
            val staged = CatalogManifestParser.parse(
                File(staging, "manifest.json").readText(Charsets.UTF_8),
                ContentSourceKind.LOCAL_IMPORT,
            )
            if (!staged.ok || staged.catalog == null) {
                staging.deleteRecursively()
                return staged
            }
            val active = File(catalogRoot, "active")
            val backup = File(catalogRoot, "backup")
            if (backup.exists()) backup.deleteRecursively()
            if (active.exists()) active.renameTo(backup)
            if (!staging.renameTo(active)) {
                if (backup.exists()) backup.renameTo(active)
                staging.deleteRecursively()
                return ManifestValidationResult(false, null, listOf("Failed to activate staged catalog"))
            }
            backup.deleteRecursively()
            reload()
            return staged
        } catch (e: Exception) {
            staging.deleteRecursively()
            return ManifestValidationResult(false, null, listOf("Import failed: ${e.message}"))
        }
    }

    override fun resolveMedia(item: ContentItem): ResolvedMedia? {
        val ref = item.mediaRef ?: return null
        if (item.packageId != null && item.packageVersion != null && packageStore != null) {
            val file = packageStore.resolveFile(item.packageId, item.packageVersion, ref)
            if (file != null) return ResolvedMedia.FilePath(file)
        }
        val imported = File(catalogRoot, "active/media/$ref")
        if (imported.isFile) return ResolvedMedia.FilePath(imported)
        return ResolvedMedia.AssetPath("catalog/media/$ref")
    }

    override fun openExperienceJson(item: ContentItem): String? {
        val ref = item.experienceRef ?: return null
        if (item.packageId != null && item.packageVersion != null && packageStore != null) {
            val path = if (ref.startsWith("experiences/")) ref else "experiences/$ref"
            val file = packageStore.resolveFile(item.packageId, item.packageVersion, path)
                ?: packageStore.resolveFile(item.packageId, item.packageVersion, ref)
            if (file != null) return file.readText(Charsets.UTF_8)
        }
        val active = File(catalogRoot, "active/experiences/$ref")
        if (active.isFile) return active.readText(Charsets.UTF_8)
        return try {
            assetOpener("catalog/experiences/$ref").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    override fun openSessionJson(item: ContentItem): String? {
        val ref = item.sessionRef ?: return null
        if (item.packageId != null && item.packageVersion != null && packageStore != null) {
            val path = if (ref.startsWith("sessions/")) ref else "sessions/$ref"
            val file = packageStore.resolveFile(item.packageId, item.packageVersion, path)
                ?: packageStore.resolveFile(item.packageId, item.packageVersion, ref)
            if (file != null) return file.readText(Charsets.UTF_8)
        }
        val active = File(catalogRoot, "active/sessions/$ref")
        if (active.isFile) return active.readText(Charsets.UTF_8)
        return try {
            assetOpener("catalog/sessions/$ref").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    override fun openAdventureJson(item: ContentItem): String? {
        val ref = item.adventureRef ?: return null
        if (item.packageId != null && item.packageVersion != null && packageStore != null) {
            val path = if (ref.startsWith("adventures/")) ref else "adventures/$ref"
            val file = packageStore.resolveFile(item.packageId, item.packageVersion, path)
                ?: packageStore.resolveFile(item.packageId, item.packageVersion, ref)
            if (file != null) return file.readText(Charsets.UTF_8)
        }
        val active = File(catalogRoot, "active/adventures/$ref")
        if (active.isFile) return active.readText(Charsets.UTF_8)
        return try {
            assetOpener("catalog/adventures/$ref").bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    /** Resolve raw media path for interactive VideoStep (package or bundled). */
    fun resolveMediaRef(item: ContentItem?, mediaRef: String): ResolvedMedia? {
        if (item?.packageId != null && item.packageVersion != null && packageStore != null) {
            val file = packageStore.resolveFile(item.packageId, item.packageVersion, mediaRef)
            if (file != null) return ResolvedMedia.FilePath(file)
        }
        val imported = File(catalogRoot, "active/media/${mediaRef.removePrefix("media/")}")
        if (imported.isFile) return ResolvedMedia.FilePath(imported)
        val asPackagePath = mediaRef
        val assetCandidate = when {
            asPackagePath.startsWith("media/") -> "catalog/$asPackagePath"
            else -> "catalog/media/$asPackagePath"
        }
        return ResolvedMedia.AssetPath(assetCandidate)
    }

    fun importDirectory(): File = File(catalogRoot.parentFile, "import").also { it.mkdirs() }

    private fun loadBaseline(): ContentCatalog {
        val active = activeManifestFile()
        if (active != null && active.exists()) {
            val result = CatalogManifestParser.parse(active.readText(Charsets.UTF_8), ContentSourceKind.LOCAL_IMPORT)
            if (result.ok && result.catalog != null) return result.catalog
        }
        val bundled = CatalogManifestParser.parse(readBundledManifest(), ContentSourceKind.BUNDLED)
        check(bundled.ok && bundled.catalog != null) { "Bundled catalog is invalid: ${bundled.errors}" }
        return bundled.catalog
    }

    private fun activeManifestFile(): File? =
        File(catalogRoot, "active/manifest.json").takeIf { it.isFile }

    private fun readBundledManifest(): String =
        assetOpener(BUNDLED_MANIFEST).bufferedReader(Charset.forName("UTF-8")).use { it.readText() }

    private fun applyOverride(item: ContentItem): ContentItem =
        item.copy(enabled = overrides.isEnabled(item.id, item.enabled))

    private fun PackageContentEntry.toContentItem(manifest: ContentPackageManifest) = ContentItem(
        id = id,
        title = title,
        description = description,
        type = ContentType.valueOf(type),
        ageRange = ageRange,
        categories = categories,
        tags = tags,
        thumbnail = thumbnail,
        durationMs = durationMs,
        mediaRef = mediaRef,
        route = route,
        experienceRef = experienceRef,
        sessionRef = sessionRef,
        adventureRef = adventureRef,
        enabled = enabled,
        sortOrder = sortOrder,
        version = version,
        homeSections = homeSections,
        metadata = metadata,
        packageId = manifest.packageId,
        packageVersion = manifest.packageVersion,
    )

    companion object {
        const val BUNDLED_MANIFEST = "catalog/manifest.json"
    }
}
