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
    private val lock = Any()

    fun load(context: Context): List<UserTool> {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(UserTool.serializer()), f.readText())
        }.getOrElse { e ->
            // Move the unreadable file aside so the next save() can't silently overwrite it.
            quarantineCorruptFile(f, "UserToolStore", e)
            emptyList()
        }
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

    fun add(context: Context, tool: UserTool) = synchronized(lock) {
        val current = load(context).filter { it.name != tool.name }
        save(context, current + tool)
    }

    fun remove(context: Context, name: String) = synchronized(lock) {
        save(context, load(context).filter { it.name != name })
    }

    fun sanitizeName(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
}

/**
 * Rename an undecodable store file to `<name>.corrupt-<timestamp>` and log it, so the data is kept
 * for recovery instead of being destroyed by the next save (which would otherwise write an empty
 * list over it). Shared by [UserToolStore] and [LearnedConceptStore].
 */
internal fun quarantineCorruptFile(file: File, tag: String, cause: Throwable) {
    val aside = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
    val moved = runCatching { Files.move(file.toPath(), aside.toPath()); true }.getOrDefault(false)
    android.util.Log.e(
        tag,
        if (moved) "Could not decode ${file.name}; moved it to ${aside.name}" else "Could not decode ${file.name} (and could not move it aside)",
        cause,
    )
}
