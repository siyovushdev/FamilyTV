package ru.familytv.content.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.familytv.content.CatalogManifestParser
import ru.familytv.content.ContentOverrideStore
import ru.familytv.content.ContentSourceKind
import ru.familytv.content.LocalContentRepository
import ru.familytv.content.memoryPrefs
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ContentPackageStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bundled = """
      {"schemaVersion":1,"catalogVersion":1,"items":[
        {"id":"v1","title":"Video","type":"VIDEO","mediaRef":"a.mp4","sortOrder":2,"homeSections":["learn"]},
        {"id":"g1","title":"Math","type":"GAME","route":"game/math","sortOrder":1,"homeSections":["play"]}
      ]}
    """.trimIndent()

    @Test
    fun validPackageInstallsAndActivates() {
        val store = store()
        val dir = buildValidPackage(version = 1)
        val result = store.importDirectory(dir)
        assertTrue(result.errors.joinToString(), result.ok)
        assertEquals("demo-pkg", result.packageId)
        assertEquals(1, result.packageVersion)
        assertEquals(1, store.listInstalled().single().activeVersion)
    }

    @Test
    fun badSchemaRejected() {
        val dir = tmp.newFolder("bad-schema")
        File(dir, "package.json").writeText("""{"schemaVersion":99,"packageId":"x","packageVersion":1,"title":"t","content":[],"files":[]}""")
        val result = store().importDirectory(dir)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("schemaVersion") })
    }

    @Test
    fun badShaRejectedAndKeepsPrevious() {
        val store = store()
        val v1 = buildValidPackage(version = 1)
        assertTrue(store.importDirectory(v1).ok)
        val v2 = buildValidPackage(version = 2)
        // corrupt hash in package.json
        val manifest = File(v2, "package.json").readText().replaceFirst(
            Regex("\"sha256\": \"[a-f0-9]{64}\""),
            "\"sha256\": \"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"",
        )
        File(v2, "package.json").writeText(manifest)
        val result = store.importDirectory(v2)
        assertFalse(result.ok)
        assertEquals(1, store.activeVersion("demo-pkg"))
    }

    @Test
    fun missingFileRejected() {
        val dir = buildValidPackage(1)
        File(dir, "media/clip.mp4").delete()
        val result = store().importDirectory(dir)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("Missing") || it.contains("SHA") || it.contains("Size") })
    }

    @Test
    fun duplicateContentIdsRejected() {
        val dir = buildValidPackage(1)
        val text = File(dir, "package.json").readText().replace(
            "\"id\": \"pkg-item\"",
            "\"id\": \"pkg-item\"",
        )
        // inject duplicate by rewriting content array
        File(dir, "package.json").writeText(
            """
            {
              "schemaVersion":1,"packageId":"dup","packageVersion":1,"title":"Dup",
              "content":[
                {"id":"same","title":"A","type":"VIDEO","mediaRef":"media/clip.mp4"},
                {"id":"same","title":"B","type":"VIDEO","mediaRef":"media/clip.mp4"}
              ],
              "files":[{"path":"media/clip.mp4","sha256":"${sha(File(dir,"media/clip.mp4"))}","size":${File(dir,"media/clip.mp4").length()},"mediaType":"video/mp4"}]
            }
            """.trimIndent(),
        )
        assertFalse(store().importDirectory(dir).ok)
    }

    @Test
    fun absoluteAndTraversalPathsRejected() {
        assertFalse(
            ContentPackageParser.parseManifest(
                """
                {"schemaVersion":1,"packageId":"p","packageVersion":1,"title":"t",
                 "content":[{"id":"c","title":"c","type":"VIDEO","mediaRef":"../x.mp4"}],
                 "files":[{"path":"../x.mp4","sha256":"${"a".repeat(64)}","size":1,"mediaType":"video/mp4"}]}
                """.trimIndent(),
            ).ok,
        )
        assertFalse(
            ContentPackageParser.parseManifest(
                """
                {"schemaVersion":1,"packageId":"p","packageVersion":1,"title":"t",
                 "content":[{"id":"c","title":"c","type":"VIDEO","mediaRef":"/tmp/x.mp4"}],
                 "files":[{"path":"/tmp/x.mp4","sha256":"${"a".repeat(64)}","size":1,"mediaType":"video/mp4"}]}
                """.trimIndent(),
            ).ok,
        )
    }

    @Test
    fun zipSlipRejected() {
        val zip = tmp.newFile("slip.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("../evil.txt"))
            zos.write("x".toByteArray())
            zos.closeEntry()
        }
        val result = store().importZip(zip)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("slip", ignoreCase = true) || it.contains("unsafe", ignoreCase = true) })
    }

    @Test
    fun versionConflictDoesNotOverwrite() {
        val store = store()
        assertTrue(store.importDirectory(buildValidPackage(1)).ok)
        val again = buildValidPackage(1)
        val result = store.importDirectory(again)
        assertFalse(result.ok)
        assertTrue(result.errors.any { it.contains("already installed") })
    }

    @Test
    fun rollbackToPreviousVersion() {
        val store = store()
        assertTrue(store.importDirectory(buildValidPackage(1)).ok)
        assertTrue(store.importDirectory(buildValidPackage(2)).ok)
        assertEquals(2, store.activeVersion("demo-pkg"))
        val rolled = store.rollback("demo-pkg")
        assertTrue(rolled.ok)
        assertEquals(1, store.activeVersion("demo-pkg"))
    }

    @Test
    fun failedStagingKeepsPreviousActive() {
        val store = store()
        assertTrue(store.importDirectory(buildValidPackage(1)).ok)
        val bad = tmp.newFolder("bad-stage")
        File(bad, "package.json").writeText("{not-json")
        assertFalse(store.importDirectory(bad).ok)
        assertEquals(1, store.activeVersion("demo-pkg"))
    }

    @Test
    fun repositoryMergesPackageWithoutOverridingBundledIds() {
        val packagesRoot = tmp.newFolder("packages")
        val importRoot = tmp.newFolder("import-packages")
        val store = LocalContentPackageStore(packagesRoot, importRoot, memoryPrefs()) { setOf("v1", "g1") }
        val conflicting = buildValidPackage(1, contentId = "v1")
        assertFalse(store.importDirectory(conflicting).ok)

        val okPkg = buildValidPackage(1, contentId = "pkg-item")
        assertTrue(store.importDirectory(okPkg).ok)
        val repo = LocalContentRepository(
            overrides = ContentOverrideStore(memoryPrefs()),
            assetOpener = { ByteArrayInputStream(bundled.toByteArray()) },
            catalogRoot = tmp.newFolder("catalog"),
            packageStore = store,
        )
        val ids = repo.reload().items.map { it.id }
        assertTrue(ids.containsAll(listOf("v1", "g1", "pkg-item")))
        assertEquals(ContentSourceKind.MERGED, repo.activeSource())
    }

    @Test
    fun bundledBaselineStillParses() {
        val result = CatalogManifestParser.parse(bundled, ContentSourceKind.BUNDLED)
        assertTrue(result.ok)
    }

    private fun store() = LocalContentPackageStore(
        packagesRoot = tmp.newFolder("pkgs-${System.nanoTime()}"),
        importRoot = tmp.newFolder("imp-${System.nanoTime()}"),
        prefs = memoryPrefs(),
        reservedContentIds = { setOf("v1", "g1") },
    )

    private fun buildValidPackage(version: Int, contentId: String = "pkg-item"): File {
        val dir = tmp.newFolder("pkg-$version-${System.nanoTime()}")
        val media = File(dir, "media").also { it.mkdirs() }
        val clip = File(media, "clip.mp4").also { it.writeBytes(ByteArray(64) { 1 }) }
        val digest = sha(clip)
        File(dir, "package.json").writeText(
            """
            {
              "schemaVersion": 1,
              "packageId": "demo-pkg",
              "packageVersion": $version,
              "title": "Demo",
              "content": [
                {
                  "id": "$contentId",
                  "title": "Pack Item",
                  "type": "VIDEO",
                  "mediaRef": "media/clip.mp4",
                  "homeSections": ["learn"],
                  "sortOrder": 1
                }
              ],
              "files": [
                {
                  "path": "media/clip.mp4",
                  "sha256": "$digest",
                  "size": ${clip.length()},
                  "mediaType": "video/mp4"
                }
              ]
            }
            """.trimIndent(),
        )
        return dir
    }

    private fun sha(file: File) = PackagePathSafety.sha256(file)
}
