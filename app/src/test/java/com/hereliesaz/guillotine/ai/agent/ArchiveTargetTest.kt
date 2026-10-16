package com.hereliesaz.guillotine.ai.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ArchiveTargetTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun normalEntryStaysInside() {
        val dest = tmp.newFolder("model")
        assertEquals(
            File(dest, "sub/tokens.txt").canonicalFile,
            ModelDownloadManager.safeArchiveTarget(dest, "sub/tokens.txt"),
        )
    }

    @Test fun zipSlipEntriesAreRejected() {
        val dest = tmp.newFolder("model")
        for (bad in listOf("../evil.so", "a/../../evil", "/../../etc/x", ".", "sub/..")) {
            assertThrows(bad, IllegalArgumentException::class.java) {
                ModelDownloadManager.safeArchiveTarget(dest, bad)
            }
        }
    }
}
