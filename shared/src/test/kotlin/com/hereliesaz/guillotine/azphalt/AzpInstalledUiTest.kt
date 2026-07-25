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
 * [AzpInstalledUi.list] must surface every current real catalog package: none of them ship a `ui`
 * schema (a static LUT/shader has nothing to adjust), and an earlier version of [AzpInstalledUi.list]
 * silently dropped exactly those packages — installed, verified, and permanently inert.
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
}
