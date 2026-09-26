package ru.familytv.parent

import android.content.Context
import android.content.SharedPreferences

/**
 * Tracks family screen time locally.
 * Soft reminders only — never kills playback without warning.
 */
class ScreenTimeTracker(
    private val prefs: SharedPreferences,
    private val settings: () -> ScreenTimeSettings,
    private val activity: ActivityHistoryStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(context: Context, settingsStore: FamilySettingsStore, activity: ActivityHistoryStore) : this(
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        settings = { settingsStore.screenTime() },
        activity = activity,
    )

    /**
     * Process-local flag: prefs alone would keep an old sessionStart across force-stop
     * and immediately show «Пора сделать перерыв» on every cold launch.
     */
    @Volatile private var sessionBoundToThisProcess = false

    fun onAppForeground() {
        if (!sessionBoundToThisProcess) {
            prefs.edit().putLong(KEY_SESSION_START, clock()).apply()
            sessionBoundToThisProcess = true
            return
        }
        if (prefs.getLong(KEY_SESSION_START, 0L) <= 0L) {
            prefs.edit().putLong(KEY_SESSION_START, clock()).apply()
        }
    }

    fun onAppBackground() {
        val start = prefs.getLong(KEY_SESSION_START, 0L)
        if (start > 0) {
            val elapsed = (clock() - start).coerceAtLeast(0L)
            addTodayMs(elapsed)
            prefs.edit().putLong(KEY_SESSION_START, 0L).apply()
        }
    }

    fun todayMs(): Long {
        val day = ActivityHistoryStore.dayKey(clock())
        val storedDay = prefs.getString(KEY_DAY, null)
        if (storedDay != day) {
            prefs.edit().putString(KEY_DAY, day).putLong(KEY_TODAY_MS, 0L).apply()
        }
        var total = prefs.getLong(KEY_TODAY_MS, 0L)
        val start = prefs.getLong(KEY_SESSION_START, 0L)
        if (sessionBoundToThisProcess && start > 0) {
            total += (clock() - start).coerceAtLeast(0L)
        }
        return total
    }

    fun reminder(): ScreenTimeReminder? {
        val st = settings()
        if (!st.enabled) return null
        val todayMin = (todayMs() / 60_000L).toInt()
        val sessionStart = prefs.getLong(KEY_SESSION_START, 0L)
        val sessionMin = if (sessionBoundToThisProcess && sessionStart > 0) {
            ((clock() - sessionStart) / 60_000L).toInt()
        } else {
            0
        }

        st.dailyLimitMinutes?.let { limit ->
            val remain = limit - todayMin
            if (remain <= 0) return ScreenTimeReminder.Rest("Пора сделать перерыв")
            if (remain <= st.warnMinutesBefore) {
                return ScreenTimeReminder.Warn("Осталось $remain мин")
            }
        }
        st.sessionReminderMinutes?.let { lim ->
            if (sessionMin >= lim) return ScreenTimeReminder.Rest("Пора сделать перерыв")
            val remain = lim - sessionMin
            if (remain in 1..st.warnMinutesBefore) {
                return ScreenTimeReminder.Warn("Осталось $remain мин")
            }
        }
        st.restReminderMinutes?.let { lim ->
            if (sessionMin >= lim) return ScreenTimeReminder.Rest("Пора сделать перерыв")
        }
        // Bedtime soft check
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val start = st.bedtimeStartHour
        val end = st.bedtimeEndHour
        if (start != null && end != null) {
            val inBed = if (start <= end) hour in start until end else hour >= start || hour < end
            if (inBed) return ScreenTimeReminder.Rest("Сейчас время отдыха")
        }
        return null
    }

    private fun addTodayMs(ms: Long) {
        val day = ActivityHistoryStore.dayKey(clock())
        val storedDay = prefs.getString(KEY_DAY, null)
        val base = if (storedDay == day) prefs.getLong(KEY_TODAY_MS, 0L) else 0L
        prefs.edit().putString(KEY_DAY, day).putLong(KEY_TODAY_MS, base + ms).apply()
    }

    companion object {
        const val PREFS = "familytv_screen_time"
        private const val KEY_SESSION_START = "sessionStart"
        private const val KEY_TODAY_MS = "todayMs"
        private const val KEY_DAY = "day"
    }
}

sealed class ScreenTimeReminder {
    data class Warn(val message: String) : ScreenTimeReminder()
    data class Rest(val message: String) : ScreenTimeReminder()
}
