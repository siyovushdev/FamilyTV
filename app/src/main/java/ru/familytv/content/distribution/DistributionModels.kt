package ru.familytv.content.distribution

data class ContentIndexEnvelope(
    val schemaVersion: Int,
    val keyId: String,
    val signatureBase64: String,
    val payload: ContentIndexPayload,
)

data class ContentIndexPayload(
    val schemaVersion: Int,
    val indexVersion: Int,
    val generatedAt: String,
    val publisherId: String,
    val packages: List<RemotePackageRecord>,
)

data class RemotePackageRecord(
    val packageId: String,
    val packageVersion: Int,
    val title: String,
    val description: String = "",
    val url: String,
    val path: String? = null,
    val size: Long,
    val sha256: String,
    val enabled: Boolean = true,
    val sortOrder: Int = 100,
)

enum class UpdatePolicy {
    MANUAL,
    AUTO_SAFE,
}

data class SyncState(
    val sourceUrl: String = "",
    val allowCleartext: Boolean = false,
    val policy: UpdatePolicy = UpdatePolicy.MANUAL,
    val highestAcceptedIndexVersion: Int = 0,
    val lastIndexVersion: Int? = null,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null,
    val connectionState: String = "idle",
    val cachedEnvelopeJson: String? = null,
)

data class AvailableUpdate(
    val packageId: String,
    val title: String,
    val installedVersion: Int?,
    val availableVersion: Int,
    val size: Long,
    val sha256: String,
    val url: String,
    val enabled: Boolean,
)

sealed class SyncResult {
    data class Ok(
        val indexVersion: Int,
        val updates: List<AvailableUpdate>,
        val packages: List<RemotePackageRecord>,
    ) : SyncResult()

    data class Err(val errors: List<String>) : SyncResult()
}

sealed class InstallRemoteResult {
    data class Ok(val packageId: String, val packageVersion: Int, val activated: Boolean) : InstallRemoteResult()
    data class Err(val errors: List<String>) : InstallRemoteResult()
}
