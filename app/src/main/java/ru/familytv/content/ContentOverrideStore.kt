package ru.familytv.content

import android.content.Context
import android.content.SharedPreferences

/** Device-scoped enable/disable overlays. Nullable profileId reserved for later. */
class ContentOverrideStore(
    private val prefs: SharedPreferences,
    private val profileId: String? = null,
) {
    constructor(context: Context, profileId: String? = null) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        profileId,
    )

    fun isEnabled(id: String, defaultEnabled: Boolean): Boolean {
        val key = key(id)
        if (!prefs.contains(key)) return defaultEnabled
        return prefs.getBoolean(key, defaultEnabled)
    }

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.edit().putBoolean(key(id), enabled).apply()
    }

    fun clear(id: String) {
        prefs.edit().remove(key(id)).apply()
    }

    fun snapshot(): Map<String, Boolean> =
        prefs.all.mapNotNull { (k, v) ->
            if (k.startsWith(prefix()) && v is Boolean) k.removePrefix(prefix()) to v else null
        }.toMap()

    private fun prefix() = if (profileId == null) "item:" else "profile:$profileId:item:"
    private fun key(id: String) = prefix() + id

    companion object {
        private const val PREFS = "familytv_content_overrides"
    }
}
