package ru.familytv.journey

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Device/family journey progress — no child identity, no mastery scores. */
class JourneyProgressStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun get(journeyId: String): JourneyProgress? {
        val p = key(journeyId)
        if (!prefs.contains("${p}updatedAt")) return null
        return JourneyProgress(
            journeyId = journeyId,
            openNodeIds = stringSet("${p}open"),
            completedNodeIds = stringSet("${p}completed"),
            lastVisitedNodeId = prefs.getString("${p}last", null),
            started = prefs.getBoolean("${p}started", false),
            completed = prefs.getBoolean("${p}done", false),
            journeyVersion = prefs.getInt("${p}version", 1),
            updatedAt = prefs.getLong("${p}updatedAt", 0L),
            chosenBranches = branchMap("${p}branches"),
        )
    }

    fun listActive(): List<JourneyProgress> =
        allIds().mapNotNull { get(it) }.filter { it.started && !it.completed }
            .sortedByDescending { it.updatedAt }

    fun listRecentlyCompleted(limit: Int = 8): List<JourneyProgress> =
        allIds().mapNotNull { get(it) }.filter { it.completed }
            .sortedByDescending { it.updatedAt }
            .take(limit)

    fun save(progress: JourneyProgress) {
        val p = key(progress.journeyId)
        prefs.edit()
            .putStringSet("${p}open", progress.openNodeIds)
            .putStringSet("${p}completed", progress.completedNodeIds)
            .putString("${p}last", progress.lastVisitedNodeId)
            .putBoolean("${p}started", progress.started)
            .putBoolean("${p}done", progress.completed)
            .putInt("${p}version", progress.journeyVersion)
            .putLong("${p}updatedAt", progress.updatedAt)
            .putString(
                "${p}branches",
                JSONObject().also { o ->
                    progress.chosenBranches.forEach { (k, v) -> o.put(k, v) }
                }.toString(),
            )
            .apply()
        rememberId(progress.journeyId)
    }

    fun clear(journeyId: String) {
        val p = key(journeyId)
        prefs.edit()
            .remove("${p}open")
            .remove("${p}completed")
            .remove("${p}last")
            .remove("${p}started")
            .remove("${p}done")
            .remove("${p}version")
            .remove("${p}updatedAt")
            .remove("${p}branches")
            .apply()
    }

    private fun stringSet(k: String): Set<String> =
        prefs.getStringSet(k, emptySet())?.toSet() ?: emptySet()

    private fun branchMap(k: String): Map<String, String> {
        val raw = prefs.getString(k, null) ?: return emptyMap()
        return runCatching {
            val o = JSONObject(raw)
            buildMap {
                o.keys().forEach { put(it, o.getString(it)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun allIds(): List<String> {
        val arr = JSONArray(prefs.getString(KEY_IDS, "[]"))
        return buildList {
            for (i in 0 until arr.length()) add(arr.getString(i))
        }
    }

    private fun rememberId(id: String) {
        val ids = allIds().toMutableList()
        if (id !in ids) {
            ids += id
            val arr = JSONArray()
            ids.forEach { arr.put(it) }
            prefs.edit().putString(KEY_IDS, arr.toString()).apply()
        }
    }

    private fun key(id: String) = "device:journey:$id:"

    companion object {
        private const val PREFS = "familytv_journey_progress"
        private const val KEY_IDS = "journey_ids"
    }
}
