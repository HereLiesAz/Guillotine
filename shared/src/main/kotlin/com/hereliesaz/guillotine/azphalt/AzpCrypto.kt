package com.hereliesaz.guillotine.azphalt

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 verification for azphalt signatures, via the JVM's JCA. Ed25519 is available on JDK 15+
 * and Android 13+ (API 33); on older Android it may be absent, so [ed25519Available] lets callers
 * distinguish "signature failed" from "cannot verify on this platform" and fall back to integrity-
 * only trust (spec/package-format.md § Signing: an unverifiable-but-integrity-valid package has
 * integrity, not established provenance).
 */
internal object AzpCrypto {

    /** True if this platform can verify Ed25519 signatures. */
    val ed25519Available: Boolean by lazy {
        runCatching { Signature.getInstance("Ed25519"); KeyFactory.getInstance("Ed25519") }.isSuccess
    }

    /**
     * Verify an Ed25519 [signature] over [message] against an SPKI (X.509) DER public key [spkiDer].
     * Returns false on any failure — a bad signature, a malformed key, or (see [ed25519Available])
     * Ed25519 being unavailable on this platform.
     */
    fun verifyEd25519(spkiDer: ByteArray, message: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiDer))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        }.getOrDefault(false)
}
