package com.hereliesaz.guillotine.data

import com.hereliesaz.guillotine.model.Document
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.concurrent.thread

class ProjectAutosaveTest {
    @get:Rule val tmp = TemporaryFolder()

    @Before fun setUp() = ProjectAutosave.resume()
    @After fun tearDown() = ProjectAutosave.resume()

    @Test
    fun missingFileIsNone() {
        assertEquals(ProjectAutosave.LoadResult.None, ProjectAutosave.loadFrom(tmp.root))
    }

    @Test
    fun roundTrip() {
        assertTrue(ProjectAutosave.saveTo(tmp.root, Document()))
        assertTrue(ProjectAutosave.loadFrom(tmp.root) is ProjectAutosave.LoadResult.Loaded)
    }

    @Test
    fun corruptFileIsMovedAsideAndAutosaveSuppressedUntilResume() {
        val real = File(tmp.root, ProjectAutosave.FILE)
        real.writeText("{ not json")
        val r = ProjectAutosave.loadFrom(tmp.root, now = 42L)
        assertTrue(r is ProjectAutosave.LoadResult.Corrupt)
        val moved = (r as ProjectAutosave.LoadResult.Corrupt).movedTo!!
        assertEquals("${ProjectAutosave.FILE}.corrupt-42", moved.name)
        assertEquals("{ not json", moved.readText())
        assertTrue(ProjectAutosave.isSuppressed)

        // The empty editor that follows must NOT be written while suppressed.
        assertFalse(ProjectAutosave.saveTo(tmp.root, Document()))
        assertFalse(real.exists())

        ProjectAutosave.resume()
        assertTrue(ProjectAutosave.saveTo(tmp.root, Document()))
        assertTrue(real.exists())
    }

    @Test
    fun concurrentSavesLeaveNoTempFilesAndAValidProject() {
        val threads = (1..8).map { thread { repeat(20) { ProjectAutosave.saveTo(tmp.root, Document()) } } }
        threads.forEach { it.join() }
        val leftovers = tmp.root.listFiles()!!.filter { it.name.endsWith(".tmp") }
        assertTrue("leftover temp files: $leftovers", leftovers.isEmpty())
        assertTrue(ProjectAutosave.loadFrom(tmp.root) is ProjectAutosave.LoadResult.Loaded)
    }
}
