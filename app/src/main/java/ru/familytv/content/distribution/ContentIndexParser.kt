package ru.familytv.content.distribution

import org.json.JSONArray
import org.json.JSONObject

object ContentIndexParser {
    fun parseEnvelope(text: String): ParseResult {
        val errors = mutableListOf<String>()
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ParseResult(false, null, listOf("Malformed index JSON: ${e.message}"))
        }
        val schema = root.optInt("schemaVersion", -1)
        if (schema != 1) errors += "Unsupported envelope schemaVersion=$schema"
        val keyId = root.optString("keyId").trim()
        if (keyId.isEmpty()) errors += "Missing keyId"
        val signature = root.optString("signature").trim()
        if (signature.isEmpty()) errors += "Missing signature"
        val payloadObj = root.optJSONObject("payload")
        if (payloadObj == null) {
            errors += "Missing payload"
            return ParseResult(false, null, errors)
        }
        val payload = parsePayload(payloadObj, errors)
        if (errors.isNotEmpty() || payload == null) {
            return ParseResult(false, null, errors)
        }
        return ParseResult(
            true,
            ContentIndexEnvelope(schema, keyId, signature, payload),
            emptyList(),
            payloadOrgJson = payloadObj,
        )
    }

    private fun parsePayload(obj: JSONObject, errors: MutableList<String>): ContentIndexPayload? {
        val schema = obj.optInt("schemaVersion", -1)
        if (schema != 1) errors += "Unsupported payload schemaVersion=$schema"
        val indexVersion = obj.optInt("indexVersion", -1)
        if (indexVersion < 1) errors += "indexVersion must be >= 1"
        val generatedAt = obj.optString("generatedAt").trim()
        val publisherId = obj.optString("publisherId").trim()
        if (publisherId.isEmpty()) errors += "Missing publisherId"
        val arr = obj.optJSONArray("packages") ?: JSONArray()
            val packages = mutableListOf<RemotePackageRecord>()
        val seen = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i)
            if (p == null) {
                errors += "packages[$i] not an object"
                continue
            }
            val packageId = p.optString("packageId").trim()
            val version = p.optInt("packageVersion", -1)
            val url = p.optString("url").trim()
            val sha = p.optString("sha256").trim().lowercase()
            val size = p.optLong("size", -1L)
            if (packageId.isEmpty()) errors += "packages[$i].packageId required"
            if (version < 1) errors += "packages[$i].packageVersion invalid"
            if (url.isEmpty()) errors += "packages[$i].url required"
            if (!sha.matches(Regex("[a-f0-9]{64}"))) errors += "packages[$i].sha256 invalid"
            if (size < 0) errors += "packages[$i].size invalid"
            if (url.contains("..")) errors += "packages[$i].url path traversal"
            val key = "$packageId@$version"
            if (!seen.add(key)) errors += "Duplicate package record $key"
            packages += RemotePackageRecord(
                packageId = packageId,
                packageVersion = version,
                title = p.optString("title").ifBlank { packageId },
                description = p.optString("description"),
                url = url,
                path = p.optString("path").ifBlank { null },
                size = size,
                sha256 = sha,
                enabled = if (p.has("enabled")) p.optBoolean("enabled") else true,
                sortOrder = p.optInt("sortOrder", 100),
            )
        }
        if (errors.isNotEmpty()) return null
        return ContentIndexPayload(schema, indexVersion, generatedAt, publisherId, packages)
    }

    data class ParseResult(
        val ok: Boolean,
        val envelope: ContentIndexEnvelope?,
        val errors: List<String>,
        /** Original payload JSONObject for canonical re-serialization. */
        val payloadOrgJson: JSONObject? = null,
    )
}
