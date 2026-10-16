package com.hereliesaz.guillotine.data

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.media.MediaImport
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
 *
 * Corruption safety: if an existing autosave can't be parsed, [load] moves it aside to a
 * `.corrupt-<timestamp>` file and **suppresses autosave** until [resume] is called (a successful
 * load or a real user edit). Otherwise the empty editor that follows a failed load would be
 * autosaved straight over the user's only copy of their project.
 */
object ProjectAutosave {
    internal const val FILE = "current_project.gilt"

    /** Outcome of [load]. */
    sealed interface LoadResult {
        /** No autosave exists yet. */
        data object None : LoadResult
        data class Loaded(val document: Document) : LoadResult
        /**
         * The autosave existed but couldn't be parsed. It was renamed to [movedTo] (null if even the
         * rename failed — the original is then left untouched) and autosave is now suppressed.
         */
        data class Corrupt(val movedTo: File?, val error: Throwable) : LoadResult
    }

    // Serializes concurrent saves (the debounced IO-thread writer vs. the main-thread on-pause
    // flush) so two writers can never interleave their temp-write + rename.
    private val lock = Any()

    @Volatile private var suppressed = false

    /** True while autosave is held off after a corrupt load (see class doc). */
    val isSuppressed: Boolean get() = suppressed

    /** Re-enable autosave after a successful load or a genuine user edit. */
    fun resume() { suppressed = false }

    fun save(context: Context, document: Document) {
        saveTo(context.filesDir, document)
    }

    /**
     * Context-free core of [save]. Returns false if the write was skipped (suppressed) or failed.
     *
     * Guarded: a failed autosave (disk full / IO error), including the on-pause flush on the main
     * thread, must not crash the app — a dropped autosave is recoverable, a crash isn't.
     * Atomic: write to a UNIQUE temp file in the same directory (two concurrent writers used to
     * share one fixed `.tmp` name and could rename each other's half-written file into place), then
     * atomically rename it over the real file — the real file is always either the old complete
     * version or the new complete version.
     */
    internal fun saveTo(dir: File, document: Document): Boolean {
        if (suppressed) return false
        return synchronized(lock) {
            if (suppressed) return@synchronized false
            var tmp: File? = null
            runCatching {
                val real = File(dir, FILE)
                val text = ProjectStore.serialize(document)
                tmp = File.createTempFile("$FILE.", ".tmp", dir).also { it.writeText(text) }
                Files.move(tmp!!.toPath(), real.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.onFailure { tmp?.delete() }.isSuccess
        }
    }

    /** Load the autosaved current project. See [LoadResult]. */
    fun load(context: Context): LoadResult = when (val r = loadFrom(context.filesDir)) {
        // Re-probing media is best-effort; a probe failure isn't project corruption.
        is LoadResult.Loaded -> LoadResult.Loaded(
            runCatching { ProjectStore.repairImportedDimensions(context, r.document) }.getOrDefault(r.document),
        )
        else -> r
    }

    /** Context-free core of [load] (parsing + corrupt-file handling, no media re-probe). */
    internal fun loadFrom(dir: File, now: Long = System.currentTimeMillis()): LoadResult = synchronized(lock) {
        val f = File(dir, FILE)
        if (!f.exists()) return@synchronized LoadResult.None
        runCatching { ProjectStore.deserialize(f.readText()) }.fold(
            onSuccess = { doc ->
                suppressed = false
                LoadResult.Loaded(doc)
            },
            onFailure = { e ->
                suppressed = true
                val target = File(dir, "$FILE.corrupt-$now")
                val moved = runCatching {
                    Files.move(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    target
                }.getOrNull()
                LoadResult.Corrupt(moved, e)
            },
        )
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

    /**
     * Repair legacy project media dimensions on Android. Builds before the project-canvas fix could
     * persist width/height transposed for rotated phone video; re-probing the original persisted URI
     * makes "Original" correct immediately even for an existing autosave/.gilt project.
     */
    fun repairImportedDimensions(context: Context, document: Document): Document {
        var changed = false
        val media = document.mediaItems.map { item ->
            val repaired = MediaImport.refreshVisualDimensions(context, item)
            if (repaired !== item && repaired != item) changed = true
            repaired
        }
        return if (changed) document.copy(mediaItems = media) else document
    }

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
        return repairImportedDimensions(context, deserialize(text))
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
