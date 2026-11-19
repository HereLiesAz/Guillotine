package com.hereliesaz.guillotine.desktop.platform

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Crash-safe small-file persistence for app state (project autosave, the user-tool store).
 *
 * [writeAtomic] writes to a sibling temp file, fsyncs it, rotates the backups (`<name>.bak` →
 * `<name>.bak.2` → `<name>.bak.3`, [BACKUP_GENERATIONS] in all), copies the current good file to
 * `<name>.bak`, then renames the temp over the target with `ATOMIC_MOVE` — so a crash or full disk mid-write leaves
 * either the old file or the new one, never a truncated mix. [readWithRecovery] parses the file and
 * falls back to the newest readable backup generation when it is unreadable; a corrupt primary is copied to `<name>.corrupt` first
 * so the next save can't destroy the only evidence of it.
 */
object AtomicFiles {

    /** How many rotating backups are kept: `.bak` (newest), `.bak.2`, `.bak.3` (oldest). */
    const val BACKUP_GENERATIONS = 3

    /** Backup generation [generation] (1 = newest, `<name>.bak`; n > 1 = `<name>.bak.n`). */
    fun backupOf(file: File, generation: Int = 1): File =
        File(file.parentFile, if (generation <= 1) "${file.name}.bak" else "${file.name}.bak.$generation")

    /** Shift `.bak` → `.bak.2` → … dropping the oldest, so a new `.bak` can be written. */
    private fun rotateBackups(file: File) {
        for (gen in BACKUP_GENERATIONS - 1 downTo 1) {
            val src = backupOf(file, gen)
            if (src.isFile) {
                runCatching {
                    Files.move(src.toPath(), backupOf(file, gen + 1).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

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
                    rotateBackups(file)
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
     * Parse [file] with [parse]; on failure (or when it's missing but a backup exists) try each backup
     * generation, newest first. Returns null when none yields a value.
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
        for (gen in 1..BACKUP_GENERATIONS) {
            val bak = backupOf(file, gen)
            if (!bak.isFile) continue
            runCatching { parse(bak.readText()) }.onSuccess { return it }
        }
        return null
    }
}
