package com.hereliesaz.guillotine.ai

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Copies a user-picked file or folder (a Storage Access Framework `content://` URI, from the native
 * file explorer) into the app's private storage and returns its **real filesystem path** — because the
 * on-device model runtimes (Vosk / sherpa-onnx / ONNX Runtime) load a model from a path, not a SAF URI.
 *
 * All methods do blocking I/O (a model folder can be large) — call them off the main thread.
 */
object ModelImport {

    private fun modelsDir(context: Context): File =
        File(context.filesDir, "imported-models").apply { mkdirs() }

    /** Copy a single picked file (from `OpenDocument`) into storage; returns its path, or null on failure. */
    fun importFile(context: Context, uri: Uri): String? {
        val name = sanitize(displayName(context, uri) ?: "model.bin")
        val dest = uniqueChild(modelsDir(context), name)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            } ?: return null
            dest.absolutePath
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }

    /** Copy a picked folder (from `OpenDocumentTree`) recursively into storage; returns the dir path, or null. */
    fun importTree(context: Context, treeUri: Uri): String? {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = sanitize(displayName(context, DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)) ?: "model")
        val destRoot = uniqueChild(modelsDir(context), rootName)
        return try {
            destRoot.mkdirs()
            copyDir(context, treeUri, rootId, destRoot)
            destRoot.absolutePath
        } catch (e: Exception) {
            destRoot.deleteRecursively()
            null
        }
    }

    private fun copyDir(context: Context, treeUri: Uri, parentDocId: String, destDir: File) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val docId = c.getString(0) ?: continue
                val childName = sanitize(c.getString(1) ?: continue)
                if (childName.isBlank()) continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    copyDir(context, treeUri, docId, File(destDir, childName).apply { mkdirs() })
                } else {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    context.contentResolver.openInputStream(fileUri)?.use { input ->
                        File(destDir, childName).outputStream().use { input.copyTo(it) }
                    }
                }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
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
