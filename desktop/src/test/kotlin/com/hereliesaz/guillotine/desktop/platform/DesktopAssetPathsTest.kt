package com.hereliesaz.guillotine.desktop.platform

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DesktopAssetPathsTest {

    private lateinit var tmp: File
    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("assetpaths").toFile()
        root = File(tmp, "root").apply { mkdirs() }
        outside = File(tmp, "outside").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun resolve(path: String) = DesktopAssetPaths.resolveAssetPathIn(path, listOf(root))

    @Test
    fun acceptsFileUnderRoot() {
        val lut = File(root, "looks/teal.cube").apply { parentFile.mkdirs(); writeText("LUT") }
        assertEquals(lut.canonicalFile, resolve(lut.path))
    }

    @Test
    fun refusesFileOutsideRoots() {
        val secret = File(outside, "secret.txt").apply { writeText("x") }
        assertThrows(IllegalArgumentException::class.java) { resolve(secret.path) }
    }

    @Test
    fun refusesDotDotEscape() {
        File(outside, "secret.txt").writeText("x")
        assertThrows(IllegalArgumentException::class.java) { resolve("${root.path}/../outside/secret.txt") }
    }

    @Test
    fun refusesSiblingWithSharedPrefix() {
        // "root2" starts with "root" as a string but is not inside it.
        val sibling = File(tmp, "root2").apply { mkdirs() }
        val f = File(sibling, "a.cube").apply { writeText("x") }
        assertThrows(IllegalArgumentException::class.java) { resolve(f.path) }
    }

    @Test
    fun refusesSymlinkPointingOutside() {
        val secret = File(outside, "secret.txt").apply { writeText("x") }
        val link = File(root, "link.cube")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), secret.toPath()) }.isSuccess
        assumeTrue("symlinks unsupported here", made)
        assertThrows(IllegalArgumentException::class.java) { resolve(link.path) }
    }

    @Test
    fun refusesMissingFileAndDirectoryAndBlank() {
        assertThrows(IllegalArgumentException::class.java) { resolve(File(root, "nope.cube").path) }
        assertThrows(IllegalArgumentException::class.java) { resolve(root.path) }
        assertThrows(IllegalArgumentException::class.java) { resolve("  ") }
    }

    @Test
    fun userRootRegisteredFromPickedFile() {
        val picked = File(outside, "picked.cube").apply { writeText("x") }
        DesktopAssetPaths.addUserRoot(picked)
        val resolved = DesktopAssetPaths.resolveAssetPathIn(picked.path, DesktopAssetPaths.userRoots())
        assertEquals(picked.canonicalFile, resolved)
    }
}
