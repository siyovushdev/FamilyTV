package ru.familytv.trial

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local anonymous trial events — never uploads, no raw voice/audio.
 * Not a skill assessment of a child.
 */
class TrialEventStore(private val prefs: android.content.SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun isTrialMode(): Boolean = prefs.getBoolean(KEY_TRIAL, false)

    fun setTrialMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TRIAL, enabled).apply()
        if (enabled && prefs.getLong(KEY_STARTED, 0L) == 0L) {
            prefs.edit().putLong(KEY_STARTED, System.currentTimeMillis()).apply()
        }
    }

    fun record(type: String, contentId: String? = null, extra: Map<String, String> = emptyMap()) {
        if (!isTrialMode()) return
        val event = JSONObject()
            .put("t", System.currentTimeMillis())
            .put("type", type)
        contentId?.let { event.put("contentId", it) }
        if (extra.isNotEmpty()) {
            val ex = JSONObject()
            extra.forEach { (k, v) -> ex.put(k, v) }
            event.put("extra", ex)
        }
        val arr = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        arr.put(event)
        val trimmed = JSONArray()
        val start = (arr.length() - MAX).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY_EVENTS, trimmed.toString()).apply()
    }

    fun summaryJson(): String {
        val arr = JSONArray(prefs.getString(KEY_EVENTS, "[]"))
        var homeOpen = 0
        var adventureComplete = 0
        var repeat = 0
        var help = 0
        var retry = 0
        var offscreen = 0
        val opened = linkedMapOf<String, Int>()
        val exits = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            when (e.optString("type")) {
                "HOME_ITEM_OPEN" -> {
                    homeOpen++
                    val id = e.optString("contentId")
                    if (id.isNotBlank()) opened[id] = (opened[id] ?: 0) + 1
                }
                "ADVENTURE_COMPLETE" -> adventureComplete++
                "REPEAT_USED" -> repeat++
                "HELP_USED" -> help++
                "CHALLENGE_RETRY" -> retry++
                "OFFSCREEN_COMPLETE" -> offscreen++
                "ADVENTURE_EXIT_POINT" -> exits += e.optString("contentId") + ":" +
                    e.optJSONObject("extra")?.optString("sceneId").orEmpty()
            }
        }
        val started = prefs.getLong(KEY_STARTED, 0L)
        val durationMs = if (started > 0) System.currentTimeMillis() - started else 0L
        return JSONObject()
            .put("durationMs", durationMs)
            .put("homeItemOpens", homeOpen)
            .put("adventureCompletes", adventureComplete)
            .put("repeatCount", repeat)
            .put("helpCount", help)
            .put("retryCount", retry)
            .put("offscreenCompletes", offscreen)
            .put("openedContent", JSONObject().also { o -> opened.forEach { (k, v) -> o.put(k, v) } })
            .put("exitPoints", JSONArray(exits))
            .put("note", "Anonymous local summary — not a child skill assessment")
            .toString(2)
    }

    fun clear() {
        prefs.edit().remove(KEY_EVENTS).remove(KEY_STARTED).apply()
    }

    companion object {
        private const val PREFS = "familytv_trial"
        private const val KEY_TRIAL = "trial_mode"
        private const val KEY_EVENTS = "events"
        private const val KEY_STARTED = "started_at"
        private const val MAX = 500

        const val HOME_ITEM_OPEN = "HOME_ITEM_OPEN"
        const val ADVENTURE_EXIT_POINT = "ADVENTURE_EXIT_POINT"
        const val REPEAT_USED = "REPEAT_USED"
        const val HELP_USED = "HELP_USED"
        const val CHALLENGE_RETRY = "CHALLENGE_RETRY"
        const val ADVENTURE_COMPLETE = "ADVENTURE_COMPLETE"
        const val OFFSCREEN_COMPLETE = "OFFSCREEN_COMPLETE"
        const val CURIOSITY_QUESTION = "CURIOSITY_QUESTION"
        const val KNOWLEDGE_FOUND = "KNOWLEDGE_FOUND"
        const val KNOWLEDGE_UNKNOWN = "KNOWLEDGE_UNKNOWN"
        const val EXPERIMENT_STARTED = "EXPERIMENT_STARTED"
        const val PREDICTION_MADE = "PREDICTION_MADE"
        const val EXPERIMENT_VARIANT_TESTED = "EXPERIMENT_VARIANT_TESTED"
        const val STORY_STARTED = "STORY_STARTED"
        const val STORY_CHOICE = "STORY_CHOICE"
        const val STORY_COMPLETED = "STORY_COMPLETED"
        const val MOVE_STARTED = "MOVE_STARTED"
        const val MOVE_COMPLETED = "MOVE_COMPLETED"
        const val REFLECTION_COMPLETED = "REFLECTION_COMPLETED"
        const val ASK_LUMI = "ASK_LUMI"
        const val ASK_LUMI_MATCHED = "ASK_LUMI_MATCHED"
        const val ASK_LUMI_UNKNOWN = "ASK_LUMI_UNKNOWN"
        const val ASK_LUMI_CLARIFICATION = "ASK_LUMI_CLARIFICATION"
        const val TELL_ME_MORE = "TELL_ME_MORE"
        const val ASK_TO_EXPERIMENT = "ASK_TO_EXPERIMENT"
        const val STORY_FREEFORM_MAPPED = "STORY_FREEFORM_MAPPED"
        const val JOURNEY_STARTED = "JOURNEY_STARTED"
        const val JOURNEY_NODE_OPENED = "JOURNEY_NODE_OPENED"
        const val JOURNEY_NODE_COMPLETED = "JOURNEY_NODE_COMPLETED"
        const val JOURNEY_RESUMED = "JOURNEY_RESUMED"
        const val JOURNEY_BRANCH_CHOSEN = "JOURNEY_BRANCH_CHOSEN"
        const val JOURNEY_COMPLETED = "JOURNEY_COMPLETED"
        const val JOURNAL_OPENED = "JOURNAL_OPENED"
    }
}
