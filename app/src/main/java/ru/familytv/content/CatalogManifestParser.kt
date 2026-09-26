package ru.familytv.content

import org.json.JSONArray
import org.json.JSONObject

object CatalogManifestParser {
    const val SUPPORTED_SCHEMA = 1

    fun parse(jsonText: String, source: ContentSourceKind): ManifestValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return ManifestValidationResult(false, null, listOf("Manifest is not valid JSON: ${e.message}"))
        }

        val schemaVersion = root.optInt("schemaVersion", -1)
        if (schemaVersion != SUPPORTED_SCHEMA) {
            errors += "Unsupported schemaVersion=$schemaVersion (expected $SUPPORTED_SCHEMA)"
        }
        val catalogVersion = root.optInt("catalogVersion", -1)
        if (catalogVersion < 1) {
            errors += "catalogVersion must be >= 1"
        }
        val itemsArray = root.optJSONArray("items")
        if (itemsArray == null) {
            errors += "Missing items array"
        }
        if (errors.isNotEmpty()) {
            return ManifestValidationResult(false, null, errors, warnings)
        }

        val seenIds = mutableSetOf<String>()
        val items = mutableListOf<ContentItem>()
        for (i in 0 until itemsArray!!.length()) {
            val raw = itemsArray.optJSONObject(i)
            if (raw == null) {
                warnings += "Item at index $i is not an object — skipped"
                continue
            }
            when (val parsed = parseItem(raw, i)) {
                is ItemParse.Ok -> {
                    if (!seenIds.add(parsed.item.id)) {
                        warnings += "Duplicate id '${parsed.item.id}' — skipped duplicate"
                    } else {
                        items += parsed.item
                        warnings += parsed.warnings
                    }
                }
                is ItemParse.Bad -> {
                    warnings += parsed.reason
                }
            }
        }

        if (items.isEmpty()) {
            return ManifestValidationResult(
                false,
                null,
                listOf("Manifest has no valid items"),
                warnings,
            )
        }

        val catalog = ContentCatalog(
            schemaVersion = schemaVersion,
            catalogVersion = catalogVersion,
            source = source,
            items = items.sortedWith(compareBy({ it.sortOrder }, { it.title })),
            validationWarnings = warnings,
        )
        return ManifestValidationResult(true, catalog, emptyList(), warnings)
    }

    private sealed interface ItemParse {
        data class Ok(val item: ContentItem, val warnings: List<String> = emptyList()) : ItemParse
        data class Bad(val reason: String) : ItemParse
    }

    private fun parseItem(json: JSONObject, index: Int): ItemParse {
        val id = json.optString("id").trim()
        if (id.isEmpty()) return ItemParse.Bad("Item[$index]: missing id — skipped")
        val title = json.optString("title").trim()
        if (title.isEmpty()) return ItemParse.Bad("Item[$id]: missing title — skipped")
        val typeRaw = json.optString("type").trim().uppercase()
        val type = try {
            ContentType.valueOf(typeRaw)
        } catch (_: Exception) {
            return ItemParse.Bad("Item[$id]: unknown type '$typeRaw' — skipped")
        }
        val warnings = mutableListOf<String>()
        when (type) {
            ContentType.VIDEO -> if (json.optString("mediaRef").isBlank()) {
                warnings += "Item[$id]: VIDEO without mediaRef"
            }
            ContentType.GAME -> if (json.optString("route").isBlank()) {
                return ItemParse.Bad("Item[$id]: GAME requires route — skipped")
            }
            ContentType.INTERACTIVE -> if (json.optString("experienceRef").isBlank()) {
                return ItemParse.Bad("Item[$id]: INTERACTIVE requires experienceRef — skipped")
            }
            ContentType.SESSION -> if (json.optString("sessionRef").isBlank()) {
                return ItemParse.Bad("Item[$id]: SESSION requires sessionRef — skipped")
            }
            ContentType.ADVENTURE -> if (json.optString("adventureRef").isBlank()) {
                return ItemParse.Bad("Item[$id]: ADVENTURE requires adventureRef — skipped")
            }
        }
        return ItemParse.Ok(
            ContentItem(
                id = id,
                title = title,
                description = json.optString("description"),
                type = type,
                ageRange = json.optString("ageRange").ifBlank { null },
                categories = stringList(json.optJSONArray("categories")),
                tags = stringList(json.optJSONArray("tags")),
                thumbnail = json.optString("thumbnail").ifBlank { null },
                durationMs = json.optLong("durationMs").takeIf { json.has("durationMs") && it > 0 },
                mediaRef = json.optString("mediaRef").ifBlank { null },
                route = json.optString("route").ifBlank { null },
                experienceRef = json.optString("experienceRef").ifBlank { null },
                sessionRef = json.optString("sessionRef").ifBlank { null },
                adventureRef = json.optString("adventureRef").ifBlank { null },
                enabled = if (json.has("enabled")) json.optBoolean("enabled") else true,
                sortOrder = json.optInt("sortOrder", 0),
                version = json.optInt("version", 1),
                homeSections = stringList(json.optJSONArray("homeSections")),
                metadata = MetadataParser.parse(json.optJSONObject("metadata")),
            ),
            warnings,
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }
}
