package com.hereliesaz.guillotine.mcp

import androidx.compose.runtime.mutableStateOf

/** Live state of the embedded MCP server, shown in Settings' MCP section. */
object McpServerStatus {
    /** Port this build's server listens on (per flavor; see app/build.gradle.kts MCP_PORT). */
    val port: Int get() = com.hereliesaz.guillotine.BuildConfig.MCP_PORT

    /** Why the server failed to start (e.g. port in use), or null while it's running fine. */
    val startError = mutableStateOf<String?>(null)
}
