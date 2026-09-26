package ru.familytv.content.distribution

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.util.Base64

class ContentSyncPrefs(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
    )

    fun getState(): SyncState = SyncState(
        sourceUrl = prefs.getString(KEY_URL, "") ?: "",
        allowCleartext = prefs.getBoolean(KEY_CLEARTEXT, false),
        policy = runCatching {
            UpdatePolicy.valueOf(prefs.getString(KEY_POLICY, UpdatePolicy.MANUAL.name)!!)
        }.getOrDefault(UpdatePolicy.MANUAL),
        highestAcceptedIndexVersion = prefs.getInt(KEY_HIGHEST_INDEX, 0),
        lastIndexVersion = prefs.getInt(KEY_LAST_INDEX, -1).takeIf { it > 0 },
        lastSyncAt = prefs.getLong(KEY_LAST_SYNC, 0L),
        lastError = prefs.getString(KEY_LAST_ERROR, null),
        connectionState = prefs.getString(KEY_CONN, "idle") ?: "idle",
        cachedEnvelopeJson = prefs.getString(KEY_CACHED_INDEX, null),
    )

    fun setSourceUrl(url: String, allowCleartext: Boolean) {
        prefs.edit()
            .putString(KEY_URL, url.trim())
            .putBoolean(KEY_CLEARTEXT, allowCleartext)
            .commit()
    }

    fun setPolicy(policy: UpdatePolicy) {
        prefs.edit().putString(KEY_POLICY, policy.name).commit()
    }

    fun setConnectionState(state: String, error: String? = null) {
        prefs.edit()
            .putString(KEY_CONN, state)
            .putString(KEY_LAST_ERROR, error)
            .commit()
    }

    fun acceptIndex(indexVersion: Int, envelopeJson: String, now: Long = System.currentTimeMillis()) {
        val highest = maxOf(prefs.getInt(KEY_HIGHEST_INDEX, 0), indexVersion)
        prefs.edit()
            .putInt(KEY_HIGHEST_INDEX, highest)
            .putInt(KEY_LAST_INDEX, indexVersion)
            .putLong(KEY_LAST_SYNC, now)
            .putString(KEY_CACHED_INDEX, envelopeJson)
            .putString(KEY_CONN, "ok")
            .remove(KEY_LAST_ERROR)
            .commit()
    }

    fun resetHighestIndexForDev() {
        prefs.edit().putInt(KEY_HIGHEST_INDEX, 0).commit()
    }

    companion object {
        const val PREFS = "familytv_content_sync"
        private const val KEY_URL = "sourceUrl"
        private const val KEY_CLEARTEXT = "allowCleartext"
        private const val KEY_POLICY = "policy"
        private const val KEY_HIGHEST_INDEX = "highestIndex"
        private const val KEY_LAST_INDEX = "lastIndex"
        private const val KEY_LAST_SYNC = "lastSyncAt"
        private const val KEY_LAST_ERROR = "lastError"
        private const val KEY_CONN = "connectionState"
        private const val KEY_CACHED_INDEX = "cachedIndex"
    }
}

object DevTrustStoreFactory {
    const val DEV_KEY_ID = "familytv-dev-1"
    const val ASSET_PUB = "content/trust/dev-publisher.pub.b64"

    fun fromAssets(context: Context): ContentTrustStore {
        val b64 = context.assets.open(ASSET_PUB).bufferedReader().use { it.readText() }.trim()
        val bytes = Base64.getDecoder().decode(b64)
        require(bytes.size == 32) { "Dev public key must be 32 bytes" }
        return StaticContentTrustStore(listOf(TrustedPublisherKey(DEV_KEY_ID, bytes)))
    }

    fun fromPublicKeyBytes(keyId: String, bytes: ByteArray): ContentTrustStore =
        StaticContentTrustStore(listOf(TrustedPublisherKey(keyId, bytes)))
}

fun ContentSyncPrefs.downloadDir(context: Context): File =
    File(requireNotNull(context.getExternalFilesDir(null)), "FamilyTV/remote-downloads").also { it.mkdirs() }
