package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Verifies trust-on-first-use publisher pinning: an update must come from the same signer. */
class AzpPublisherPinsTest {

    @get:Rule val tmp = TemporaryFolder()

    // ---- signing helpers (mirror AzpPackageTest / @azphalt/azp) ----
    private fun ed25519(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun sign(kp: KeyPair, m: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run { initSign(kp.private); update(m); sign() }
    private fun spkiB64(kp: KeyPair) = b64(kp.public.encoded)

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bos.toByteArray()
    }

    private fun manifestBytes(id: String, digest: String): ByteArray = """
        {"azphalt":"0.1","id":"$id","name":"Model","version":"1.0.0","kind":"asset","license":"MIT",
         "compat":">=0.1","assets":[{"type":"onnx","path":"assets/m.onnx","role":"subject-segmentation"}],
         "files":{"assets/m.onnx":"$digest"}}
    """.trimIndent().encodeToByteArray()

    /** A bundled-asset .azp for [id], optionally signed by [author]. */
    private fun pkg(id: String, author: KeyPair?): ByteArray {
        val weights = "WEIGHTS-$id".encodeToByteArray()
        val manifest = manifestBytes(id, AzpPackage.digest(weights))
        val entries = linkedMapOf("manifest.json" to manifest, "assets/m.onnx" to weights)
        if (author != null) {
            val sigJson = """{"alg":"ed25519","publicKey":"${spkiB64(author)}","signature":"${b64(sign(author, manifest))}"}"""
            entries["signature.json"] = sigJson.encodeToByteArray()
        }
        return zip(entries)
    }

    private fun install(azp: ByteArray, pins: AzpPublisherPins, allowPublisherChange: Boolean = false) =
        AzpModelInstall.install(
            azp, emptySet(), tmp.newFolder(), allowUntrusted = true,
            pins = pins, allowPublisherChange = allowPublisherChange,
        )

    private val id = "com.hereliesaz.azphalt.whisper"

    @Test fun firstSignedInstallPinsThePublisher() {
        val author = ed25519()
        val pins = AzpPublisherPins(tmp.newFile("pins1.json").also { it.delete() })
        val result = install(pkg(id, author), pins)
        assertEquals(spkiB64(author), result.signerPublicKey)
        assertEquals(spkiB64(author), pins.keyFor(id))
    }

    @Test fun samePublisherUpdateIsAccepted() {
        val author = ed25519()
        val pins = AzpPublisherPins(tmp.newFile("pins2.json").also { it.delete() })
        install(pkg(id, author), pins)
        // A second install signed by the same key succeeds and keeps the pin.
        assertEquals(1, install(pkg(id, author), pins).installed.size)
        assertEquals(spkiB64(author), pins.keyFor(id))
    }

    @Test fun differentPublisherIsRejected() {
        val author = ed25519()
        val attacker = ed25519()
        val pins = AzpPublisherPins(tmp.newFile("pins3.json").also { it.delete() })
        install(pkg(id, author), pins)
        val ex = assertThrows(AzpModelInstall.PublisherChangedException::class.java) {
            install(pkg(id, attacker), pins)
        }
        assertEquals(id, ex.packageId)
        assertEquals(spkiB64(author), ex.pinnedKey)
        assertEquals(spkiB64(attacker), ex.newSignerKey)
        // The pin is unchanged — the rejected package never overwrote the original publisher.
        assertEquals(spkiB64(author), pins.keyFor(id))
    }

    @Test fun approvedRotationRepinsToTheNewPublisher() {
        val author = ed25519()
        val rotated = ed25519()
        val pins = AzpPublisherPins(tmp.newFile("pins4.json").also { it.delete() })
        install(pkg(id, author), pins)
        install(pkg(id, rotated), pins, allowPublisherChange = true)
        assertEquals(spkiB64(rotated), pins.keyFor(id))
    }

    @Test fun unsignedUpdateOfPinnedIdIsRejected() {
        val author = ed25519()
        val pins = AzpPublisherPins(tmp.newFile("pins5.json").also { it.delete() })
        install(pkg(id, author), pins)
        val ex = assertThrows(AzpModelInstall.PublisherChangedException::class.java) {
            install(pkg(id, null), pins)
        }
        assertNull(ex.newSignerKey)
    }

    @Test fun unsignedFirstInstallPinsNothing() {
        val pins = AzpPublisherPins(tmp.newFile("pins6.json").also { it.delete() })
        val result = install(pkg(id, null), pins)
        assertNull(result.signerPublicKey)
        assertNull(pins.keyFor(id))
        // A later unsigned install of the same id is still fine (nothing to enforce).
        assertTrue(install(pkg(id, null), pins).installed.isNotEmpty())
    }
}
