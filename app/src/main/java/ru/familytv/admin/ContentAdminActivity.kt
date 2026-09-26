package ru.familytv.admin

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.familytv.content.PlatformServices
import ru.familytv.content.distribution.InstallRemoteResult
import ru.familytv.content.distribution.SyncResult
import ru.familytv.content.distribution.UpdatePolicy
import ru.familytv.content.packages.PackageInstallResult
import ru.familytv.games.GameStyle
import ru.familytv.home.HomeActivity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local content admin — not child-facing. Debug long-press on Home brand.
 */
class ContentAdminActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var remoteStatus: TextView
    private lateinit var listHost: LinearLayout
    private lateinit var packageHost: LinearLayout
    private lateinit var remoteHost: LinearLayout
    private lateinit var sourceUrlInput: EditText
    private lateinit var cleartextSwitch: Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fun dp(v: Int) = GameStyle.dp(this, v)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(20))
            tag = "content-admin-root"
        }
        root.addView(GameStyle.text(this, "Content Admin", 28f, true))
        root.addView(
            GameStyle.text(this, "Каталог, packages, remote sync", 16f).apply { setTextColor(GameStyle.muted) },
        )
        status = GameStyle.text(this, "", 14f).apply { setPadding(0, dp(10), 0, dp(10)) }
        root.addView(status)

        val actions = LinearLayout(this)
        actions.addView(btn("Reload") { reload() }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        actions.addView(btn("Validate") { validate() }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        actions.addView(btn("Import catalog") { importCatalog() }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(actions)

        val pkgActions = LinearLayout(this).apply { setPadding(0, dp(8), 0, 0) }
        pkgActions.addView(btn("Import package ZIP") { importPackageZip() }, LinearLayout.LayoutParams(-2, dp(48)).apply { marginEnd = dp(8) })
        pkgActions.addView(btn("Import package dir") { importPackageDir() }, LinearLayout.LayoutParams(-2, dp(48)))
        root.addView(pkgActions)

        root.addView(btn("‹ Home") {
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }, LinearLayout.LayoutParams(-2, dp(48)).apply { topMargin = dp(8) })

        root.addView(
            GameStyle.text(this, "Remote content source", 20f, true).apply {
                setPadding(0, dp(16), 0, dp(8))
            },
        )
        val sync = PlatformServices.contentSync(this)
        val st = sync.state()
        sourceUrlInput = EditText(this).apply {
            setText(st.sourceUrl.ifBlank { "http://192.168.68.1:8080/index.json" })
            hint = "https://…/index.json (or http://LAN in debug)"
            setTextColor(GameStyle.ink)
            setHintTextColor(GameStyle.muted)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GameStyle.rounded(this@ContentAdminActivity, Color.WHITE, 10)
        }
        root.addView(sourceUrlInput, LinearLayout.LayoutParams(-1, -2))
        cleartextSwitch = Switch(this).apply {
            text = "Allow cleartext (debug LAN)"
            isChecked = st.allowCleartext
            setTextColor(GameStyle.ink)
        }
        root.addView(cleartextSwitch)
        remoteStatus = GameStyle.text(this, "", 13f).apply {
            setTextColor(GameStyle.muted)
            setPadding(0, dp(6), 0, dp(6))
        }
        root.addView(remoteStatus)
        val remoteActions = LinearLayout(this)
        remoteActions.addView(btn("Save URL") { saveSourceUrl() }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        remoteActions.addView(btn("Sync index") { syncIndex() }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
        remoteActions.addView(btn("Reset index guard") {
            PlatformServices.contentSync(this).resetIndexProtection()
            status.text = "Highest accepted indexVersion reset to 0"
            renderRemote()
        }, LinearLayout.LayoutParams(-2, dp(44)))
        root.addView(remoteActions)
        remoteHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(remoteHost)

        root.addView(GameStyle.text(this, "Packages", 20f, true).apply {
            setPadding(0, dp(16), 0, dp(8))
        })
        packageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(packageHost)

        root.addView(GameStyle.text(this, "Catalog items", 20f, true).apply {
            setPadding(0, dp(16), 0, dp(8))
        })
        listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(listHost)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 250))
            addView(root)
            tag = "content-admin-scroll"
        })
        reload()
        handleAutomationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomationIntent(intent)
    }

    private fun handleAutomationIntent(intent: Intent) {
        when (intent.getStringExtra(EXTRA_ACTION)) {
            "importZips" -> importPackageZip()
            "activate" -> {
                val packageId = intent.getStringExtra(EXTRA_PACKAGE_ID) ?: return
                val version = intent.getIntExtra(EXTRA_VERSION, -1)
                if (version < 1) return
                val result = PlatformServices.packageStore(this).activate(packageId, version)
                status.text = if (result.ok) "Active $packageId@$version" else result.errors.joinToString()
                PlatformServices.contentRepository(this).reload()
                reload()
            }
            "rollback" -> {
                val packageId = intent.getStringExtra(EXTRA_PACKAGE_ID) ?: return
                val result = PlatformServices.packageStore(this).rollback(packageId)
                status.text = if (result.ok) {
                    "Rolled back to ${result.packageId}@${result.packageVersion}"
                } else {
                    result.errors.joinToString()
                }
                PlatformServices.contentRepository(this).reload()
                reload()
            }
            "configureSource" -> {
                val url = intent.getStringExtra(EXTRA_SOURCE_URL) ?: return
                val cleartext = intent.getBooleanExtra(EXTRA_ALLOW_CLEARTEXT, true)
                sourceUrlInput.setText(url)
                cleartextSwitch.isChecked = cleartext
                saveSourceUrl()
            }
            "syncIndex" -> syncIndex()
            "downloadInstall" -> {
                val packageId = intent.getStringExtra(EXTRA_PACKAGE_ID) ?: return
                val activate = intent.getBooleanExtra(EXTRA_ACTIVATE, true)
                downloadInstall(packageId, activate)
            }
            else -> Unit
        }
    }

    private fun btn(title: String, action: () -> Unit) = GameStyle.button(this, title, action)

    private fun saveSourceUrl() {
        val url = sourceUrlInput.text?.toString().orEmpty().trim()
        val errors = PlatformServices.contentSync(this).configureSource(url, cleartextSwitch.isChecked)
        status.text = if (errors.isEmpty()) "Saved source URL" else errors.joinToString("\n")
        renderRemote()
    }

    private fun syncIndex() {
        status.text = "Syncing…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PlatformServices.contentSync(this@ContentAdminActivity).syncIndex()
            }
            status.text = when (result) {
                is SyncResult.Ok -> "Index OK v=${result.indexVersion} updates=${result.updates.size} packages=${result.packages.size}"
                is SyncResult.Err -> "Sync failed:\n" + result.errors.joinToString("\n")
            }
            renderRemote()
        }
    }

    private fun downloadInstall(packageId: String, activate: Boolean) {
        status.text = "Downloading $packageId…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                PlatformServices.contentSync(this@ContentAdminActivity).downloadAndInstall(packageId, activate)
            }
            status.text = when (result) {
                is InstallRemoteResult.Ok ->
                    "Remote installed ${result.packageId}@${result.packageVersion} activated=${result.activated}"
                is InstallRemoteResult.Err -> "Remote install failed:\n" + result.errors.joinToString("\n")
            }
            if (result is InstallRemoteResult.Ok) {
                PlatformServices.contentRepository(this@ContentAdminActivity).reload()
            }
            reload()
        }
    }

    private fun reload() {
        val repo = PlatformServices.contentRepository(this)
        val catalog = repo.reload()
        val store = PlatformServices.packageStore(this)
        status.text = "catalog source=${catalog.source} v=${catalog.catalogVersion} items=${catalog.items.size}\n" +
            "catalogImport=${repo.importDirectory().absolutePath}\n" +
            "packageImport=${store.importDirectoryRoot().absolutePath}"
        renderRemote()
        renderPackages()
        renderItems()
    }

    private fun validate() {
        val result = PlatformServices.contentRepository(this).validateActiveManifest()
        status.text = if (result.ok) {
            "Catalog OK v=${result.catalog?.catalogVersion} items=${result.catalog?.items?.size}"
        } else {
            "INVALID:\n" + result.errors.joinToString("\n")
        }
    }

    private fun importCatalog() {
        val result = PlatformServices.contentRepository(this).importFromImportDirectory()
        status.text = if (result.ok) "Catalog imported v=${result.catalog?.catalogVersion}"
        else "Catalog import failed:\n" + result.errors.joinToString("\n")
        if (result.ok) reload()
    }

    private fun importPackageZip() {
        val store = PlatformServices.packageStore(this)
        val dir = store.importDirectoryRoot()
        val zips = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".zip", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()
        if (zips.isEmpty()) {
            status.text = "Place a .zip in ${dir.absolutePath}"
            return
        }
        val lines = mutableListOf<String>()
        var anyOk = false
        for (zip in zips) {
            val result = store.importZip(zip)
            if (result.ok) {
                anyOk = true
                lines += "OK ${result.packageId}@${result.packageVersion} (${zip.name})"
            } else {
                lines += "FAIL ${zip.name}: ${result.errors.joinToString("; ")}"
            }
        }
        status.text = lines.joinToString("\n")
        if (anyOk) {
            PlatformServices.contentRepository(this).reload()
            reload()
        }
    }

    private fun importPackageDir() {
        val store = PlatformServices.packageStore(this)
        val dir = store.importDirectoryRoot()
        val pkg = dir.listFiles()?.firstOrNull { it.isDirectory && File(it, "package.json").isFile }
            ?: File(dir, "apple-adventure").takeIf { File(it, "package.json").isFile }
        if (pkg == null) {
            status.text = "Place a package directory with package.json in ${dir.absolutePath}"
            return
        }
        showInstall(store.importDirectory(pkg))
    }

    private fun showInstall(result: PackageInstallResult) {
        status.text = if (result.ok) {
            "Installed ${result.packageId}@${result.packageVersion}"
        } else {
            "Package import failed:\n" + result.errors.joinToString("\n")
        }
        if (result.ok) {
            PlatformServices.contentRepository(this).reload()
            reload()
        }
    }

    private fun renderRemote() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        remoteHost.removeAllViews()
        val sync = PlatformServices.contentSync(this)
        val st = sync.state()
        val whenTxt = if (st.lastSyncAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(st.lastSyncAt))
        } else {
            "never"
        }
        remoteStatus.text = buildString {
            append("url=${st.sourceUrl.ifBlank { "(unset)" }}\n")
            append("state=${st.connectionState} policy=${st.policy} cleartext=${st.allowCleartext}\n")
            append("index=${st.lastIndexVersion ?: "-"} highestAccepted=${st.highestAcceptedIndexVersion} lastSync=$whenTxt\n")
            if (!st.lastError.isNullOrBlank()) append("error=${st.lastError}")
        }
        val updates = sync.availableUpdates()
        if (updates.isEmpty()) {
            remoteHost.addView(GameStyle.text(this, "No remote updates (sync first or already current)", 14f).apply {
                setTextColor(GameStyle.muted)
            })
            return
        }
        updates.forEach { u ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GameStyle.rounded(this@ContentAdminActivity, Color.WHITE, 12)
            }
            val from = u.installedVersion?.toString() ?: "—"
            box.addView(
                GameStyle.text(
                    this,
                    "${u.packageId}  $from → ${u.availableVersion}\n${u.title}  ${u.size} bytes",
                    14f,
                ),
            )
            val row = LinearLayout(this)
            row.addView(btn("Download+Install") {
                downloadInstall(u.packageId, activate = false)
            }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
            row.addView(btn("Install+Activate") {
                downloadInstall(u.packageId, activate = true)
            }, LinearLayout.LayoutParams(-2, dp(44)))
            box.addView(row)
            remoteHost.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        // Policy toggle
        val policyRow = LinearLayout(this)
        policyRow.addView(btn("Policy MANUAL") {
            sync.setPolicy(UpdatePolicy.MANUAL)
            renderRemote()
        }, LinearLayout.LayoutParams(-2, dp(40)).apply { marginEnd = dp(6) })
        policyRow.addView(btn("Policy AUTO_SAFE") {
            sync.setPolicy(UpdatePolicy.AUTO_SAFE)
            renderRemote()
            status.text = "AUTO_SAFE set — still requires Sync; does not auto-replace during testing"
        }, LinearLayout.LayoutParams(-2, dp(40)))
        remoteHost.addView(policyRow)
    }

    private fun renderPackages() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        packageHost.removeAllViews()
        val store = PlatformServices.packageStore(this)
        val installed = store.listInstalled()
        if (installed.isEmpty()) {
            packageHost.addView(GameStyle.text(this, "No packages installed", 14f).apply {
                setTextColor(GameStyle.muted)
            })
            return
        }
        installed.forEach { info ->
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = GameStyle.rounded(this@ContentAdminActivity, Color.WHITE, 12)
            }
            box.addView(
                GameStyle.text(
                    this,
                    "${info.packageId} — ${info.title}\nversions=${info.installedVersions} active=${info.activeVersion}",
                    14f,
                ),
            )
            store.activePackages().find { it.first.packageId == info.packageId }?.let { (manifest, _) ->
                val skills = manifest.content.flatMap { it.metadata.skills }.distinct().take(6)
                val minutes = manifest.content.mapNotNull { it.metadata.estimatedMinutes }.sum().takeIf { it > 0 }
                if (skills.isNotEmpty() || minutes != null) {
                    box.addView(
                        GameStyle.text(
                            this,
                            "skills=${skills.joinToString()} minutes≈${minutes ?: "-"}",
                            13f,
                        ).apply { setTextColor(GameStyle.muted) },
                    )
                }
            }
            val row = LinearLayout(this)
            info.installedVersions.forEach { version ->
                row.addView(btn("Activate $version") {
                    status.text = store.activate(info.packageId, version).let {
                        if (it.ok) "Active ${info.packageId}@$version" else it.errors.joinToString()
                    }
                    PlatformServices.contentRepository(this).reload()
                    reload()
                }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
                if (version != info.activeVersion) {
                    row.addView(btn("Remove $version") {
                        status.text = store.removeVersion(info.packageId, version).let {
                            if (it.ok) "Removed $version" else it.errors.joinToString()
                        }
                        reload()
                    }, LinearLayout.LayoutParams(-2, dp(44)).apply { marginEnd = dp(6) })
                }
            }
            row.addView(btn("Rollback") {
                status.text = store.rollback(info.packageId).let {
                    if (it.ok) "Rolled back to ${it.packageVersion}" else it.errors.joinToString()
                }
                PlatformServices.contentRepository(this).reload()
                reload()
            }, LinearLayout.LayoutParams(-2, dp(44)))
            box.addView(row)
            packageHost.addView(box, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun renderItems() {
        fun dp(v: Int) = GameStyle.dp(this, v)
        listHost.removeAllViews()
        val repo = PlatformServices.contentRepository(this)
        repo.getEffectiveItems().forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(8))
                background = GameStyle.rounded(this@ContentAdminActivity, Color.WHITE, radius = 12)
            }
            val pkg = item.packageId?.let { " · pkg=$it@${item.packageVersion}" } ?: ""
            row.addView(
                GameStyle.text(this, "${item.id}\n${item.title} · ${item.type}$pkg", 14f),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            row.addView(
                Switch(this).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        repo.setEnabled(item.id, checked)
                        status.text = "${item.id} enabled=$checked"
                    }
                },
            )
            listHost.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    companion object {
        const val EXTRA_ACTION = "ftvAction"
        const val EXTRA_PACKAGE_ID = "ftvPackageId"
        const val EXTRA_VERSION = "ftvVersion"
        const val EXTRA_SOURCE_URL = "ftvSourceUrl"
        const val EXTRA_ALLOW_CLEARTEXT = "ftvAllowCleartext"
        const val EXTRA_ACTIVATE = "ftvActivate"
    }
}
