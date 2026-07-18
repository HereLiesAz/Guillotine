package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * On-device neural TTS on the desktop: runs a **VITS / Piper** `.onnx` voice directly through ONNX
 * Runtime (no sherpa-onnx JVM binding) and writes a WAV. The desktop equivalent of Android's
 * `SherpaTts`.
 *
 * The hard part of a VITS voice is turning text into the phoneme-id sequence the model wants. Desktop
 * has no espeak-ng phonemizer, so this supports the two contracts that DON'T need one:
 *  - a **lexicon** voice: a `lexicon.txt` (word → space-separated phonemes) sibling of the model, or
 *  - a **character-level** voice: `tokens.txt` maps individual characters.
 * A voice whose `tokens.txt` is a phoneme set with no lexicon (a plain espeak Piper voice) can't be
 * driven without a phonemizer — [synthesize] throws a clear error in that case instead of emitting
 * garbage.
 */
object DesktopTts {

    data class Result(val wavPath: String, val durationMs: Long)

    /**
     * Synthesize [text] with the VITS model at [modelPath] (its `tokens.txt` / optional `lexicon.txt` /
     * `config.json` are read from the same directory) and write a mono WAV to [outWavPath].
     */
    fun synthesize(modelPath: String, text: String, outWavPath: String, speed: Float = 1f): Result {
        val modelFile = File(modelPath)
        val dir = modelFile.parentFile ?: File(".")
        val tokens = loadTokens(File(dir, "tokens.txt"))
        require(tokens.isNotEmpty()) { "TTS voice is missing tokens.txt next to the model." }
        val lexicon = loadLexicon(File(dir, "lexicon.txt"))
        val sampleRate = readSampleRate(File(dir, "config.json"))

        val ids = tokensToIds(text.trim(), tokens, lexicon)
        require(ids.isNotEmpty()) {
            "Couldn't turn the text into this voice's phonemes. Desktop can't phonemize without espeak-ng — " +
                "install a character-level or lexicon-based .azp voice."
        }
        // Piper/VITS convention: intersperse a blank (pad, id 0) between and around the ids.
        val seq = intersperseBlank(ids)

        val session = DesktopOnnx.session(modelPath)
        val inputs = HashMap<String, OnnxTensor>()
        try {
            val env = DesktopOnnx.environment
            for (name in session.inputNames) {
                val lower = name.lowercase()
                when {
                    lower.contains("length") ->
                        inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(seq.size.toLong())), longArrayOf(1))
                    lower == "scales" || lower.contains("scale") -> {
                        // [noise_scale, length_scale, noise_scale_w]. length_scale = 1/speed (slower = longer).
                        val scales = floatArrayOf(0.667f, 1f / speed.coerceIn(0.5f, 2f), 0.8f)
                        inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(scales), longArrayOf(3))
                    }
                    lower == "sid" || lower.contains("speaker") ->
                        inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(0L)), longArrayOf(1))
                    else -> // the phoneme-id input
                        inputs[name] = OnnxTensor.createTensor(env, LongBuffer.wrap(seq), longArrayOf(1, seq.size.toLong()))
                }
            }
            session.run(inputs).use { result ->
                val out = result.iterator().asSequence().firstOrNull()?.value as? OnnxTensor
                    ?: throw IllegalStateException("The TTS model produced no audio output.")
                val fb = out.floatBuffer
                val audio = FloatArray(fb.remaining()).also { fb.get(it) }
                writeWav(File(outWavPath), audio, sampleRate)
                val durationMs = if (sampleRate > 0) audio.size * 1000L / sampleRate else 0L
                return Result(outWavPath, durationMs)
            }
        } finally {
            inputs.values.forEach { runCatching { it.close() } }
        }
    }

    /** tokens.txt: `symbol id` per line (Piper), or one symbol per line (id = line index). */
    private fun loadTokens(f: File): Map<String, Long> {
        if (!f.isFile) return emptyMap()
        val map = HashMap<String, Long>()
        f.readLines().forEachIndexed { i, line ->
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(Regex("\\s+"))
            when {
                parts.size >= 2 -> parts[1].toLongOrNull()?.let { map[parts[0]] = it }
                parts.size == 1 -> map[parts[0]] = i.toLong()
            }
        }
        return map
    }

    /** lexicon.txt: `word phon1 phon2 …` per line (lowercased word key). */
    private fun loadLexicon(f: File): Map<String, List<String>> {
        if (!f.isFile) return emptyMap()
        val map = HashMap<String, List<String>>()
        f.readLines().forEach { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 2) map[parts[0].lowercase()] = parts.drop(1)
        }
        return map
    }

    private fun readSampleRate(f: File): Int {
        if (!f.isFile) return 22_050
        return runCatching {
            val m = Regex("\"sample_rate\"\\s*:\\s*(\\d+)").find(f.readText())
            m?.groupValues?.get(1)?.toInt()
        }.getOrNull() ?: 22_050
    }

    /**
     * Map text to token ids. A lexicon (word→phonemes) is used when present; otherwise falls back to
     * character-level mapping. Requires at least a fifth of the characters/phonemes to be known, so a
     * phoneme-only voice with no lexicon fails loudly rather than emitting a few stray ids.
     */
    private fun tokensToIds(text: String, tokens: Map<String, Long>, lexicon: Map<String, List<String>>): LongArray {
        val out = ArrayList<Long>()
        var considered = 0
        if (lexicon.isNotEmpty()) {
            for (word in text.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }) {
                val phones = lexicon[word.trim('.', ',', '!', '?', ';', ':')]
                if (phones != null) {
                    considered += phones.size
                    phones.forEach { p -> tokens[p]?.let { out.add(it) } }
                }
            }
        }
        if (out.isEmpty()) {
            // Character-level fallback.
            for (ch in text.lowercase()) {
                if (ch.isWhitespace()) { tokens[" "]?.let { out.add(it) }; continue }
                considered++
                tokens[ch.toString()]?.let { out.add(it) }
            }
        }
        if (considered == 0 || out.size < considered / 5) return LongArray(0)
        return out.toLongArray()
    }

    private fun intersperseBlank(ids: LongArray, blank: Long = 0L): LongArray {
        val out = ArrayList<Long>(ids.size * 2 + 1)
        out.add(blank)
        for (id in ids) { out.add(id); out.add(blank) }
        return out.toLongArray()
    }

    /** Write mono 16-bit PCM WAV from normalized [-1,1] float samples. */
    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val bytesPerSample = 2
        val dataSize = samples.size * bytesPerSample
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)                      // fmt chunk size
        buf.putShort(1)                     // PCM
        buf.putShort(1)                     // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * bytesPerSample) // byte rate
        buf.putShort(bytesPerSample.toShort()) // block align
        buf.putShort(16)                    // bits per sample
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataSize)
        for (s in samples) {
            buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        file.parentFile?.mkdirs()
        file.writeBytes(buf.array())
    }
}
