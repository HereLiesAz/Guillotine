package com.hereliesaz.guillotine.desktop.platform

import java.io.File

/**
 * Resolves a logical on-device model slot to a real, **existing** path, or `""` when nothing is
 * installed. Desktop delivers models only via the `.azp` installer, which lands each asset flat in
 * `DesktopStorage.dataDir/azp-models/<filename>` (see `AzpModelInstall`). The old resolver returned a
 * fixed `~/.azphalt/packages/<pkg>/<asset>` path with no existence check — a location nothing writes to —
 * so resolution never matched a file and every "no model installed" guard was dead. This checks the real
 * install directory and returns `""` when the model isn't there.
 */
object ModelResolver {

    private val modelsDir: File get() = File(DesktopStorage.dataDir, "azp-models")

    fun resolve(property: String): String {
        val name = fileNameFor(property) ?: return ""
        val f = File(modelsDir, name)
        return if (f.exists()) f.absolutePath else ""
    }

    /**
     * An installed sherpa-onnx Whisper bundle (encoder, decoder, tokens), flat in the models directory
     * or in a `whisper` subdirectory of it. Null when none is complete.
     */
    fun whisperBundle(): com.hereliesaz.guillotine.desktop.media.DesktopWhisper.Bundle? =
        com.hereliesaz.guillotine.desktop.media.DesktopWhisper.findBundle(File(modelsDir, "whisper"))
            ?: com.hereliesaz.guillotine.desktop.media.DesktopWhisper.findBundle(modelsDir)
            ?: extractedWhisperDirs().firstNotNullOfOrNull { com.hereliesaz.guillotine.desktop.media.DesktopWhisper.findBundle(it) }

    /**
     * Folders a `sherpa-bundle` store package extracted into (`azp-models/<zip name>/`, e.g. the
     * `com.hereliesaz.azphalt.whisper` bundle), plus one nested level for zips with a top folder.
     */
    private fun extractedWhisperDirs(): List<File> {
        val top = modelsDir.listFiles()?.filter { it.isDirectory && "whisper" in it.name.lowercase() }.orEmpty()
        return top + top.flatMap { d -> d.listFiles()?.filter { it.isDirectory }.orEmpty() }
    }

    /** The on-disk basename an `.azp` install produces for a slot, or null for an unsupported slot. */
    private fun fileNameFor(property: String): String? = when (property) {
        "labelModelPath" -> "mobilenetv3.onnx"
        "audioEventModelPath" -> "yamnet.onnx"
        "faceDetectModelPath" -> "version-RFB-320.onnx"
        "idEmbedModelPath" -> "mobilenetv3.onnx"
        "faceEmbedModelPath" -> "face-embed.onnx"
        "segModelPath" -> "selfie_segmentation.onnx"
        "speechModelPath" -> "vosk-model"
        "stemModelPath" -> "spleeter-2stems"
        "diarizeSegModelPath" -> "segmentation.onnx"
        "diarizeEmbedModelPath" -> "embedding.onnx"
        "vlmModelPath" -> "moondream2.onnx"
        // Parity with the app resolver: slots whose desktop runtimes may still be stubs resolve here too,
        // so an installed .azp is found and an uninstalled one honestly yields "" instead of a fake path.
        "asrModelPath" -> "whisper-base.onnx"
        "ttsModelPath" -> "tts.onnx"
        "denoiseModelPath" -> "gtcrn_simple.onnx"
        "agentModelPath" -> "agent.onnx"
        "effect_depth" -> "midas.onnx"
        "effect_superres" -> "realesrgan.onnx"
        "effect_lowlight" -> "mirnet.onnx"
        "effect_style" -> "style.onnx"
        "inpaintModelPath" -> "lama.onnx"
        else -> null
    }
}
