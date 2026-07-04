package com.hereliesaz.guillotine.mcp

data class RelayConfig(
    val enabled: Boolean = false,
    val workerUrl: String = "",
    val accessKey: String = "",
) {
    val isUsable: Boolean get() = enabled && workerUrl.startsWith("wss://", ignoreCase = true)
}
