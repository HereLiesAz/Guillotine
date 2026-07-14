package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException

/**
 * Copies a user-picked file or folder (a Storage Access Framework `content://` URI, from the native
 * file explorer) into the app's private storage and returns its **real filesystem path** — because the
 * on-device model runtimes (Vosk / sherpa-onnx / ONNX Runtime) load a model from a path, not a SAF URI.
 *
 * The methods are `suspend` and do blocking I/O (a model folder can be large) — call them on
 * `Dispatchers.IO`. They cooperate with cancellation (a closed settings sheet aborts a big copy) and
 * clean up any partial output on failure.
 */
object ModelImport {

    private fun modelsDir(context: Context): File =
        File(context.filesDir, "imported-models").apply { mkdirs() }

    /** Copy a single picked file (from `OpenDocument`) into storage; returns its path, or null on failure. */
    suspend fun importFile(context: Context, uri: Uri): String? {
        val name = sanitize(displayName(context, uri) ?: "model.bin")
        val dest = uniqueChild(modelsDir(context), name)
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("could not open $uri")
            input.use { inp -> dest.outputStream().use { inp.copyTo(it) } }
            dest.absolutePath
        } catch (e: Exception) {
            dest.delete()
            if (e is CancellationException) throw e // cancellation must propagate, not read as a failed import
            null
        }
    }

    /** Copy a picked folder (from `OpenDocumentTree`) recursively into storage; returns the dir path, or null. */
    suspend fun importTree(context: Context, treeUri: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = sanitize(displayName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "model")
        val destRoot = uniqueChild(modelsDir(context), rootName)
        return try {
            destRoot.mkdirs()
            copyDir(context, treeUri, rootId, destRoot)
            destRoot.absolutePath
        } catch (e: Exception) {
            destRoot.deleteRecursively()
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun copyDir(context: Context, treeUri: Uri, parentDocId: String, destDir: File) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        ) ?: throw IOException("could not list $parentDocId")
        cursor.use { c ->
            // Resolve columns by name: some OEM DocumentProviders ignore the projection order.
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            if (idCol == -1 || nameCol == -1 || mimeCol == -1) throw IOException("document cursor missing required columns")
            while (c.moveToNext()) {
                currentCoroutineContext().ensureActive() // abort a large copy if the caller was cancelled
                val docId = c.getString(idCol) ?: continue
                val childName = sanitize(c.getString(nameCol) ?: continue)
                if (childName.isBlank()) continue
                if (c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    copyDir(context, treeUri, docId, File(destDir, childName).apply { mkdirs() })
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val input = context.contentResolver.openInputStream(fileUri)
                        ?: throw IOException("could not open $childName")
                    input.use { inp -> File(destDir, childName).outputStream().use { inp.copyTo(it) } }
                }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            val col = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (col != -1 && c.moveToFirst()) c.getString(col) else null
        }

    /** Keep only a safe filename component so a SAF display name can't escape the models directory. */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' || it == ' ' }
            .trim()
            .takeIf { it.isNotBlank() && it != "." && it != ".." } ?: "file"

    /** Return `dir/name`, or `dir/name-2`, `dir/name-3`… if it already exists — never overwrite. */
    private fun uniqueChild(dir: File, name: String): File {
        val first = File(dir, name)
        if (!first.exists()) return first
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        var candidate = File(dir, "$base-$i$ext")
        while (candidate.exists()) {
            i++
            candidate = File(dir, "$base-$i$ext")
        }
        return candidate
    }
}
