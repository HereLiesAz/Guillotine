package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Parsing/scoping tests for the [AzphaltRepository] client. The JSON samples mirror the response
 * shape `GET https://www.azphalt.store/api/packages` happens to return (a bare array of summaries —
 * that's the Next.js storefront's own internal catalog fetch, NOT the Repository API) as well as the
 * spec's `{ "packages": [...] }` envelope the real (no-`/api`) root returns, so the client tolerates
 * both regardless of which one a given base URL serves. Network calls aren't exercised here — only
 * the pure parse + host-scoping logic; see [AzphaltRepositoryDownloadTest] for the download guard.
 */
class AzphaltRepositoryTest {

    private val repo = AzphaltRepository()
    private val hostId = "com.hereliesaz.guillotine"

    /** A trimmed but faithful copy of the live catalog: a free asset, a paid code plugin, a scoped app. */
    private val liveArray = """
        [
          {"id":"com.azphalt.model.sherpa-onnx","name":"Sherpa-ONNX Whisper Base",
           "description":"Local Whisper Base model.","author":"Azphalt Models","version":"1.0.0",
           "kind":"asset","capabilities":["assets"],"downloads":8900,"ratingCount":0,
           "updatedAt":"2026-07-20T01:13:18.420Z","targetApps":[],"mediaDomains":["model"],
           "types":["model"],"price":null},
          {"id":"com.hereliesaz.halftone","name":"Halftone Studio","description":"A halftone filter.",
           "author":"Az","version":"1.2.0","kind":"code","capabilities":["bitmap","params"],
           "downloads":2140,"rating":4.5,"ratingCount":10,"targetApps":[],"mediaDomains":["image"],
           "types":[],"price":{"amountCents":600,"currency":"USD"}},
          {"id":"com.acme.ar-stencil-capture","name":"AR Stencil Capture","description":"A companion app.",
           "author":"Acme AR","version":"1.0.0","kind":"app","capabilities":[],"downloads":760,
           "rating":4.5,"ratingCount":4,"targetApps":["com.hereliesaz.graffitixr"],"price":null}
        ]
    """.trimIndent()

    @Test fun parsesLiveBareArray() {
        val pkgs = repo.parseCatalog(liveArray)
        assertEquals(3, pkgs.size)

        val model = pkgs[0]
        assertEquals("com.azphalt.model.sherpa-onnx", model.id)
        assertEquals("asset", model.kind)
        assertTrue("null price ⇒ free", model.isFree)
        assertEquals(8900, model.downloads)

        val paid = pkgs[1]
        assertFalse("has a price ⇒ not free", paid.isFree)
        assertEquals(600, paid.price?.amountCents)
        assertEquals("USD", paid.price?.currency)
        assertEquals(4.5, paid.rating!!, 0.001)

        val app = pkgs[2]
        assertEquals("app", app.kind)
        assertEquals(listOf("com.hereliesaz.graffitixr"), app.targetApps)
    }

    @Test fun parsesSpecEnvelope() {
        val envelope = """{"packages":$liveArray,"total":3,"page":1,"pages":1}"""
        assertEquals(3, repo.parseCatalog(envelope).size)
    }

    @Test fun toleratesMissingOptionalFields() {
        // Only `id` present — everything else must default rather than fail to parse.
        val pkg = repo.parseCatalog("""[{"id":"com.example.minimal"}]""").single()
        assertEquals("com.example.minimal", pkg.id)
        assertEquals("com.example.minimal", pkg.name) // name defaults to id
        assertEquals("asset", pkg.kind)
        assertTrue(pkg.isFree)
        assertTrue(pkg.targetApps.isEmpty())
    }

    @Test fun rejectsNonJsonBody() {
        try {
            repo.parseCatalog("<!DOCTYPE html><html><head><title>Azphalt Storefront</title>")
            fail("expected RepoException for an HTML body")
        } catch (e: AzphaltRepository.RepoException) {
            // expected — an SPA shell is not a catalog
        }
    }

    @Test fun scopesToHost() {
        val pkgs = repo.parseCatalog(liveArray)
        // Only the app is scoped to another host (graffitixr); model + halftone are global.
        val scoped = repo.scopeToHost(pkgs, hostId).map { it.id }
        assertTrue(scoped.contains("com.azphalt.model.sherpa-onnx"))
        assertTrue(scoped.contains("com.hereliesaz.halftone"))
        assertFalse("package scoped to another host must be hidden", scoped.contains("com.acme.ar-stencil-capture"))

        // A blank host id disables scoping (test/harness behavior).
        assertEquals(3, repo.scopeToHost(pkgs, "").size)
    }

    @Test fun defaultBaseUrlIsFlagshipRegistry() {
        // No `/api` — that's the storefront's own internal namespace, not the Repository API root.
        // Verified live: with `/api`, download()/`.well-known` fall through the SPA and return
        // index.html; without it, they return the real package bytes / registry document.
        assertEquals("https://www.azphalt.store", AzphaltRepository.DEFAULT_BASE_URL)
    }
}
