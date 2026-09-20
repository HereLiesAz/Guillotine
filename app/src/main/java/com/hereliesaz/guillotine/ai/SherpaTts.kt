package com.hereliesaz.guillotine.ai

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKittenModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Offline text-to-speech via sherpa-onnx (`OfflineTts`). Given a directory holding an extracted
 * Piper/VITS, KittenTTS, or Kokoro bundle, it auto-discovers the family and synthesizes a WAV file.
 * On-device only — text and audio never leave the device.
 */
object SherpaTts {

    /** A synthesized clip: the WAV file path and its duration in milliseconds. */
    data class Result(val wavPath: String, val durationMs: Long)

    /**
     * Synthesize [text] with the voice in [modelDir], writing a WAV to [outWavPath]. Returns the WAV
     * path and its duration. Throws if the directory has no recognizable model files.
     */
    fun synthesize(modelDir: String, text: String, outWavPath: String, speed: Float = 1.0f): Result {
        val dir = File(modelDir)
        val model = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.firstOrNull()
            ?: throw IllegalStateException("TTS voice directory has no .onnx model.")
        val tokens = File(dir, "tokens.txt")
        val dataDir = File(dir, "espeak-ng-data")
        val lexicon = File(dir, "lexicon.txt")
        val voices = File(dir, "voices.bin")
        val name = dir.name.lowercase()

        val modelConfig = when {
            voices.isFile && "kitten" in name -> OfflineTtsModelConfig(
                kitten = OfflineTtsKittenModelConfig(
                    model = model.absolutePath,
                    voices = voices.absolutePath,
                    tokens = if (tokens.isFile) tokens.absolutePath else "",
                    dataDir = if (dataDir.isDirectory) dataDir.absolutePath else "",
                ),
                numThreads = 2,
            )
            voices.isFile && "kokoro" in name -> OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = model.absolutePath,
                    voices = voices.absolutePath,
                    tokens = if (tokens.isFile) tokens.absolutePath else "",
                    dataDir = if (dataDir.isDirectory) dataDir.absolutePath else "",
                    lexicon = if (lexicon.isFile) lexicon.absolutePath else "",
                    lang = "en",
                ),
                numThreads = 2,
            )
            else -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = model.absolutePath,
                    lexicon = if (lexicon.isFile) lexicon.absolutePath else "",
                    tokens = if (tokens.isFile) tokens.absolutePath else "",
                    dataDir = if (dataDir.isDirectory) dataDir.absolutePath else "",
                ),
                numThreads = 2,
            )
        }
        val config = OfflineTtsConfig(model = modelConfig)
        val tts = OfflineTts(assetManager = null, config = config)
        return try {
            val audio = tts.generate(text = text, sid = 0, speed = speed)
            audio.save(outWavPath)
            val durationMs =
                if (audio.sampleRate > 0) audio.samples.size * 1000L / audio.sampleRate else 0L
            Result(outWavPath, durationMs)
        } finally {
            tts.release()
        }
    }
}
