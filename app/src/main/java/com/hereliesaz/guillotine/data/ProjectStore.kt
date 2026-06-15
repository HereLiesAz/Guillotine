package com.hereliesaz.guillotine.data

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import com.hereliesaz.guillotine.model.Document
import kotlinx.serialization.json.Json

/** Saves/loads the editor [Document] as JSON (".gilt" project files) via SAF. */
object ProjectStore {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun serialize(document: Document): String = json.encodeToString(Document.serializer(), document)

    fun deserialize(text: String): Document = json.decodeFromString(Document.serializer(), text)

    fun save(context: Context, uri: Uri, document: Document) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(serialize(document).toByteArray())
        }
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
