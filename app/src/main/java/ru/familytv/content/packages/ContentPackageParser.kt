package ru.familytv.content.packages

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

object PackagePathSafety {
    private val FORBIDDEN_EXTENSIONS = setOf(
        "apk", "dex", "so", "jar", "class", "exe", "bat", "cmd", "sh", "bin",
        "js", "mjs", "py", "rb", "php", "pl", "ps1", "vbs", "dll", "dylib",
    )
    private val ALLOWED_MEDIA_TYPES = setOf(
        "application/json",
        "video/mp4",
        "video/webm",
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/svg+xml",
        "audio/mpeg",
        "audio/mp4",
        "audio/aac",
        "text/plain",
    )
    const val MAX_FILE_BYTES = 200L * 1024L * 1024L // 200 MB sanity
    const val MAX_PACKAGE_FILES = 500

    fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.startsWith("/") || path.startsWith("\\")) return false
        if (':' in path) return false // Windows absolute / drive
        val normalized = path.replace('\\', '/')
        if (normalized.contains("..")) return false
        if (normalized.startsWith("./")) return false
        return normalized.matches(Regex("^[A-Za-z0-9._\\-/]+$"))
    }

    fun extensionAllowed(path: String): Boolean {
        val ext = path.substringAfterLast('.', missingDelimiterValue = "").lowercase(Locale.US)
        if (ext.isEmpty()) return true
        return ext !in FORBIDDEN_EXTENSIONS
    }

    fun mediaTypeAllowed(mediaType: String?): Boolean {
        if (mediaType.isNullOrBlank()) return true
        return mediaType.lowercase(Locale.US) in ALLOWED_MEDIA_TYPES
    }

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object ContentPackageParser {
    const val SUPPORTED_SCHEMA = 1

    fun parseManifest(jsonText: String): PackageValidationResult {
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return PackageValidationResult(false, errors = listOf("package.json is not valid JSON: ${e.message}"))
        }
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SUPPORTED_SCHEMA) {
            errors += "Unsupported schemaVersion=$schemaVersion (expected $SUPPORTED_SCHEMA)"
        }
        val packageId = root.optString("packageId").trim()
        if (packageId.isEmpty() || !packageId.matches(Regex("^[a-z0-9][a-z0-9._-]{1,63}$"))) {
            errors += "Invalid packageId"
        }
        val packageVersion = root.optInt("packageVersion", -1)
        if (packageVersion < 1) errors += "packageVersion must be >= 1"
        val title = root.optString("title").trim()
        if (title.isEmpty()) errors += "Missing title"

        val contentArray = root.optJSONArray("content")
        if (contentArray == null || contentArray.length() == 0) {
            errors += "content array required and non-empty"
        }
        val filesArray = root.optJSONArray("files")
        if (filesArray == null || filesArray.length() == 0) {
            errors += "files array required and non-empty"
        }
        if (errors.isNotEmpty()) return PackageValidationResult(false, errors = errors)

        val content = mutableListOf<PackageContentEntry>()
        val contentIds = mutableSetOf<String>()
        for (i in 0 until contentArray!!.length()) {
            val obj = contentArray.optJSONObject(i)
            if (obj == null) {
                errors += "content[$i] is not an object"
                continue
            }
            val id = obj.optString("id").trim()
            if (id.isEmpty()) {
                errors += "content[$i] missing id"
                continue
            }
            if (!contentIds.add(id)) {
                errors += "Duplicate content id '$id'"
                continue
            }
            val type = obj.optString("type").trim().uppercase()
            if (type !in setOf("VIDEO", "GAME", "INTERACTIVE", "SESSION", "ADVENTURE")) {
                errors += "content[$id] unknown type '$type'"
                continue
            }
            when (type) {
                "VIDEO" -> if (obj.optString("mediaRef").isBlank()) errors += "content[$id] VIDEO requires mediaRef"
                "GAME" -> if (obj.optString("route").isBlank()) errors += "content[$id] GAME requires route"
                "INTERACTIVE" -> if (obj.optString("experienceRef").isBlank()) {
                    errors += "content[$id] INTERACTIVE requires experienceRef"
                }
                "SESSION" -> if (obj.optString("sessionRef").isBlank()) {
                    errors += "content[$id] SESSION requires sessionRef"
                }
                "ADVENTURE" -> if (obj.optString("adventureRef").isBlank()) {
                    errors += "content[$id] ADVENTURE requires adventureRef"
                }
            }
            content += PackageContentEntry(
                id = id,
                title = obj.optString("title").ifBlank { id },
                description = obj.optString("description"),
                type = type,
                ageRange = obj.optString("ageRange").ifBlank { null },
                categories = stringList(obj.optJSONArray("categories")),
                tags = stringList(obj.optJSONArray("tags")),
                thumbnail = obj.optString("thumbnail").ifBlank { null },
                durationMs = obj.optLong("durationMs").takeIf { obj.has("durationMs") && it > 0 },
                mediaRef = obj.optString("mediaRef").ifBlank { null },
                route = obj.optString("route").ifBlank { null },
                experienceRef = obj.optString("experienceRef").ifBlank { null },
                sessionRef = obj.optString("sessionRef").ifBlank { null },
                adventureRef = obj.optString("adventureRef").ifBlank { null },
                enabled = if (obj.has("enabled")) obj.optBoolean("enabled") else true,
                sortOrder = obj.optInt("sortOrder", 0),
                version = obj.optInt("version", 1),
                homeSections = stringList(obj.optJSONArray("homeSections")),
                metadata = ru.familytv.content.MetadataParser.parse(obj.optJSONObject("metadata")),
            )
        }

        val files = mutableListOf<PackageFileEntry>()
        val filePaths = mutableSetOf<String>()
        if (filesArray!!.length() > PackagePathSafety.MAX_PACKAGE_FILES) {
            errors += "Too many files (>${PackagePathSafety.MAX_PACKAGE_FILES})"
        }
        for (i in 0 until filesArray.length()) {
            val obj = filesArray.optJSONObject(i) ?: run {
                errors += "files[$i] is not an object"
                continue
            }
            val path = obj.optString("path").trim().replace('\\', '/')
            if (!PackagePathSafety.isSafeRelativePath(path)) {
                errors += "Unsafe path '$path'"
                continue
            }
            if (!PackagePathSafety.extensionAllowed(path)) {
                errors += "Forbidden executable/extension in '$path'"
                continue
            }
            if (!filePaths.add(path)) {
                errors += "Duplicate file path '$path'"
                continue
            }
            val sha = obj.optString("sha256").trim().lowercase(Locale.US)
            if (!sha.matches(Regex("^[a-f0-9]{64}$"))) {
                errors += "Invalid sha256 for '$path'"
                continue
            }
            val size = obj.optLong("size", -1)
            if (size < 0 || size > PackagePathSafety.MAX_FILE_BYTES) {
                errors += "Invalid size for '$path'"
                continue
            }
            val mediaType = obj.optString("mediaType").ifBlank { null }
            if (!PackagePathSafety.mediaTypeAllowed(mediaType)) {
                errors += "Disallowed mediaType '$mediaType' for '$path'"
                continue
            }
            files += PackageFileEntry(path, sha, size, mediaType)
        }

        // package.json itself should be listed OR we treat it separately — require it in files
        if ("package.json" !in filePaths) {
            warnings += "package.json not listed in files (still validated separately)"
        }

        for (entry in content) {
            entry.mediaRef?.let { ref ->
                if (!PackagePathSafety.isSafeRelativePath(ref)) errors += "Unsafe mediaRef '$ref'"
                else if (ref !in filePaths) errors += "mediaRef '$ref' missing from files"
            }
            entry.experienceRef?.let { ref ->
                val path = if (ref.startsWith("experiences/")) ref else "experiences/$ref"
                if (!PackagePathSafety.isSafeRelativePath(path)) errors += "Unsafe experienceRef '$ref'"
                else if (path !in filePaths && ref !in filePaths) {
                    errors += "experienceRef '$ref' missing from files"
                }
            }
            entry.sessionRef?.let { ref ->
                val path = if (ref.startsWith("sessions/")) ref else "sessions/$ref"
                if (!PackagePathSafety.isSafeRelativePath(path)) errors += "Unsafe sessionRef '$ref'"
                else if (path !in filePaths && ref !in filePaths) {
                    errors += "sessionRef '$ref' missing from files"
                }
            }
            entry.adventureRef?.let { ref ->
                val path = if (ref.startsWith("adventures/")) ref else "adventures/$ref"
                if (!PackagePathSafety.isSafeRelativePath(path)) errors += "Unsafe adventureRef '$ref'"
                else if (path !in filePaths && ref !in filePaths) {
                    errors += "adventureRef '$ref' missing from files"
                }
            }
            entry.thumbnail?.let { ref ->
                if (PackagePathSafety.isSafeRelativePath(ref) && ref !in filePaths) {
                    warnings += "thumbnail '$ref' not in files"
                }
            }
        }

        if (errors.isNotEmpty()) return PackageValidationResult(false, errors = errors, warnings = warnings)
        return PackageValidationResult(
            ok = true,
            manifest = ContentPackageManifest(
                schemaVersion = schemaVersion,
                packageId = packageId,
                packageVersion = packageVersion,
                title = title,
                description = root.optString("description"),
                content = content,
                files = files,
            ),
            warnings = warnings,
        )
    }

    fun verifyPayload(rootDir: File, manifest: ContentPackageManifest): PackageValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        for (entry in manifest.files) {
            if (!PackagePathSafety.isSafeRelativePath(entry.path)) {
                errors += "Unsafe path '${entry.path}'"
                continue
            }
            val file = File(rootDir, entry.path)
            val canonicalRoot = rootDir.canonicalFile
            val canonicalFile = try {
                file.canonicalFile
            } catch (e: Exception) {
                errors += "Cannot resolve '${entry.path}': ${e.message}"
                continue
            }
            if (!canonicalFile.path.startsWith(canonicalRoot.path + File.separator) &&
                canonicalFile != canonicalRoot
            ) {
                errors += "Path traversal detected for '${entry.path}'"
                continue
            }
            if (!canonicalFile.isFile) {
                errors += "Missing file '${entry.path}'"
                continue
            }
            if (canonicalFile.length() != entry.size) {
                errors += "Size mismatch for '${entry.path}': expected ${entry.size}, got ${canonicalFile.length()}"
            }
            if (canonicalFile.length() > PackagePathSafety.MAX_FILE_BYTES) {
                errors += "File too large '${entry.path}'"
            }
            val actual = PackagePathSafety.sha256(canonicalFile)
            if (actual != entry.sha256.lowercase(Locale.US)) {
                errors += "SHA-256 mismatch for '${entry.path}'"
            }
        }
        return PackageValidationResult(
            ok = errors.isEmpty(),
            manifest = manifest,
            errors = errors,
            warnings = warnings,
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val v = array.optString(i).trim()
                if (v.isNotEmpty()) add(v)
            }
        }
    }
}
