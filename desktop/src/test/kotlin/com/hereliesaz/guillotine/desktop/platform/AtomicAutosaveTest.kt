package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.model.Document
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AtomicAutosaveTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("autosave").toFile()
        file = File(dir, "current_project.gilt")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun roundTripsAndLeavesNoTempFiles() {
        DesktopProjectAutosave.saveToFile(file, Document(name = "one"))
        assertEquals("one", DesktopProjectAutosave.loadWithRecovery(file)?.name)
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test
    fun secondSaveKeepsPreviousVersionAsBackup() {
        DesktopProjectAutosave.saveToFile(file, Document(name = "one"))
        DesktopProjectAutosave.saveToFile(file, Document(name = "two"))
        assertEquals("two", DesktopProjectAutosave.loadWithRecovery(file)?.name)
        val bak = AtomicFiles.backupOf(file)
        assertTrue(bak.isFile)
        assertEquals("one", DesktopProjectAutosave.loadWithRecovery(bak)?.name)
    }

    @Test
    fun corruptPrimaryRecoversFromBackupAndIsPreserved() {
        DesktopProjectAutosave.saveToFile(file, Document(name = "good"))
        DesktopProjectAutosave.saveToFile(file, Document(name = "newer"))
        file.writeText("{ this is not json") // simulate a torn/corrupt write
        assertEquals("good", DesktopProjectAutosave.loadWithRecovery(file)?.name)
        assertTrue(File(dir, "${file.name}.corrupt").isFile)
    }

    @Test
    fun missingEverythingIsNull() {
        assertNull(DesktopProjectAutosave.loadWithRecovery(file))
    }
}
