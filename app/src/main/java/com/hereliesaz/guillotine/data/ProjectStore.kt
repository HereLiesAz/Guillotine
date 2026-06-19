package com.hereliesaz.guillotine.data

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.model.Document
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The always-on current project: the editor document is continuously written to a file in
 * app-internal storage so work is never lost and is restored automatically on next launch —
 * the user never has to explicitly save. Explicit Save/Load (SAF) is for exporting/importing
 * a copy to a user-chosen location.
 */
object ProjectAutosave {
    private const val FILE = "current_project.gilt"

    fun save(context: Context, document: Document) {
        File(context.filesDir, FILE).writeText(ProjectStore.serialize(document))
    }

    /** The autosaved current project, or null if none exists yet / it can't be read. */
    fun load(context: Context): Document? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { ProjectStore.deserialize(f.readText()) }.getOrNull()
    }
}

/** Saves/loads the editor [Document] as JSON (".gilt" project files) via SAF. */
object ProjectStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        // If a future version renames/removes an enum constant (e.g. a Quality/AspectRatio value),
        // coerce the now-unknown value in an old project to the property's default instead of
        // throwing — so a saved project never bricks the app after an update.
        coerceInputValues = true
    }

    fun serialize(document: Document): String = json.encodeToString(Document.serializer(), document)

    fun deserialize(text: String): Document = json.decodeFromString(Document.serializer(), text)

    fun save(context: Context, uri: Uri, document: Document) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(serialize(document).toByteArray())
        } ?: throw IllegalStateException("Could not open project file for writing.")
    }

    fun load(context: Context, uri: Uri): Document {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            ?: throw IllegalStateException("Could not read project file.")
        return deserialize(text)
    }
}

@Composable
fun rememberSaveProjectLauncher(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri -> uri?.let(onPicked) }
    return { launcher.launch("project.gilt") }
}

@Composable
fun rememberOpenProjectLauncher(onPicked: (Uri) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onPicked) }
    return { launcher.launch(arrayOf("*/*")) }
}
