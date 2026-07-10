package com.hereliesaz.guillotine.ai

import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig

/**
 * On-device speech noise reduction via sherpa-onnx `OfflineSpeechDenoiser` (GTCRN). Removes hiss, hum,
 * and background noise from voice audio. The denoiser resamples internally and outputs 16 kHz.
 * On-device only — audio never leaves the device.
 */
object SherpaDenoiser {

    /**
     * Denoise [pcm] (float [-1, 1] at [sampleRate]) with the GTCRN model at [modelPath], writing the
     * cleaned audio to [outWavPath]. Returns the output duration in ms.
     */
    fun denoiseToWav(modelPath: String, pcm: FloatArray, sampleRate: Int, outWavPath: String): Long {
        val denoiser = OfflineSpeechDenoiser(
            assetManager = null,
            config = OfflineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(
                    gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = modelPath),
                    numThreads = 1,
                    provider = "cpu",
                ),
            ),
        )
        return try {
            val out = denoiser.run(pcm, sampleRate)
            out.save(outWavPath)
            if (out.sampleRate > 0) out.samples.size * 1000L / out.sampleRate else 0L
        } finally {
            denoiser.release()
        }
    }
}
