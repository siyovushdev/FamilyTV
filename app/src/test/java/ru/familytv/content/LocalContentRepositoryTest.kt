package ru.familytv.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class LocalContentRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bundled = """
        {
          "schemaVersion": 1,
          "catalogVersion": 1,
          "items": [
            {"id":"v1","title":"Video","type":"VIDEO","mediaRef":"a.mp4","sortOrder":2,"homeSections":["learn"],"enabled":true},
            {"id":"g1","title":"Math","type":"GAME","route":"game/math","sortOrder":1,"homeSections":["play"],"enabled":true}
          ]
        }
    """.trimIndent()

    @Test
    fun loadsBundledAndOrdersEnabledItems() {
        val repo = repo()
        val items = repo.getEnabledItems()
        assertEquals(listOf("g1", "v1"), items.map { it.id })
        assertEquals(ContentSourceKind.BUNDLED, repo.activeSource())
        assertEquals(1, repo.catalogVersion())
    }

    @Test
    fun enableDisableAffectsSections() {
        val store = ContentOverrideStore(memoryPrefs())
        val repo = repo(store)
        repo.setEnabled("g1", false)
        assertEquals(listOf("v1"), repo.getEnabledItems().map { it.id })
        assertTrue(repo.itemsForSection("learn").any { it.id == "v1" })
        assertTrue(repo.itemsForSection("play").isEmpty())
        repo.setEnabled("g1", true)
        assertEquals(2, repo.getEnabledItems().size)
    }

    @Test
    fun badActiveCatalogFallsBackToBundled() {
        val root = tmp.newFolder("catalog")
        val active = File(root, "active").also { it.mkdirs() }
        File(active, "manifest.json").writeText("""{"schemaVersion":1,"catalogVersion":9,"items":[]}""")
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { ByteArrayInputStream(bundled.toByteArray()) },
            catalogRoot = root,
        )
        val catalog = repo.reload()
        assertEquals(ContentSourceKind.BUNDLED, catalog.source)
        assertEquals(1, catalog.catalogVersion)
        assertEquals(2, catalog.items.size)
    }

    @Test
    fun importRejectsBadManifestWithoutDestroyingActive() {
        val root = tmp.newFolder("catalog2")
        val active = File(root, "active").also { it.mkdirs() }
        File(active, "manifest.json").writeText(bundled)
        val importDir = File(root.parentFile, "import").also { it.mkdirs() }
        File(importDir, "manifest.json").writeText("{bad")
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { ByteArrayInputStream(bundled.toByteArray()) },
            catalogRoot = root,
        )
        repo.reload()
        val result = repo.importFromImportDirectory()
        assertFalse(result.ok)
        assertEquals(1, repo.catalogVersion())
        assertTrue(File(active, "manifest.json").exists())
    }

    @Test
    fun importActivatesValidCatalogAtomically() {
        val root = tmp.newFolder("catalog3")
        val importDir = File(root.parentFile, "import").also { it.mkdirs() }
        val imported = """
            {
              "schemaVersion": 1,
              "catalogVersion": 7,
              "items": [
                {"id":"new","title":"New","type":"VIDEO","mediaRef":"n.mp4","homeSections":["learn"]}
              ]
            }
        """.trimIndent()
        File(importDir, "manifest.json").writeText(imported)
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { ByteArrayInputStream(bundled.toByteArray()) },
            catalogRoot = root,
        )
        val result = repo.importFromImportDirectory()
        assertTrue(result.ok)
        assertEquals(7, repo.catalogVersion())
        assertEquals(ContentSourceKind.LOCAL_IMPORT, repo.activeSource())
        assertEquals(listOf("new"), repo.getEnabledItems().map { it.id })
    }

    private fun repo(store: ContentOverrideStore = ContentOverrideStore(memoryPrefs())) = LocalContentRepository(
        overrides = store,
        assetOpener = { ByteArrayInputStream(bundled.toByteArray()) },
        catalogRoot = tmp.newFolder("cat-${System.nanoTime()}"),
    )
}

internal fun memoryPrefs(): android.content.SharedPreferences = object : android.content.SharedPreferences {
    private val map = mutableMapOf<String, Any>()
    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String?, defValue: String?) = map[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
    override fun getInt(key: String?, defValue: Int) = map[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as? Boolean ?: defValue
    override fun contains(key: String?) = map.containsKey(key)
    override fun edit(): android.content.SharedPreferences.Editor = object : android.content.SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { if (key != null && value != null) map[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = this
        override fun putInt(key: String?, value: Int) = apply { if (key != null) map[key] = value }
        override fun putLong(key: String?, value: Long) = apply { if (key != null) map[key] = value }
        override fun putFloat(key: String?, value: Float) = apply { if (key != null) map[key] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) map[key] = value }
        override fun remove(key: String?) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() = Unit
        private fun apply(block: () -> Unit): android.content.SharedPreferences.Editor {
            block(); return this
        }
    }
    override fun registerOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}
