package ru.familytv.knowledge

import org.json.JSONObject

/**
 * Strict structured output from the constrained matcher.
 * Free prose is never treated as educational authority.
 */
sealed class MatchContract {
    data class Selected(
        val intent: CuriosityIntent?,
        val knowledgeId: String,
        val confidenceClass: ConfidenceClass,
    ) : MatchContract()

    data class Ambiguous(
        val knowledgeIds: List<String>,
        val intent: CuriosityIntent?,
    ) : MatchContract()

    data object Unknown : MatchContract()
}

/**
 * Optional inference backend. When null/unavailable, [DeterministicGroundedMatcher] is used.
 * Real Qwen must only see candidate ids/titles/aliases/facts — never unrestricted curriculum.
 */
fun interface KnowledgeMatchBackend {
    /** Returns raw model text (ideally JSON). Off main thread for real Qwen. */
    fun complete(prompt: String): String?
}

object GroundedMatchPrompt {
    fun build(
        utterance: String,
        intent: CuriosityIntent?,
        context: DialogueContext,
        candidates: List<KnowledgeEntry>,
    ): String {
        val lines = buildString {
            appendLine("Select ONE approved knowledgeId for the child utterance.")
            appendLine("Return ONLY JSON: {\"intent\":\"WHY\",\"knowledgeId\":\"id\",\"confidenceClass\":\"HIGH\"}")
            appendLine("or {\"result\":\"UNKNOWN\"} or {\"result\":\"AMBIGUOUS\",\"knowledgeIds\":[\"a\",\"b\"]}")
            appendLine("Do NOT invent facts. Do NOT use ids outside the list.")
            appendLine("utterance: $utterance")
            intent?.let { appendLine("hintIntent: ${it.name}") }
            context.currentEntityId?.let { appendLine("contextEntity: $it") }
            context.lastKnowledgeId?.let { appendLine("lastKnowledgeId: $it") }
            appendLine("candidates:")
            candidates.forEach { e ->
                appendLine(
                    "- id=${e.id} topic=${e.topic} entity=${e.entityId ?: ""} " +
                        "aliases=${e.aliases.joinToString("|")} " +
                        "label=${e.clarificationLabel ?: e.shortAnswer.take(40)}",
                )
            }
        }
        return lines
    }
}

object MatchContractParser {
    fun parse(raw: String?, allowlist: Set<String>): MatchContract {
        if (raw.isNullOrBlank()) return MatchContract.Unknown
        val text = raw.trim().let { t ->
            val start = t.indexOf('{')
            val end = t.lastIndexOf('}')
            if (start >= 0 && end > start) t.substring(start, end + 1) else t
        }
        return try {
            val o = JSONObject(text)
            when {
                o.optString("result").equals("UNKNOWN", true) -> MatchContract.Unknown
                o.optString("result").equals("AMBIGUOUS", true) -> {
                    val ids = o.optJSONArray("knowledgeIds")?.let { arr ->
                        buildList {
                            for (i in 0 until arr.length()) {
                                val id = arr.getString(i)
                                if (id in allowlist) add(id)
                            }
                        }
                    } ?: emptyList()
                    if (ids.size >= 2) MatchContract.Ambiguous(ids, parseIntent(o.optString("intent")))
                    else if (ids.size == 1) MatchContract.Selected(parseIntent(o.optString("intent")), ids[0], ConfidenceClass.MEDIUM)
                    else MatchContract.Unknown
                }
                else -> {
                    val id = o.optString("knowledgeId").ifBlank { o.optString("id") }
                    if (id.isBlank() || id !in allowlist) return MatchContract.Unknown
                    val conf = runCatching {
                        ConfidenceClass.valueOf(o.optString("confidenceClass", "MEDIUM").uppercase())
                    }.getOrDefault(ConfidenceClass.MEDIUM)
                    MatchContract.Selected(parseIntent(o.optString("intent")), id, conf)
                }
            }
        } catch (_: Exception) {
            MatchContract.Unknown
        }
    }

    private fun parseIntent(raw: String): CuriosityIntent? =
        runCatching { CuriosityIntent.valueOf(raw.trim().uppercase()) }.getOrNull()
}

/**
 * Ranks candidates deterministically. Used as primary matcher and as Qwen fallback.
 */
class DeterministicGroundedMatcher {
    data class Ranked(val entry: KnowledgeEntry, val score: Int)

    fun rank(
        utterance: String,
        query: KnowledgeQuery,
        context: DialogueContext,
        candidates: List<KnowledgeEntry>,
    ): List<Ranked> {
        val q = utterance.lowercase().trim()
        return candidates.map { Ranked(it, score(it, q, query, context)) }
            .filter { it.score > 0 }
            .sortedByDescending { it.score }
    }

    fun toContract(ranked: List<Ranked>, intent: CuriosityIntent?): MatchContract {
        if (ranked.isEmpty()) return MatchContract.Unknown
        val top = ranked[0]
        if (top.score < 3) return MatchContract.Unknown
        val second = ranked.getOrNull(1)
        if (second != null && second.score >= top.score - 1 && second.score >= 4) {
            return MatchContract.Ambiguous(
                listOf(top.entry.id, second.entry.id),
                intent,
            )
        }
        val conf = when {
            top.score >= 8 -> ConfidenceClass.HIGH
            top.score >= 5 -> ConfidenceClass.MEDIUM
            else -> ConfidenceClass.LOW
        }
        return MatchContract.Selected(intent, top.entry.id, conf)
    }

    /** Explicit “A or B” ambiguity from child phrasing. */
    fun ambiguousFromOrPhrase(utterance: String, candidates: List<KnowledgeEntry>): MatchContract? {
        val q = utterance.lowercase()
        if (!q.contains(" или ")) return null
        val hits = candidates.filter { e ->
            e.aliases.any { q.contains(it.lowercase()) } ||
                (e.clarificationLabel != null && q.contains(e.clarificationLabel!!.lowercase())) ||
                (e.entityId != null && q.contains(e.entityId!!.lowercase()))
        }.distinctBy { it.id }
        if (hits.size >= 2) {
            return MatchContract.Ambiguous(hits.take(2).map { it.id }, CuriosityIntent.WHY)
        }
        return null
    }

    private fun score(
        entry: KnowledgeEntry,
        q: String,
        query: KnowledgeQuery,
        context: DialogueContext,
    ): Int {
        var score = 0
        if (context.lastKnowledgeId == entry.id) score += 2
        if (context.currentEntityId != null && context.currentEntityId == entry.entityId) score += 6
        if (context.currentTopicId != null &&
            (entry.topic == context.currentTopicId || entry.id == context.currentTopicId)
        ) {
            score += 4
        }
        if (query.topicHints.any { h -> entry.topic.contains(h, true) || entry.entityId == h }) {
            score += 5
        }
        if (query.sceneId != null && entry.aliases.any { query.sceneId.contains(it, true) }) score += 2

        for (a in entry.aliases) {
            if (q.contains(a.lowercase())) score += 4
        }
        if (entry.entityId != null) {
            val ent = entry.entityId!!.lowercase().replace('_', ' ')
            if (q.contains(ent) || q.contains(entry.entityId!!.lowercase())) score += 3
        }
        for (p in entry.questionPatterns) {
            val pl = p.lowercase()
            if (q.contains(pl) || patternLoose(q, pl)) score += 3
        }
        // Pronoun / short follow-up boost when entity context exists
        if (isShortFollowUp(q) && context.currentEntityId == entry.entityId) score += 5
        if (isShortFollowUp(q) && context.lastKnowledgeId == entry.id) score += 3
        // «а днём?» / «а ночью?» prefer entries whose topic/aliases match the shift
        if ((q.contains("днём") || q.contains("днем")) &&
            (entry.topic.contains("day") || entry.aliases.any { it.contains("дн") })
        ) {
            score += 6
        }
        if (q.contains("ночью") &&
            (entry.topic.contains("night") || entry.aliases.any { it.contains("ноч") })
        ) {
            score += 6
        }

        val bag = (entry.aliases + entry.topic.split('_') + entry.facts.flatMap { it.split(' ') })
            .map { it.lowercase() }.filter { it.length >= 4 }
        score += bag.count { q.contains(it) }.coerceAtMost(4)
        return score
    }

    private fun isShortFollowUp(q: String): Boolean {
        val short = listOf(
            "почему", "а почему", "а это почему", "а зачем", "зачем",
            "а днём", "а днем", "а ночью", "а ещё", "а еще", "а если",
            "а наоборот", "как это", "что это", "я не понял", "я не поняла",
        )
        return short.any { q == it || q.startsWith("$it ") || q == "$it?" }
    }

    private fun patternLoose(q: String, pattern: String): Boolean {
        val tokens = pattern.split(' ').filter { it.length >= 3 }
        if (tokens.isEmpty()) return false
        val hits = tokens.count { q.contains(it) }
        val need = if (tokens.size >= 2) maxOf(2, (tokens.size + 1) / 2) else 1
        return hits >= need
    }
}

/**
 * Constrained semantic layer: optional Qwen + allowlisted IDs + deterministic fallback.
 */
class GroundedQwenMatcher(
    private val backend: KnowledgeMatchBackend? = null,
    private val deterministic: DeterministicGroundedMatcher = DeterministicGroundedMatcher(),
) {
    fun match(
        utterance: String,
        intent: CuriosityIntent?,
        query: KnowledgeQuery,
        context: DialogueContext,
        candidates: List<KnowledgeEntry>,
    ): MatchContract {
        if (candidates.isEmpty()) return MatchContract.Unknown
        val ranked = deterministic.rank(utterance, query, context, candidates)
        val allow = candidates.map { it.id }.toSet()

        deterministic.ambiguousFromOrPhrase(utterance, candidates)?.let { return it }

        // Prefer deterministic when confident; Qwen only to break near-ties / paraphrase
        val det = deterministic.toContract(ranked, intent)
        if (backend == null) return det
        if (det is MatchContract.Selected && det.confidenceClass == ConfidenceClass.HIGH) return det

        val prompt = GroundedMatchPrompt.build(utterance, intent, context, candidates.take(8))
        val raw = runCatching { backend!!.complete(prompt) }.getOrNull()
        val parsed = MatchContractParser.parse(raw, allow)
        return when (parsed) {
            is MatchContract.Selected, is MatchContract.Ambiguous -> parsed
            MatchContract.Unknown -> det
        }
    }
}
