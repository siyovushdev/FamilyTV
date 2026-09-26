package ru.familytv.content.distribution

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

interface HttpFetcher {
    fun getText(url: String, etag: String? = null, lastModified: String? = null): FetchTextResult
    fun downloadToFile(
        url: String,
        dest: File,
        expectedSize: Long? = null,
        onProgress: ((Long) -> Unit)? = null,
    ): FetchFileResult
}

data class FetchTextResult(
    val ok: Boolean,
    val body: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val notModified: Boolean = false,
    val error: String? = null,
    val httpCode: Int = 0,
)

data class FetchFileResult(
    val ok: Boolean,
    val sha256: String? = null,
    val bytesWritten: Long = 0,
    val error: String? = null,
)

class UrlHttpFetcher(
    private val connectTimeoutMs: Int = 8_000,
    private val readTimeoutMs: Int = 30_000,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : HttpFetcher {

    override fun getText(url: String, etag: String?, lastModified: String?): FetchTextResult {
        return try {
            val conn = open(url)
            if (etag != null) conn.setRequestProperty("If-None-Match", etag)
            if (lastModified != null) conn.setRequestProperty("If-Modified-Since", lastModified)
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return FetchTextResult(true, notModified = true, httpCode = code, etag = etag, lastModified = lastModified)
            }
            if (code !in 200..299) {
                return FetchTextResult(false, error = "HTTP $code for $url", httpCode = code)
            }
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            FetchTextResult(
                ok = true,
                body = body,
                etag = conn.getHeaderField("ETag"),
                lastModified = conn.getHeaderField("Last-Modified"),
                httpCode = code,
            )
        } catch (e: Exception) {
            FetchTextResult(false, error = e.message ?: "Network error")
        }
    }

    override fun downloadToFile(
        url: String,
        dest: File,
        expectedSize: Long?,
        onProgress: ((Long) -> Unit)?,
    ): FetchFileResult {
        val partial = File(dest.parentFile, dest.name + ".partial")
        return try {
            partial.parentFile?.mkdirs()
            if (partial.exists()) partial.delete()
            val conn = open(url)
            conn.requestMethod = "GET"
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                return FetchFileResult(false, error = "HTTP $code downloading $url")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var written = 0L
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        written += n
                        onProgress?.invoke(written)
                        if (expectedSize != null && written > expectedSize) {
                            partial.delete()
                            return FetchFileResult(false, error = "Download exceeded expected size")
                        }
                    }
                }
            }
            if (expectedSize != null && written != expectedSize) {
                partial.delete()
                return FetchFileResult(false, bytesWritten = written, error = "Size mismatch: got $written expected $expectedSize")
            }
            if (dest.exists()) dest.delete()
            if (!partial.renameTo(dest)) {
                partial.copyTo(dest, overwrite = true)
                partial.delete()
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            FetchFileResult(true, sha256 = sha, bytesWritten = written)
        } catch (e: Exception) {
            partial.delete()
            FetchFileResult(false, error = e.message ?: "Download failed")
        }
    }

    private fun open(url: String): HttpURLConnection {
        val u = URL(url)
        val conn = openConnection(u)
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        conn.instanceFollowRedirects = true
        return conn
    }
}

object UrlPolicy {
    fun validateSourceUrl(url: String, allowCleartext: Boolean, debuggable: Boolean): List<String> {
        val errors = mutableListOf<String>()
        if (url.isBlank()) {
            errors += "Source URL is empty"
            return errors
        }
        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return listOf("Malformed source URL")
        }
        val scheme = uri.scheme?.lowercase()
        when (scheme) {
            "https" -> Unit
            "http" -> {
                if (!(allowCleartext && debuggable)) {
                    errors += "Cleartext HTTP only allowed in debug with explicit allowCleartext"
                }
            }
            else -> errors += "Unsupported URL scheme: $scheme"
        }
        if (uri.host.isNullOrBlank()) errors += "URL host required"
        return errors
    }

    fun resolvePackageUrl(indexUrl: String, record: RemotePackageRecord): String {
        val raw = record.url
        if (raw.startsWith("http://") || raw.startsWith("https://")) return raw
        val base = indexUrl.substringBeforeLast('/') + "/"
        return URI(base).resolve(record.path ?: raw).toString()
    }
}
