package ru.familytv.session

import org.json.JSONObject

/** Curated short learning path — not recommendation AI. */
data class LearningSession(
    val id: String,
    val title: String,
    val description: String = "",
    val estimatedMinutes: Int? = null,
    val items: List<SessionItemRef>,
)

data class SessionItemRef(
    val contentId: String,
    val optional: Boolean = false,
)

object LearningSessionParser {
    fun parse(jsonText: String): LearningSession {
        val root = JSONObject(jsonText)
        val itemsJson = root.getJSONArray("items")
        val items = buildList {
            for (i in 0 until itemsJson.length()) {
                val obj = itemsJson.getJSONObject(i)
                add(
                    SessionItemRef(
                        contentId = obj.getString("contentId"),
                        optional = obj.optBoolean("optional", false),
                    ),
                )
            }
        }
        require(items.isNotEmpty()) { "Session has no items" }
        return LearningSession(
            id = root.getString("id"),
            title = root.getString("title"),
            description = root.optString("description"),
            estimatedMinutes = root.optInt("estimatedMinutes").takeIf { root.has("estimatedMinutes") && it > 0 },
            items = items,
        )
    }
}

data class SessionProgress(
    val sessionId: String,
    val index: Int,
    val completed: Boolean,
    val updatedAt: Long,
)

class SessionProgressStore(
    private val prefs: android.content.SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: android.content.Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE),
    )

    fun get(sessionId: String): SessionProgress? {
        val p = key(sessionId)
        if (!prefs.contains("${p}index")) return null
        return SessionProgress(
            sessionId = sessionId,
            index = prefs.getInt("${p}index", 0),
            completed = prefs.getBoolean("${p}completed", false),
            updatedAt = prefs.getLong("${p}updatedAt", 0L),
        )
    }

    fun save(sessionId: String, index: Int, completed: Boolean = false) {
        val p = key(sessionId)
        prefs.edit()
            .putInt("${p}index", index)
            .putBoolean("${p}completed", completed)
            .putLong("${p}updatedAt", clock())
            .apply()
    }

    fun clear(sessionId: String) {
        val p = key(sessionId)
        prefs.edit().remove("${p}index").remove("${p}completed").remove("${p}updatedAt").apply()
    }

    private fun key(sessionId: String) = "device:session:$sessionId:"

    companion object {
        private const val PREFS = "familytv_session_progress"
    }
}
