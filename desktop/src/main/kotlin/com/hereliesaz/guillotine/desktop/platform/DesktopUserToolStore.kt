package com.hereliesaz.guillotine.desktop.platform

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UserTool(
    val id: String,
    val name: String,
    val description: String,
)

object DesktopUserToolStore {
    private const val FILE = "user_tools.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(): List<UserTool> {
        val f = File(DesktopStorage.dataDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(UserTool.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    fun save(tools: List<UserTool>) {
        runCatching {
            File(DesktopStorage.dataDir, FILE).writeText(
                json.encodeToString(ListSerializer(UserTool.serializer()), tools),
            )
        }
    }

    fun add(tool: UserTool) {
        val current = load().filter { it.name != tool.name }
        save(current + tool)
    }

    fun remove(name: String) {
        save(load().filter { it.name != name })
    }

    fun sanitizeName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
