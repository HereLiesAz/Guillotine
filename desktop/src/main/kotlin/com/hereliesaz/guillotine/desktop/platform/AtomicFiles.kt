package com.hereliesaz.guillotine.desktop.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe small-file persistence for app state (project autosave, the user-tool store).
 *
 * [writeAtomic] writes to a sibling temp file, fsyncs it, copies the current good file to `<name>.bak`,
 * then renames the temp over the target with `ATOMIC_MOVE` — so a crash or full disk mid-write leaves
 * either the old file or the new one, never a truncated mix. [readWithRecovery] parses the file and
 * falls back to the `.bak` when it is unreadable; a corrupt primary is copied to `<name>.corrupt` first
 * so the next save can't destroy the only evidence of it.
 */
object AtomicFiles {

    fun backupOf(file: File): File = File(file.parentFile, "${file.name}.bak")

    fun writeAtomic(file: File, text: String) {
        val dir = file.absoluteFile.parentFile ?: throw IllegalArgumentException("No parent folder for $file")
        dir.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", dir)
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (file.isFile && file.length() > 0) {
                runCatching {
                    Files.copy(file.toPath(), backupOf(file).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Parse [file] with [parse]; on failure (or when it's missing but a backup exists) try `<name>.bak`.
     * Returns null when neither yields a value.
     */
    fun <T> readWithRecovery(file: File, parse: (String) -> T): T? {
        if (file.isFile) {
            val primary = runCatching { parse(file.readText()) }
            if (primary.isSuccess) return primary.getOrNull()
            runCatching {
                Files.copy(file.toPath(), File(file.parentFile, "${file.name}.corrupt").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            System.err.println("Guillotine: ${file.name} is unreadable (${primary.exceptionOrNull()?.message}); trying the backup.")
        }
        val bak = backupOf(file)
        if (!bak.isFile) return null
        return runCatching { parse(bak.readText()) }.getOrNull()
    }
}
