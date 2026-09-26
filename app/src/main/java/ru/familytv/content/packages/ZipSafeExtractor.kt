package ru.familytv.content.packages

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

object ZipSafeExtractor {
    data class Result(val ok: Boolean, val errors: List<String> = emptyList())

    fun extract(zipStream: InputStream, destDir: File): Result {
        destDir.mkdirs()
        val canonicalDest = destDir.canonicalFile
        val errors = mutableListOf<String>()
        ZipInputStream(zipStream.buffered()).use { zis ->
            var entries = 0
            while (true) {
                val entry = zis.nextEntry ?: break
                entries++
                if (entries > PackagePathSafety.MAX_PACKAGE_FILES) {
                    return Result(false, listOf("ZIP has too many entries"))
                }
                val name = entry.name.replace('\\', '/')
                if (name.isBlank() || name.endsWith("/")) {
                    zis.closeEntry()
                    continue
                }
                // Strip a single top-level folder if present (package/)
                val relative = name.removePrefix("./")
                if (!PackagePathSafety.isSafeRelativePath(relative) || relative.contains("..")) {
                    return Result(false, listOf("ZIP slip / unsafe entry: '$name'"))
                }
                if (!PackagePathSafety.extensionAllowed(relative)) {
                    return Result(false, listOf("Forbidden entry extension: '$relative'"))
                }
                val out = File(destDir, relative)
                val canonicalOut = out.canonicalFile
                if (!canonicalOut.path.startsWith(canonicalDest.path + File.separator)) {
                    return Result(false, listOf("ZIP slip blocked for '$name'"))
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { output ->
                    var total = 0L
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zis.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > PackagePathSafety.MAX_FILE_BYTES) {
                            return Result(false, listOf("ZIP entry too large: '$relative'"))
                        }
                        output.write(buffer, 0, read)
                    }
                }
                zis.closeEntry()
            }
        }
        // If extracted into a single nested directory containing package.json, flatten conceptually
        // by returning dest as-is; store will locate package.json.
        if (errors.isNotEmpty()) return Result(false, errors)
        return Result(true)
    }

    /** Locate directory that contains package.json (root or one nested folder). */
    fun findPackageRoot(extracted: File): File? {
        val direct = File(extracted, "package.json")
        if (direct.isFile) return extracted
        val children = extracted.listFiles()?.filter { it.isDirectory } ?: return null
        if (children.size == 1) {
            val nested = File(children[0], "package.json")
            if (nested.isFile) return children[0]
        }
        return extracted.walkTopDown().maxDepth(2).firstOrNull { it.name == "package.json" }?.parentFile
    }
}
