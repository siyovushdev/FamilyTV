package ru.familytv.interaction

/**
 * Debug-only simulation: minimize reliance on readable text so QA can verify
 * visuals + speech + remote/voice. Never a child-facing Parent setting.
 */
object NonReaderSimulation {
    const val PREFS = "familytv_debug"
    const val KEY = "non_reader_simulation"

    fun isEnabled(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(KEY, false)

    fun setEnabled(prefs: android.content.SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY, enabled).apply()
    }
}
