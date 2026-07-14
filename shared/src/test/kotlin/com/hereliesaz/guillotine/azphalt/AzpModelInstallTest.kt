package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.ai.AiSettings
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Tests the platform-agnostic install executor: bundled write, routing, remote download + verify. */
class AzpModelInstallTest {

    @get:Rule val tmp = TemporaryFolder()

    private var server: HttpServer? = null

    @After fun stopServer() { server?.stop(0) }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(ZipEntry(name)); zout.write(data); zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun manifest(assetJson: String, files: Map<String, String>): ByteArray {
        val filesJson = files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """
            {"azphalt":"0.1","id":"com.hereliesaz.model","name":"Model","version":"1.0.0","kind":"asset",
             "license":"MIT","compat":">=0.1","assets":[$assetJson],"files":{$filesJson}}
        """.trimIndent().encodeToByteArray()
    }

    /** Serve [body] at `/model` and return its URL. */
    private fun serve(body: ByteArray): String {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/model") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return "http://127.0.0.1:${s.address.port}/model"
    }

    @Test fun installsBundledModelAndRoutesToSettings() {
        val weights = "ONNX-SEG-WEIGHTS".encodeToByteArray()
        val asset = """{"type":"onnx","path":"assets/seg.onnx","role":"subject-segmentation"}"""
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(asset, mapOf("assets/seg.onnx" to AzpPackage.digest(weights))),
                "assets/seg.onnx" to weights,
            ),
        )
        val dir = tmp.newFolder("models")
        val result = AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true)

        val one = result.installed.single()
        assertEquals(AzpModelInstaller.ModelSlot.SUBJECT_SEGMENTATION, one.slot)
        val onDisk = java.io.File(one.path)
        assertTrue(onDisk.isFile)
        assertArrayEquals(weights, onDisk.readBytes())
        // The routed slot is folded into settings for persistence.
        val settings = result.applyTo(AiSettings())
        assertEquals(one.path, settings.segModelPath)
        // No `.part` files linger.
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun untrustedRequiresConsent() {
        val weights = "w".encodeToByteArray()
        val asset = """{"type":"onnx","path":"assets/m.onnx","role":"image-labeling"}"""
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(asset, mapOf("assets/m.onnx" to AzpPackage.digest(weights))),
                "assets/m.onnx" to weights,
            ),
        )
        val dir = tmp.newFolder("models")
        try {
            AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = false)
            fail("expected UntrustedException for an unsigned package")
        } catch (e: AzpModelInstall.UntrustedException) {
            assertFalse(e.trust.trusted)
        }
        // With consent it installs.
        assertEquals(1, AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true).installed.size)
    }

    @Test fun downloadsRemoteModelAndVerifiesChecksum() {
        val weights = ByteArray(200_000) { (it % 251).toByte() } // > one buffer, exercises streaming
        val url = serve(weights)
        val checksum = AzpPackage.digest(weights)
        val asset =
            """{"type":"onnx","path":"","role":"speech-to-text","remoteUrl":"$url","checksum":"$checksum","byteSize":${weights.size}}"""
        val azp = zip(mapOf("manifest.json" to manifest(asset, emptyMap())))
        val dir = tmp.newFolder("models")

        var sawDownloading = false
        val result = AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true) { p ->
            if (p.phase == AzpModelInstall.Phase.DOWNLOADING) sawDownloading = true
        }
        val one = result.installed.single()
        assertEquals(AzpModelInstaller.ModelSlot.SPEECH_TO_TEXT, one.slot)
        assertArrayEquals(weights, java.io.File(one.path).readBytes())
        assertTrue(sawDownloading)
    }

    @Test fun rejectsRemoteChecksumMismatch() {
        val served = "REAL-BYTES".encodeToByteArray()
        val url = serve(served)
        val wrong = AzpPackage.digest("DIFFERENT".encodeToByteArray())
        val asset = """{"type":"onnx","path":"","role":"image-labeling","remoteUrl":"$url","checksum":"$wrong"}"""
        val azp = zip(mapOf("manifest.json" to manifest(asset, emptyMap())))
        val dir = tmp.newFolder("models")
        try {
            AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true)
            fail("expected AzpException for checksum mismatch")
        } catch (e: AzpPackage.AzpException) {
            assertTrue(e.message!!.contains("checksum mismatch"))
        }
        // Nothing is left behind on a rejected install.
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".part") || it.name.endsWith(".onnx") })
    }

    @Test fun sherpaBundleExtractsToDirectory() {
        val innerModel = "TOKENS".encodeToByteArray()
        val bundle = zip(mapOf("tokens.txt" to innerModel, "model.onnx" to "NET".encodeToByteArray()))
        val asset = """{"type":"sherpa-bundle","path":"assets/asr.zip","role":"speech-to-text"}"""
        val azp = zip(
            mapOf(
                "manifest.json" to manifest(asset, mapOf("assets/asr.zip" to AzpPackage.digest(bundle))),
                "assets/asr.zip" to bundle,
            ),
        )
        val dir = tmp.newFolder("models")
        val one = AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true).installed.single()
        val outDir = java.io.File(one.path)
        assertTrue(outDir.isDirectory)
        assertArrayEquals(innerModel, java.io.File(outDir, "tokens.txt").readBytes())
    }

    @Test fun extractZipRejectsZipSlip() {
        val evil = zip(mapOf("../escape.txt" to "x".encodeToByteArray()))
        val dir = tmp.newFolder("out")
        try {
            AzpModelInstall.extractZipBytes(evil, dir)
            fail("expected AzpException for zip-slip entry")
        } catch (e: AzpPackage.AzpException) {
            assertTrue(e.message!!.contains("escapes target dir"))
        }
    }
}
