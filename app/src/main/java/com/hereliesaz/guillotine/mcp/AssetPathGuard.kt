package com.hereliesaz.guillotine.mcp

import java.io.File

/**
 * Path check for files an MCP client / the assistant names by path (LUTs, shaders, background images).
 * A path is accepted only if it canonicalizes (symlinks and `../` resolved) to an existing file under one
 * of [resolve]'s `roots` — in the app, its own filesDir (imported LUTs, models, azp extensions) and
 * cacheDir (generated intermediates). Anything else — `/sdcard/...`, `/system/...`, another app's
 * storage, a `../` escape — is rejected with an MCP-facing [IllegalArgumentException].
 */
internal object AssetPathGuard {

    fun resolve(rawPath: String, whatFor: String, roots: List<File>): File {
        require(rawPath.isNotBlank()) { "Provide the path to $whatFor." }
        val canonical = runCatching { File(rawPath).canonicalFile }
            .getOrElse { throw IllegalArgumentException("Not a valid path: $rawPath") }
        require(isUnder(canonical, roots)) {
            "Path outside the app's own storage isn't allowed: $rawPath. Import it into the app first " +
                "(e.g. via the LUT picker on the clip, or the media importer) so it lives under the " +
                "app's own storage, then pass that path."
        }
        require(canonical.isFile) { "No file at: $rawPath" }
        return canonical
    }

    /** True when the already-canonical [file] is one of [roots] or lies beneath one of them. */
    fun isUnder(file: File, roots: List<File>): Boolean =
        roots.mapNotNull { runCatching { it.canonicalFile }.getOrNull() }.any { root ->
            file == root || file.path.startsWith(root.path + File.separator)
        }
}
