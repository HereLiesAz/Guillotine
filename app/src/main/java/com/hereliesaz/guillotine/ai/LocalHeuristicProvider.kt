package com.hereliesaz.guillotine.ai

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.hereliesaz.guillotine.model.EditAction
import com.hereliesaz.guillotine.model.EditSegment
import com.hereliesaz.guillotine.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Free, on-device, no-key analyzer: decodes the audio track to PCM and marks
 * quiet stretches as 'remove' and audible stretches as 'keep' (a "cut the
 * silences" heuristic). Falls back to keeping the whole clip if there's no audio
 * or decoding fails. No network, no API key — always available.
 */
object LocalHeuristicProvider : ClipAnalyzer {

    private const val WINDOW_MS = 100L
    private const val SILENCE_FRACTION = 0.06f   // window RMS below 6% of peak => silent
    private const val PAD_MS = 200L              // keep a little around audible parts
    private const val MIN_REMOVE_MS = 400L       // ignore tiny gaps

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
    ): List<EditSegment> = withContext(Dispatchers.Default) {
        val keepWhole = listOf(EditSegment(0, durationMs, EditAction.KEEP, "Kept (local heuristic)"))
        if (kind == MediaKind.IMAGE) return@withContext keepWhole

        val rms = runCatching { computeWindowRms(context, mediaUri) }.getOrNull()
            ?: return@withContext keepWhole
        if (rms.isEmpty()) return@withContext keepWhole

        val peak = rms.max().coerceAtLeast(1e-6f)
        val threshold = peak * SILENCE_FRACTION
        // Classify each window as audible/silent.
        val audible = BooleanArray(rms.size) { rms[it] >= threshold }

        // Build remove ranges from runs of silence longer than MIN_REMOVE_MS,
        // shrunk by PAD_MS on each side to avoid clipping speech onsets.
        val segments = mutableListOf<EditSegment>()
        var i = 0
        var cursor = 0L
        while (i < audible.size) {
            if (audible[i]) { i++; continue }
            val startW = i
            while (i < audible.size && !audible[i]) i++
            var s = startW * WINDOW_MS + PAD_MS
            var e = i * WINDOW_MS - PAD_MS
            if (e - s >= MIN_REMOVE_MS) {
                s = s.coerceIn(0, durationMs)
                e = e.coerceIn(0, durationMs)
                if (s > cursor) segments += EditSegment(cursor, s, EditAction.KEEP, "Audible")
                segments += EditSegment(s, e, EditAction.REMOVE, "Silence")
                cursor = e
            }
        }
        if (cursor < durationMs) segments += EditSegment(cursor, durationMs, EditAction.KEEP, "Audible")
        if (segments.isEmpty()) keepWhole else segments
    }

    /** Decode the first audio track to PCM16 and return per-window RMS values. */
    private fun computeWindowRms(context: Context, uri: Uri): List<Float> {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (t in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(t)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = t; format = f; break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release(); return emptyList()
        }
        extractor.selectTrack(trackIndex)

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val windowSamples = (sampleRate * channels * WINDOW_MS / 1000L).toInt().coerceAtLeast(1)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val rmsList = mutableListOf<Float>()
        var sumSquares = 0.0
        var sampleCount = 0
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(inBuf, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuf = codec.getOutputBuffer(outIndex)
                    if (outBuf != null && info.size > 0) {
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            val s = shorts.get() / 32768.0
                            sumSquares += s * s
                            sampleCount++
                            if (sampleCount >= windowSamples) {
                                rmsList += sqrt(sumSquares / sampleCount).toFloat()
                                sumSquares = 0.0; sampleCount = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
            if (sampleCount > 0) rmsList += sqrt(sumSquares / sampleCount).toFloat()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        return rmsList
    }
}
