package ru.familytv.journey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Discovery Journal — authored unlocks and structured observations only.
 * Child-facing copy uses “мы”, never mastery scores.
 */
class DiscoveryJournalStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun list(limit: Int = 80): List<JournalEntry> {
        val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        return buildList {
            for (i in 0 until arr.length()) {
                parseEntry(arr.getJSONObject(i))?.let { add(it) }
            }
        }.sortedByDescending { it.createdAtMs }.take(limit)
    }

    fun listStories(): List<JournalEntry> =
        list().filter { it.type == JournalEntryType.STORY_CREATED }

    fun listForWorld(world: String): List<JournalEntry> =
        list().filter { it.world == world }

    fun listForJourney(journeyId: String): List<JournalEntry> =
        list().filter { it.journeyId == journeyId }

    fun hasUnlock(authoredUnlockId: String): Boolean =
        list().any { it.authoredUnlockId == authoredUnlockId }

    fun unlock(
        authoredUnlockId: String,
        type: JournalEntryType,
        title: String,
        body: String,
        spokenBody: String? = null,
        world: String? = null,
        journeyId: String? = null,
        contentId: String? = null,
        knowledgeId: String? = null,
        experimentId: String? = null,
        storyId: String? = null,
        clock: () -> Long = { System.currentTimeMillis() },
    ): JournalEntry? {
        if (hasUnlock(authoredUnlockId)) return null
        val entry = JournalEntry(
            id = "j-${clock().toString(36)}-${authoredUnlockId.take(12)}",
            type = type,
            title = title,
            body = body,
            spokenBody = spokenBody,
            world = world,
            journeyId = journeyId,
            contentId = contentId,
            knowledgeId = knowledgeId,
            experimentId = experimentId,
            storyId = storyId,
            createdAtMs = clock(),
            authoredUnlockId = authoredUnlockId,
        )
        append(entry)
        return entry
    }

    fun addObservation(obs: ExperimentObservation): JournalEntry {
        val entry = JournalEntry(
            id = "exp-${obs.createdAtMs.toString(36)}",
            type = JournalEntryType.EXPERIMENT_OBSERVED,
            title = "Мы проверили",
            body = obs.observedOutcome,
            spokenBody = obs.spokenOutcome ?: obs.observedOutcome,
            world = null,
            journeyId = obs.journeyId,
            contentId = obs.contentId,
            experimentId = obs.experimentId,
            createdAtMs = obs.createdAtMs,
            authoredUnlockId = "exp:${obs.experimentId}:${obs.observedOutcome.hashCode()}",
        )
        if (!hasUnlock(entry.authoredUnlockId!!)) append(entry)
        saveObservation(obs)
        return entry
    }

    fun observations(limit: Int = 40): List<ExperimentObservation> {
        val arr = JSONArray(prefs.getString(KEY_OBS, "[]"))
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val variants = o.optJSONArray("variants")?.let { a ->
                    buildList { for (j in 0 until a.length()) add(a.getString(j)) }
                } ?: emptyList()
                add(
                    ExperimentObservation(
                        experimentId = o.getString("experimentId"),
                        contentId = o.optString("contentId").ifBlank { null },
                        variants = variants,
                        observedOutcome = o.getString("observedOutcome"),
                        spokenOutcome = o.optString("spokenOutcome").ifBlank { null },
                        journeyId = o.optString("journeyId").ifBlank { null },
                        createdAtMs = o.getLong("createdAtMs"),
                    ),
                )
            }
        }.sortedByDescending { it.createdAtMs }.take(limit)
    }

    fun clear() {
        prefs.edit().remove(KEY_ENTRIES).remove(KEY_OBS).apply()
    }

    private fun append(entry: JournalEntry) {
        val arr = JSONArray(prefs.getString(KEY_ENTRIES, "[]"))
        arr.put(toJson(entry))
        val trimmed = JSONArray()
        val start = (arr.length() - MAX).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY_ENTRIES, trimmed.toString()).apply()
    }

    private fun saveObservation(obs: ExperimentObservation) {
        val arr = JSONArray(prefs.getString(KEY_OBS, "[]"))
        arr.put(
            JSONObject()
                .put("experimentId", obs.experimentId)
                .put("contentId", obs.contentId)
                .put("variants", JSONArray(obs.variants))
                .put("observedOutcome", obs.observedOutcome)
                .put("spokenOutcome", obs.spokenOutcome)
                .put("journeyId", obs.journeyId)
                .put("createdAtMs", obs.createdAtMs),
        )
        val trimmed = JSONArray()
        val start = (arr.length() - MAX_OBS).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY_OBS, trimmed.toString()).apply()
    }

    private fun toJson(e: JournalEntry): JSONObject =
        JSONObject()
            .put("id", e.id)
            .put("type", e.type.name)
            .put("title", e.title)
            .put("body", e.body)
            .put("spokenBody", e.spokenBody)
            .put("world", e.world)
            .put("journeyId", e.journeyId)
            .put("contentId", e.contentId)
            .put("knowledgeId", e.knowledgeId)
            .put("experimentId", e.experimentId)
            .put("storyId", e.storyId)
            .put("createdAtMs", e.createdAtMs)
            .put("authoredUnlockId", e.authoredUnlockId)

    private fun parseEntry(o: JSONObject): JournalEntry? = runCatching {
        JournalEntry(
            id = o.getString("id"),
            type = JournalEntryType.valueOf(o.getString("type")),
            title = o.getString("title"),
            body = o.getString("body"),
            spokenBody = o.optString("spokenBody").ifBlank { null },
            world = o.optString("world").ifBlank { null },
            journeyId = o.optString("journeyId").ifBlank { null },
            contentId = o.optString("contentId").ifBlank { null },
            knowledgeId = o.optString("knowledgeId").ifBlank { null },
            experimentId = o.optString("experimentId").ifBlank { null },
            storyId = o.optString("storyId").ifBlank { null },
            createdAtMs = o.getLong("createdAtMs"),
            authoredUnlockId = o.optString("authoredUnlockId").ifBlank { null },
        )
    }.getOrNull()

    companion object {
        private const val PREFS = "familytv_discovery_journal"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_OBS = "observations"
        private const val MAX = 120
        private const val MAX_OBS = 60
    }
}

/** Authored journal card definitions loaded from journey/knowledge packs. */
data class JournalUnlockDef(
    val id: String,
    val type: JournalEntryType,
    val title: String,
    val body: String,
    val spokenBody: String? = null,
    val world: String? = null,
    val knowledgeId: String? = null,
    /** If true, only unlock after experiment/discovery — not mere page open. */
    val requireDiscovery: Boolean = true,
)

object JournalUnlockCatalog {
    fun parseDefs(json: String): List<JournalUnlockDef> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("unlocks") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                add(
                    JournalUnlockDef(
                        id = o.getString("id"),
                        type = JournalEntryType.valueOf(o.optString("type", "FACT_DISCOVERED")),
                        title = o.getString("title"),
                        body = o.getString("body"),
                        spokenBody = o.optString("spokenBody").ifBlank { null },
                        world = o.optString("world").ifBlank { null },
                        knowledgeId = o.optString("knowledgeId").ifBlank { null },
                        requireDiscovery = o.optBoolean("requireDiscovery", true),
                    ),
                )
            }
        }
    }
}
