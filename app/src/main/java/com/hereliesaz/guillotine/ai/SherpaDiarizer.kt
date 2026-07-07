package com.hereliesaz.guillotine.ai

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File

/**
 * Offline speaker diarization ("who spoke when") via sherpa-onnx. Pairs a pyannote SEGMENTATION model
 * (a directory holding `model.onnx`) with a speaker-EMBEDDING `.onnx`, and returns time-stamped speaker
 * turns. On-device only — audio never leaves the device.
 */
object SherpaDiarizer {

    /** One diarized turn: its time span (ms) and 0-based speaker index. */
    data class Turn(val startMs: Long, val endMs: Long, val speaker: Int)

    /**
     * Diarize [pcm16k] (16 kHz mono, [-1,1]) using the segmentation model in [segModelDir] and the
     * embedding model file [embedModelPath]. If [numSpeakers] > 0 the count is fixed; else it's inferred.
     */
    fun diarize(segModelDir: String, embedModelPath: String, pcm16k: FloatArray, numSpeakers: Int): List<Turn> {
        val segModel = File(segModelDir, "model.onnx").takeIf { it.isFile }
            ?: File(segModelDir).listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
            ?: throw IllegalStateException("Segmentation model directory has no .onnx.")
        val config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segModel.absolutePath),
                numThreads = 2,
            ),
            embedding = SpeakerEmbeddingExtractorConfig(model = embedModelPath, numThreads = 2),
            clustering = FastClusteringConfig(
                numClusters = if (numSpeakers > 0) numSpeakers else -1,
                threshold = 0.5f,
            ),
        )
        val sd = OfflineSpeakerDiarization(assetManager = null, config = config)
        return try {
            sd.process(pcm16k).map {
                Turn((it.start * 1000).toLong(), (it.end * 1000).toLong(), it.speaker)
            }
        } finally {
            sd.release()
        }
    }
}
