package com.hereliesaz.guillotine.desktop.media

import ai.onnxruntime.OnnxTensor
import com.hereliesaz.guillotine.ai.WhisperFrontend
import java.io.File
import java.nio.LongBuffer

/**
 * Offline Whisper on desktop, driven directly through ONNX Runtime (sherpa-onnx, which Android uses,
 * has no clean JVM distribution). Takes a sherpa-onnx Whisper bundle (the same files Android installs):
 * an encoder (`mel [1, 80, T]` → per-layer cross-attention K/V), a decoder with a self-attention KV
 * cache (`tokens`, caches, cross K/V, `offset` → `logits`, caches) and a `tokens.txt` vocabulary.
 *
 * Audio is transcribed in 30-second windows with greedy decoding and no timestamps. Checked against
 * sherpa-onnx's own recogniser on its test clips. 16 kHz mono in; nothing leaves the machine.
 */
object DesktopWhisper {

    data class Bundle(val encoder: File, val decoder: File, val tokens: File)

    private const val MAX_TOKENS = 224

    /**
     * Find a Whisper bundle in [dir]: `*encoder*.onnx`, `*decoder*.onnx` and `*tokens.txt`, preferring
     * the int8 models when both precisions are present. Null when any piece is missing.
     */
    fun findBundle(dir: File): Bundle? {
        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        fun pick(part: String): File? = files
            .filter { it.name.endsWith(".onnx") && part in it.name.lowercase() }
            .sortedByDescending { "int8" in it.name }
            .firstOrNull()
        val encoder = pick("encoder") ?: return null
        val decoder = pick("decoder") ?: return null
        val tokens = files.firstOrNull { it.name.endsWith("tokens.txt") } ?: return null
        return Bundle(encoder, decoder, tokens)
    }

    /** Transcribe [pcm16k] (16 kHz mono, [-1, 1]) with [bundle]. Returns the text, possibly empty. */
    fun transcribe(bundle: Bundle, pcm16k: FloatArray): String {
        val encoder = DesktopOnnx.session(bundle.encoder.absolutePath)
        val decoder = DesktopOnnx.session(bundle.decoder.absolutePath)
        val meta = runCatching { encoder.metadata.customMetadata }.getOrDefault(emptyMap())
        fun metaInt(key: String, default: Int) = meta[key]?.trim()?.toIntOrNull() ?: default
        val sot = metaInt("sot", 50257)
        val eot = metaInt("eot", 50256)
        val noTimestamps = metaInt("no_timestamps", 50362)
        val layers = metaInt("n_text_layer", 4)
        val textCtx = metaInt("n_text_ctx", 448)
        val state = metaInt("n_text_state", 384)
        // English-only models start with <|startoftranscript|>; multilingual ones also name the
        // language and the task.
        val prompt = if (meta["is_multilingual"]?.trim() == "1") {
            val codes = meta["all_language_codes"].orEmpty().split(',')
            val ids = meta["all_language_tokens"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }
            val en = codes.indexOf("en").takeIf { it >= 0 }?.let { ids.getOrNull(it) }
            listOfNotNull(sot, en, metaInt("transcribe", 50359), noTimestamps)
        } else {
            listOf(sot, noTimestamps)
        }
        val vocab = bundle.tokens.useLines { WhisperFrontend.parseVocab(it) }

        val out = StringBuilder()
        var start = 0
        while (start < pcm16k.size) {
            val end = minOf(pcm16k.size, start + WhisperFrontend.CHUNK_SAMPLES)
            val chunk = pcm16k.copyOfRange(start, end)
            val tokens = decodeWindow(encoder, decoder, chunk, prompt, eot, layers, textCtx, state)
            val text = WhisperFrontend.decodeTokens(tokens, vocab, firstSpecial = eot)
            if (text.isNotBlank()) {
                if (out.isNotEmpty()) out.append(' ')
                out.append(text)
            }
            start = end
        }
        return out.toString()
    }

    private fun decodeWindow(
        encoder: ai.onnxruntime.OrtSession,
        decoder: ai.onnxruntime.OrtSession,
        samples: FloatArray,
        prompt: List<Int>,
        eot: Int,
        layers: Int,
        textCtx: Int,
        state: Int,
    ): List<Int> {
        val mel = WhisperFrontend.logMel(samples)
        val melTensor = DesktopOnnx.floatTensor(mel, longArrayOf(1, WhisperFrontend.N_MELS.toLong(), WhisperFrontend.N_FRAMES.toLong()))
        melTensor.use {
            encoder.run(mapOf(encoder.inputNames.first() to melTensor)).use { enc ->
                val crossK = enc.get("n_layer_cross_k").get() as OnnxTensor
                val crossV = enc.get("n_layer_cross_v").get() as OnnxTensor
                val cacheShape = longArrayOf(layers.toLong(), 1, textCtx.toLong(), state.toLong())
                var selfK = FloatArray(layers * textCtx * state)
                var selfV = FloatArray(layers * textCtx * state)
                var input = prompt
                var offset = 0L
                val result = ArrayList<Int>()
                repeat(MAX_TOKENS) {
                    val tokensTensor = OnnxTensor.createTensor(
                        DesktopOnnx.environment,
                        LongBuffer.wrap(LongArray(input.size) { input[it].toLong() }),
                        longArrayOf(1, input.size.toLong()),
                    )
                    val kTensor = DesktopOnnx.floatTensor(selfK, cacheShape)
                    val vTensor = DesktopOnnx.floatTensor(selfV, cacheShape)
                    val offsetTensor = OnnxTensor.createTensor(DesktopOnnx.environment, LongBuffer.wrap(longArrayOf(offset)), longArrayOf(1))
                    val next = try {
                        decoder.run(
                            mapOf(
                                "tokens" to tokensTensor,
                                "in_n_layer_self_k_cache" to kTensor,
                                "in_n_layer_self_v_cache" to vTensor,
                                "n_layer_cross_k" to crossK,
                                "n_layer_cross_v" to crossV,
                                "offset" to offsetTensor,
                            ),
                        ).use { dec ->
                            val logits = dec.get("logits").get() as OnnxTensor
                            val shape = logits.info.shape
                            val vocab = shape.last().toInt()
                            val buf = logits.floatBuffer
                            val base = buf.remaining() - vocab // last position's row
                            var best = 0
                            var bestV = Float.NEGATIVE_INFINITY
                            for (i in 0 until vocab) {
                                val v = buf.get(base + i)
                                if (v > bestV) { bestV = v; best = i }
                            }
                            selfK = (dec.get("out_n_layer_self_k_cache").get() as OnnxTensor).floatBuffer.let { FloatArray(it.remaining()).also { a -> it.get(a) } }
                            selfV = (dec.get("out_n_layer_self_v_cache").get() as OnnxTensor).floatBuffer.let { FloatArray(it.remaining()).also { a -> it.get(a) } }
                            best
                        }
                    } finally {
                        tokensTensor.close(); kTensor.close(); vTensor.close(); offsetTensor.close()
                    }
                    offset += input.size
                    if (next == eot || offset >= textCtx) return result
                    result += next
                    input = listOf(next)
                }
                return result
            }
        }
    }
}
