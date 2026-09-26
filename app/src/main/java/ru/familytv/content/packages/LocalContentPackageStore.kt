package ru.familytv.content.packages

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.io.File

/**
 * Installs versioned packages under:
 * FamilyTV/packages/<packageId>/<version>/
 * Active version metadata in SharedPreferences (verified at import; not re-hashed on Home).
 */
class LocalContentPackageStore(
    private val packagesRoot: File,
    private val importRoot: File,
    private val prefs: SharedPreferences,
    private val reservedContentIds: () -> Set<String> = { emptySet() },
) : ContentPackageStore {

    constructor(context: Context, reservedContentIds: () -> Set<String> = { emptySet() }) : this(
        packagesRoot = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "FamilyTV/packages",
        ).also { it.mkdirs() },
        importRoot = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "FamilyTV/import-packages",
        ).also { it.mkdirs() },
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        reservedContentIds = reservedContentIds,
    )

    override fun importDirectoryRoot(): File = importRoot.also { it.mkdirs() }

    override fun listInstalled(): List<InstalledPackageInfo> {
        if (!packagesRoot.exists()) return emptyList()
        return packagesRoot.listFiles()?.filter { it.isDirectory }?.mapNotNull { pkgDir ->
            val versions = pkgDir.listFiles()
                ?.mapNotNull { it.name.toIntOrNull() }
                ?.sorted()
                ?: emptyList()
            if (versions.isEmpty()) return@mapNotNull null
            val active = activeVersion(pkgDir.name)
            val title = versions.lastOrNull()?.let { readManifest(pkgDir.name, it)?.title } ?: pkgDir.name
            InstalledPackageInfo(pkgDir.name, title, versions, active)
        }?.sortedBy { it.packageId } ?: emptyList()
    }

    override fun activePackages(): List<Pair<ContentPackageManifest, File>> {
        return listInstalled().mapNotNull { info ->
            val version = info.activeVersion ?: return@mapNotNull null
            val manifest = readManifest(info.packageId, version) ?: return@mapNotNull null
            val dir = versionDir(info.packageId, version)
            manifest to dir
        }
    }

    override fun importDirectory(sourceDir: File): PackageInstallResult {
        if (!sourceDir.isDirectory) {
            return PackageInstallResult(false, errors = listOf("Not a directory: ${sourceDir.absolutePath}"))
        }
        val staging = File(packagesRoot, ".staging-${System.nanoTime()}").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        return try {
            sourceDir.copyRecursively(staging, overwrite = true)
            installFromStaging(staging)
        } catch (e: Exception) {
            staging.deleteRecursively()
            PackageInstallResult(false, errors = listOf("Import failed: ${e.message}"))
        }
    }

    override fun importZip(zipFile: File): PackageInstallResult {
        if (!zipFile.isFile) {
            return PackageInstallResult(false, errors = listOf("ZIP not found: ${zipFile.absolutePath}"))
        }
        val staging = File(packagesRoot, ".staging-${System.nanoTime()}").also {
            if (it.exists()) it.deleteRecursively()
            it.mkdirs()
        }
        return try {
            val extracted = ZipSafeExtractor.extract(zipFile.inputStream(), staging)
            if (!extracted.ok) {
                staging.deleteRecursively()
                return PackageInstallResult(false, errors = extracted.errors)
            }
            val root = ZipSafeExtractor.findPackageRoot(staging)
                ?: run {
                    staging.deleteRecursively()
                    return PackageInstallResult(false, errors = listOf("package.json not found in ZIP"))
                }
            if (root != staging) {
                // Move nested root contents up conceptually by installing from nested root
                val nestedStaging = File(packagesRoot, ".staging-nested-${System.nanoTime()}").also { it.mkdirs() }
                root.copyRecursively(nestedStaging, overwrite = true)
                staging.deleteRecursively()
                return installFromStaging(nestedStaging)
            }
            installFromStaging(staging)
        } catch (e: Exception) {
            staging.deleteRecursively()
            PackageInstallResult(false, errors = listOf("ZIP import failed: ${e.message}"))
        }
    }

    private fun installFromStaging(staging: File): PackageInstallResult {
        val manifestFile = File(staging, "package.json")
        if (!manifestFile.isFile) {
            staging.deleteRecursively()
            return PackageInstallResult(false, errors = listOf("Missing package.json"))
        }
        val parsed = ContentPackageParser.parseManifest(manifestFile.readText(Charsets.UTF_8))
        if (!parsed.ok || parsed.manifest == null) {
            staging.deleteRecursively()
            return PackageInstallResult(false, errors = parsed.errors, warnings = parsed.warnings)
        }
        val manifest = parsed.manifest
        val verified = ContentPackageParser.verifyPayload(staging, manifest)
        if (!verified.ok) {
            staging.deleteRecursively()
            return PackageInstallResult(
                false,
                packageId = manifest.packageId,
                packageVersion = manifest.packageVersion,
                errors = verified.errors,
                warnings = verified.warnings + parsed.warnings,
            )
        }

        val conflict = checkContentIdConflicts(manifest)
        if (conflict.isNotEmpty()) {
            staging.deleteRecursively()
            return PackageInstallResult(
                false,
                packageId = manifest.packageId,
                packageVersion = manifest.packageVersion,
                errors = conflict,
            )
        }

        val target = versionDir(manifest.packageId, manifest.packageVersion)
        if (target.exists()) {
            // Do not overwrite known-good version in place — reject duplicate version install
            staging.deleteRecursively()
            return PackageInstallResult(
                false,
                packageId = manifest.packageId,
                packageVersion = manifest.packageVersion,
                errors = listOf("Version ${manifest.packageVersion} already installed for ${manifest.packageId}"),
            )
        }
        target.parentFile?.mkdirs()
        if (!staging.renameTo(target)) {
            // fallback copy
            try {
                staging.copyRecursively(target, overwrite = false)
                staging.deleteRecursively()
            } catch (e: Exception) {
                staging.deleteRecursively()
                target.deleteRecursively()
                return PackageInstallResult(false, errors = listOf("Failed to install to ${target.absolutePath}"))
            }
        }

        writeIndex(manifest.packageId, manifest.packageVersion, manifest)
        setActiveVersion(manifest.packageId, manifest.packageVersion)
        return PackageInstallResult(
            ok = true,
            packageId = manifest.packageId,
            packageVersion = manifest.packageVersion,
            warnings = parsed.warnings + verified.warnings,
        )
    }

    override fun activate(packageId: String, version: Int): PackageInstallResult {
        val dir = versionDir(packageId, version)
        if (!dir.isDirectory || !File(dir, "package.json").isFile) {
            return PackageInstallResult(false, packageId, version, listOf("Version not installed"))
        }
        val previous = activeVersion(packageId)
        setActiveVersion(packageId, version)
        return PackageInstallResult(true, packageId, version, warnings = listOfNotNull(
            previous?.let { "Previous active was $it" },
        ))
    }

    override fun rollback(packageId: String): PackageInstallResult {
        val info = listInstalled().find { it.packageId == packageId }
            ?: return PackageInstallResult(false, packageId, errors = listOf("Package not installed"))
        val active = info.activeVersion
            ?: return PackageInstallResult(false, packageId, errors = listOf("No active version"))
        val previous = info.installedVersions.filter { it < active }.maxOrNull()
            ?: return PackageInstallResult(false, packageId, active, listOf("No previous version to rollback to"))
        return activate(packageId, previous)
    }

    override fun removeVersion(packageId: String, version: Int): PackageInstallResult {
        if (activeVersion(packageId) == version) {
            return PackageInstallResult(false, packageId, version, listOf("Cannot remove active version"))
        }
        val dir = versionDir(packageId, version)
        if (!dir.exists()) {
            return PackageInstallResult(false, packageId, version, listOf("Version not found"))
        }
        dir.deleteRecursively()
        return PackageInstallResult(true, packageId, version)
    }

    override fun resolveFile(packageId: String, version: Int, relativePath: String): File? {
        if (!PackagePathSafety.isSafeRelativePath(relativePath)) return null
        val file = File(versionDir(packageId, version), relativePath)
        val root = versionDir(packageId, version).canonicalFile
        val canonical = try {
            file.canonicalFile
        } catch (_: Exception) {
            return null
        }
        if (!canonical.path.startsWith(root.path + File.separator)) return null
        return canonical.takeIf { it.isFile }
    }

    fun activeVersion(packageId: String): Int? {
        val v = prefs.getInt(activeKey(packageId), -1)
        return v.takeIf { it > 0 }
    }

    private fun setActiveVersion(packageId: String, version: Int) {
        prefs.edit().putInt(activeKey(packageId), version).commit()
    }

    private fun writeIndex(packageId: String, version: Int, manifest: ContentPackageManifest) {
        // Trusted index written at install — Home reads this, does not re-hash media.
        val index = JSONObject()
            .put("packageId", packageId)
            .put("packageVersion", version)
            .put("title", manifest.title)
            .put("contentIds", org.json.JSONArray(manifest.content.map { it.id }))
        File(versionDir(packageId, version), "index.json").writeText(index.toString())
    }

    private fun readManifest(packageId: String, version: Int): ContentPackageManifest? {
        val file = File(versionDir(packageId, version), "package.json")
        if (!file.isFile) return null
        return ContentPackageParser.parseManifest(file.readText(Charsets.UTF_8)).manifest
    }

    private fun versionDir(packageId: String, version: Int) = File(packagesRoot, "$packageId/$version")

    private fun activeKey(packageId: String) = "active:$packageId"

    private fun checkContentIdConflicts(manifest: ContentPackageManifest): List<String> {
        val reserved = reservedContentIds()
        val errors = mutableListOf<String>()
        // IDs owned by other active packages
        val ownedByOthers = mutableMapOf<String, String>()
        for ((other, _) in activePackages()) {
            if (other.packageId == manifest.packageId) continue
            other.content.forEach { ownedByOthers[it.id] = other.packageId }
        }
        for (entry in manifest.content) {
            if (entry.id in reserved) {
                errors += "Content id '${entry.id}' conflicts with bundled/baseline catalog"
            }
            ownedByOthers[entry.id]?.let { owner ->
                errors += "Content id '${entry.id}' already owned by active package '$owner'"
            }
        }
        return errors
    }

    companion object {
        private const val PREFS = "familytv_package_store"
    }
}
