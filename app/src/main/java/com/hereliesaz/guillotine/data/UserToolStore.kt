package com.hereliesaz.guillotine.data

import android.content.Context
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
        runCatching {
            File(context.filesDir, FILE).writeText(
                json.encodeToString(ListSerializer(UserTool.serializer()), tools),
            )
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
