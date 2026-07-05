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
            val token = existing ?: generate().also { f.writeText(it) }
            cached = token
            return token
        }
    }

    fun regenerate(): String {
        val token = generate()
        File(DesktopStorage.dataDir, FILE).writeText(token)
        cached = token
        return token
    }

    private fun generate(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
