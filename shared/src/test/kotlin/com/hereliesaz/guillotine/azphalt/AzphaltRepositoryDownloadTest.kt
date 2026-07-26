package com.hereliesaz.guillotine.azphalt

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetSocketAddress

/**
 * [AzphaltRepository.download] must reject a non-ZIP HTTP-200 body with a clear, upstream-pointing
 * error — the exact shape a wrong base URL produces (an SPA's `index.html` served from a catch-all
 * route for a path the real API doesn't have). Before this guard, that body reached [AzpPackage] and
 * failed as a confusing "manifest.json is missing", with no hint the real problem was the registry
 * URL, not the package.
 */
class AzphaltRepositoryDownloadTest {

    private var server: HttpServer? = null

    @After fun stopServer() { server?.stop(0) }

    private fun repositoryServing(body: ByteArray, contentType: String): AzphaltRepository {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/packages") { ex ->
            ex.responseHeaders.add("Content-Type", contentType)
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        s.start()
        server = s
        return AzphaltRepository(baseUrl = "http://127.0.0.1:${s.address.port}")
    }

    @Test fun rejectsHtmlSpaFallback() {
        val html = "<!DOCTYPE html><html><head><title>Azphalt Storefront</title></head></html>".encodeToByteArray()
        val repo = repositoryServing(html, "text/html; charset=utf-8")
        try {
            repo.download("com.example.pkg", "1.0.0")
            fail("expected RepoException for an HTML body")
        } catch (e: AzphaltRepository.RepoException) {
            assertTrue(e.message?.contains("did not return a package") == true)
            assertTrue(e.message?.contains("HTML") == true)
        }
    }

    @Test fun rejectsJsonErrorEnvelope() {
        val json = """{"error":{"code":"not_found","message":"not found"}}""".encodeToByteArray()
        val repo = repositoryServing(json, "application/json; charset=utf-8")
        try {
            repo.download("com.example.pkg", "1.0.0")
            fail("expected RepoException for a JSON body")
        } catch (e: AzphaltRepository.RepoException) {
            assertTrue(e.message?.contains("did not return a package") == true)
        }
    }

    @Test fun acceptsRealZipBytes() {
        // A minimal but real ZIP local-file-header + end-of-central-directory pair is enough to pass
        // the "PK" sniff — full manifest/integrity verification is AzpPackage's job, not download()'s.
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bos).use { zout ->
            zout.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zout.write("{}".encodeToByteArray())
            zout.closeEntry()
        }
        val repo = repositoryServing(bos.toByteArray(), "application/x-azphalt")
        val bytes = repo.download("com.example.pkg", "1.0.0")
        assertTrue(bytes.isNotEmpty())
    }
}
