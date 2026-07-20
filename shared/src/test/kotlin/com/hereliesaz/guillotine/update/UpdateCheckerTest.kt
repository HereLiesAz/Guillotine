package com.hereliesaz.guillotine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private val sampleJson = """
        {
          "tag_name": "latest-release-v0.9",
          "name": "Latest Release v0.9 (0.9.463.205921353)",
          "body": "Release build from commit abc123",
          "html_url": "https://github.com/HereLiesAz/Guillotine/releases/tag/latest-release-v0.9",
          "assets": [
            { "name": "Guillotine-0.9.463.205921353-release.apk",
              "browser_download_url": "https://example/Guillotine.apk", "size": 12345 },
            { "name": "guillotine_1.9.463-1_amd64.deb",
              "browser_download_url": "https://example/guillotine.deb", "size": 6789 },
            { "name": "Guillotine-1.9.463.msi",
              "browser_download_url": "https://example/Guillotine.msi", "size": 4242 },
            { "name": "Guillotine-1.9.463.dmg",
              "browser_download_url": "https://example/Guillotine.dmg", "size": 2424 }
          ]
        }
    """.trimIndent()

    @Test fun parsesReleaseMetadataAndAssets() {
        val r = UpdateChecker.parseRelease(sampleJson)
        assertEquals("latest-release-v0.9", r.tag)
        assertEquals("https://github.com/HereLiesAz/Guillotine/releases/tag/latest-release-v0.9", r.htmlUrl)
        assertEquals(4, r.assets.size)
        val apk = r.asset { it.endsWith(".apk") }
        assertEquals("Guillotine-0.9.463.205921353-release.apk", apk?.name)
        assertEquals("https://example/Guillotine.apk", apk?.downloadUrl)
        assertEquals(12345L, apk?.sizeBytes)
    }

    @Test fun parseToleratesMissingAssets() {
        val r = UpdateChecker.parseRelease("""{"tag_name":"t","name":"n"}""")
        assertEquals("t", r.tag)
        assertTrue(r.assets.isEmpty())
        assertNull(r.asset { true })
    }

    @Test fun versionInPrefersLongestForm() {
        assertEquals("0.9.463.205921353", UpdateChecker.versionIn("Guillotine-0.9.463.205921353-release.apk"))
        assertEquals("1.9.463", UpdateChecker.versionIn("guillotine_1.9.463-1_amd64.deb"))
        assertEquals("1.9.463", UpdateChecker.versionIn("Guillotine-1.9.463.msi"))
        assertEquals("0.9", UpdateChecker.versionIn("latest-release-v0.9"))
        assertNull(UpdateChecker.versionIn("no-version-here"))
    }

    @Test fun comparesVersionsComponentwiseWithZeroPadding() {
        assertTrue(UpdateChecker.compareVersions("0.9.464", "0.9.463") > 0)
        assertTrue(UpdateChecker.compareVersions("0.10.0", "0.9.999") > 0)
        assertEquals(0, UpdateChecker.compareVersions("1.9.463", "1.9.463"))
        // Zero-padding: "1.9" == "1.9.0", and "1.9.1" > "1.9".
        assertEquals(0, UpdateChecker.compareVersions("1.9", "1.9.0"))
        assertTrue(UpdateChecker.compareVersions("1.9.1", "1.9") > 0)
    }

    @Test fun isNewerIsStrict() {
        assertTrue(UpdateChecker.isNewer(current = "0.9.463.100", latest = "0.9.463.205921353"))
        assertFalse(UpdateChecker.isNewer(current = "0.9.463.205921353", latest = "0.9.463.205921353"))
        assertFalse(UpdateChecker.isNewer(current = "0.9.464.0", latest = "0.9.463.999"))
    }

    @Test fun endToEndAssetVersionComparison() {
        val r = UpdateChecker.parseRelease(sampleJson)
        // Android compares its versionName against the .apk asset.
        val apkLatest = UpdateChecker.versionIn(r.asset { it.endsWith(".apk") }!!.name)!!
        assertTrue(UpdateChecker.isNewer(current = "0.9.463.1", latest = apkLatest))
        // Desktop (linux) compares its package version against the .deb asset.
        val debLatest = UpdateChecker.versionIn(r.asset { it.endsWith(".deb") }!!.name)!!
        assertTrue(UpdateChecker.isNewer(current = "1.9.462", latest = debLatest))
        assertFalse(UpdateChecker.isNewer(current = "1.9.463", latest = debLatest))
    }
}
