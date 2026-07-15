package com.hereliesaz.guillotine.desktop.media

/**
 * On-device audio-event classifier (YAMNet) for the desktop (JVM) build via a user-supplied ONNX
 * export — the desktop analogue of `:app`'s TFLite `YamnetClassifier`. Powers `find_highlights`
 * ("find the best moments"): each ~1 s window of a clip's audio is tagged with the loudest of 521
 * AudioSet classes and the windows containing an *exciting* event (applause, cheering, laughter,
 * music, screaming, a roaring crowd…) are returned. Pure JVM; audio never leaves the machine.
 *
 * The classifier is the standard TF-Hub YAMNet classification model exported to ONNX: it takes a
 * FIXED 15600-sample (0.975 s @ 16 kHz) mono waveform (`[15600]` float32 in `[-1,1]`) and returns
 * `[1, 521]` class scores as its first output. Feed 16 kHz mono PCM (decode via
 * `DesktopMediaDecoder.decodePcmMono(uri, 16_000)`); nothing else is needed.
 */
object DesktopYamnet {

    const val SAMPLE_RATE = 16_000

    /** YAMNet's fixed input length: 0.975 s at 16 kHz. */
    const val FRAME = 15_600
    const val NUM_CLASSES = 521

    /** One classified window: its start (ms into the decoded audio), the winning label, and score. */
    data class Hit(val startMs: Long, val label: String, val score: Float)

    /**
     * AudioSet class indices (canonical `yamnet_class_map.csv` order) that signal a highlight-worthy
     * moment, mapped to a short label — identical to the Android classifier's set. Crowd / Chatter /
     * Hubbub are the contiguous "crowd noise" trio (63/64/65).
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
     * Classify [pcm16k] (16 kHz mono, `[-1,1]`) in non-overlapping [FRAME]-sample windows and return,
     * for each window whose strongest *highlight* class clears [threshold], a [Hit]. Non-highlight
     * windows (speech, silence, ambient) are dropped. Loads (and caches) the ONNX model at [modelPath]
     * via [DesktopOnnx]; throws if the model can't be loaded.
     */
    fun scanHighlights(
        modelPath: String,
        pcm16k: FloatArray,
        threshold: Float = 0.3f,
        hopSamples: Int = FRAME,
    ): List<Hit> {
        if (pcm16k.size < FRAME) return emptyList()
        val session = DesktopOnnx.session(modelPath)
        val hop = hopSamples.coerceAtLeast(FRAME / 4)
        val frame = FloatArray(FRAME)
        val shape = longArrayOf(FRAME.toLong())   // fixed per call — hoisted out of the frame loop
        val hits = ArrayList<Hit>()
        var offset = 0
        while (offset + FRAME <= pcm16k.size) {
            System.arraycopy(pcm16k, offset, frame, 0, FRAME)
            // Let a bad/corrupt model surface a clear error rather than silently yielding no highlights.
            val scores = DesktopOnnx.runSingle(session, frame, shape)
            var bestIdx = -1
            var bestScore = 0f
            for ((idx, _) in HIGHLIGHT_CLASSES) {
                val s = scores.getOrElse(idx) { 0f }
                if (s > bestScore) { bestScore = s; bestIdx = idx }
            }
            if (bestIdx >= 0 && bestScore >= threshold) {
                hits += Hit(offset * 1000L / SAMPLE_RATE, HIGHLIGHT_CLASSES.getValue(bestIdx), bestScore)
            }
            offset += hop
        }
        return hits
    }
}
