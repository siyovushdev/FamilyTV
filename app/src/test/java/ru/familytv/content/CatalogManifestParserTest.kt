package ru.familytv.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogManifestParserTest {
    @Test
    fun parsesValidManifestAndOrdersBySortOrder() {
        val json = """
            {
              "schemaVersion": 1,
              "catalogVersion": 3,
              "items": [
                {"id":"b","title":"B","type":"VIDEO","mediaRef":"a.mp4","sortOrder":20,"homeSections":["learn"]},
                {"id":"a","title":"A","type":"GAME","route":"game/math","sortOrder":10,"homeSections":["play"]}
              ]
            }
        """.trimIndent()
        val result = CatalogManifestParser.parse(json, ContentSourceKind.BUNDLED)
        assertTrue(result.ok)
        val catalog = checkNotNull(result.catalog)
        assertEquals(3, catalog.catalogVersion)
        assertEquals(listOf("a", "b"), catalog.items.map { it.id })
    }

    @Test
    fun skipsBadItemsWithoutFailingWholeCatalog() {
        val json = """
            {
              "schemaVersion": 1,
              "catalogVersion": 1,
              "items": [
                {"id":"good","title":"Good","type":"VIDEO","mediaRef":"a.mp4"},
                {"id":"bad","title":"Bad","type":"NOPE"},
                {"title":"NoId","type":"VIDEO","mediaRef":"a.mp4"},
                {"id":"game-bad","title":"Game","type":"GAME"}
              ]
            }
        """.trimIndent()
        val result = CatalogManifestParser.parse(json, ContentSourceKind.BUNDLED)
        assertTrue(result.ok)
        assertEquals(listOf("good"), result.catalog!!.items.map { it.id })
        assertTrue(result.warnings.any { it.contains("unknown type") })
        assertTrue(result.warnings.any { it.contains("missing id") })
        assertTrue(result.warnings.any { it.contains("GAME requires route") })
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val json = """{"schemaVersion":99,"catalogVersion":1,"items":[{"id":"a","title":"A","type":"VIDEO","mediaRef":"a.mp4"}]}"""
        val result = CatalogManifestParser.parse(json, ContentSourceKind.BUNDLED)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun rejectsEmptyValidItemList() {
        val json = """{"schemaVersion":1,"catalogVersion":1,"items":[{"id":"x","title":"X","type":"GAME"}]}"""
        val result = CatalogManifestParser.parse(json, ContentSourceKind.BUNDLED)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("no valid items") })
    }

    @Test
    fun rejectsInvalidJson() {
        val result = CatalogManifestParser.parse("{not-json", ContentSourceKind.BUNDLED)
        assertFalse(result.ok)
    }
}
