package ru.familytv.knowledge

import org.json.JSONArray
import org.json.JSONObject

object KnowledgeParser {
    fun parsePack(json: String): KnowledgePack {
        val root = JSONObject(json)
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val list = buildList {
            for (i in 0 until entries.length()) {
                val o = entries.getJSONObject(i)
                val provObj = o.optJSONObject("provenance")
                val provenance = if (provObj != null) {
                    KnowledgeProvenance(
                        sourceTitle = provObj.optString("sourceTitle").ifBlank { null },
                        sourceOrganization = provObj.optString("sourceOrganization").ifBlank { null },
                        sourceReference = provObj.optString("sourceReference").ifBlank { null },
                        reviewStatus = provObj.optString("reviewStatus", "draft"),
                        lastReviewed = provObj.optString("lastReviewed").ifBlank { null },
                    )
                } else {
                    KnowledgeProvenance(
                        sourceTitle = o.optString("sourceMetadata").ifBlank { null },
                        reviewStatus = if (o.has("sourceMetadata")) "authored" else "draft",
                    )
                }
                add(
                    KnowledgeEntry(
                        id = o.getString("id"),
                        topic = o.getString("topic"),
                        entityId = o.optString("entityId").ifBlank { null },
                        aliases = stringList(o.optJSONArray("aliases")),
                        ageMin = o.optInt("ageMin").takeIf { o.has("ageMin") },
                        ageMax = o.optInt("ageMax").takeIf { o.has("ageMax") },
                        questionPatterns = stringList(o.optJSONArray("questionPatterns")),
                        facts = stringList(o.optJSONArray("facts")),
                        additionalFacts = stringList(o.optJSONArray("additionalFacts")),
                        shortAnswer = o.getString("shortAnswer"),
                        spokenAnswer = o.optString("spokenAnswer").ifBlank { null },
                        spokenAnswerAudio = o.optString("spokenAnswerAudio").ifBlank { null },
                        followUps = stringList(o.optJSONArray("followUps")),
                        relatedContentIds = stringList(o.optJSONArray("relatedContentIds")),
                        experimentActionRef = o.optString("experimentActionRef").ifBlank { null },
                        clarificationLabel = o.optString("clarificationLabel").ifBlank { null },
                        provenance = provenance,
                        sourceMetadata = o.optString("sourceMetadata").ifBlank { null },
                    ),
                )
            }
        }
        return KnowledgePack(
            id = root.optString("id", "pack"),
            world = root.optString("world").ifBlank { null },
            adventureId = root.optString("adventureId").ifBlank { null },
            entries = list,
        )
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }
}

/**
 * Thin facade over [GroundedDialogueEngine] for callers that only need resolve().
 */
class KnowledgeResolver(
    private val packs: List<KnowledgePack>,
    matchBackend: KnowledgeMatchBackend? = null,
) {
    val engine = GroundedDialogueEngine(packs, GroundedQwenMatcher(matchBackend))

    fun resolve(query: KnowledgeQuery): KnowledgeResult {
        engine.bindSurface(query.adventureId, query.world, query.sceneId)
        return engine.ask(query)
    }
}
