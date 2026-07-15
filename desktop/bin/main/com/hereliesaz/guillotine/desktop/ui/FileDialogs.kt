package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * Open the native file picker and return the chosen file, or null if cancelled. Blocking/modal — call
 * from a UI onClick (Compose Desktop dispatches those on the AWT thread).
 */
fun pickFile(title: String): File? {
    val fd = FileDialog(null as Frame?, title, FileDialog.LOAD)
    fd.isVisible = true
    val dir = fd.directory
    val name = fd.file
    return if (dir != null && name != null) File(dir, name) else null
}

/**
 * Open a folder picker and return the chosen directory, or null if cancelled. Uses [JFileChooser] in
 * DIRECTORIES_ONLY mode — the cross-platform way to select a folder (AWT [FileDialog] can't on Windows/Linux).
 */
fun pickFolder(title: String): File? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

@Composable
fun rememberSaveProjectLauncher(onPicked: (File) -> Unit): () -> Unit {
    return remember {
        {
            val fd = FileDialog(null as Frame?, "Save Project", FileDialog.SAVE)
            fd.file = "project.gilt"
            fd.isVisible = true
            val dir = fd.directory
            val name = fd.file
            if (dir != null && name != null) onPicked(File(dir, name))
        }
    }
}

@Composable
fun rememberOpenProjectLauncher(onPicked: (File) -> Unit): () -> Unit {
    return remember {
        {
            val fd = FileDialog(null as Frame?, "Open Project", FileDialog.LOAD)
            fd.isVisible = true
            val dir = fd.directory
            val name = fd.file
            if (dir != null && name != null) onPicked(File(dir, name))
        }
    }
}

@Composable
fun rememberModelInstallLauncher(onPicked: (File) -> Unit): () -> Unit {
    return remember {
        {
            val fd = FileDialog(null as Frame?, "Install AI model (.azp)", FileDialog.LOAD)
            fd.file = "*.azp"
            fd.isVisible = true
            val dir = fd.directory
            val name = fd.file
            if (dir != null && name != null) onPicked(File(dir, name))
        }
    }
}

@Composable
fun rememberMediaImportLauncher(onPicked: (List<File>) -> Unit): () -> Unit {
    return remember {
        {
            val fd = FileDialog(null as Frame?, "Import Media", FileDialog.LOAD)
            fd.isMultipleMode = true
            fd.isVisible = true
            val files = fd.files
            if (files != null && files.isNotEmpty()) onPicked(files.toList())
        }
    }
}
