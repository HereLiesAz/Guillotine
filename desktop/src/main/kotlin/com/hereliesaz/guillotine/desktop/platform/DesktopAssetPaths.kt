package com.hereliesaz.guillotine.desktop.platform

import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Path confinement for MCP tools that take a filesystem path from outside the app (an MCP client or the
 * assistant model): `apply_lut`, `apply_shader`, `list_shader_params`, `replace_background`. Without it a
 * caller could make the app read (and in the LUT/shader case, partly echo back in error messages) any
 * file this process can open.
 *
 * A path is accepted only when, after canonicalisation (`..` and symlinks resolved), it is an existing
 * regular file under one of the allowed roots: the app's [DesktopStorage.dataDir], a folder the user
 * picked through a file dialog in this session ([addUserRoot]), or extra roots the caller passes (the
 * MCP surface passes the folders of media already imported into the project).
 */
object DesktopAssetPaths {

    private val userRoots: MutableSet<File> = ConcurrentHashMap.newKeySet()

    /**
     * Record [fileOrDir] (or its parent folder, when it's a file) as a user-picked root. Called by the
     * native file pickers, so a LUT/shader/image the user chose — and its siblings — are reachable.
     */
    fun addUserRoot(fileOrDir: File) {
        val dir = if (fileOrDir.isDirectory) fileOrDir else fileOrDir.parentFile ?: return
        runCatching { userRoots.add(dir.canonicalFile) }
    }

    /** The user-picked roots recorded so far (canonical). */
    fun userRoots(): Set<File> = userRoots.toSet()

    /** Folder of a media item URI (a plain path or a `file:` URI), or null if it isn't a local file. */
    fun folderOfMediaUri(uri: String): File? = runCatching {
        val f = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
        f.parentFile
    }.getOrNull()

    /**
     * Resolve [path] to a canonical existing file under [DesktopStorage.dataDir], a user-picked root,
     * or one of [extraRoots]. Throws [IllegalArgumentException] otherwise.
     */
    fun resolveAssetPath(path: String, extraRoots: Collection<File> = emptyList()): File =
        resolveAssetPathIn(path, listOf(DesktopStorage.dataDir) + userRoots + extraRoots)

    /** Core check of [resolveAssetPath], with the allowed [roots] given explicitly (used directly by tests). */
    fun resolveAssetPathIn(path: String, roots: Collection<File>): File {
        require(path.isNotBlank()) { "Provide a file path." }
        require('\u0000' !in path) { "Invalid path." }
        val file = try {
            File(path).canonicalFile
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid path: $path")
        }
        val canonicalRoots = roots.mapNotNull { r -> runCatching { r.canonicalFile }.getOrNull() }
        val inside = canonicalRoots.any { root -> isUnder(file, root) }
        require(inside) {
            "That file is outside the folders Guillotine may read. Pick it with a file dialog (or import it) first."
        }
        require(file.isFile) { "No file at: $path" }
        return file
    }

    /** True when canonical [file] is [root] itself or a descendant of it (path-segment aware). */
    private fun isUnder(file: File, root: File): Boolean = file.toPath().startsWith(root.toPath())
}
