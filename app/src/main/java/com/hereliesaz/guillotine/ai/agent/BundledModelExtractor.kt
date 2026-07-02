package com.hereliesaz.guillotine.ai.agent

import android.content.Context
import java.io.File

/**
 * Copies the bundled SmolLM-135M `.task` model from APK assets to the on-device models directory
 * on first launch. Subsequent calls are no-ops once the file is present at the expected size.
 */
object BundledModelExtractor {

    private val bundledModel: OnDeviceModel =
        RECOMMENDED_ON_DEVICE_MODELS.first { it.bundled }

    /**
     * Ensures the bundled model is present in the models directory. Call from [kotlinx.coroutines.Dispatchers.IO].
     * @return absolute path of the extracted file.
     */
    fun ensureExtracted(context: Context): String {
        val dir = ModelDownloadManager.modelsDir(context)
        val target = File(dir, bundledModel.fileName)
        // Match ModelDownloadManager.installedPath's exact-size check: a truncated/interrupted copy
        // (length > 0 but != expected) must be re-extracted, not accepted as complete forever.
        if (target.isFile && target.length() == bundledModel.sizeBytes) return target.absolutePath
        // Copy to a temp file first, then rename, so an interrupted copy never leaves a partial file
        // sitting at the final path where the size check above would still reject it on next launch.
        val tmp = File(dir, bundledModel.fileName + ".part")
        context.assets.open(bundledModel.fileName).use { input ->
            tmp.outputStream().use { output ->
                input.copyTo(output, bufferSize = 1 shl 16)
            }
        }
        if (target.exists()) target.delete()
        tmp.renameTo(target)
        return target.absolutePath
    }
}
