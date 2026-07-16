package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests the platform-agnostic model-install planner: routing by role, bundled vs remote, checksum. */
class AzpModelInstallerTest {

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

    private fun modelManifest(assetJson: String, files: Map<String, String>): String {
        val filesJson = files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """
            {"azphalt":"0.1","id":"com.hereliesaz.model","name":"Model","version":"1.0.0","kind":"asset",
             "license":"MIT","compat":">=0.1","assets":[$assetJson],"files":{$filesJson}}
        """.trimIndent()
    }

    @Test fun plansBundledModelAndRoutesByRole() {
        val model = "ONNX-WEIGHTS".encodeToByteArray()
        val asset = """{"type":"onnx","path":"assets/seg.onnx","role":"subject-segmentation"}"""
        val azp = zip(
            mapOf(
                "manifest.json" to modelManifest(asset, mapOf("assets/seg.onnx" to AzpPackage.digest(model))).encodeToByteArray(),
                "assets/seg.onnx" to model,
            ),
        )
        val plan = AzpModelInstaller.plan(azp, emptySet())
        val m = plan.models.single()
        assertTrue(m.bundled)
        assertEquals(AzpModelInstaller.ModelSlot.SUBJECT_SEGMENTATION, m.slot)
        assertArrayEquals(model, AzpModelInstaller.bundledBytes(plan, m))
        // The role routes to the SUBJECT_SEGMENTATION slot (asserted above); models now load straight
        // from the on-disk registry (ModelResolver), not from an AiSettings path field.
    }

    @Test fun plansRemoteModelAndVerifiesChecksum() {
        val weights = "BIG-REMOTE-WEIGHTS".encodeToByteArray()
        val checksum = AzpPackage.digest(weights) // sha256-<hex>
        val asset = """{"type":"onnx","path":"","role":"image-labeling","remoteUrl":"https://ex.com/m.onnx","checksum":"$checksum","byteSize":${weights.size}}"""
        val azp = zip(mapOf("manifest.json" to modelManifest(asset, emptyMap()).encodeToByteArray()))
        val plan = AzpModelInstaller.plan(azp, emptySet())
        val m = plan.models.single()
        assertFalse(m.bundled)
        assertEquals("https://ex.com/m.onnx", m.remoteUrl)
        assertEquals(AzpModelInstaller.ModelSlot.IMAGE_LABELING, m.slot)
        // The host downloads the weights, then verifies before wiring.
        assertTrue(AzpModelInstaller.checksumMatches(weights, m.checksum))
        assertFalse(AzpModelInstaller.checksumMatches("tampered".encodeToByteArray(), m.checksum))
        assertFalse(AzpModelInstaller.checksumMatches(weights, null)) // no checksum ⇒ never trusted
    }

    @Test fun roleMapping() {
        assertEquals(AzpModelInstaller.ModelSlot.SPEECH_TO_TEXT, AzpModelInstaller.slotForRole("speech-to-text"))
        assertEquals(AzpModelInstaller.ModelSlot.FACE_DETECTION, AzpModelInstaller.slotForRole("Face-Detection"))
        assertEquals(AzpModelInstaller.ModelSlot.SUBJECT_SEGMENTATION, AzpModelInstaller.slotForRole("matting"))
        assertNull(AzpModelInstaller.slotForRole("something-unknown"))
        assertNull(AzpModelInstaller.slotForRole(null))
    }

    @Test fun ignoresNonModelAssets() {
        val lut = "CUBE-DATA".encodeToByteArray()
        val asset = """{"type":"lut","path":"assets/look.cube"}"""
        val azp = zip(
            mapOf(
                "manifest.json" to modelManifest(asset, mapOf("assets/look.cube" to AzpPackage.digest(lut))).encodeToByteArray(),
                "assets/look.cube" to lut,
            ),
        )
        assertTrue(AzpModelInstaller.plan(azp, emptySet()).models.isEmpty())
    }

    @Test fun rejectsIntegrityFailure() {
        val model = "m".encodeToByteArray()
        val asset = """{"type":"onnx","path":"assets/m.onnx","role":"image-labeling"}"""
        val azp = zip(
            mapOf(
                // digest deliberately wrong
                "manifest.json" to modelManifest(asset, mapOf("assets/m.onnx" to AzpPackage.digest("other".encodeToByteArray()))).encodeToByteArray(),
                "assets/m.onnx" to model,
            ),
        )
        try {
            AzpModelInstaller.plan(azp, emptySet())
            fail("expected AzpException")
        } catch (_: AzpPackage.AzpException) {
            // expected — a corrupt model must never install
        }
    }

    @Test fun unsignedPlansButFlaggedUntrusted() {
        val model = "m".encodeToByteArray()
        val asset = """{"type":"onnx","path":"assets/m.onnx","role":"image-embedding"}"""
        val azp = zip(
            mapOf(
                "manifest.json" to modelManifest(asset, mapOf("assets/m.onnx" to AzpPackage.digest(model))).encodeToByteArray(),
                "assets/m.onnx" to model,
            ),
        )
        val plan = AzpModelInstaller.plan(azp, emptySet())
        assertTrue(plan.trust.ok)
        assertFalse(plan.trust.signed)
        assertFalse(plan.trust.trusted) // host should warn before installing
        assertEquals(AzpModelInstaller.ModelSlot.IMAGE_EMBEDDING, plan.models.single().slot)
    }
}
