package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.TensorInfo
import com.hereliesaz.guillotine.ai.Fft
import java.nio.FloatBuffer
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * On-device speaker diarization ("who spoke when") on the desktop, built from the pieces desktop
 * already has rather than a sherpa-onnx binding: **energy VAD** to find speech, an **ONNX speaker-
 * embedding** model per speech window, and **agglomerative clustering** of the embeddings by cosine
 * distance. The desktop counterpart to Android's `SherpaDiarizer`.
 *
 * The embedding model's input contract is auto-detected: a rank-3 `[1, T, 80]` input is fed log-mel
 * fbank features; anything else is fed the raw 16 kHz waveform. Diarization quality tracks the installed
 * embedding model — this is a genuine on-device pipeline, not a fake, but it's a lighter approach than a
 * full pyannote segmentation stack.
 */
object DesktopDiarizer {

    /** A contiguous stretch attributed to one [speaker], in ms from the start of the decoded audio. */
    data class Turn(val speaker: Int, val startMs: Long, val endMs: Long)

    private const val SR = 16_000
    private const val WIN = 400      // 25 ms analysis frame
    private const val HOP = 160      // 10 ms hop
    private const val FFT = 512
    private const val MELS = 80

    /**
     * Diarize [samples] (16 kHz mono, normalized). [numSpeakers] > 0 forces that many clusters; 0 infers
     * from a cosine-distance threshold. Returns time-ordered speaker turns.
     */
    fun diarize(embedModelPath: String, samples: FloatArray, numSpeakers: Int): List<Turn> {
        val speech = detectSpeech(samples)
        if (speech.isEmpty()) return emptyList()

        // Split each speech region into ~2 s windows and embed each.
        data class Win(val startMs: Long, val endMs: Long, val emb: FloatArray)
        val winSamples = 2 * SR
        val windows = ArrayList<Win>()
        val session = DesktopOnnx.session(embedModelPath)
        // Detect a fbank input and, if so, which axis is the mel axis: `[1, 80, T]` (mel-first) or
        // `[1, T, 80]` (mel-last). Anything else is fed the raw waveform.
        val inShape = runCatching { (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape }.getOrNull()
        val melFirst = inShape != null && inShape.size == 3 && inShape[1] == MELS.toLong()
        val melLast = inShape != null && inShape.size == 3 && inShape[2] == MELS.toLong()
        val wantsFbank = melFirst || melLast
        for ((s, e) in speech) {
            var w = s
            while (w < e) {
                val end = min(e, w + winSamples)
                if (end - w >= SR / 2) { // need >= 0.5 s to embed
                    val seg = samples.copyOfRange(w, end)
                    val emb = embed(session, seg, wantsFbank, melFirst)
                    if (emb != null) windows.add(Win(w * 1000L / SR, end * 1000L / SR, l2norm(emb)))
                }
                w += winSamples
            }
        }
        if (windows.isEmpty()) return emptyList()
        if (windows.size == 1) return listOf(Turn(0, windows[0].startMs, windows[0].endMs))

        val labels = cluster(windows.map { it.emb }, numSpeakers)

        // Merge adjacent windows with the same label into turns.
        val turns = ArrayList<Turn>()
        var curLabel = labels[0]
        var curStart = windows[0].startMs
        var curEnd = windows[0].endMs
        for (i in 1 until windows.size) {
            if (labels[i] == curLabel && windows[i].startMs - curEnd <= 400) {
                curEnd = windows[i].endMs
            } else {
                turns.add(Turn(curLabel, curStart, curEnd))
                curLabel = labels[i]; curStart = windows[i].startMs; curEnd = windows[i].endMs
            }
        }
        turns.add(Turn(curLabel, curStart, curEnd))
        return turns
    }

    // ---- VAD -----------------------------------------------------------------

    /** Energy VAD → list of [startSample, endSample) speech regions. */
    private fun detectSpeech(samples: FloatArray): List<Pair<Int, Int>> {
        if (samples.size < WIN) return emptyList()
        val nFrames = (samples.size - WIN) / HOP + 1
        val rms = FloatArray(nFrames)
        for (f in 0 until nFrames) {
            var sum = 0.0
            val off = f * HOP
            for (i in 0 until WIN) { val v = samples[off + i]; sum += v * v }
            rms[f] = sqrt(sum / WIN).toFloat()
        }
        val sorted = rms.sorted()
        val floor = sorted[(sorted.size * 0.2).toInt().coerceIn(0, sorted.size - 1)]
        val peak = sorted[(sorted.size * 0.95).toInt().coerceIn(0, sorted.size - 1)]
        val thresh = (floor + (peak - floor) * 0.15f).coerceAtLeast(0.008f)

        val regions = ArrayList<Pair<Int, Int>>()
        var inSpeech = false
        var start = 0
        var lastVoiced = 0
        for (f in 0 until nFrames) {
            val voiced = rms[f] >= thresh
            val pos = f * HOP
            if (voiced) {
                if (!inSpeech) { inSpeech = true; start = pos }
                lastVoiced = pos + WIN
            } else if (inSpeech && pos - lastVoiced > SR / 4) { // >0.25 s silence closes a region
                regions.add(start to lastVoiced)
                inSpeech = false
            }
        }
        if (inSpeech) regions.add(start to lastVoiced)
        // Drop regions shorter than 0.4 s.
        return regions.filter { it.second - it.first >= SR * 2 / 5 }
    }

    // ---- embedding -----------------------------------------------------------

    private fun embed(session: ai.onnxruntime.OrtSession, seg: FloatArray, wantsFbank: Boolean, melFirst: Boolean): FloatArray? = runCatching {
        val env = DesktopOnnx.environment
        val name = session.inputNames.first()
        val tensor = if (wantsFbank) {
            val feats = fbank(seg)                 // [T][80]
            val t = feats.size
            val flat = FloatArray(t * MELS)
            if (melFirst) {
                // [1, 80, T]: mel-major — flat[m * T + i].
                for (i in 0 until t) for (m in 0 until MELS) flat[m * t + i] = feats[i][m]
                OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, MELS.toLong(), t.toLong()))
            } else {
                // [1, T, 80]: frame-major.
                for (i in 0 until t) System.arraycopy(feats[i], 0, flat, i * MELS, MELS)
                OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, t.toLong(), MELS.toLong()))
            }
        } else {
            OnnxTensor.createTensor(env, FloatBuffer.wrap(seg), longArrayOf(1, seg.size.toLong()))
        }
        tensor.use { input ->
            session.run(mapOf(name to input)).use { result ->
                val out = result.iterator().asSequence().firstOrNull()?.value as? OnnxTensor ?: return null
                val fb = out.floatBuffer
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
        }
    }.getOrNull()

    /** Log-mel fbank: 80 bins, 25 ms/10 ms, Hamming, per-utterance mean-normalized. */
    private fun fbank(samples: FloatArray): Array<FloatArray> {
        val nFrames = ((samples.size - WIN) / HOP + 1).coerceAtLeast(1)
        val filters = melFilters
        val window = hamming
        val out = Array(nFrames) { FloatArray(MELS) }
        val re = DoubleArray(FFT)
        val im = DoubleArray(FFT)
        for (f in 0 until nFrames) {
            val off = f * HOP
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until WIN) {
                val idx = off + i
                if (idx < samples.size) re[i] = samples[idx] * window[i]
            }
            Fft.transform(re, im, false)
            // Power spectrum over the first FFT/2+1 bins.
            val power = DoubleArray(FFT / 2 + 1)
            for (k in 0..FFT / 2) power[k] = re[k] * re[k] + im[k] * im[k]
            for (m in 0 until MELS) {
                var sum = 0.0
                val filt = filters[m]
                for (k in filt.indices) sum += filt[k] * power[k]
                out[f][m] = ln((sum + 1e-6)).toFloat()
            }
        }
        // Cepstral mean normalization across the utterance.
        for (m in 0 until MELS) {
            var mean = 0.0
            for (f in 0 until nFrames) mean += out[f][m]
            mean /= nFrames
            for (f in 0 until nFrames) out[f][m] = (out[f][m] - mean).toFloat()
        }
        return out
    }

    private val hamming: DoubleArray by lazy {
        DoubleArray(WIN) { 0.54 - 0.46 * Math.cos(2.0 * Math.PI * it / (WIN - 1)) }
    }

    /** Triangular mel filterbank over the [0, 8000] Hz range, one weight row per mel bin. */
    private val melFilters: Array<DoubleArray> by lazy {
        val nBins = FFT / 2 + 1
        fun hzToMel(h: Double) = 2595.0 * Math.log10(1.0 + h / 700.0)
        fun melToHz(m: Double) = 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0)
        val melLo = hzToMel(0.0)
        val melHi = hzToMel(SR / 2.0)
        val points = DoubleArray(MELS + 2) { melToHz(melLo + (melHi - melLo) * it / (MELS + 1)) }
        val binHz = DoubleArray(nBins) { it * SR.toDouble() / FFT }
        Array(MELS) { m ->
            val lo = points[m]; val ctr = points[m + 1]; val hi = points[m + 2]
            DoubleArray(nBins) { k ->
                val h = binHz[k]
                when {
                    h < lo || h > hi -> 0.0
                    h <= ctr -> (h - lo) / (ctr - lo)
                    else -> (hi - h) / (hi - ctr)
                }
            }
        }
    }

    // ---- clustering ----------------------------------------------------------

    private fun l2norm(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        n = sqrt(n)
        if (n < 1e-9) return v
        return FloatArray(v.size) { (v[it] / n).toFloat() }
    }

    private fun cosineDist(a: FloatArray, b: FloatArray): Float {
        var dot = 0.0
        val n = min(a.size, b.size)
        for (i in 0 until n) dot += a[i] * b[i]
        return (1.0 - dot).toFloat() // vectors are L2-normalized
    }

    /**
     * Average-link agglomerative clustering. Merges the closest pair until [numSpeakers] clusters remain
     * (when > 0) or the closest distance exceeds a threshold (when inferring).
     */
    private fun cluster(embs: List<FloatArray>, numSpeakers: Int): IntArray {
        val n = embs.size
        val clusters = ArrayList<MutableList<Int>>()
        for (i in 0 until n) clusters.add(mutableListOf(i))
        val threshold = 0.55f

        // Precompute the pairwise distance matrix once (O(N^2) vector ops) instead of recomputing
        // cosine distances on every merge iteration.
        val dist = Array(n) { FloatArray(n) }
        for (i in 0 until n) for (j in i + 1 until n) {
            val d = cosineDist(embs[i], embs[j])
            dist[i][j] = d; dist[j][i] = d
        }

        while (clusters.size > 1) {
            var bestI = -1; var bestJ = -1; var bestD = Float.MAX_VALUE
            for (i in clusters.indices) {
                for (j in i + 1 until clusters.size) {
                    var sum = 0f; var cnt = 0
                    for (a in clusters[i]) for (b in clusters[j]) { sum += dist[a][b]; cnt++ }
                    val d = if (cnt > 0) sum / cnt else Float.MAX_VALUE
                    if (d < bestD) { bestD = d; bestI = i; bestJ = j }
                }
            }
            if (bestI < 0) break
            val stop = if (numSpeakers > 0) clusters.size <= numSpeakers else bestD > threshold
            if (stop) break
            clusters[bestI].addAll(clusters[bestJ])
            clusters.removeAt(bestJ)
        }
        val labels = IntArray(n)
        clusters.forEachIndexed { label, members -> members.forEach { labels[it] = label } }
        return labels
    }
}
