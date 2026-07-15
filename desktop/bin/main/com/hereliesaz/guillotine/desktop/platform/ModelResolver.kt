package com.hereliesaz.guillotine.desktop.platform

import java.io.File

object ModelResolver {
    fun resolve(property: String): String {
        return when (property) {
            "labelModelPath" -> getBundledPath("mobilenetv3.onnx")
            "audioEventModelPath" -> getBundledPath("yamnet.onnx")
            "faceDetectModelPath" -> getBundledPath("version-RFB-320.onnx")
            "idEmbedModelPath" -> getBundledPath("mobilenetv3.onnx")
            "segModelPath" -> getBundledPath("selfie_segmentation.onnx")
            "speechModelPath" -> getRegistryPath("com.azphalt.model.vosk", "assets/vosk-model")
            "stemModelPath" -> getRegistryPath("com.azphalt.model.spleeter", "assets/spleeter-2stems")
            "diarizeSegModelPath" -> getRegistryPath("com.azphalt.model.pyannote", "assets/segmentation.onnx")
            "diarizeEmbedModelPath" -> getRegistryPath("com.azphalt.model.pyannote", "assets/embedding.onnx")
            "vlmModelPath" -> getRegistryPath("com.azphalt.model.moondream2", "assets/moondream2.onnx")
            else -> ""
        }
    }

    private fun getBundledPath(filename: String): String {
        // Since Guillotine bundles < 5MB models directly:
        // We'll point to a 'resources/models' directory. In a real desktop app deployment, 
        // these are usually copied out to the app data dir, but we'll assume they are available 
        // in a known location for the desktop jar.
        return System.getProperty("user.dir") + "/resources/models/" + filename
    }

    private fun getRegistryPath(packageName: String, assetPath: String): String {
        return System.getProperty("user.home") + "/.azphalt/packages/" + packageName + "/" + assetPath
    }
}
