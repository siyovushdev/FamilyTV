package ru.familytv.parent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ActivityKind {
    WATCH,
    INTERACTIVE,
    GAME,
    MOVE,
    SESSION,
    OTHER,
}

data class ActivityEvent(
    val kind: ActivityKind,
    val contentId: String?,
    val title: String,
    val topics: List<String> = emptyList(),
    val durationMs: Long = 0L,
    val at: Long,
)

data class DaySummary(
    val dayKey: String,
    val totalWatchMs: Long,
    val events: List<ActivityEvent>,
    val topics: List<String>,
)

/**
 * Device/family activity log for Parent Mode.
 * Neutral language only — never claims individual mastery.
 */
class ActivityHistoryStore(
    private val prefs: SharedPreferences,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun record(
        kind: ActivityKind,
        title: String,
        contentId: String? = null,
        topics: List<String> = emptyList(),
        durationMs: Long = 0L,
    ) {
        val day = dayKey(clock())
        val arr = JSONArray(prefs.getString(key(day), "[]"))
        arr.put(
            JSONObject()
                .put("kind", kind.name)
                .put("contentId", contentId)
                .put("title", title)
                .put("topics", JSONArray(topics))
                .put("durationMs", durationMs)
                .put("at", clock()),
        )
        // Keep last 80 events per day
        while (arr.length() > 80) arr.remove(0)
        prefs.edit().putString(key(day), arr.toString()).apply()
        val days = prefs.getStringSet(KEY_DAYS, emptySet())!!.toMutableSet()
        days += day
        // Retain ~14 days
        if (days.size > 14) {
            val sorted = days.sorted()
            sorted.take(days.size - 14).forEach {
                days.remove(it)
                prefs.edit().remove(key(it)).apply()
            }
        }
        prefs.edit().putStringSet(KEY_DAYS, days).apply()
    }

    fun todaySummary(): DaySummary = summaryFor(dayKey(clock()))

    fun summaryFor(day: String): DaySummary {
        val arr = JSONArray(prefs.getString(key(day), "[]"))
        val events = mutableListOf<ActivityEvent>()
        val topics = linkedSetOf<String>()
        var watch = 0L
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val kind = runCatching { ActivityKind.valueOf(o.getString("kind")) }.getOrDefault(ActivityKind.OTHER)
            val tops = buildList {
                val a = o.optJSONArray("topics") ?: JSONArray()
                for (j in 0 until a.length()) add(a.getString(j))
            }
            topics += tops
            val dur = o.optLong("durationMs", 0L)
            if (kind == ActivityKind.WATCH || kind == ActivityKind.SESSION) watch += dur
            events += ActivityEvent(
                kind = kind,
                contentId = o.optString("contentId").ifBlank { null },
                title = o.optString("title"),
                topics = tops,
                durationMs = dur,
                at = o.optLong("at"),
            )
        }
        return DaySummary(day, watch, events, topics.toList())
    }

    fun minutesWatchedToday(): Int = (todaySummary().totalWatchMs / 60_000L).toInt()

    companion object {
        const val PREFS = "familytv_activity"
        private const val KEY_DAYS = "days"
        fun dayKey(now: Long): String {
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
            return "%04d-%02d-%02d".format(
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1,
                cal.get(java.util.Calendar.DAY_OF_MONTH),
            )
        }
        private fun key(day: String) = "day:$day"
    }
}
