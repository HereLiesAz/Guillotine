package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Downloads a recommended on-device model in the background and reports progress. A process-level
 * singleton with its own scope so a 1.5–3.7 GB download keeps running when the Settings sheet closes;
 * the UI just observes [state]. Files land in app-specific external storage (no permission needed).
 */
object ModelDownloadManager {

    sealed class DownloadState {
        data object Idle : DownloadState()
        data class Downloading(
            val modelId: String,
            val fraction: Float,
            val doneBytes: Long,
            val totalBytes: Long,
        ) : DownloadState()
        data class Done(val modelId: String, val path: String) : DownloadState()
        data class Failed(val modelId: String, val message: String) : DownloadState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    @Volatile private var job: Job? = null

    /** Directory holding downloaded `.task` models (app-specific, permission-free). */
    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "llm-models").apply { mkdirs() }

    /** Absolute path of [model] if it's fully downloaded (present at its expected size), else null. */
    fun installedPath(context: Context, model: OnDeviceModel): String? =
        File(modelsDir(context), model.fileName)
            .takeIf { it.isFile && it.length() == model.sizeBytes }
            ?.absolutePath

    /**
     * Bytes already on disk from an interrupted download of [model] (its `.part` file), or 0 if none.
     * Lets the picker offer "Resume" instead of "Download" and show how far along it is.
     */
    fun partialBytes(context: Context, model: OnDeviceModel): Long =
        File(modelsDir(context), "${model.fileName}.part")
            .takeIf { it.isFile }?.length()?.coerceAtMost(model.sizeBytes) ?: 0L

    /**
     * Stop the in-flight download. The partial `.part` file is **kept** so a later [start] for the
     * same model resumes from where it left off (HTTP Range), rather than starting over.
     */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = DownloadState.Idle
    }

    /** Start (or resume) downloading [model]; no-op if a download is already running or the model is gated. */
    fun start(context: Context, model: OnDeviceModel) {
        if (job?.isActive == true) return
        val url = model.downloadUrl ?: run {
            _state.value = DownloadState.Failed(model.id, "This model must be downloaded from Hugging Face.")
            return
        }
        val dir = modelsDir(context)
        val finalFile = File(dir, model.fileName)
        val partFile = File(dir, "${model.fileName}.part")

        job = scope.launch {
            // Resume point: bytes already on disk from a previous interrupted attempt. A stale `.part`
            // larger than the expected size is bogus — discard it and start clean.
            var resumeFrom = if (partFile.isFile) partFile.length() else 0L
            if (resumeFrom > model.sizeBytes) {
                runCatching { partFile.delete() }
                resumeFrom = 0L
            }
            _state.value = DownloadState.Downloading(
                model.id, (resumeFrom.toFloat() / model.sizeBytes).coerceIn(0f, 1f), resumeFrom, model.sizeBytes,
            )
            try {
                // Only the still-missing bytes need to fit on disk.
                if (availableBytes(dir) < (model.sizeBytes - resumeFrom) + SAFETY_MARGIN) {
                    throw IllegalStateException("Not enough free space for ${model.sizeLabel}.")
                }
                val request = Request.Builder().url(url).apply {
                    if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
                }.build()
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("Download failed (HTTP ${resp.code}).")
                    val body = resp.body ?: throw IllegalStateException("Empty response.")
                    // 206 => server honoured the range, body is the tail to append. Anything else (e.g. 200)
                    // means the range was ignored, so we must rewrite the file from the start.
                    val resuming = resumeFrom > 0 && resp.code == 206
                    val startBytes = if (resuming) resumeFrom else 0L
                    val total = model.sizeBytes
                    java.io.FileOutputStream(partFile, /* append = */ resuming).use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(1 shl 16)
                            var done = startBytes
                            var lastEmit = startBytes
                            while (true) {
                                ensureActive() // cooperative cancel
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done - lastEmit >= PROGRESS_STEP) {
                                    lastEmit = done
                                    _state.value = DownloadState.Downloading(
                                        model.id, (done.toFloat() / total).coerceIn(0f, 1f), done, total,
                                    )
                                }
                            }
                        }
                    }
                }
                if (partFile.length() != model.sizeBytes) {
                    throw IllegalStateException("Downloaded file is incomplete; try again.")
                }
                finalFile.delete()
                if (!partFile.renameTo(finalFile)) throw IllegalStateException("Could not save the model file.")
                _state.value = DownloadState.Done(model.id, finalFile.absolutePath)
            } catch (e: Throwable) {
                // Keep the `.part` file so the next start resumes — both on user cancel and on transient
                // network failure. (A size-mismatch "incomplete" is also resumable: the tail is retried.)
                if (e is kotlinx.coroutines.CancellationException) {
                    _state.value = DownloadState.Idle
                    throw e
                }
                _state.value = DownloadState.Failed(model.id, e.message ?: "Download failed.")
            } finally {
                job = null
            }
        }
    }

    private fun availableBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private const val PROGRESS_STEP = 2L * 1024 * 1024   // emit every ~2 MB
    private const val SAFETY_MARGIN = 200L * 1024 * 1024 // keep some headroom free
}
