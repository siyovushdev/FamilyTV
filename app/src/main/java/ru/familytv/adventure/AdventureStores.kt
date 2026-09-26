package ru.familytv.adventure

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local anonymous adventure interaction events — never uploads, no raw audio.
 */
class AdventureEventStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun record(
        adventureId: String,
        type: String,
        challengeId: String? = null,
        hintLevel: String? = null,
        retries: Int? = null,
        durationMs: Long? = null,
        extra: Map<String, String> = emptyMap(),
    ) {
        val event = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("adventureId", adventureId)
            .put("type", type)
        challengeId?.let { event.put("challengeId", it) }
        hintLevel?.let { event.put("hintLevel", it) }
        retries?.let { event.put("retries", it) }
        durationMs?.let { event.put("durationMs", it) }
        if (extra.isNotEmpty()) {
            val ex = JSONObject()
            extra.forEach { (k, v) -> ex.put(k, v) }
            event.put("extra", ex)
        }
        val arr = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        arr.put(event)
        // Cap local log
        val trimmed = JSONArray()
        val start = (arr.length() - MAX).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply()
    }

    fun recent(limit: Int = 50): List<JSONObject> {
        val arr = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        val out = mutableListOf<JSONObject>()
        val from = (arr.length() - limit).coerceAtLeast(0)
        for (i in from until arr.length()) out.add(arr.getJSONObject(i))
        return out
    }

    companion object {
        private const val PREFS = "familytv_adventure_events"
        private const val KEY_EVENTS = "events"
        private const val MAX = 400
    }
}

class AdventureProgressStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun save(adventureId: String, sceneId: String, completed: Boolean, packageId: String?, packageVersion: Int?) {
        val p = prefix(adventureId)
        prefs.edit()
            .putString("${p}scene", sceneId)
            .putBoolean("${p}completed", completed)
            .putString("${p}packageId", packageId.orEmpty())
            .putInt("${p}packageVersion", packageVersion ?: -1)
            .putLong("${p}updatedAt", System.currentTimeMillis())
            .apply()
    }

    fun resumeSceneId(adventureId: String, validIds: Set<String>): String? {
        val p = prefix(adventureId)
        if (prefs.getBoolean("${p}completed", false)) return null
        val scene = prefs.getString("${p}scene", null) ?: return null
        return scene.takeIf { it in validIds }
    }

    fun hasIncomplete(adventureId: String): Boolean {
        val p = prefix(adventureId)
        return !prefs.getBoolean("${p}completed", false) &&
            !prefs.getString("${p}scene", null).isNullOrBlank()
    }

    private fun prefix(id: String) = "device:$id:"

    companion object {
        private const val PREFS = "familytv_adventure_progress"
    }
}
