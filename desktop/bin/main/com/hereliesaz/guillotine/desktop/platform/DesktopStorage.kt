package com.hereliesaz.guillotine.desktop.platform

import java.io.File

object DesktopStorage {
    val dataDir: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val base = when {
            "win" in os -> File(
                System.getenv("APPDATA") ?: System.getProperty("user.home"),
                "Guillotine",
            )
            "mac" in os -> File(
                System.getProperty("user.home"),
                "Library/Application Support/Guillotine",
            )
            else -> File(System.getProperty("user.home"), ".guillotine")
        }
        base.also { it.mkdirs() }
    }
}
