package com.hereliesaz.guillotine.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Serializable
data class UserTool(
    val id: String,
    val name: String,
    val description: String,
)

object UserToolStore {
    private const val FILE = "user_tools.json"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun load(context: Context): List<UserTool> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(UserTool.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    fun save(context: Context, tools: List<UserTool>) {
        // Atomic write (temp file + atomic rename): a plain writeText() truncates the real file
        // to 0 bytes before writing, so a process death mid-write would silently destroy all
        // user-defined tools with no error. See ProjectStore.ProjectAutosave.save for the same fix.
        runCatching {
            val real = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(json.encodeToString(ListSerializer(UserTool.serializer()), tools))
            Files.move(tmp.toPath(), real.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    fun add(context: Context, tool: UserTool) {
        val current = load(context).filter { it.name != tool.name }
        save(context, current + tool)
    }

    fun remove(context: Context, name: String) {
        save(context, load(context).filter { it.name != name })
    }

    fun sanitizeName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}
