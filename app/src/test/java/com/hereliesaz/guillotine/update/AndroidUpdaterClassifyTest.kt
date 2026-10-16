package com.hereliesaz.guillotine.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidUpdaterClassifyTest {
    private fun release(vararg assetNames: String) = ReleaseInfo(
        tag = "t", name = "n", notes = "notes", htmlUrl = "https://example.invalid",
        assets = assetNames.map { ReleaseAsset(it, "https://example.invalid/$it", 1L) },
    )

    @Test
    fun offlineIsFailureNotUpToDate() {
        assertTrue(AndroidUpdater.classify(null, "0.1.0.1") is AndroidUpdater.CheckResult.Failed)
    }

    @Test
    fun noApkIsFailure() {
        assertTrue(AndroidUpdater.classify(release("notes.txt"), "0.1.0.1") is AndroidUpdater.CheckResult.Failed)
    }

    @Test
    fun newerAndSameVersions() {
        val newer = AndroidUpdater.classify(release("Guillotine-githubRelease-0.2.0.5.apk"), "0.1.0.1")
        assertTrue(newer is AndroidUpdater.CheckResult.Update)
        val same = AndroidUpdater.classify(release("Guillotine-githubRelease-0.1.0.1.apk"), "0.1.0.1")
        assertEquals(AndroidUpdater.CheckResult.UpToDate, same)
    }
}
