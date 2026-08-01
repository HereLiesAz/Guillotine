package com.hereliesaz.guillotine.azphalt

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The inventory a host hands a store app (azphalt `spec/state-reporting.md` § 3.1). Two things are being
 * protected here: that the store gets enough to say *Open* instead of *Get*, and that § 2's normative
 * "MUST NOT contain" list is honoured — no identifiers of any kind, nothing the user made.
 */
class AzpStateReportTest {

    @get:Rule val tmp = TemporaryFolder()

    private val host = "com.hereliesaz.guillotine"

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for ((n, d) in entries) { z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() }
        }
        return bos.toByteArray()
    }

    private fun install(dir: java.io.File, id: String, version: String = "1.0.0", targetApps: String = "") {
        val payload = "x".encodeToByteArray()
        val apps = if (targetApps.isEmpty()) "" else ""","targetApps":[$targetApps]"""
        val manifest = """
            {"azphalt":"0.1","id":"$id","name":"$id","version":"$version","kind":"asset","license":"MIT",
             "compat":">=0.1"$apps,"assets":[{"type":"lut","path":"assets/a.cube"}],
             "files":{"assets/a.cube":"${AzpPackage.digest(payload)}"}}
        """.trimIndent().encodeToByteArray()
        dir.resolve("$id.azp").writeBytes(zip(mapOf("manifest.json" to manifest, "assets/a.cube" to payload)))
    }

    @Test fun reportsIdVersionAndState() {
        val dir = tmp.newFolder("ext")
        install(dir, "com.example.one", "1.2.0")
        val entries = AzpStateReport.entries(dir, host)
        assertEquals(1, entries.size)
        assertEquals("com.example.one", entries[0].id)
        assertEquals("1.2.0", entries[0].version)
        // Guillotine has no disabled state, so anything installed is available right now.
        assertEquals("active", entries[0].state)
    }

    @Test fun packagesForOtherHostsAreNotReported() {
        val dir = tmp.newFolder("ext")
        install(dir, "com.example.mine")
        install(dir, "com.example.theirs", targetApps = "\"com.other.editor\"")
        assertEquals(listOf("com.example.mine"), AzpStateReport.entries(dir, host).map { it.id })
    }

    @Test fun theDocumentCarriesNothingItMustNot() {
        // § 2 is normative and this is the test that would catch a well-meaning addition of, say, a
        // device id "just to dedupe". The document must contain only the four permitted keys.
        val dir = tmp.newFolder("ext")
        install(dir, "com.example.one")
        val json = JSONObject(AzpStateReport.inventoryJson(dir, host)!!)
        val entry = json.getJSONArray("entries").getJSONObject(0)
        assertEquals(setOf("id", "version", "state", "at"), entry.keys().asSequence().toSet())
        // And nothing anywhere in the document that looks like a path or a host package name.
        val raw = AzpStateReport.inventoryJson(dir, host)!!
        assertTrue("must not leak a filesystem path", !raw.contains(dir.absolutePath))
        assertTrue("must not name the host app", !raw.contains(host))
    }

    @Test fun nothingInstalledSendsNoDocumentAtAll() {
        // Conforming, and cheaper than spending Binder budget to say "I have nothing".
        assertNull(AzpStateReport.inventoryJson(tmp.newFolder("empty"), host))
    }

    @Test fun invalidPackagesAreSkippedRatherThanFailingTheWholeInventory() {
        val dir = tmp.newFolder("ext")
        install(dir, "com.example.good")
        dir.resolve("broken.azp").writeBytes("not a zip".encodeToByteArray())
        assertEquals(listOf("com.example.good"), AzpStateReport.entries(dir, host).map { it.id })
    }

    @Test fun theEntryCapIsHonoured() {
        val dir = tmp.newFolder("ext")
        repeat(AzpStateReport.MAX_ENTRIES + 25) { install(dir, "com.example.p$it") }
        assertEquals(AzpStateReport.MAX_ENTRIES, AzpStateReport.entries(dir, host).size)
    }

    @Test fun theDocumentStaysUnderTheBinderCap() {
        val dir = tmp.newFolder("ext")
        repeat(AzpStateReport.MAX_ENTRIES) { install(dir, "com.example.${"p".repeat(60)}$it") }
        val json = AzpStateReport.inventoryJson(dir, host)!!
        assertTrue(
            "inventory is ${json.toByteArray().size} bytes, over the ${AzpStateReport.MAX_BYTES} cap",
            json.toByteArray().size <= AzpStateReport.MAX_BYTES,
        )
    }

    @Test fun theJsonShapeIsTheSpecsEnvelope() {
        val json = JSONObject(AzpStateReport.toJson(listOf(AzpStateReport.Entry("a", "1", "active", null))))
        assertTrue(json.has("entries"))
        assertEquals(1, json.getJSONArray("entries").length())
        // `at` is optional and omitted rather than sent as null.
        assertTrue(!json.getJSONArray("entries").getJSONObject(0).has("at"))
    }
}
