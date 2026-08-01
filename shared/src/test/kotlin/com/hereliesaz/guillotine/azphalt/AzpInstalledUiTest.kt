package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [AzpInstalledUi.list] must surface a package whose asset ships no `ui` schema (a static LUT has
 * nothing to adjust), because an earlier version dropped exactly those — installed, verified, and
 * permanently inert. It must equally **not** surface the asset types other subsystems own.
 */
class AzpInstalledUiTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(ZipEntry(name)); zout.write(data); zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun manifest(id: String, assetPath: String, files: Map<String, String>): ByteArray = """
        {"azphalt":"0.1","id":"$id","name":"Test LUT","version":"1.0.0","kind":"asset","license":"MIT",
         "compat":">=0.1",
         "files":{${files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}},
         "assets":[{"type":"lut","path":"$assetPath"}]}
    """.trimIndent().encodeToByteArray()

    @Test fun `asset with no ui schema still gets a panel`() {
        val id = "com.hereliesaz.guillotine.no-ui-lut"
        val lut = "some lut bytes".encodeToByteArray()
        val manifestBytes = manifest(id, "assets/test.cube", mapOf("assets/test.cube" to AzpPackage.digest(lut)))
        val azp = zip(mapOf("manifest.json" to manifestBytes, "assets/test.cube" to lut))
        val dir = tmp.newFolder("ext")
        java.io.File(dir, "$id.azp").writeBytes(azp)

        val panels = AzpInstalledUi.list(dir, hostAppId = "com.hereliesaz.guillotine")

        assertEquals(1, panels.size)
        val panel = panels.single()
        assertEquals(id, panel.packageId)
        assertEquals(AzpInstalledUi.RenderKind.LUT, panel.renderKind)
        assertTrue("a schema-less asset should still get an (empty) applicable panel", panel.schema.controls.isEmpty())
        assertTrue(panel.defaultParams.isEmpty())
    }

    private fun typedManifest(id: String, type: String, files: Map<String, String>): ByteArray = """
        {"azphalt":"0.1","id":"$id","name":"Typed","version":"1.0.0","kind":"asset","license":"MIT",
         "compat":">=0.1",
         "files":{${files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }}},
         "assets":[{"type":"$type","path":"assets/a.bin"}]}
    """.trimIndent().encodeToByteArray()

    @Test fun `assets owned by other subsystems are not listed as clip panels`() {
        // A motion package is the kinetic-type picker's, and a model package is the AI-model flow's.
        // Listing them here drew a second clip-panel section claiming applying "arrives with the
        // extension runtime" — false for motion, whose runtime ships and works, and which is most of
        // the live catalog.
        val dir = tmp.newFolder("ext")
        val payload = "x".encodeToByteArray()
        for ((i, type) in listOf("motion", "onnx", "tflite", "litert", "sherpa-bundle").withIndex()) {
            val files = mapOf("assets/a.bin" to AzpPackage.digest(payload))
            dir.resolve("p$i.azp").writeBytes(
                zip(mapOf("manifest.json" to typedManifest("com.example.p$i", type, files), "assets/a.bin" to payload)),
            )
        }
        assertEquals(emptyList<AzpInstalledUi.Panel>(), AzpInstalledUi.list(dir, "com.hereliesaz.guillotine"))
    }

    @Test fun `an unrecognised asset type is still listed`() {
        // Not owned by anything else, so the clip panel is where it belongs — it just can't be applied.
        val dir = tmp.newFolder("ext2")
        val payload = "x".encodeToByteArray()
        dir.resolve("f.azp").writeBytes(
            zip(
                mapOf(
                    "manifest.json" to typedManifest("com.example.font", "font", mapOf("assets/a.bin" to AzpPackage.digest(payload))),
                    "assets/a.bin" to payload,
                ),
            ),
        )
        val panels = AzpInstalledUi.list(dir, "com.hereliesaz.guillotine")
        assertEquals(1, panels.size)
        assertEquals(AzpInstalledUi.RenderKind.OTHER, panels[0].renderKind)
    }
}
