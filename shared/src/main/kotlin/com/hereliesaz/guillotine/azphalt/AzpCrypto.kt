package com.hereliesaz.guillotine.azphalt

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 verification for azphalt signatures, via the JVM's JCA. Ed25519 is available on JDK 15+ and
 * Android 13+ (API 33); on older Android it may be absent, so [ed25519Available] lets callers
 * distinguish "signature failed" from "cannot verify on this platform" and fall back to integrity-only
 * trust (spec/package-format.md § Signing: an unverifiable-but-integrity-valid package has integrity,
 * not established provenance).
 *
 * That distinction only works if it is drawn accurately, and it previously wasn't. Two faults compounded:
 * [ed25519Available] asked only whether `getInstance` resolved the algorithm *names*, and [verifyEd25519]
 * collapsed every thrown exception into `false` — the same value a genuinely forged signature produces.
 * A platform where the names resolve but the operation throws (Android's Conscrypt provider can accept
 * `Ed25519` from `getInstance` and then reject the SPKI key spec or the key at `initVerify`) therefore
 * reported *every* correctly-signed package as "signature verification failed", which
 * [AzpPackage.verifyTrust] turns into `ok = false` — a hard refusal to install. Every package in the
 * flagship catalog verifies correctly on a desktop JVM, so the packages were never the problem.
 *
 * So availability is now proven end to end against a known-good RFC 8032 vector, and verification is
 * three-valued: only a verifier that ran and said "no" means the signature is bad.
 */
internal object AzpCrypto {

    /** The outcome of an Ed25519 check — deliberately not a Boolean. See [verifyEd25519]. */
    enum class Verification {
        /** The verifier ran and accepted the signature. */
        VALID,

        /** The verifier ran and rejected it — the signature really is bad. */
        INVALID,

        /** No verdict: this platform could not perform the check. Not evidence of anything. */
        UNAVAILABLE,
    }

    /**
     * RFC 8032 § 7.1 test vector 1 — the canonical Ed25519 known-answer test: a 32-byte public key, an
     * empty message, and the signature over it. Verifying this proves the whole pipeline works
     * (`KeyFactory` + `X509EncodedKeySpec` + `Signature`), which is what callers actually depend on.
     */
    private const val TEST_PUBLIC_KEY_HEX = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a"
    private const val TEST_SIGNATURE_HEX =
        "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
            "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"

    /** DER prefix wrapping a raw 32-byte Ed25519 key as an X.509 SPKI (`AlgorithmIdentifier` 1.3.101.112). */
    private const val SPKI_PREFIX_HEX = "302a300506032b6570032100"

    /**
     * True only if this platform can actually complete an Ed25519 verification — established by running
     * one, not by asking whether the algorithm name is known. A false here means every signature check
     * is a non-answer, and callers must degrade to integrity-only trust rather than call packages forged.
     */
    val ed25519Available: Boolean by lazy {
        runCatching {
            val spki = hex(SPKI_PREFIX_HEX + TEST_PUBLIC_KEY_HEX)
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(ByteArray(0))
                verify(hex(TEST_SIGNATURE_HEX))
            }
        }.getOrDefault(false)
    }

    /**
     * Verify an Ed25519 [signature] over [message] against an SPKI (X.509) DER public key [spkiDer].
     *
     * A thrown exception is only [Verification.INVALID] when the platform has already proven it can
     * verify ([ed25519Available]) — then the fault is in *this* key or signature, which is a real defect
     * in the package. On a platform that failed its self-test the same exception means nothing about the
     * package, so it is [Verification.UNAVAILABLE].
     */
    fun verifyEd25519(spkiDer: ByteArray, message: ByteArray, signature: ByteArray): Verification =
        runCatching {
            val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spkiDer))
            Signature.getInstance("Ed25519").run {
                initVerify(key)
                update(message)
                verify(signature)
            }
        }.fold(
            onSuccess = { if (it) Verification.VALID else Verification.INVALID },
            onFailure = { if (ed25519Available) Verification.INVALID else Verification.UNAVAILABLE },
        )

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { ((Character.digit(s[it * 2], 16) shl 4) + Character.digit(s[it * 2 + 1], 16)).toByte() }
}
