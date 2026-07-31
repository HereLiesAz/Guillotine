package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the azphalt `spec/extension-manifest.md` (format `0.1`) fields added since the host's first
 * cut: the `app` / `mcp` kinds and the `targetApps` / `preview` / `app` / `mcp` manifest fields, plus
 * the manifest-level `targetsApp` scoping helper.
 */
class AzpManifestSpecTest {

    private val hostId = "com.hereliesaz.guillotine"

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

    /** A minimal valid `.azp` for [manifestJson], digesting every entry in [payload]. */
    private fun azp(manifestJson: (Map<String, String>) -> String, payload: Map<String, ByteArray>): ByteArray {
        val digests = payload.mapValues { AzpPackage.digest(it.value) }
        return zip(payload + ("manifest.json" to manifestJson(digests).encodeToByteArray()))
    }

    @Test fun parsesTargetAppsAndPreview() {
        val png = byteArrayOf(1, 2, 3, 4)
        val azp = azp(
            { d ->
                """
                {"azphalt":"0.1","id":"com.hereliesaz.lut","name":"LUT","version":"1.0.0",
                 "kind":"asset","license":"MIT","compat":">=0.1",
                 "targetApps":["$hostId"],
                 "preview":{"image":"assets/card.png"},
                 "assets":[{"type":"lut","path":"assets/x.cube"}],
                 "files":{"assets/card.png":"${d["assets/card.png"]}","assets/x.cube":"${d["assets/x.cube"]}"}}
                """.trimIndent()
            },
            mapOf("assets/card.png" to png, "assets/x.cube" to "TITLE".encodeToByteArray()),
        )
        val m = AzpPackage.load(azp).manifest
        assertEquals(listOf(hostId), m.targetApps)
        assertEquals("assets/card.png", m.preview?.image)
        assertNull(m.preview?.clip)
        assertTrue(m.targetsApp(hostId))
        assertFalse(m.targetsApp("com.example.other"))
    }

    @Test fun globalPackageTargetsEveryHost() {
        val azp = azp(
            { d ->
                """
                {"azphalt":"0.1","id":"com.hereliesaz.global","name":"G","version":"1.0.0",
                 "kind":"asset","license":"MIT","compat":">=0.1",
                 "assets":[{"type":"lut","path":"assets/x.cube"}],
                 "files":{"assets/x.cube":"${d["assets/x.cube"]}"}}
                """.trimIndent()
            },
            mapOf("assets/x.cube" to "TITLE".encodeToByteArray()),
        )
        val m = AzpPackage.load(azp).manifest
        assertTrue(m.targetApps.isEmpty())
        assertTrue(m.targetsApp(hostId))
        assertTrue(m.targetsApp("anything"))
    }

    @Test fun parsesAppAndMcpKinds() {
        val appAzp = azp(
            { d ->
                """
                {"azphalt":"0.1","id":"com.hereliesaz.companion","name":"C","version":"1.0.0",
                 "kind":"app","license":"MIT","compat":">=0.1",
                 "app":{"platforms":{"android":{"packageId":"com.hereliesaz.companion"}},"handoffs":[]},
                 "files":{"LICENSE":"${d["LICENSE"]}"}}
                """.trimIndent()
            },
            mapOf("LICENSE" to "MIT".encodeToByteArray()),
        )
        val appM = AzpPackage.load(appAzp).manifest
        assertTrue(appM.isApp)
        assertFalse(appM.isAsset)
        assertTrue(appM.app != null)

        val mcpAzp = azp(
            { d ->
                """
                {"azphalt":"0.1","id":"com.hereliesaz.srv","name":"S","version":"1.0.0",
                 "kind":"mcp","license":"MIT","compat":">=0.1",
                 "mcp":{"remote":{"url":"https://example.com/sse"}},
                 "files":{"LICENSE":"${d["LICENSE"]}"}}
                """.trimIndent()
            },
            mapOf("LICENSE" to "MIT".encodeToByteArray()),
        )
        val mcpM = AzpPackage.load(mcpAzp).manifest
        assertTrue(mcpM.isMcp)
        assertTrue(mcpM.mcp != null)
    }
}
