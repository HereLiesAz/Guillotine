package com.hereliesaz.guillotine.data

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.model.Document
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The always-on current project: the editor document is continuously written to a file in
 * app-internal storage so work is never lost and is restored automatically on next launch —
 * the user never has to explicitly save. Explicit Save/Load (SAF) is for exporting/importing
 * a copy to a user-chosen location.
 */
object ProjectAutosave {
    private const val FILE = "current_project.gilt"

    fun save(context: Context, document: Document) {
        // Guard the write: a failed autosave (disk full / IO error), including the on-pause flush on
        // the main thread, must not crash the app — a dropped autosave is recoverable, a crash isn't.
        // Write atomically: a plain writeText() truncates the real file to 0 bytes before writing,
        // so a process death mid-write (OOM-kill while backgrounded, force-stop) leaves a
        // truncated/corrupt file that load() silently treats as "no project". Instead write to a
        // temp file in the same directory, then atomically rename it over the real file — the
        // real file is either the old complete version or the new complete version, never a
        // partial write, no matter when the process dies.
        runCatching {
            val real = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(ProjectStore.serialize(document))
            Files.move(tmp.toPath(), real.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
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
        // Serialize BEFORE opening the destination stream: "wt" mode truncates the target the
        // moment it's opened, so if we serialized lazily inside the `use` block a bad Document
        // (an encoding failure) would leave an empty file at the user's chosen location. This
        // way that class of failure never touches the target at all. SAF gives us no portable
        // atomic-replace primitive (unlike the internal-storage autosave above, which uses a
        // temp-file + atomic rename), so a genuine I/O failure *during* the write can still leave
        // a truncated file — that residual risk is inherent to SAF, not fixable from here.
        val text = serialize(document)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(text.toByteArray())
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
