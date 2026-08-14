package com.hereliesaz.guillotine.azphalt

import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
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
import java.security.KeyStore
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Tests the platform-agnostic install executor: bundled write, routing, remote download + verify. */
class AzpModelInstallTest {

    @get:Rule val tmp = TemporaryFolder()

    private var server: HttpServer? = null

    // Captured before any test mutates them, restored after every test — installTo's https-only guard
    // (see downloadUrlMustBeHttps below) means exercising the real download path needs a real TLS
    // handshake, so these tests spin up a local HttpsServer with a throwaway self-signed cert and point
    // the JVM's default trust at it for the duration of the test only.
    private val originalSslSocketFactory = HttpsURLConnection.getDefaultSSLSocketFactory()
    private val originalHostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()

    @After fun stopServer() {
        server?.stop(0)
        HttpsURLConnection.setDefaultSSLSocketFactory(originalSslSocketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier(originalHostnameVerifier)
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(ZipEntry(name)); zout.write(data); zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun manifest(assetJson: String, files: Map<String, String>): ByteArray =
        manifestWithId("com.hereliesaz.model", assetJson, files)

    private fun manifestWithId(id: String, assetJson: String, files: Map<String, String>): ByteArray {
        val filesJson = files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """
            {"azphalt":"0.1","id":"$id","name":"Model","version":"1.0.0","kind":"asset",
             "license":"MIT","compat":">=0.1","assets":[$assetJson],"files":{$filesJson}}
        """.trimIndent().encodeToByteArray()
    }

    /** Serve [body] at `/model` over plain http and return its URL — only for the http-rejection test. */
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

    /** A throwaway self-signed [SSLContext] for `/model`'s server *and* the test client's trust. */
    private fun testSslContext(): SSLContext {
        val ks = KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/azp-model-install-test.p12")!!.use { ks.load(it, "changeit".toCharArray()) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, "changeit".toCharArray())
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks) // the keystore's own self-signed cert doubles as the truststore
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, tmf.trustManagers, null) }
    }

    /**
     * Serve [body] at `/model` over **https**, using a local throwaway self-signed cert, and points the
     * JVM's default `HttpsURLConnection` trust at that same cert so the production download code (which
     * configures no custom trust manager of its own — it relies on the platform default, exactly as a
     * real install does against a real CA-issued cert) can complete the handshake.
     */
    private fun serveHttps(body: ByteArray): String {
        val ctx = testSslContext()
        val s = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.httpsConfigurator = HttpsConfigurator(ctx)
        s.createContext("/model") { ex ->
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        HttpsURLConnection.setDefaultSSLSocketFactory(ctx.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { hostname, _ -> hostname == "127.0.0.1" }
        return "https://127.0.0.1:${s.address.port}/model"
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
        val url = serveHttps(weights)
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
        val url = serveHttps(served)
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

    // ---- filename-collision guard: a different package must not silently swap another's model ----

    @Test fun differentPackagesCannotSilentlyOverwriteEachOthersModelFile() {
        val filename = "shared-model.onnx"
        val dir = tmp.newFolder("models")

        val weightsA = "WEIGHTS-A".encodeToByteArray()
        val assetA = """{"type":"onnx","path":"assets/$filename","role":"image-labeling"}"""
        val azpA = zip(
            mapOf(
                "manifest.json" to manifestWithId("com.hereliesaz.model-a", assetA, mapOf("assets/$filename" to AzpPackage.digest(weightsA))),
                "assets/$filename" to weightsA,
            ),
        )
        AzpModelInstall.install(azpA, emptySet(), dir, allowUntrusted = true)

        val weightsB = "WEIGHTS-B".encodeToByteArray()
        val assetB = """{"type":"onnx","path":"assets/$filename","role":"image-labeling"}"""
        val azpB = zip(
            mapOf(
                "manifest.json" to manifestWithId("com.hereliesaz.model-b", assetB, mapOf("assets/$filename" to AzpPackage.digest(weightsB))),
                "assets/$filename" to weightsB,
            ),
        )
        try {
            AzpModelInstall.install(azpB, emptySet(), dir, allowUntrusted = true)
            fail("expected AzpException: a different package must not silently overwrite an installed model")
        } catch (e: AzpPackage.AzpException) {
            assertTrue(e.message!!.contains("different package"))
        }
        // The original package's weights are untouched.
        assertArrayEquals(weightsA, java.io.File(dir, filename).readBytes())
    }

    @Test fun sameIdReinstallingItsOwnModelFileIsStillAnOrdinaryUpdate() {
        val filename = "own-model.onnx"
        val dir = tmp.newFolder("models")
        val asset = """{"type":"onnx","path":"assets/$filename","role":"image-labeling"}"""

        val v1 = "V1-WEIGHTS".encodeToByteArray()
        AzpModelInstall.install(
            zip(mapOf("manifest.json" to manifestWithId("com.hereliesaz.own", asset, mapOf("assets/$filename" to AzpPackage.digest(v1))), "assets/$filename" to v1)),
            emptySet(), dir, allowUntrusted = true,
        )
        val v2 = "V2-WEIGHTS-NEWER".encodeToByteArray()
        AzpModelInstall.install(
            zip(mapOf("manifest.json" to manifestWithId("com.hereliesaz.own", asset, mapOf("assets/$filename" to AzpPackage.digest(v2))), "assets/$filename" to v2)),
            emptySet(), dir, allowUntrusted = true,
        )
        assertArrayEquals(v2, java.io.File(dir, filename).readBytes())
    }

    // ---- download hardening: https-only, no plaintext (CRITICAL: MITM can swap model weights) ----

    @Test fun plaintextHttpDownloadUrlIsRejectedOutright() {
        // The URL doesn't even need to resolve to a live server — this must be refused before any
        // connection attempt is made, exactly like a malformed URL would be.
        val asset = """{"type":"onnx","path":"","role":"image-labeling","remoteUrl":"http://127.0.0.1:1/model","checksum":"sha256-x"}"""
        val azp = zip(mapOf("manifest.json" to manifest(asset, emptyMap())))
        val dir = tmp.newFolder("models")
        try {
            AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true)
            fail("expected AzpException for a plaintext http remoteUrl")
        } catch (e: AzpPackage.AzpException) {
            assertTrue(e.message!!.contains("https"))
        }
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun downloadExceedingDeclaredByteSizeIsRejected() {
        // A server that keeps sending well past the manifest's declared byteSize (with no fixed
        // Content-Length the client could catch earlier) must be cut off, not allowed to fill the disk.
        val weights = ByteArray(50_000) { 7 }
        val url = serveHttps(weights)
        // Declare a byteSize far smaller than what the server actually sends.
        val asset = """{"type":"onnx","path":"","role":"image-labeling","remoteUrl":"$url","checksum":"sha256-x","byteSize":10}"""
        val azp = zip(mapOf("manifest.json" to manifest(asset, emptyMap())))
        val dir = tmp.newFolder("models")
        try {
            AzpModelInstall.install(azp, emptySet(), dir, allowUntrusted = true)
            fail("expected AzpException for exceeding the declared byteSize")
        } catch (e: AzpPackage.AzpException) {
            assertTrue(e.message!!.contains("MB"))
        }
    }
}
