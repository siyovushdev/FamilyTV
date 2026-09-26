package ru.familytv.content.packages

import ru.familytv.content.ContentMetadata

/**
 * Versioned content package — installable without APK rebuild.
 * Media refs are relative package paths only (no Android resource IDs).
 */
data class ContentPackageManifest(
    val schemaVersion: Int,
    val packageId: String,
    val packageVersion: Int,
    val title: String,
    val description: String = "",
    val content: List<PackageContentEntry>,
    val files: List<PackageFileEntry>,
)

data class PackageContentEntry(
    val id: String,
    val title: String,
    val description: String = "",
    val type: String,
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
)

data class PackageFileEntry(
    val path: String,
    val sha256: String,
    val size: Long,
    val mediaType: String? = null,
)

data class PackageValidationResult(
    val ok: Boolean,
    val manifest: ContentPackageManifest? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

data class InstalledPackageInfo(
    val packageId: String,
    val title: String,
    val installedVersions: List<Int>,
    val activeVersion: Int?,
)

data class PackageInstallResult(
    val ok: Boolean,
    val packageId: String? = null,
    val packageVersion: Int? = null,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

interface ContentPackageStore {
    fun listInstalled(): List<InstalledPackageInfo>
    fun activePackages(): List<Pair<ContentPackageManifest, java.io.File>>
    fun importDirectory(sourceDir: java.io.File): PackageInstallResult
    fun importZip(zipFile: java.io.File): PackageInstallResult
    fun activate(packageId: String, version: Int): PackageInstallResult
    fun rollback(packageId: String): PackageInstallResult
    fun removeVersion(packageId: String, version: Int): PackageInstallResult
    fun resolveFile(packageId: String, version: Int, relativePath: String): java.io.File?
    fun importDirectoryRoot(): java.io.File
}
