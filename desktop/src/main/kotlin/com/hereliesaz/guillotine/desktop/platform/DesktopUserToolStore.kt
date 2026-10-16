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

    /** The saved tools; a corrupt file falls back to its `.bak` ([AtomicFiles.readWithRecovery]). */
    fun load(): List<UserTool> =
        AtomicFiles.readWithRecovery(File(DesktopStorage.dataDir, FILE)) {
            json.decodeFromString(ListSerializer(UserTool.serializer()), it)
        } ?: emptyList()

    /**
     * Atomic write with a `.bak` of the previous list. Throws on failure so add/remove callers report it
     * instead of claiming success.
     */
    fun save(tools: List<UserTool>) {
        AtomicFiles.writeAtomic(
            File(DesktopStorage.dataDir, FILE),
            json.encodeToString(ListSerializer(UserTool.serializer()), tools),
        )
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
