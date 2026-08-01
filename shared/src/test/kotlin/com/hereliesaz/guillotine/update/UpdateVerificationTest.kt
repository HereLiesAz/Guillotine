package com.hereliesaz.guillotine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Both updaters downloaded an installer/APK and handed it straight to the OS with nothing checked.
 * HTTPS covers the wire; it does not cover a truncated write, a full disk, or a stale file left in the
 * download directory — and each of those ends with the OS being asked to install whatever bytes are
 * there. These pin the check that now stands between the two.
 */
class UpdateVerificationTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun asset(size: Long, sha: String? = null) =
        ReleaseAsset(name = "Guillotine.msi", downloadUrl = "https://example/x", sizeBytes = size, sha256 = sha)

    private fun fileOf(content: String) = tmp.newFile().apply { writeText(content) }

    @Test fun matchingDigestVerifies() {
        val f = fileOf("installer bytes")
        val sha = UpdateChecker.sha256Of(f)!!
        assertEquals(UpdateVerification.Verified, UpdateChecker.verify(f, asset(f.length(), sha)))
    }

    @Test fun mismatchedDigestFails() {
        val f = fileOf("installer bytes")
        val wrong = "0".repeat(64)
        val v = UpdateChecker.verify(f, asset(f.length(), wrong))
        assertTrue("expected Failed, got $v", v is UpdateVerification.Failed)
        assertTrue((v as UpdateVerification.Failed).reason.contains("SHA-256"))
    }

    @Test fun truncatedDownloadFailsOnSizeBeforeHashing() {
        // The common real failure. Caught by size alone, so it is caught even when no digest is published.
        val f = fileOf("short")
        val v = UpdateChecker.verify(f, asset(999_999))
        assertTrue("expected Failed, got $v", v is UpdateVerification.Failed)
        assertTrue((v as UpdateVerification.Failed).reason.contains("size mismatch"))
    }

    @Test fun noPublishedDigestIsSizeOnlyNotVerified() {
        // The distinction that keeps the UI honest: nothing to check against is not the same as checked.
        val f = fileOf("installer bytes")
        assertEquals(UpdateVerification.SizeOnly, UpdateChecker.verify(f, asset(f.length(), sha = null)))
    }

    @Test fun aMissingFileFails() {
        val f = tmp.newFile().apply { delete() }
        assertTrue(UpdateChecker.verify(f, asset(10)) is UpdateVerification.Failed)
    }

    @Test fun unknownSizeSkipsTheSizeCheck() {
        // GitHub always sends a size, but a 0 means "not stated" — don't invent a failure from it.
        val f = fileOf("bytes")
        val sha = UpdateChecker.sha256Of(f)!!
        assertEquals(UpdateVerification.Verified, UpdateChecker.verify(f, asset(0, sha)))
    }

    @Test fun sha256IsLowercaseHexOfTheRightLength() {
        val f = fileOf("x")
        val sha = UpdateChecker.sha256Of(f)!!
        assertEquals(64, sha.length)
        assertTrue(sha.all { it in '0'..'9' || it in 'a'..'f' })
        // Known answer for "x", so a broken hex encoding (e.g. dropped leading zeros) fails here.
        assertEquals("2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881", sha)
    }

    @Test fun digestIsParsedFromTheApiOnlyWhenItIsASha256() {
        val sha = "a".repeat(64)
        val json = """
            {"tag_name":"v1","name":"v1","html_url":"https://example/r","body":"",
             "assets":[
               {"name":"a.msi","browser_download_url":"https://example/a","size":10,"digest":"sha256:$sha"},
               {"name":"b.msi","browser_download_url":"https://example/b","size":10,"digest":"sha512:$sha"},
               {"name":"c.msi","browser_download_url":"https://example/c","size":10,"digest":"sha256:nope"},
               {"name":"d.msi","browser_download_url":"https://example/d","size":10}
             ]}
        """.trimIndent()
        val assets = UpdateChecker.parseRelease(json).assets
        assertEquals(sha, assets[0].sha256)
        assertEquals("a sha512 digest is not a sha256", null, assets[1].sha256)
        assertEquals("a malformed hex digest is treated as absent", null, assets[2].sha256)
        assertEquals("an asset with no digest", null, assets[3].sha256)
    }
}
