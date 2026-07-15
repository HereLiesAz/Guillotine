package com.hereliesaz.guillotine.desktop.platform

import java.io.File

object ModelResolver {
    fun resolve(property: String): String {
        return when (property) {
            "labelModelPath" -> getRegistryPath("com.azphalt.model.mobilenetv3", "assets/mobilenetv3.onnx")
            "audioEventModelPath" -> getRegistryPath("com.azphalt.model.yamnet", "assets/yamnet.onnx")
            "faceDetectModelPath" -> getRegistryPath("com.azphalt.model.rfb-320", "assets/version-RFB-320.onnx")
            "idEmbedModelPath" -> getRegistryPath("com.azphalt.model.mobilenetv3", "assets/mobilenetv3.onnx")
            "segModelPath" -> getRegistryPath("com.azphalt.model.selfie-segmentation", "assets/selfie_segmentation.onnx")
            "speechModelPath" -> getRegistryPath("com.azphalt.model.vosk", "assets/vosk-model")
            "stemModelPath" -> getRegistryPath("com.azphalt.model.spleeter", "assets/spleeter-2stems")
            "diarizeSegModelPath" -> getRegistryPath("com.azphalt.model.pyannote", "assets/segmentation.onnx")
            "diarizeEmbedModelPath" -> getRegistryPath("com.azphalt.model.pyannote", "assets/embedding.onnx")
            "vlmModelPath" -> getRegistryPath("com.azphalt.model.moondream2", "assets/moondream2.onnx")
            else -> ""
        }
    }

    private fun getRegistryPath(packageName: String, assetPath: String): String {
        return System.getProperty("user.home") + "/.azphalt/packages/" + packageName + "/" + assetPath
    }
}
