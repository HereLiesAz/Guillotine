package com.hereliesaz.guillotine.ai.tflite

import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.io.File

/**
 * On-device audio-event classifier (YAMNet, raw LiteRT `Interpreter`). Powers "find the highlights /
 * best moments" — it tags each ~1-second window of a clip's audio with the loudest of 521 AudioSet
 * classes and reports the windows that contain an *exciting* event (applause, cheering, laughter,
 * music, screaming, a roaring crowd…).
 *
 * The standard TF-Hub YAMNet classification export takes a FIXED 15600-sample (0.975 s @ 16 kHz) mono
 * waveform `[15600]` float32 in [-1,1] and returns `[1,521]` class scores. On-device only — audio never
 * leaves the device.
 */
class YamnetClassifier(modelPath: String) : Closeable {

    private val interpreter: Interpreter? = runCatching {
        Interpreter(File(modelPath), Interpreter.Options().apply { setNumThreads(2) })
    }.getOrNull()

    val available: Boolean get() = interpreter != null

    /** One classified window: its start (ms into the decoded audio), the winning highlight label, and score. */
    data class Hit(val startMs: Long, val label: String, val score: Float)

    /**
     * Classify [pcm16k] (16 kHz mono, [-1,1]) in non-overlapping 0.975 s frames and return, for each
     * frame whose strongest *highlight* class clears [threshold], a [Hit]. Non-highlight frames (plain
     * speech, silence, ambient) are dropped. Frames are hopped by [hopSamples] (default = one full frame).
     */
    fun scanHighlights(
        pcm16k: FloatArray,
        threshold: Float = 0.3f,
        hopSamples: Int = FRAME,
    ): List<Hit> {
        val itp = interpreter ?: return emptyList()
        if (pcm16k.size < FRAME) return emptyList()
        val input = FloatArray(FRAME)
        val output = Array(1) { FloatArray(NUM_CLASSES) }
        val hits = ArrayList<Hit>()
        var offset = 0
        while (offset + FRAME <= pcm16k.size) {
            System.arraycopy(pcm16k, offset, input, 0, FRAME)
            val ok = runCatching {
                output[0].fill(0f)
                itp.run(input, output)
            }.isSuccess
            if (ok) {
                var bestIdx = -1
                var bestScore = 0f
                for ((idx, label) in HIGHLIGHT_CLASSES) {
                    val s = output[0].getOrElse(idx) { 0f }
                    if (s > bestScore) { bestScore = s; bestIdx = idx }
                }
                if (bestIdx >= 0 && bestScore >= threshold) {
                    hits += Hit(offset * 1000L / SAMPLE_RATE, HIGHLIGHT_CLASSES.getValue(bestIdx), bestScore)
                }
            }
            offset += hopSamples.coerceAtLeast(FRAME / 4)
        }
        return hits
    }

    override fun close() {
        runCatching { interpreter?.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        /** YAMNet's fixed input length: 0.975 s at 16 kHz. */
        const val FRAME = 15_600
        const val NUM_CLASSES = 521

        /**
         * AudioSet class indices (from the canonical `yamnet_class_map.csv`, fixed order) that signal an
         * exciting / highlight-worthy moment, mapped to a short label. Crowd / Chatter / Hubbub are the
         * contiguous "crowd noise" trio (63/64/65).
         */
        val HIGHLIGHT_CLASSES: Map<Int, String> = mapOf(
            10 to "children shouting",
            11 to "screaming",
            13 to "laughter",
            35 to "whistling",
            58 to "clapping",
            61 to "cheering",
            62 to "applause",
            63 to "chatter",
            64 to "crowd",
            65 to "crowd noise",
            132 to "music",
        )

        /**
         * Linear-resample mono [src] from [srcRate] Hz to [SAMPLE_RATE] (16 kHz) — YAMNet's required
         * rate. [PcmDecoder] only decimates by an integer stride, so a resample is needed to hit 16 kHz
         * exactly. Returns [src] unchanged when already at 16 kHz.
         */
        fun resampleTo16k(src: FloatArray, srcRate: Int): FloatArray {
            if (srcRate == SAMPLE_RATE || src.isEmpty()) return src
            val ratio = SAMPLE_RATE.toDouble() / srcRate
            val outLen = (src.size * ratio).toInt().coerceAtLeast(1)
            val out = FloatArray(outLen)
            for (i in 0 until outLen) {
                val srcPos = i / ratio
                val i0 = srcPos.toInt()
                val i1 = (i0 + 1).coerceAtMost(src.size - 1)
                val frac = (srcPos - i0).toFloat()
                out[i] = src[i0] * (1f - frac) + src[i1] * frac
            }
            return out
        }
    }
}
