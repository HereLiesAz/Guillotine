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
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device speech analyzer: transcribes the clip's audio track (Vosk or Whisper) and produces
 * keep/remove segments based on two complementary criteria:
 *
 *  - **Content matching** — what's being said. Transcript text is matched against search terms
 *    from the prompt (e.g. "keep parts where they talk about cooking").
 *  - **Tone matching** — how it's said. Audio features (volume, speech rate) are computed per
 *    transcript segment and matched against tone qualifiers in the prompt (e.g. "keep the parts
 *    where they're yelling", "cut the calm parts").
 *
 * Both criteria are AND-ed when present: "keep excited parts about cooking" keeps only segments
 * that mention cooking AND have emphatic delivery. When only one is present, the other is ignored.
 *
 * Routed to by [Analysis.run] when the prompt contains speech-intent triggers or tone triggers.
 */
class SpeechContentAnalyzer(private val settings: AiSettings) : ClipAnalyzer {

    enum class ToneTag { LOUD, QUIET, FAST, SLOW, EMPHATIC, CALM }

    private data class Intent(
        val terms: List<String>,
        val tones: Set<ToneTag>,
        val keepMatches: Boolean,
    )

    override suspend fun analyze(
        context: Context,
        mediaUri: Uri,
        kind: MediaKind,
        prompt: String,
        durationMs: Long,
        onProgress: (AnalysisProgress) -> Unit,
        checkpoint: () -> Unit,
    ): List<EditSegment> = withContext(Dispatchers.IO) {
        val intent = parseIntent(prompt)
        if (intent.terms.isEmpty() && intent.tones.isEmpty()) {
            throw IllegalStateException(
                "Tell the speech analyzer what to listen for, " +
                    "e.g. \"keep parts where they say cooking\" or \"keep the loud parts\".",
            )
        }

        onProgress(AnalysisProgress("Transcribing audio…", null, 0))
        val cues = Transcription.transcribe(context, settings, mediaUri)

        if (cues.isEmpty()) {
            val action = if (intent.keepMatches) EditAction.REMOVE else EditAction.KEEP
            return@withContext listOf(EditSegment(0, durationMs, action, "no speech detected"))
        }

        val windowRms: FloatArray?
        val peakRms: Float
        if (intent.tones.isNotEmpty()) {
            onProgress(AnalysisProgress("Analyzing audio tone…", 0.5f, 0))
            windowRms = decodeWindowRms(context, mediaUri)
            peakRms = windowRms?.max()?.coerceAtLeast(1e-6f) ?: 1f
        } else {
            windowRms = null
            peakRms = 1f
        }

        onProgress(AnalysisProgress("Matching ${cues.size} segments…", 0.8f, 0))

        val matchedRanges = mutableListOf<LongRange>()
        var matchCount = 0
        for (cue in cues) {
            checkpoint()
            val contentOk = intent.terms.isEmpty() ||
                intent.terms.any { cue.text.lowercase().contains(it) }
            val toneOk = intent.tones.isEmpty() ||
                matchesTone(cue, windowRms, peakRms, intent.tones)

            if (contentOk && toneOk) {
                matchCount++
                matchedRanges += (cue.startMs - PAD_MS).coerceAtLeast(0L)..
                    (cue.endMs + PAD_MS).coerceAtMost(durationMs)
            }
        }

        onProgress(AnalysisProgress("Building segments…", 0.95f, matchCount))
        buildCover(mergeRanges(matchedRanges), durationMs, intent.keepMatches)
    }

    private fun matchesTone(
        cue: TranscriptCue,
        windowRms: FloatArray?,
        peakRms: Float,
        tones: Set<ToneTag>,
    ): Boolean {
        if (windowRms == null || windowRms.isEmpty()) return false

        val startW = (cue.startMs / RMS_WINDOW_MS).toInt().coerceIn(0, windowRms.lastIndex)
        val endW = (cue.endMs / RMS_WINDOW_MS).toInt().coerceIn(startW, windowRms.lastIndex)
        var sum = 0f
        for (i in startW..endW) sum += windowRms[i]
        val avgRms = sum / (endW - startW + 1)
        val relVol = avgRms / peakRms

        val durSec = (cue.endMs - cue.startMs) / 1000f
        val words = cue.text.split(WS).count { it.isNotBlank() }
        val wps = if (durSec > 0.1f) words / durSec else 0f

        val loud = relVol > 0.55f
        val quiet = relVol < 0.15f
        val fast = wps > 3.5f
        val slow = wps < 1.5f

        return tones.any { tag ->
            when (tag) {
                ToneTag.LOUD -> loud
                ToneTag.QUIET -> quiet
                ToneTag.FAST -> fast
                ToneTag.SLOW -> slow
                ToneTag.EMPHATIC -> loud && fast
                ToneTag.CALM -> !loud && slow
            }
        }
    }

    private fun parseIntent(prompt: String): Intent {
        val p = prompt.lowercase()
        val removeWords = listOf("cut", "remove", "delete", "without", "drop", "trim", "no ")
        val keepMatches = removeWords.none { p.contains(it) }

        val tones = mutableSetOf<ToneTag>()
        for ((trigger, tags) in TONE_MAP) {
            if (trigger in p) tones += tags
        }

        val allStop = STOP_WORDS + TONE_STOP_WORDS
        val terms = Regex("[a-z]+").findAll(p).map { it.value }
            .filter { it.length > 2 && it !in allStop }
            .distinct().toList()

        return Intent(terms, tones, keepMatches)
    }

    private fun mergeRanges(ranges: List<LongRange>): List<LongRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val out = mutableListOf(sorted.first())
        for (r in sorted.drop(1)) {
            val last = out.last()
            if (r.first <= last.last) out[out.lastIndex] = last.first..max(last.last, r.last)
            else out += r
        }
        return out
    }

    private fun buildCover(
        matched: List<LongRange>,
        dur: Long,
        keepMatches: Boolean,
    ): List<EditSegment> {
        val matchAction = if (keepMatches) EditAction.KEEP else EditAction.REMOVE
        val other = if (keepMatches) EditAction.REMOVE else EditAction.KEEP
        val out = mutableListOf<EditSegment>()
        var cursor = 0L
        for (r in matched) {
            val s = r.first.coerceIn(0, dur)
            val e = r.last.coerceIn(0, dur)
            if (e <= s) continue
            if (s > cursor) out += EditSegment(cursor, s, other, "no speech match")
            out += EditSegment(s, e, matchAction, "speech match")
            cursor = e
        }
        if (cursor < dur) out += EditSegment(cursor, dur, other, "no speech match")
        return out
    }

    /** Decode the first audio track to PCM and return per-window RMS values (100 ms windows). */
    private fun decodeWindowRms(context: Context, uri: Uri): FloatArray? {
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
        if (trackIndex < 0 || format == null) { extractor.release(); return null }
        extractor.selectTrack(trackIndex)

        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val windowSamples = (sampleRate * channels * RMS_WINDOW_MS / 1000L).toInt().coerceAtLeast(1)

        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val rmsList = mutableListOf<Float>()
        var sumSq = 0.0
        var count = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = codec.getInputBuffer(idx)!!
                        val sz = extractor.readSampleData(buf, 0)
                        if (sz < 0) {
                            codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(idx, 0, sz, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val shorts = outBuf.asShortBuffer()
                        while (shorts.hasRemaining()) {
                            val s = shorts.get() / 32768.0
                            sumSq += s * s
                            count++
                            if (count >= windowSamples) {
                                rmsList += sqrt(sumSq / count).toFloat()
                                sumSq = 0.0; count = 0
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            if (count > 0) rmsList += sqrt(sumSq / count).toFloat()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }
        return rmsList.toFloatArray()
    }

    companion object {
        private const val PAD_MS = 500L
        private const val RMS_WINDOW_MS = 100L
        private val WS = Regex("\\s+")

        private val STOP_WORDS = setOf(
            "cut", "keep", "only", "remove", "delete", "the", "and", "with", "without",
            "that", "has", "have", "are", "where", "when", "there", "show", "shows",
            "clip", "clips", "part", "parts", "footage", "scene", "scenes", "frame",
            "frames", "out", "any", "all", "for", "into", "drop", "trim",
            "says", "said", "saying", "say", "talking", "talks", "talk", "about",
            "mentioned", "mentions", "mention", "speaking", "speaks", "speak",
            "discusses", "discussed", "discussing", "discussion",
            "told", "tells", "telling", "transcript", "dialogue", "dialog",
            "spoken", "voice", "hear", "heard", "hearing", "listen",
            "they", "them", "their", "this", "these", "those", "what",
            "being", "were", "was", "not", "don", "really", "very", "something",
        )

        private val TONE_STOP_WORDS = setOf(
            "loud", "loudly", "yelling", "shouting", "screaming",
            "whispering", "softly", "gently", "quietly",
            "fast", "rapid", "rapidly", "quickly",
            "slow", "slowly", "deliberate", "deliberately",
            "excited", "enthusiastic", "energetic", "animated", "passionate", "intense",
            "calm", "relaxed", "mellow", "chill",
            "sarcastic", "sarcasm", "deadpan", "ironic", "irony", "dry",
            "angry", "furious", "heated", "mad",
            "emotional", "emphatic", "emphatically",
            "monotone", "monotonous", "flat", "boring",
            "tone",
        )

        private val TONE_MAP: List<Pair<String, Set<ToneTag>>> = listOf(
            "yelling" to setOf(ToneTag.LOUD),
            "shouting" to setOf(ToneTag.LOUD),
            "screaming" to setOf(ToneTag.LOUD),
            "loud" to setOf(ToneTag.LOUD),

            "whispering" to setOf(ToneTag.QUIET),
            "softly" to setOf(ToneTag.QUIET),
            "gently" to setOf(ToneTag.QUIET),

            "rapid" to setOf(ToneTag.FAST),
            "quickly" to setOf(ToneTag.FAST),
            "fast" to setOf(ToneTag.FAST),

            "slowly" to setOf(ToneTag.SLOW),
            "deliberate" to setOf(ToneTag.SLOW),
            "slow" to setOf(ToneTag.SLOW),

            "excited" to setOf(ToneTag.EMPHATIC),
            "enthusiastic" to setOf(ToneTag.EMPHATIC),
            "energetic" to setOf(ToneTag.EMPHATIC),
            "animated" to setOf(ToneTag.EMPHATIC),
            "passionate" to setOf(ToneTag.EMPHATIC),
            "intense" to setOf(ToneTag.EMPHATIC),
            "emphatic" to setOf(ToneTag.EMPHATIC),

            "calm" to setOf(ToneTag.CALM),
            "relaxed" to setOf(ToneTag.CALM),
            "mellow" to setOf(ToneTag.CALM),
            "chill" to setOf(ToneTag.CALM),

            // Approximations: sarcasm → deliberate pace, anger → loud + fast.
            "sarcastic" to setOf(ToneTag.SLOW),
            "sarcasm" to setOf(ToneTag.SLOW),
            "deadpan" to setOf(ToneTag.SLOW),
            "ironic" to setOf(ToneTag.SLOW),
            "angry" to setOf(ToneTag.LOUD, ToneTag.FAST),
            "furious" to setOf(ToneTag.LOUD, ToneTag.FAST),
            "heated" to setOf(ToneTag.LOUD, ToneTag.FAST),
            "monotone" to setOf(ToneTag.CALM),
            "monotonous" to setOf(ToneTag.CALM),
        )

        private val SPEECH_TRIGGERS = listOf(
            "says", "said", "saying",
            "talking about", "talks about", "talk about",
            "mentions", "mentioned", "mention",
            "speaking about", "speaks about",
            "discusses", "discussed", "discussing",
            "telling", "told",
            "transcript", "dialogue", "dialog",
        )

        private val TONE_TRIGGERS = listOf(
            "yelling", "shouting", "screaming",
            "whispering",
            "excited", "enthusiastic", "energetic", "animated",
            "sarcastic", "sarcasm", "deadpan", "ironic",
            "angry", "furious", "heated",
            "emotional", "passionate",
            "monotone", "monotonous",
            "emphatic", "emphatically",
            "tone of voice",
        )

        fun isSpeechContentIntent(prompt: String): Boolean {
            val p = prompt.lowercase()
            return SPEECH_TRIGGERS.any { it in p } || TONE_TRIGGERS.any { it in p }
        }
    }
}
