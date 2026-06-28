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
        if (target.isFile && target.length() > 0) return target.absolutePath
        context.assets.open(bundledModel.fileName).use { input ->
            target.outputStream().use { output ->
                input.copyTo(output, bufferSize = 1 shl 16)
            }
        }
        return target.absolutePath
    }
}
