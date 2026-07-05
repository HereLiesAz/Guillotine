package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

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
