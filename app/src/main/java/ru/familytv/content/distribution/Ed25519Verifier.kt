package ru.familytv.content.distribution

import java.util.Base64
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.EdDSASecurityProvider
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import java.security.MessageDigest
import java.security.Security
import java.security.Signature

object Ed25519Verifier {
    private val SPEC = EdDSANamedCurveTable.getByName("Ed25519")

    init {
        if (Security.getProvider(EdDSASecurityProvider.PROVIDER_NAME) == null) {
            Security.addProvider(EdDSASecurityProvider())
        }
    }

    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            val pub = EdDSAPublicKey(EdDSAPublicKeySpec(publicKey, SPEC))
            val engine = Signature.getInstance(
                EdDSAEngine.SIGNATURE_ALGORITHM,
                EdDSASecurityProvider.PROVIDER_NAME,
            )
            engine.initVerify(pub)
            engine.update(message)
            engine.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    fun decodeBase64(value: String): ByteArray =
        Base64.getDecoder().decode(value.trim())

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
