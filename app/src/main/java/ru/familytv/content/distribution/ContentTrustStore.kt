package ru.familytv.content.distribution

/**
 * Trusted publisher public keys.
 * Private keys never ship in the APK.
 */
data class TrustedPublisherKey(
    val keyId: String,
    val publicKeyBytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedPublisherKey) return false
        return keyId == other.keyId && publicKeyBytes.contentEquals(other.publicKeyBytes)
    }

    override fun hashCode(): Int = 31 * keyId.hashCode() + publicKeyBytes.contentHashCode()
}

interface ContentTrustStore {
    fun find(keyId: String): TrustedPublisherKey?
    fun all(): List<TrustedPublisherKey>
}

class StaticContentTrustStore(
    private val keys: List<TrustedPublisherKey>,
) : ContentTrustStore {
    override fun find(keyId: String): TrustedPublisherKey? = keys.find { it.keyId == keyId }
    override fun all(): List<TrustedPublisherKey> = keys
}
