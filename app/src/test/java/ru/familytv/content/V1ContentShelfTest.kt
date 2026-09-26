package ru.familytv.content

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.familytv.trial.TrialEventStore

class V1ContentShelfTest {
    @Test
    fun hidesDevOnly() {
        val demo = ContentItem(
            id = "x",
            title = "Demo",
            type = ContentType.VIDEO,
            tags = listOf("dev_only"),
            homeSections = listOf("stories"),
        )
        val flagship = ContentItem(
            id = "adv-space-expedition",
            title = "Space",
            type = ContentType.ADVENTURE,
            tags = listOf("v1", "flagship"),
            homeSections = listOf("mission"),
        )
        assertFalse(V1ContentShelf.isV1HomeVisible(demo))
        assertTrue(V1ContentShelf.isV1HomeVisible(flagship))
    }
}

class TrialEventStoreTest {
    @Test
    fun summaryWhenTrialOffIsEmptyish() {
        val prefs = InMemoryPrefs()
        val store = TrialEventStore(prefs)
        store.record(TrialEventStore.HOME_ITEM_OPEN, "a")
        assertTrue(store.summaryJson().contains("\"homeItemOpens\": 0"))
        store.setTrialMode(true)
        store.record(TrialEventStore.HOME_ITEM_OPEN, "a")
        store.record(TrialEventStore.REPEAT_USED, "a")
        val s = store.summaryJson()
        assertTrue(s.contains("\"homeItemOpens\": 1"))
        assertTrue(s.contains("\"repeatCount\": 1"))
        assertTrue(s.contains("not a child skill assessment"))
    }
}

/** Minimal SharedPreferences stub for JVM tests. */
private class InMemoryPrefs : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = null
    override fun getInt(key: String?, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private var clearAll = false
        override fun putString(key: String?, value: String?) = apply { pending[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = apply { pending[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { pending[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { pending[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { pending[key!!] = value }
        override fun remove(key: String?) = apply { pending[key!!] = null }
        override fun clear() = apply { clearAll = true }
        override fun commit(): Boolean {
            apply(); return true
        }
        override fun apply() {
            if (clearAll) map.clear()
            pending.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
            pending.clear(); clearAll = false
        }
    }
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
