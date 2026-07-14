package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Pure-JVM tests for the azphalt `.azp` loader/verifier — mirrors `@azphalt/azp` verify semantics. */
class AzpPackageTest {

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(ZipEntry(name))
                zout.write(data)
                zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun manifest(files: Map<String, String>): String {
        val filesJson = files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """
            {"azphalt":"0.1","id":"com.hereliesaz.test","name":"Test","version":"1.0.0",
             "kind":"code","license":"MIT","compat":">=0.1","entry":"code/main.js","runtime":"js",
             "capabilities":["canvas","bitmap","params"],"files":{$filesJson}}
        """.trimIndent()
    }

    @Test fun loadsAValidPackage() {
        val code = "export function applyHalftone(){}".encodeToByteArray()
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(mapOf("code/main.js" to AzpPackage.digest(code))).encodeToByteArray(),
                "code/main.js" to code,
            ),
        )
        assertTrue(AzpPackage.verify(azp).isEmpty())
        val loaded = AzpPackage.load(azp)
        assertEquals("com.hereliesaz.test", loaded.manifest.id)
        assertEquals("js", loaded.manifest.runtime)
        assertTrue(loaded.manifest.isCode)
        assertArrayEquals(code, loaded.payload["code/main.js"])
    }

    @Test fun rejectsDigestMismatch() {
        val code = "real".encodeToByteArray()
        val wrong = AzpPackage.digest("different".encodeToByteArray())
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(mapOf("code/main.js" to wrong)).encodeToByteArray(),
                "code/main.js" to code,
            ),
        )
        assertTrue(AzpPackage.verify(azp).any { it.contains("digest mismatch") })
    }

    @Test fun rejectsUnlistedPayload() {
        val code = "x".encodeToByteArray()
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(mapOf("code/main.js" to AzpPackage.digest(code))).encodeToByteArray(),
                "code/main.js" to code,
                "code/sneaky.js" to "y".encodeToByteArray(), // present but not in manifest.files
            ),
        )
        assertTrue(AzpPackage.verify(azp).any { it.contains("unlisted payload") })
    }

    @Test fun rejectsUnsafePath() {
        val data = "x".encodeToByteArray()
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(mapOf("../evil.js" to AzpPackage.digest(data))).encodeToByteArray(),
                "../evil.js" to data,
            ),
        )
        assertTrue(AzpPackage.verify(azp).any { it.contains("unsafe path") })
    }

    @Test fun rejectsColonPath() {
        // Windows drive-letter / NTFS ADS names must not slip past the `..` check.
        val data = "x".encodeToByteArray()
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(mapOf("C:evil.js" to AzpPackage.digest(data))).encodeToByteArray(),
                "C:evil.js" to data,
            ),
        )
        assertTrue(AzpPackage.verify(azp).any { it.contains("unsafe path") })
    }

    @Test fun rejectsMissingManifest() {
        val azp = zip(mapOf("code/main.js" to "x".encodeToByteArray()))
        assertFalse(AzpPackage.isValid(azp))
    }

    @Test fun loadThrowsOnInvalid() {
        val azp = zip(mapOf("code/main.js" to "x".encodeToByteArray()))
        try {
            AzpPackage.load(azp)
            fail("expected AzpException")
        } catch (_: AzpPackage.AzpException) {
            // expected
        }
    }

    @Test fun digestMatchesAzphaltForm() {
        val d = AzpPackage.digest("abc".encodeToByteArray())
        assertTrue(d.startsWith("sha256-"))
        assertEquals(64, d.removePrefix("sha256-").length)
        // Known SHA-256("abc"); mirrors @azphalt/azp's `sha256-<lowercase-hex>` form.
        assertEquals("sha256-ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", d)
    }

    // ---- signing / trust (mirrors @azphalt/azp signAzp / verifyTrust / countersign) ----

    private fun ed25519Key(): java.security.KeyPair =
        java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun b64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

    private fun sign(kp: java.security.KeyPair, message: ByteArray): ByteArray =
        java.security.Signature.getInstance("Ed25519").run { initSign(kp.private); update(message); sign() }

    /** SPKI (X.509) DER of an Ed25519 public key — the form azphalt stores as base64. */
    private fun spki(kp: java.security.KeyPair): ByteArray = kp.public.encoded

    /** A `signature.json` for [manifestBytes] signed by [author], optionally carrying [countersig]. */
    private fun signatureJson(author: java.security.KeyPair, manifestBytes: ByteArray, countersig: String? = null): String {
        val pub = b64(spki(author))
        val sig = b64(sign(author, manifestBytes))
        val cs = if (countersig != null) ",\"countersignature\":$countersig" else ""
        return """{"alg":"ed25519","publicKey":"$pub","signature":"$sig"$cs}"""
    }

    /** A counter-signature JSON: [registry] vouches for [vouchedFor] by signing its SPKI DER bytes. */
    private fun countersignatureJson(registry: java.security.KeyPair, vouchedFor: java.security.KeyPair): String {
        val pub = b64(spki(registry))
        val sig = b64(sign(registry, spki(vouchedFor)))
        return """{"publicKey":"$pub","signature":"$sig"}"""
    }

    private fun signedPackage(author: java.security.KeyPair, countersig: String? = null): ByteArray {
        val code = "export function f(){}".encodeToByteArray()
        val manifestBytes = manifest(mapOf("code/main.js" to AzpPackage.digest(code))).encodeToByteArray()
        return zip(
            mapOf(
                "manifest.json" to manifestBytes,
                "code/main.js" to code,
                "signature.json" to signatureJson(author, manifestBytes, countersig).encodeToByteArray(),
            ),
        )
    }

    @Test fun signedPackagePassesIntegrity() {
        // signature.json must be exempt from the completeness check.
        assertTrue(AzpPackage.verify(signedPackage(ed25519Key())).isEmpty())
    }

    @Test fun signatureStatusValid() {
        val author = ed25519Key()
        val s = AzpPackage.signatureStatus(signedPackage(author))
        assertTrue(s.signed)
        assertTrue(s.valid)
        assertEquals(b64(spki(author)), s.signerPublicKey)
    }

    @Test fun signatureStatusDetectsTampering() {
        val author = ed25519Key()
        val code = "export function f(){}".encodeToByteArray()
        val realManifest = manifest(mapOf("code/main.js" to AzpPackage.digest(code))).encodeToByteArray()
        val sig = signatureJson(author, realManifest) // signs the real bytes
        // Ship manifest bytes different from what was signed (trailing space — still valid JSON,
        // digests still cover code/main.js, so integrity passes but the signature must not).
        val tampered = (realManifest.decodeToString() + " ").encodeToByteArray()
        val azp = zip(
            mapOf(
                "manifest.json" to tampered,
                "code/main.js" to code,
                "signature.json" to sig.encodeToByteArray(),
            ),
        )
        assertTrue(AzpPackage.verify(azp).isEmpty()) // integrity intact
        val s = AzpPackage.signatureStatus(azp)
        assertTrue(s.signed)
        assertFalse(s.valid) // signature no longer matches the shipped manifest
    }

    @Test fun trustDirect() {
        val author = ed25519Key()
        val r = AzpPackage.verifyTrust(signedPackage(author), setOf(b64(spki(author))))
        assertTrue(r.ok)
        assertTrue(r.signed)
        assertTrue(r.trusted)
    }

    @Test fun trustRejectsUnknownSigner() {
        val author = ed25519Key()
        val stranger = ed25519Key()
        val r = AzpPackage.verifyTrust(signedPackage(author), setOf(b64(spki(stranger))))
        assertTrue(r.ok) // integrity + valid signature
        assertFalse(r.trusted) // but signer not in the store
    }

    @Test fun trustViaCountersignature() {
        val author = ed25519Key()
        val registry = ed25519Key()
        val azp = signedPackage(author, countersig = countersignatureJson(registry, author))
        val r = AzpPackage.verifyTrust(azp, setOf(b64(spki(registry)))) // trust the registry, not the author
        assertTrue(r.ok)
        assertTrue(r.trusted)
        assertEquals(b64(spki(registry)), r.viaRegistryPublicKey)
    }

    @Test fun trustUnsignedIsOkButUntrusted() {
        val code = "x".encodeToByteArray()
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(mapOf("code/main.js" to AzpPackage.digest(code))).encodeToByteArray(),
                "code/main.js" to code,
            ),
        )
        val r = AzpPackage.verifyTrust(azp, emptySet())
        assertTrue(r.ok)
        assertFalse(r.signed)
        assertFalse(r.trusted)
    }
}
