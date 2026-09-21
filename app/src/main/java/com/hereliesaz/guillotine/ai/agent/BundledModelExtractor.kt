package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Materializes the bundled SmolLM-135M `.task` model from APK assets into the on-device models
 * directory.
 *
 * Normal app startup uses [prewarmOverFirstOpen]: the ~167 MB copy is split into small bursts across
 * roughly the first 20 minutes the editor is open, with long sleeps between bursts. If the model is
 * actually needed before that finishes, [ensureExtracted] takes over the same resumable `.part`
 * file and completes the remaining bytes immediately.
 */
object BundledModelExtractor {

    private val bundledModel: OnDeviceModel =
        RECOMMENDED_ON_DEVICE_MODELS.first { it.bundled }

    private const val PREWARM_SLICES = 20
    private const val PREWARM_DURATION_MS = 20L * 60L * 1_000L
    private const val STARTUP_GRACE_MS = 10_000L
    private const val BUSY_RETRY_MS = 2_000L
    private const val BUFFER_SIZE = 1 shl 16

    private val lock = Any()
    private var session: CopySession? = null

    private data class CopySession(
        val target: File,
        val part: File,
        val input: InputStream,
        val output: OutputStream,
        var copied: Long,
    )

    /** Returns the final model path only when the complete expected file is already present. */
    fun installedPath(context: Context): String? {
        val target = targetFile(context)
        return target.takeIf { it.isFile && it.length() == bundledModel.sizeBytes }?.absolutePath
    }

    /** Bytes already materialized, including a resumable partial copy. */
    fun partialBytes(context: Context): Long {
        val target = targetFile(context)
        if (target.isFile && target.length() == bundledModel.sizeBytes) return bundledModel.sizeBytes
        val part = partFile(context)
        return part.length().coerceIn(0L, bundledModel.sizeBytes)
    }

    /**
     * Finish extraction now. This is the path used when Prompt Coach (or an explicit model choice)
     * needs the starter model before background prewarming has completed.
     *
     * Call from [Dispatchers.IO].
     */
    fun ensureExtracted(context: Context): String = synchronized(lock) {
        installedPath(context)?.let { return@synchronized it }

        val copy = sessionForLocked(context)
        copyRemainingLocked(copy)
        finishLocked(copy)
        targetFile(context).absolutePath
    }

    /**
     * Spread extraction over the first ~20 minutes the editor is open.
     *
     * Each slice copies about 1/20th of the model (roughly 8 MB) and then sleeps for about a minute.
     * [canRun] lets the editor yield these background bursts while playback, export, analysis, or the
     * assistant is busy. Yielding can extend the wall-clock finish beyond 20 minutes; urgent callers
     * still use [ensureExtracted] and complete immediately.
     */
    suspend fun prewarmOverFirstOpen(
        context: Context,
        canRun: () -> Boolean = { true },
    ) = withContext(Dispatchers.IO) {
        if (installedPath(context) != null) return@withContext

        delay(STARTUP_GRACE_MS)

        val sliceBytes =
            (bundledModel.sizeBytes + PREWARM_SLICES - 1L) / PREWARM_SLICES
        val intervalMs =
            ((PREWARM_DURATION_MS - STARTUP_GRACE_MS) / (PREWARM_SLICES - 1L))
                .coerceAtLeast(1_000L)

        var slicesDone = 0
        try {
            while (slicesDone < PREWARM_SLICES && installedPath(context) == null) {
                if (!canRun()) {
                    delay(BUSY_RETRY_MS)
                    continue
                }

                val complete = copySlice(context, sliceBytes)
                slicesDone++
                if (complete) return@withContext

                delay(intervalMs)
            }
        } finally {
            // A cancelled screen/app session leaves the .part file resumable next time.
            synchronized(lock) { closeSessionLocked() }
        }
    }

    private fun copySlice(context: Context, maxBytes: Long): Boolean = synchronized(lock) {
        installedPath(context)?.let { return@synchronized true }

        val copy = sessionForLocked(context)
        copyAtMostLocked(copy, maxBytes)
        if (copy.copied >= bundledModel.sizeBytes) {
            finishLocked(copy)
            true
        } else {
            false
        }
    }

    private fun sessionForLocked(context: Context): CopySession {
        val target = targetFile(context)
        session?.takeIf { it.target.absolutePath == target.absolutePath }?.let { return it }

        closeSessionLocked()

        if (target.exists() && target.length() != bundledModel.sizeBytes) {
            target.delete()
        }

        val part = partFile(context)
        if (part.exists() && part.length() > bundledModel.sizeBytes) {
            part.delete()
        }
        part.parentFile?.mkdirs()

        val copied = part.length()
        val input = context.assets.open(bundledModel.fileName)
        skipFully(input, copied)
        val output = try {
            FileOutputStream(part, true)
        } catch (e: Exception) {
            runCatching { input.close() }
            throw e
        }

        return CopySession(target, part, input, output, copied).also { session = it }
    }

    private fun copyRemainingLocked(copy: CopySession) {
        val remaining = bundledModel.sizeBytes - copy.copied
        if (remaining > 0) copyAtMostLocked(copy, remaining)
    }

    private fun copyAtMostLocked(copy: CopySession, maxBytes: Long) {
        var left = minOf(maxBytes, bundledModel.sizeBytes - copy.copied)
        if (left <= 0) return

        val buffer = ByteArray(BUFFER_SIZE)
        while (left > 0) {
            val read = copy.input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) {
                throw IOException(
                    "Bundled model asset ended at ${copy.copied} bytes; expected ${bundledModel.sizeBytes}.",
                )
            }
            copy.output.write(buffer, 0, read)
            copy.copied += read
            left -= read
        }
        copy.output.flush()
    }

    private fun finishLocked(copy: CopySession) {
        if (copy.copied != bundledModel.sizeBytes) {
            throw IOException(
                "Bundled model copy incomplete: ${copy.copied}/${bundledModel.sizeBytes} bytes.",
            )
        }

        closeSessionLocked()
        if (copy.target.exists() && !copy.target.delete()) {
            throw IOException("Could not replace old bundled model file.")
        }
        if (!copy.part.renameTo(copy.target)) {
            throw IOException("Could not finalize bundled model extraction.")
        }
    }

    private fun closeSessionLocked() {
        val active = session ?: return
        session = null
        runCatching { active.output.close() }
        runCatching { active.input.close() }
    }

    private fun skipFully(input: InputStream, byteCount: Long) {
        var remaining = byteCount
        val scratch = ByteArray(BUFFER_SIZE)
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read < 0) {
                throw IOException("Bundled model partial copy is longer than its APK asset.")
            }
            remaining -= read
        }
    }

    private fun targetFile(context: Context): File =
        File(ModelDownloadManager.modelsDir(context), bundledModel.fileName)

    private fun partFile(context: Context): File =
        File(ModelDownloadManager.modelsDir(context), bundledModel.fileName + ".part")
}
