package ru.familytv.content.distribution

object ContentIndexVerifier {
    fun verify(
        parse: ContentIndexParser.ParseResult,
        trustStore: ContentTrustStore,
        highestAcceptedIndexVersion: Int,
        allowIndexDowngrade: Boolean = false,
    ): VerifyResult {
        if (!parse.ok || parse.envelope == null || parse.payloadOrgJson == null) {
            return VerifyResult(false, parse.errors.ifEmpty { listOf("Parse failed") })
        }
        val env = parse.envelope
        val key = trustStore.find(env.keyId)
            ?: return VerifyResult(false, listOf("Unknown signer keyId=${env.keyId}"))
        val sig = try {
            Ed25519Verifier.decodeBase64(env.signatureBase64)
        } catch (_: Exception) {
            return VerifyResult(false, listOf("Malformed signature encoding"))
        }
        val message = CanonicalJson.bytes(CanonicalJson.fromOrgJson(parse.payloadOrgJson))
        if (!Ed25519Verifier.verify(key.publicKeyBytes, message, sig)) {
            return VerifyResult(false, listOf("Invalid index signature (tampered or wrong key)"))
        }
        val indexVersion = env.payload.indexVersion
        if (!allowIndexDowngrade && indexVersion < highestAcceptedIndexVersion) {
            return VerifyResult(
                false,
                listOf(
                    "Index rollback/replay blocked: indexVersion=$indexVersion < highestAccepted=$highestAcceptedIndexVersion",
                ),
            )
        }
        return VerifyResult(true, emptyList(), env)
    }

    data class VerifyResult(
        val ok: Boolean,
        val errors: List<String>,
        val envelope: ContentIndexEnvelope? = null,
    )
}
