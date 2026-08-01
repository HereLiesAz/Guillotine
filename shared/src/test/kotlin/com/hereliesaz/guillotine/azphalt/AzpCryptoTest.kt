package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * Ed25519 verification must be **three-valued**, because "this device can't check" and "this signature
 * is forged" are different facts and only one of them justifies refusing to install.
 *
 * Collapsing them is not hypothetical: it shipped, and it made Guillotine reject every correctly-signed
 * package on the flagship registry with "Package failed verification and was not installed: signature
 * verification failed" — on a device whose provider resolves `Ed25519` from `getInstance` and then throws
 * when handed the key. All 20 packages in that catalog verify on a desktop JVM, so the packages were
 * never at fault; the availability probe was, because it only asked whether the algorithm names resolved.
 */
class AzpCryptoTest {

    @Test fun platformSelfTestPassesOnThisJvm() {
        // The self-test runs a real RFC 8032 vector end to end. On a JVM that can do Ed25519 it must
        // pass — if this fails, ed25519Available is broken rather than the platform.
        assertTrue("Ed25519 self-test failed on a JVM that should support it", AzpCrypto.ed25519Available)
    }

    @Test fun aGenuineSignatureVerifies() {
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = "the signed manifest".encodeToByteArray()
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(message); sign() }
        assertEquals(
            AzpCrypto.Verification.VALID,
            AzpCrypto.verifyEd25519(kp.public.encoded, message, sig),
        )
    }

    @Test fun aTamperedMessageIsInvalidNotUnavailable() {
        // The distinction that matters: a real forgery must still be INVALID, or the fix would have
        // turned a security gate into a rubber stamp.
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update("a".encodeToByteArray()); sign() }
        assertEquals(
            AzpCrypto.Verification.INVALID,
            AzpCrypto.verifyEd25519(kp.public.encoded, "b".encodeToByteArray(), sig),
        )
    }

    @Test fun aWrongSignerIsInvalid() {
        val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val stranger = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val message = "m".encodeToByteArray()
        val sig = Signature.getInstance("Ed25519").run { initSign(signer.private); update(message); sign() }
        assertEquals(
            AzpCrypto.Verification.INVALID,
            AzpCrypto.verifyEd25519(stranger.public.encoded, message, sig),
        )
    }

    @Test fun garbageKeyIsInvalidOnACapablePlatform() {
        // generatePublic throws here. On a platform that passed its self-test the fault is the package's,
        // so this is INVALID — UNAVAILABLE is reserved for platforms that genuinely can't verify.
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(ByteArray(0)); sign() }
        assertEquals(
            AzpCrypto.Verification.INVALID,
            AzpCrypto.verifyEd25519("not a key".encodeToByteArray(), ByteArray(0), sig),
        )
    }

    @Test fun theFlagshipSigningKeyIsAWellFormedEd25519Spki() {
        // The pinned key must be loadable, or every trust decision against the flagship catalog is a
        // silent no-op. Decoding it through the same path verification uses is the cheap proof.
        val spki = Base64.getDecoder().decode(AzphaltTrust.FLAGSHIP_SIGNING_KEY)
        val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val sig = Signature.getInstance("Ed25519").run { initSign(kp.private); update(ByteArray(0)); sign() }
        // Not the signer, so the verdict is INVALID — the point is that it is a *verdict*, reached by
        // loading the key, rather than UNAVAILABLE (which would mean the key never parsed).
        assertEquals(AzpCrypto.Verification.INVALID, AzpCrypto.verifyEd25519(spki, ByteArray(0), sig))
        assertEquals(44, spki.size) // 12-byte SPKI AlgorithmIdentifier prefix + 32-byte key
    }
}
