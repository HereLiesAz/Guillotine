package com.hereliesaz.guillotine.platform

import android.content.Context
import java.io.File

object ModelResolver {
    fun resolve(context: Context, property: String): String {
        return when (property) {
            "labelModelPath" -> "" // Handled by MLKit
            "audioEventModelPath" -> getRegistryPath(context, "com.azphalt.model.audio-events", "assets/yamnet.tflite")
            "faceDetectModelPath" -> "" // Handled by MLKit
            "idEmbedModelPath" -> getRegistryPath(context, "com.azphalt.model.id-embed", "assets/mobilenetv3.tflite")
            "faceEmbedModelPath" -> getRegistryPath(context, "com.azphalt.model.face-embed", "assets/face-embed.tflite")
            "segModelPath" -> "" // Handled by MLKit
            "speechModelPath" -> getRegistryPath(context, "com.azphalt.model.vosk", "assets/vosk-model")
            "stemModelPath" -> getRegistryPath(context, "com.azphalt.model.spleeter", "assets/spleeter.onnx")
            "diarizeSegModelPath" -> getRegistryPath(context, "com.azphalt.model.pyannote", "assets/segmentation.onnx")
            "diarizeEmbedModelPath" -> getRegistryPath(context, "com.azphalt.model.pyannote", "assets/embedding.onnx")
            "vlmModelPath" -> getRegistryPath(context, "com.azphalt.model.vlm-gemma", "assets/gemma-3n.task")
            "asrModelPath" -> getRegistryPath(context, "com.azphalt.model.sherpa-onnx", "assets/whisper-base.onnx")
            "ttsModelPath" -> getRegistryPath(context, "com.azphalt.model.tts", "assets/tts.onnx")
            "denoiseModelPath" -> getRegistryPath(context, "com.azphalt.model.denoiser", "assets/denoiser.onnx")
            "agentModelPath" -> getRegistryPath(context, "com.azphalt.model.agent", "assets/agent.task")
            else -> ""
        }
    }

    private fun getRegistryPath(context: Context, packageName: String, assetPath: String): String {
        val dir = File(context.filesDir, "azp-models")
        val file = File(dir, "$packageName/$assetPath")
        return file.absolutePath
    }
}
