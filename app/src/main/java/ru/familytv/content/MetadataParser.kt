package ru.familytv.content

import org.json.JSONObject

object MetadataParser {
    fun parse(json: JSONObject?): ContentMetadata {
        if (json == null) return ContentMetadata()
        return ContentMetadata(
            ageMin = json.optInt("ageMin").takeIf { json.has("ageMin") },
            ageMax = json.optInt("ageMax").takeIf { json.has("ageMax") },
            skills = stringList(json.optJSONArray("skills")),
            difficulty = json.optString("difficulty").ifBlank { null },
            estimatedMinutes = json.optInt("estimatedMinutes").takeIf { json.has("estimatedMinutes") && it > 0 },
            topics = stringList(json.optJSONArray("topics")),
            interactionModes = stringList(json.optJSONArray("interactionModes")),
            locale = json.optString("locale").ifBlank { null },
            seriesId = json.optString("seriesId").ifBlank { null },
            episodeOrder = json.optInt("episodeOrder").takeIf { json.has("episodeOrder") },
            contentPurpose = json.optString("contentPurpose").ifBlank { null },
            captionRef = json.optString("captionRef").ifBlank { null }
                ?: json.optString("subtitleRef").ifBlank { null },
        )
    }

    private fun stringList(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                val v = array.optString(i).trim()
                if (v.isNotEmpty()) add(v)
            }
        }
    }
}
