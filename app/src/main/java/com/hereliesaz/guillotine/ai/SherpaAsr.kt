package com.hereliesaz.guillotine.ai

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Offline speech-to-text via sherpa-onnx (`OfflineRecognizer`). Given a directory holding an extracted
 * sherpa model bundle, it auto-discovers the model files (so any Whisper or transducer bundle drops in)
 * and transcribes 16 kHz mono float PCM. On-device only — audio never leaves the device.
 */
object SherpaAsr {

    /**
     * Transcribe [pcm16k] (16 kHz mono, [-1,1]) using the sherpa model in [modelDir]. Returns the
     * transcript text (possibly empty). Throws if the directory has no recognizable model files.
     */
    fun transcribe(modelDir: String, pcm16k: FloatArray): String {
        val dir = File(modelDir)
        val onnx = dir.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.toList() ?: emptyList()
        val encoder = onnx.firstOrNull { it.name.contains("encoder") }
            ?: throw IllegalStateException("ASR model directory has no encoder .onnx.")
        val decoder = onnx.firstOrNull { it.name.contains("decoder") }
            ?: throw IllegalStateException("ASR model directory has no decoder .onnx.")
        val joiner = onnx.firstOrNull { it.name.contains("joiner") }
        val tokens = (File(dir, "tokens.txt").takeIf { it.isFile }
            ?: dir.listFiles { f -> f.name.endsWith("tokens.txt") }?.firstOrNull())
            ?: throw IllegalStateException("ASR model directory has no tokens.txt.")

        // Transducer bundles ship a joiner; Whisper bundles are just encoder+decoder.
        val modelConfig = if (joiner != null) {
            OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath,
                ),
                tokens = tokens.absolutePath,
                modelType = "transducer",
                numThreads = 2,
            )
        } else {
            OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                ),
                tokens = tokens.absolutePath,
                modelType = "whisper",
                numThreads = 2,
            )
        }
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search",
        )
        val recognizer = OfflineRecognizer(assetManager = null, config = config)
        return try {
            val stream = recognizer.createStream()
            stream.acceptWaveform(pcm16k, 16000)
            recognizer.decode(stream)
            val text = recognizer.getResult(stream).text
            stream.release()
            text
        } finally {
            recognizer.release()
        }
    }
}
