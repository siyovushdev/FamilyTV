package ru.familytv.content.distribution

import android.content.Context
import android.content.pm.ApplicationInfo
import ru.familytv.content.packages.ContentPackageStore
import ru.familytv.content.packages.PackageInstallResult
import java.io.File

/**
 * Fetches signed index, compares to installed packages, downloads/verifies ZIPs,
 * then installs via existing [ContentPackageStore] only.
 */
class ContentSyncEngine(
    private val prefs: ContentSyncPrefs,
    private val trustStore: ContentTrustStore,
    private val fetcher: HttpFetcher,
    private val packageStore: ContentPackageStore,
    private val downloadDir: File,
    private val debuggable: Boolean,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    constructor(
        context: Context,
        packageStore: ContentPackageStore,
        trustStore: ContentTrustStore = DevTrustStoreFactory.fromAssets(context),
        fetcher: HttpFetcher = UrlHttpFetcher(),
    ) : this(
        prefs = ContentSyncPrefs(context),
        trustStore = trustStore,
        fetcher = fetcher,
        packageStore = packageStore,
        downloadDir = File(
            requireNotNull(context.getExternalFilesDir(null)),
            "FamilyTV/remote-downloads",
        ).also { it.mkdirs() },
        debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
    )

    fun state(): SyncState = prefs.getState()

    fun configureSource(url: String, allowCleartext: Boolean): List<String> {
        val errors = UrlPolicy.validateSourceUrl(url, allowCleartext, debuggable)
        if (errors.isEmpty()) prefs.setSourceUrl(url, allowCleartext)
        return errors
    }

    fun setPolicy(policy: UpdatePolicy) = prefs.setPolicy(policy)

    fun resetIndexProtection() = prefs.resetHighestIndexForDev()

    /** Fetch + verify index. Never installs. Safe to call from background. */
    fun syncIndex(allowIndexDowngrade: Boolean = false): SyncResult {
        val state = prefs.getState()
        val urlErrors = UrlPolicy.validateSourceUrl(state.sourceUrl, state.allowCleartext, debuggable)
        if (urlErrors.isNotEmpty()) {
            prefs.setConnectionState("error", urlErrors.joinToString())
            return SyncResult.Err(urlErrors)
        }
        prefs.setConnectionState("syncing")
        val fetch = fetcher.getText(state.sourceUrl)
        if (!fetch.ok || fetch.body.isNullOrBlank()) {
            // Offline: return cached index if present
            val cached = state.cachedEnvelopeJson
            if (cached != null) {
                prefs.setConnectionState("offline_cached", fetch.error)
                return evaluateEnvelope(cached, state, allowIndexDowngrade, persist = false)
            }
            prefs.setConnectionState("error", fetch.error ?: "Fetch failed")
            return SyncResult.Err(listOf(fetch.error ?: "Failed to fetch index"))
        }
        return evaluateEnvelope(fetch.body, state, allowIndexDowngrade, persist = true)
    }

    fun availableUpdates(envelope: ContentIndexEnvelope? = null): List<AvailableUpdate> {
        val env = envelope ?: cachedEnvelope() ?: return emptyList()
        val installed = packageStore.listInstalled().associate { it.packageId to (it.activeVersion ?: it.installedVersions.maxOrNull()) }
        return env.payload.packages
            .filter { it.enabled }
            .groupBy { it.packageId }
            .mapNotNull { (_, versions) ->
                val rec = versions.maxByOrNull { it.packageVersion }!!
                val current = installed[rec.packageId]
                if (current != null && current >= rec.packageVersion) return@mapNotNull null
                AvailableUpdate(
                    packageId = rec.packageId,
                    title = rec.title,
                    installedVersion = current,
                    availableVersion = rec.packageVersion,
                    size = rec.size,
                    sha256 = rec.sha256,
                    url = UrlPolicy.resolvePackageUrl(prefs.getState().sourceUrl, rec),
                    enabled = rec.enabled,
                )
            }
            .sortedBy { it.packageId }
    }

    fun cachedEnvelope(): ContentIndexEnvelope? {
        val json = prefs.getState().cachedEnvelopeJson ?: return null
        val parsed = ContentIndexParser.parseEnvelope(json)
        return if (parsed.ok) parsed.envelope else null
    }

    /**
     * Download → hash → importZip via ContentPackageStore.
     * [activate] true activates the new version (MANUAL default leaves activation to admin unless true).
     */
    fun downloadAndInstall(packageId: String, activate: Boolean): InstallRemoteResult {
        val env = cachedEnvelope()
            ?: return InstallRemoteResult.Err(listOf("No verified index cached; sync first"))
        val rec = env.payload.packages
            .filter { it.packageId == packageId && it.enabled }
            .maxByOrNull { it.packageVersion }
            ?: return InstallRemoteResult.Err(listOf("Package $packageId not in index"))
        val url = UrlPolicy.resolvePackageUrl(prefs.getState().sourceUrl, rec)
        val dest = File(downloadDir, "${rec.packageId}-v${rec.packageVersion}.zip")
        val dl = fetcher.downloadToFile(url, dest, expectedSize = rec.size)
        if (!dl.ok) {
            return InstallRemoteResult.Err(listOf(dl.error ?: "Download failed"))
        }
        if (!dl.sha256.equals(rec.sha256, ignoreCase = true)) {
            dest.delete()
            return InstallRemoteResult.Err(
                listOf("SHA-256 mismatch for $packageId: expected ${rec.sha256}, got ${dl.sha256}"),
            )
        }
        val previousActive = packageStore.listInstalled().find { it.packageId == packageId }?.activeVersion
        val result: PackageInstallResult = packageStore.importZip(dest)
        if (!result.ok) {
            // Keep previous active; leave corrupt zip deleted
            dest.delete()
            return InstallRemoteResult.Err(result.errors.ifEmpty { listOf("Package import failed") })
        }
        var activated = false
        if (activate) {
            val act = packageStore.activate(packageId, rec.packageVersion)
            activated = act.ok
            if (!act.ok) {
                return InstallRemoteResult.Err(
                    listOf("Installed but activate failed: ${act.errors.joinToString()}") +
                        listOfNotNull(previousActive?.let { "Previous active remains $it" }),
                )
            }
        }
        return InstallRemoteResult.Ok(packageId, rec.packageVersion, activated)
    }

    private fun evaluateEnvelope(
        body: String,
        state: SyncState,
        allowIndexDowngrade: Boolean,
        persist: Boolean,
    ): SyncResult {
        val parsed = ContentIndexParser.parseEnvelope(body)
        val verified = ContentIndexVerifier.verify(
            parsed,
            trustStore,
            state.highestAcceptedIndexVersion,
            allowIndexDowngrade = allowIndexDowngrade,
        )
        if (!verified.ok || verified.envelope == null) {
            prefs.setConnectionState("error", verified.errors.joinToString())
            return SyncResult.Err(verified.errors)
        }
        if (persist) {
            prefs.acceptIndex(verified.envelope.payload.indexVersion, body, clock())
        }
        val updates = availableUpdates(verified.envelope)
        return SyncResult.Ok(verified.envelope.payload.indexVersion, updates, verified.envelope.payload.packages)
    }
}
