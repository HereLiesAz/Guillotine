package com.hereliesaz.guillotine.desktop.platform

import java.io.File
import java.security.SecureRandom
import java.util.Base64

object DesktopMcpAuth {

    private const val FILE = "mcp_token"

    @Volatile
    private var cached: String? = null

    fun token(): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val f = File(DesktopStorage.dataDir, FILE)
            val existing = runCatching { f.readText().trim() }
                .getOrNull()?.takeIf { it.isNotBlank() }
            val token = existing ?: generate().also { writeRestricted(f, it) }
            cached = token
            return token
        }
    }

    fun regenerate(): String {
        val token = generate()
        writeRestricted(File(DesktopStorage.dataDir, FILE), token)
        cached = token
        return token
    }

    /** Write the bearer token owner-readable only — it grants full control of the local MCP tool
     *  surface, so any other local user/process reading it off disk could drive the editor. Restrict
     *  the file to the owner (POSIX 0600); on non-POSIX filesystems (Windows) fall back to the File API. */
    private fun writeRestricted(f: File, token: String) {
        f.writeText(token)
        val restricted = runCatching {
            val perms = java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")
            java.nio.file.Files.setPosixFilePermissions(f.toPath(), perms)
        }.isSuccess
        if (!restricted) {
            f.setReadable(false, false); f.setWritable(false, false)
            f.setReadable(true, true); f.setWritable(true, true)
        }
    }

    private fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
