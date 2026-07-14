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
}
