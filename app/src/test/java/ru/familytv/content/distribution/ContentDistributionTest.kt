package ru.familytv.content.distribution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.familytv.content.memoryPrefs
import ru.familytv.content.packages.LocalContentPackageStore
import ru.familytv.content.packages.PackagePathSafety
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.Base64
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import org.json.JSONObject
import java.security.SecureRandom
import java.security.Security
import java.security.Signature

class ContentDistributionTest {
    @get:Rule val tmp = TemporaryFolder()

    private val spec = EdDSANamedCurveTable.getByName("Ed25519")

    companion object {
        @JvmStatic
        @BeforeClass
        fun installProvider() {
            if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
                Security.addProvider(EdDSASecurityProvider())
            }
        }
    }

    @Test
    fun validSignatureAccepted() {
        val (sk, pk) = keyPair()
        val payload = payloadJson(indexVersion = 3)
        val envelope = sign(payload, sk, "test-key")
        val trust = DevTrustStoreFactory.fromPublicKeyBytes("test-key", pk)
        val parsed = ContentIndexParser.parseEnvelope(envelope)
        assertTrue(parsed.ok)
        val verified = ContentIndexVerifier.verify(parsed, trust, highestAcceptedIndexVersion = 0)
        assertTrue(verified.errors.joinToString(), verified.ok)
        assertEquals(3, verified.envelope!!.payload.indexVersion)
    }

    @Test
    fun tamperedIndexRejected() {
        val (sk, pk) = keyPair()
        val envelope = sign(payloadJson(1), sk, "test-key")
        val tampered = JSONObject(envelope).apply {
            getJSONObject("payload").put("indexVersion", 99)
        }.toString()
        val parsed = ContentIndexParser.parseEnvelope(tampered)
        val verified = ContentIndexVerifier.verify(
            parsed,
            DevTrustStoreFactory.fromPublicKeyBytes("test-key", pk),
            0,
        )
        assertFalse(verified.ok)
        assertTrue(verified.errors.any { it.contains("signature", ignoreCase = true) })
    }

    @Test
    fun unknownKeyRejected() {
        val (sk, _) = keyPair()
        val (_, otherPk) = keyPair()
        val envelope = sign(payloadJson(1), sk, "key-a")
        val verified = ContentIndexVerifier.verify(
            ContentIndexParser.parseEnvelope(envelope),
            DevTrustStoreFactory.fromPublicKeyBytes("key-b", otherPk),
            0,
        )
        assertFalse(verified.ok)
        assertTrue(verified.errors.any { it.contains("Unknown signer") })
    }

    @Test
    fun oldIndexReplayBlocked() {
        val (sk, pk) = keyPair()
        val envelope = sign(payloadJson(2), sk, "test-key")
        val verified = ContentIndexVerifier.verify(
            ContentIndexParser.parseEnvelope(envelope),
            DevTrustStoreFactory.fromPublicKeyBytes("test-key", pk),
            highestAcceptedIndexVersion = 5,
        )
        assertFalse(verified.ok)
        assertTrue(verified.errors.any { it.contains("rollback") || it.contains("replay") })
    }

    @Test
    fun shaMismatchKeepsPreviousActive() {
        val packagesRoot = tmp.newFolder("pkgs")
        val importRoot = tmp.newFolder("imp")
        val dl = tmp.newFolder("dl")
        val store = LocalContentPackageStore(packagesRoot, importRoot, memoryPrefs()) { emptySet() }
        val v1 = buildZip(1)
        assertTrue(store.importZip(v1).ok)
        assertEquals(1, store.activeVersion("demo-pkg"))

        val (sk, pk) = keyPair()
        val badSha = "a".repeat(64)
        val zipBytes = File(tmp.newFolder("z"), "demo-pkg-v2.zip").also { f ->
            buildZip(2).copyTo(f, overwrite = true)
        }
        val payload = """
          {"schemaVersion":1,"indexVersion":1,"generatedAt":"t","publisherId":"p","packages":[
            {"packageId":"demo-pkg","packageVersion":2,"title":"D","url":"http://x/p.zip","size":${zipBytes.length()},"sha256":"$badSha","enabled":true,"sortOrder":1}
          ]}
        """.trimIndent()
        val envelope = sign(payload, sk, "test-key")
        val fetcher = object : HttpFetcher {
            override fun getText(url: String, etag: String?, lastModified: String?) =
                FetchTextResult(true, body = envelope)
            override fun downloadToFile(url: String, dest: File, expectedSize: Long?, onProgress: ((Long) -> Unit)?) =
                FetchFileResult(
                    true,
                    sha256 = PackagePathSafety.sha256(zipBytes),
                    bytesWritten = zipBytes.length(),
                ).also { zipBytes.copyTo(dest, overwrite = true) }
        }
        val prefs = ContentSyncPrefs(memoryPrefs())
        prefs.setSourceUrl("https://example.test/index.json", allowCleartext = false)
        val engine = ContentSyncEngine(
            prefs = prefs,
            trustStore = DevTrustStoreFactory.fromPublicKeyBytes("test-key", pk),
            fetcher = fetcher,
            packageStore = store,
            downloadDir = dl,
            debuggable = true,
        )
        assertTrue(engine.syncIndex() is SyncResult.Ok)
        val result = engine.downloadAndInstall("demo-pkg", activate = true)
        assertTrue(result is InstallRemoteResult.Err)
        assertEquals(1, store.activeVersion("demo-pkg"))
    }

    @Test
    fun successfulRemoteUpdateAndRollback() {
        val packagesRoot = tmp.newFolder("pkgs2")
        val importRoot = tmp.newFolder("imp2")
        val dl = tmp.newFolder("dl2")
        val store = LocalContentPackageStore(packagesRoot, importRoot, memoryPrefs()) { emptySet() }
        assertTrue(store.importZip(buildZip(1)).ok)

        val (sk, pk) = keyPair()
        val v2 = buildZip(2)
        val sha = PackagePathSafety.sha256(v2)
        val payload = """
          {"schemaVersion":1,"indexVersion":4,"generatedAt":"t","publisherId":"p","packages":[
            {"packageId":"demo-pkg","packageVersion":2,"title":"D","url":"http://x/p.zip","size":${v2.length()},"sha256":"$sha","enabled":true,"sortOrder":1}
          ]}
        """.trimIndent()
        val envelope = sign(payload, sk, "test-key")
        val fetcher = object : HttpFetcher {
            override fun getText(url: String, etag: String?, lastModified: String?) =
                FetchTextResult(true, body = envelope)
            override fun downloadToFile(url: String, dest: File, expectedSize: Long?, onProgress: ((Long) -> Unit)?) =
                FetchFileResult(true, sha256 = sha, bytesWritten = v2.length()).also {
                    v2.copyTo(dest, overwrite = true)
                }
        }
        val prefs = ContentSyncPrefs(memoryPrefs())
        prefs.setSourceUrl("https://example.test/index.json", false)
        val engine = ContentSyncEngine(
            prefs, DevTrustStoreFactory.fromPublicKeyBytes("test-key", pk), fetcher, store, dl, true,
        )
        val sync = engine.syncIndex() as SyncResult.Ok
        assertEquals(4, sync.indexVersion)
        assertEquals(1, sync.updates.size)
        val installed = engine.downloadAndInstall("demo-pkg", activate = true)
        assertTrue(installed is InstallRemoteResult.Ok)
        assertEquals(2, store.activeVersion("demo-pkg"))
        assertTrue(store.rollback("demo-pkg").ok)
        assertEquals(1, store.activeVersion("demo-pkg"))
    }

    @Test
    fun cleartextBlockedOutsideDebug() {
        val errors = UrlPolicy.validateSourceUrl("http://192.168.1.1:8080/index.json", allowCleartext = true, debuggable = false)
        assertTrue(errors.any { it.contains("Cleartext") })
    }

    @Test
    fun privateKeyNotInApkAssets() {
        // Guardrail: only .pub.b64 may live under assets/content/trust
        val trustDir = File("src/main/assets/content/trust")
        if (trustDir.isDirectory) {
            trustDir.listFiles()?.forEach { f ->
                assertFalse("Private key leaked into assets: ${f.name}", f.name.contains(".sk"))
                assertFalse(f.name.endsWith(".ed25519.sk"))
            }
        }
    }

    @Test
    fun canonicalJsonMatchesPythonStyle() {
        val map = mapOf(
            "b" to 2L,
            "a" to "привет",
            "c" to listOf(1L, 2L),
        )
        val s = CanonicalJson.dumps(map)
        assertEquals("""{"a":"привет","b":2,"c":[1,2]}""", s)
    }

    private fun keyPair(): Pair<ByteArray, ByteArray> {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
        val pub = EdDSAPublicKey(EdDSAPublicKeySpec(priv.a, spec))
        return priv.seed to pub.abyte
    }

    private fun sign(payloadJson: String, seed: ByteArray, keyId: String): String {
        val payloadObj = JSONObject(payloadJson)
        val canonical = CanonicalJson.bytes(CanonicalJson.fromOrgJson(payloadObj))
        val priv = EdDSAPrivateKey(EdDSAPrivateKeySpec(seed, spec))
        val engine = Signature.getInstance(
            EdDSAEngine.SIGNATURE_ALGORITHM,
            EdDSASecurityProvider.PROVIDER_NAME,
        )
        engine.initSign(priv)
        engine.update(canonical)
        val sig = engine.sign()
        return JSONObject()
            .put("schemaVersion", 1)
            .put("keyId", keyId)
            .put("signature", Base64.getEncoder().encodeToString(sig))
            .put("payload", payloadObj)
            .toString()
    }

    private fun payloadJson(indexVersion: Int) = """
      {"schemaVersion":1,"indexVersion":$indexVersion,"generatedAt":"2026-01-01T00:00:00Z","publisherId":"p","packages":[]}
    """.trimIndent()

    private fun buildZip(version: Int): File {
        val dir = tmp.newFolder("zip-$version-${System.nanoTime()}")
        val media = File(dir, "media").also { it.mkdirs() }
        val clip = File(media, "clip.mp4").also { it.writeBytes(ByteArray(64) { version.toByte() }) }
        val digest = PackagePathSafety.sha256(clip)
        File(dir, "package.json").writeText(
            """
            {"schemaVersion":1,"packageId":"demo-pkg","packageVersion":$version,"title":"Demo",
             "content":[{"id":"pkg-item","title":"Item","type":"VIDEO","mediaRef":"media/clip.mp4","homeSections":["learn"],"sortOrder":1}],
             "files":[{"path":"media/clip.mp4","sha256":"$digest","size":${clip.length()},"mediaType":"video/mp4"}]}
            """.trimIndent(),
        )
        val zip = tmp.newFile("demo-pkg-v$version-${System.nanoTime()}.zip")
        ZipOutputStream(zip.outputStream()).use { zos ->
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(dir).invariantSeparatorsPath
                zos.putNextEntry(ZipEntry(rel))
                file.inputStream().use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
        return zip
    }
}
