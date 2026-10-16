package com.hereliesaz.guillotine.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssetPathGuardTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun acceptsFileUnderRoot() {
        val root = tmp.newFolder("files")
        val f = File(root, "luts/a.cube").apply { parentFile!!.mkdirs(); writeText("x") }
        assertEquals(f.canonicalFile, AssetPathGuard.resolve(f.path, "a LUT", listOf(root)))
    }

    @Test fun rejectsDotDotEscape() {
        val root = tmp.newFolder("files")
        val outside = tmp.newFile("secret.txt")
        assertThrows(IllegalArgumentException::class.java) {
            AssetPathGuard.resolve("${root.path}/../${outside.name}", "a LUT", listOf(root))
        }
    }

    @Test fun rejectsSiblingWithSharedPrefix() {
        val root = tmp.newFolder("files")
        val sibling = File(tmp.root, "files-evil/a.cube").apply { parentFile!!.mkdirs(); writeText("x") }
        assertThrows(IllegalArgumentException::class.java) {
            AssetPathGuard.resolve(sibling.path, "a LUT", listOf(root))
        }
    }

    @Test fun rejectsMissingFileAndBlank() {
        val root = tmp.newFolder("files")
        assertThrows(IllegalArgumentException::class.java) {
            AssetPathGuard.resolve(File(root, "nope.cube").path, "a LUT", listOf(root))
        }
        assertThrows(IllegalArgumentException::class.java) { AssetPathGuard.resolve(" ", "a LUT", listOf(root)) }
    }

    @Test fun rejectsSymlinkOutOfRoot() {
        val root = tmp.newFolder("files")
        val outside = tmp.newFile("outside.glsl")
        val link = File(root, "link.glsl").toPath()
        runCatching { java.nio.file.Files.createSymbolicLink(link, outside.toPath()) }.getOrElse { return }
        assertThrows(IllegalArgumentException::class.java) {
            AssetPathGuard.resolve(link.toString(), "a shader", listOf(root))
        }
    }
}
