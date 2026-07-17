package com.hereliesaz.guillotine.ai.gen

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * The concrete generation backends: one [GenJob] per [GenProviderType], built from a [GenRequest].
 * Each owns its own submit/poll request shapes; [AsyncJobPoller] drives them uniformly and a
 * [GenSink] turns the result (remote url or raw bytes) into a local `file://` uri.
 *
 * The widely-used providers (OpenAI, Stability, FLUX, Imagen, Runway, Luma, Veo, ElevenLabs, and the
 * fal.ai / Replicate aggregators) are implemented against their documented request shapes; the
 * longer-tail music providers use their best-known shapes and may need field tuning against a live
 * account. All follow the same submit → poll → (download) contract, so tuning one never affects the
 * others.
 */
object GenBackends {

    /** Default file extension for a generated result of this kind. */
    fun extFor(kind: GenKind): String = when (kind) {
        GenKind.IMAGE -> "png"
        GenKind.VIDEO -> "mp4"
        GenKind.MUSIC -> "mp3"
    }

    fun jobFor(req: GenRequest, sink: GenSink): GenJob = when (req.provider) {
        GenProviderType.POLLINATIONS -> pollinations(req)
        GenProviderType.LEONARDO -> leonardo(req, sink)
        GenProviderType.OPENAI_IMAGE -> openAiImage(req, sink)
        GenProviderType.STABILITY_IMAGE -> stabilityImage(req, sink)
        GenProviderType.BFL_FLUX -> bflFlux(req)
        GenProviderType.GEMINI_IMAGEN -> geminiImagen(req, sink)
        GenProviderType.IDEOGRAM -> ideogram(req)
        GenProviderType.RECRAFT -> recraft(req)
        GenProviderType.GUILLOTINE_FREE -> guillotineFree(req)
        GenProviderType.RUNWAY -> runway(req)
        GenProviderType.LUMA -> luma(req)
        GenProviderType.GEMINI_VEO -> geminiVeo(req)
        GenProviderType.MINIMAX -> minimax(req)
        GenProviderType.OPENAI_SORA -> openAiSora(req, sink)
        GenProviderType.KLING -> kling(req)
        GenProviderType.PIKA -> pika(req)
        GenProviderType.STABILITY_VIDEO -> stabilityVideo(req, sink)
        GenProviderType.ELEVENLABS -> elevenLabs(req, sink)
        GenProviderType.STABILITY_AUDIO -> stabilityAudio(req, sink)
        GenProviderType.GEMINI_LYRIA -> geminiLyria(req, sink)
        GenProviderType.MUSICGEN_REPLICATE -> replicate(req.copy(model = req.model.ifBlank { "meta/musicgen" }))
        GenProviderType.MUBERT -> mubert(req)
        GenProviderType.BEATOVEN -> beatoven(req)
        GenProviderType.LOUDLY -> loudly(req)
        GenProviderType.CASSETTE -> cassette(req)
        GenProviderType.SUNO_WRAPPER -> wrapper(req)
        GenProviderType.UDIO_WRAPPER -> wrapper(req)
        GenProviderType.FAL -> fal(req)
        GenProviderType.REPLICATE -> replicate(req)
    }

    // ---- helpers -----------------------------------------------------------

    /** A synchronous provider: does all its work in [block], returning the final url/uri. */
    private fun sync(block: suspend () -> String) = object : GenJob {
        override suspend fun submit(): String = block()
        override suspend fun poll(handle: String): JobStatus = JobStatus.Done(handle)
    }

    private fun decodeB64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun firstUrlIn(json: JSONObject): String? {
        // Depth-first search for the first "url"/"uri" string value.
        for (key in json.keys()) {
            when (val v = json.get(key)) {
                is String -> if ((key == "url" || key == "uri" || key == "video" || key == "audio") &&
                    v.startsWith("http")) return v
                is JSONObject -> firstUrlIn(v)?.let { return it }
                is JSONArray -> firstUrlIn(v)?.let { return it }
            }
        }
        return null
    }

    private fun firstUrlIn(arr: JSONArray): String? {
        for (i in 0 until arr.length()) {
            when (val v = arr.get(i)) {
                is String -> if (v.startsWith("http")) return v
                is JSONObject -> firstUrlIn(v)?.let { return it }
                is JSONArray -> firstUrlIn(v)?.let { return it }
            }
        }
        return null
    }

    // ============================================================ IMAGE

    /** Free, keyless: the prompt URL *is* the image. Returned remote so the sink downloads it. */
    private fun pollinations(req: GenRequest) = sync {
        val model = req.model.ifBlank { "flux" }
        "https://image.pollinations.ai/prompt/${GenHttp.urlEncode(req.prompt)}" +
            "?width=${req.widthPx}&height=${req.heightPx}&model=$model&nologo=true"
    }

    private fun leonardo(req: GenRequest, sink: GenSink) = object : GenJob {
        val base = "https://cloud.leonardo.ai/api/rest/v1"
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("prompt", req.prompt)
                if (req.model.isNotBlank()) put("modelId", req.model)
                put("width", req.widthPx); put("height", req.heightPx); put("num_images", 1)
            }
            val created = GenHttp.requestJson("POST", "$base/generations", GenHttp.bearer(req.apiKey), body)
            return JSONObject(created).optJSONObject("sdGenerationJob")?.optString("generationId").orEmpty()
                .ifBlank { throw GenException("Leonardo did not return a generation id.") }
        }
        override suspend fun poll(handle: String): JobStatus {
            val txt = GenHttp.requestJson("GET", "$base/generations/$handle", GenHttp.bearer(req.apiKey))
            val pk = JSONObject(txt).optJSONObject("generations_by_pk") ?: return JobStatus.Running()
            return when (pk.optString("status")) {
                "COMPLETE" -> {
                    val url = pk.optJSONArray("generated_images")?.optJSONObject(0)?.optString("url").orEmpty()
                    if (url.isBlank()) JobStatus.Failed("Leonardo returned no image.") else JobStatus.Done(url)
                }
                "FAILED" -> JobStatus.Failed("Leonardo generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun openAiImage(req: GenRequest, sink: GenSink) = sync {
        val model = req.model.ifBlank { "gpt-image-1" }
        val size = openAiSize(req.widthPx, req.heightPx, model)
        val body = JSONObject().apply {
            put("model", model); put("prompt", req.prompt); put("n", 1); put("size", size)
            if (model != "gpt-image-1") put("response_format", "b64_json")
        }
        val resp = GenHttp.requestJson(
            "POST", "https://api.openai.com/v1/images/generations", GenHttp.bearer(req.apiKey), body,
        )
        val d0 = JSONObject(resp).getJSONArray("data").getJSONObject(0)
        val b64 = d0.optString("b64_json", "")
        if (b64.isNotBlank()) sink.saveBytes(decodeB64(b64), "png")
        else sink.saveUrl(d0.getString("url"), "png")
    }

    private fun openAiSize(w: Int, h: Int, model: String): String = when {
        model == "dall-e-3" && w > h -> "1792x1024"
        model == "dall-e-3" && h > w -> "1024x1792"
        model == "gpt-image-1" && w > h -> "1536x1024"
        model == "gpt-image-1" && h > w -> "1024x1536"
        else -> "1024x1024"
    }

    private fun stabilityImage(req: GenRequest, sink: GenSink) = sync {
        val model = req.model.ifBlank { "sd3.5-large" }
        val path = when (model) {
            "ultra" -> "ultra"; "core" -> "core"; else -> "sd3"
        }
        val fields = mutableMapOf("prompt" to req.prompt, "output_format" to "png")
        if (path == "sd3") fields["model"] = model
        val body = multipart(fields)
        // Accept image/* → the API returns raw image bytes.
        val bytes = GenHttp.getBytesViaMultipart(
            "https://api.stability.ai/v2beta/stable-image/generate/$path",
            GenHttp.bearer(req.apiKey) + ("Accept" to "image/*"), body.first, body.second,
        )
        sink.saveBytes(bytes, "png")
    }

    private fun bflFlux(req: GenRequest) = object : GenJob {
        val model = req.model.ifBlank { "flux-pro-1.1" }
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("prompt", req.prompt); put("width", req.widthPx); put("height", req.heightPx)
            }
            val resp = GenHttp.requestJson("POST", "https://api.bfl.ai/v1/$model", mapOf("x-key" to req.apiKey), body)
            return JSONObject(resp).optString("polling_url").ifBlank {
                "https://api.bfl.ai/v1/get_result?id=" + JSONObject(resp).optString("id")
            }
        }
        override suspend fun poll(handle: String): JobStatus {
            val resp = GenHttp.requestJson("GET", handle, mapOf("x-key" to req.apiKey))
            val o = JSONObject(resp)
            return when (o.optString("status")) {
                "Ready" -> JobStatus.Done(o.getJSONObject("result").getString("sample"))
                "Error", "Failed" -> JobStatus.Failed("FLUX generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun geminiImagen(req: GenRequest, sink: GenSink) = sync {
        val model = req.model.ifBlank { "imagen-4.0-generate-001" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:predict"
        val body = JSONObject().apply {
            put("instances", JSONArray().put(JSONObject().put("prompt", req.prompt)))
            put("parameters", JSONObject().put("sampleCount", 1))
        }
        val resp = GenHttp.requestJson("POST", url, mapOf("x-goog-api-key" to req.apiKey), body)
        val b64 = JSONObject(resp).getJSONArray("predictions").getJSONObject(0).getString("bytesBase64Encoded")
        sink.saveBytes(decodeB64(b64), "png")
    }

    private fun ideogram(req: GenRequest) = sync {
        val body = JSONObject().apply {
            put("prompt", req.prompt)
            put("rendering_speed", "DEFAULT")
        }
        val resp = GenHttp.requestJson(
            "POST", "https://api.ideogram.ai/v1/ideogram-v3/generate",
            mapOf("Api-Key" to req.apiKey), body,
        )
        JSONObject(resp).getJSONArray("data").getJSONObject(0).getString("url")
    }

    private fun recraft(req: GenRequest) = sync {
        val body = JSONObject().apply {
            put("prompt", req.prompt); put("model", req.model.ifBlank { "recraftv3" })
        }
        val resp = GenHttp.requestJson(
            "POST", "https://external.api.recraft.ai/v1/images/generations", GenHttp.bearer(req.apiKey), body,
        )
        JSONObject(resp).getJSONArray("data").getJSONObject(0).getString("url")
    }

    // ============================================================ VIDEO

    /** Guillotine's own free Hugging Face Space (ZeroGPU). Owner/space lowercased → the `.hf.space` host. */
    private const val DEFAULT_FREE_T2V_SPACE = "https://hereliesaz-guillotine-t2v.hf.space"

    /**
     * Free, keyless text-to-video via the Guillotine HF Space's Gradio API. Two-step: POST the prompt
     * to `/gradio_api/call/generate` for an event id, then read the SSE result stream for the generated
     * file url (which the sink downloads). Only the text prompt leaves the device — never the user's
     * media. Shared free GPU, so it can queue; the base host is overridable via `extra["base_url"]`.
     */
    private fun guillotineFree(req: GenRequest) = object : GenJob {
        val base = (req.extra["base_url"]?.takeIf { it.isNotBlank() } ?: DEFAULT_FREE_T2V_SPACE).trimEnd('/')
        override suspend fun submit(): String {
            val body = JSONObject().put("data", JSONArray().apply {
                put(req.prompt)                              // prompt
                put("")                                      // negative prompt
                put(req.durationSec.coerceIn(1, 6))          // seconds (kept short for the free tier)
                put(0)                                       // seed (0 = random on the Space)
            })
            val resp = GenHttp.requestJson("POST", "$base/gradio_api/call/generate", emptyMap(), body)
            return JSONObject(resp).optString("event_id").ifBlank {
                throw GenException("The free video generator didn't accept the request — it may be waking up. Try again in a minute.")
            }
        }
        override suspend fun poll(handle: String): JobStatus {
            // The result endpoint streams Server-Sent Events; the final "data:" line carries the output.
            val sse = GenHttp.requestJson("GET", "$base/gradio_api/call/generate/$handle", emptyMap())
            val dataLine = sse.lineSequence().lastOrNull { it.startsWith("data:") } ?: return JobStatus.Running()
            val json = dataLine.removePrefix("data:").trim()
            if (json.isBlank() || json == "null") {
                return JobStatus.Failed("The free video generator returned no result — it may have hit its free-GPU quota. Try later or add a key for a paid provider.")
            }
            val url = runCatching { firstUrlIn(JSONArray(json)) }.getOrNull()
            return if (url != null) JobStatus.Done(url)
            else JobStatus.Failed("The free video generator produced no video (busy or over quota). Try later or add a key for a paid provider.")
        }
    }

    private fun runway(req: GenRequest) = object : GenJob {
        val hdr = GenHttp.bearer(req.apiKey) + ("X-Runway-Version" to "2024-11-06")
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("promptText", req.prompt); put("model", req.model.ifBlank { "gen4_turbo" })
                put("duration", req.durationSec); put("ratio", ratio(req))
            }
            val resp = GenHttp.requestJson("POST", "https://api.dev.runwayml.com/v1/text_to_video", hdr, body)
            return JSONObject(resp).getString("id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val resp = GenHttp.requestJson("GET", "https://api.dev.runwayml.com/v1/tasks/$handle", hdr)
            val o = JSONObject(resp)
            return when (o.optString("status")) {
                "SUCCEEDED" -> JobStatus.Done(o.getJSONArray("output").getString(0))
                "FAILED" -> JobStatus.Failed("Runway generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun luma(req: GenRequest) = object : GenJob {
        val base = "https://api.lumalabs.ai/dream-machine/v1/generations"
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("prompt", req.prompt); put("model", req.model.ifBlank { "ray-2" })
                put("aspect_ratio", if (req.heightPx > req.widthPx) "9:16" else "16:9")
            }
            return JSONObject(GenHttp.requestJson("POST", base, GenHttp.bearer(req.apiKey), body)).getString("id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/$handle", GenHttp.bearer(req.apiKey)))
            return when (o.optString("state")) {
                "completed" -> JobStatus.Done(o.getJSONObject("assets").getString("video"))
                "failed" -> JobStatus.Failed(o.optString("failure_reason", "Luma generation failed."))
                else -> JobStatus.Running()
            }
        }
    }

    private fun geminiVeo(req: GenRequest) = object : GenJob {
        val model = req.model.ifBlank { "veo-3.1-generate-preview" }
        override suspend fun submit(): String {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:predictLongRunning"
            val body = JSONObject().apply {
                put("instances", JSONArray().put(JSONObject().put("prompt", req.prompt)))
            }
            return JSONObject(GenHttp.requestJson("POST", url, mapOf("x-goog-api-key" to req.apiKey), body)).getString("name")
        }
        override suspend fun poll(handle: String): JobStatus {
            val url = "https://generativelanguage.googleapis.com/v1beta/$handle"
            val o = JSONObject(GenHttp.requestJson("GET", url, mapOf("x-goog-api-key" to req.apiKey)))
            if (!o.optBoolean("done", false)) return JobStatus.Running()
            o.optJSONObject("error")?.let { return JobStatus.Failed(it.optString("message", "Veo failed.")) }
            // Response nests generatedSamples[].video.uri; the file uri needs the key appended.
            val uri = firstUrlIn(o.optJSONObject("response") ?: o)
                ?: return JobStatus.Failed("Veo returned no video.")
            val withKey = if ("generativelanguage.googleapis.com" in uri && "key=" !in uri) {
                uri + (if ("?" in uri) "&" else "?") + "key=${req.apiKey}"
            } else uri
            return JobStatus.Done(withKey)
        }
    }

    private fun minimax(req: GenRequest) = object : GenJob {
        val base = "https://api.minimax.io/v1"
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("model", req.model.ifBlank { "MiniMax-Hailuo-02" }); put("prompt", req.prompt)
            }
            return JSONObject(GenHttp.requestJson("POST", "$base/video_generation", GenHttp.bearer(req.apiKey), body))
                .getString("task_id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson(
                "GET", "$base/query/video_generation?task_id=$handle", GenHttp.bearer(req.apiKey),
            ))
            return when (o.optString("status")) {
                "Success" -> {
                    val fileId = o.optString("file_id")
                    val f = JSONObject(GenHttp.requestJson(
                        "GET", "$base/files/retrieve?file_id=$fileId", GenHttp.bearer(req.apiKey),
                    ))
                    JobStatus.Done(f.getJSONObject("file").getString("download_url"))
                }
                "Fail" -> JobStatus.Failed("MiniMax generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun openAiSora(req: GenRequest, sink: GenSink) = object : GenJob {
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("model", req.model.ifBlank { "sora-2" }); put("prompt", req.prompt)
            }
            return JSONObject(GenHttp.requestJson("POST", "https://api.openai.com/v1/videos", GenHttp.bearer(req.apiKey), body))
                .getString("id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "https://api.openai.com/v1/videos/$handle", GenHttp.bearer(req.apiKey)))
            return when (o.optString("status")) {
                "completed" -> {
                    val bytes = GenHttp.getBytes(
                        "https://api.openai.com/v1/videos/$handle/content", GenHttp.bearer(req.apiKey),
                    )
                    JobStatus.Done(sink.saveBytes(bytes, "mp4"))
                }
                "failed" -> JobStatus.Failed("Sora generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun kling(req: GenRequest) = object : GenJob {
        val base = "https://api.klingai.com/v1/videos/text2video"
        override suspend fun submit(): String {
            val body = JSONObject().apply {
                put("model_name", req.model.ifBlank { "kling-v2" }); put("prompt", req.prompt)
            }
            return JSONObject(GenHttp.requestJson("POST", base, GenHttp.bearer(req.apiKey), body))
                .getJSONObject("data").getString("task_id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/$handle", GenHttp.bearer(req.apiKey))).getJSONObject("data")
            return when (o.optString("task_status")) {
                "succeed" -> JobStatus.Done(
                    o.getJSONObject("task_result").getJSONArray("videos").getJSONObject(0).getString("url"),
                )
                "failed" -> JobStatus.Failed("Kling generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun pika(req: GenRequest) = object : GenJob {
        val base = "https://api.pika.art/v1/generate"
        override suspend fun submit(): String {
            val body = JSONObject().put("prompt", req.prompt).put("model", req.model.ifBlank { "pika-2.2" })
            return JSONObject(GenHttp.requestJson("POST", base, GenHttp.bearer(req.apiKey), body)).getString("id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/$handle", GenHttp.bearer(req.apiKey)))
            return when (o.optString("status")) {
                "finished", "completed" -> firstUrlIn(o)?.let { JobStatus.Done(it) }
                    ?: JobStatus.Failed("Pika returned no video.")
                "failed" -> JobStatus.Failed("Pika generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun stabilityVideo(req: GenRequest, sink: GenSink) = object : GenJob {
        // Stability image-to-video needs a start image, uploaded as the multipart `image` file part.
        override suspend fun submit(): String {
            val imgUrl = req.extra["image_url"]
                ?: throw GenException("Stability image-to-video needs a start image. Generate or select an image first, then retry.")
            val imageBytes = GenHttp.getBytes(imgUrl)
            val (boundary, body) = multipartWithImage(
                mapOf("seed" to "0", "cfg_scale" to "1.8", "motion_bucket_id" to "127"), imageBytes,
            )
            val resp = GenHttp.requestRawMultipart(
                "https://api.stability.ai/v2beta/image-to-video", GenHttp.bearer(req.apiKey), boundary, body,
            )
            return JSONObject(resp).getString("id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val bytes = GenHttp.getBytes(
                "https://api.stability.ai/v2beta/image-to-video/result/$handle",
                GenHttp.bearer(req.apiKey) + ("Accept" to "video/*"),
            )
            // While generating, Stability returns a small JSON body (202); when done, the raw mp4.
            if (bytes.isNotEmpty() && bytes.size < 512 && bytes[0] == '{'.code.toByte()) return JobStatus.Running()
            return JobStatus.Done(sink.saveBytes(bytes, "mp4"))
        }
    }

    // ============================================================ MUSIC

    private fun elevenLabs(req: GenRequest, sink: GenSink) = sync {
        val ms = (req.durationSec.coerceIn(3, 300)) * 1000
        if (req.model == "sound_effects") {
            val body = JSONObject().put("text", req.prompt).put("duration_seconds", req.durationSec.coerceIn(1, 30))
            val bytes = GenHttp.getBytesViaJson(
                "https://api.elevenlabs.io/v1/sound-generation", mapOf("xi-api-key" to req.apiKey), body,
            )
            sink.saveBytes(bytes, "mp3")
        } else {
            val body = JSONObject().put("prompt", req.prompt).put("music_length_ms", ms)
            val bytes = GenHttp.getBytesViaJson(
                "https://api.elevenlabs.io/v1/music", mapOf("xi-api-key" to req.apiKey), body,
            )
            sink.saveBytes(bytes, "mp3")
        }
    }

    private fun stabilityAudio(req: GenRequest, sink: GenSink) = sync {
        val body = multipart(mapOf(
            "prompt" to req.prompt, "duration" to req.durationSec.coerceIn(1, 190).toString(),
            "output_format" to "mp3", "model" to req.model.ifBlank { "stable-audio-2.0" },
        ))
        val bytes = GenHttp.getBytesViaMultipart(
            "https://api.stability.ai/v2beta/audio/stable-audio-2/text-to-audio",
            GenHttp.bearer(req.apiKey) + ("Accept" to "audio/*"), body.first, body.second,
        )
        sink.saveBytes(bytes, "mp3")
    }

    private fun geminiLyria(req: GenRequest, sink: GenSink) = sync {
        val model = req.model.ifBlank { "lyria-002" }
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:predict"
        val body = JSONObject().put("instances", JSONArray().put(JSONObject().put("prompt", req.prompt)))
        val resp = GenHttp.requestJson("POST", url, mapOf("x-goog-api-key" to req.apiKey), body)
        val b64 = JSONObject(resp).getJSONArray("predictions").getJSONObject(0).getString("bytesBase64Encoded")
        sink.saveBytes(decodeB64(b64), "wav")
    }

    private fun mubert(req: GenRequest) = object : GenJob {
        val base = "https://music-api.mubert.com/api/v3"
        override suspend fun submit(): String {
            val body = JSONObject().put("prompt", req.prompt).put("duration", req.durationSec)
            val resp = GenHttp.requestJson("POST", "$base/track", mapOf("customer-id" to req.apiKey), body)
            return JSONObject(resp).optJSONObject("data")?.optString("id").orEmpty()
                .ifBlank { throw GenException("Mubert did not return a track id.") }
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/track/$handle", mapOf("customer-id" to req.apiKey)))
            return firstUrlIn(o)?.let { JobStatus.Done(it) } ?: JobStatus.Running()
        }
    }

    private fun beatoven(req: GenRequest) = object : GenJob {
        val base = "https://public-api.beatoven.ai/api/v1"
        override suspend fun submit(): String {
            val body = JSONObject().put("prompt", JSONObject().put("text", req.prompt)).put("format", "mp3")
            return JSONObject(GenHttp.requestJson("POST", "$base/tracks/compose", GenHttp.bearer(req.apiKey), body))
                .getString("task_id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/tasks/$handle", GenHttp.bearer(req.apiKey)))
            return when (o.optString("status")) {
                "composed" -> JobStatus.Done(o.getJSONObject("meta").getString("track_url"))
                "failed" -> JobStatus.Failed("Beatoven generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun loudly(req: GenRequest) = sync {
        val body = multipart(mapOf("prompt" to req.prompt, "duration" to req.durationSec.toString()))
        val resp = GenHttp.requestRawMultipart(
            "https://soundtracks-dev.loudly.com/b2b/ai/prompt/songs",
            mapOf("API-KEY" to req.apiKey), body.first, body.second,
        )
        firstUrlIn(JSONObject(resp)) ?: throw GenException("Loudly returned no track.")
    }

    private fun cassette(req: GenRequest) = object : GenJob {
        val base = "https://api.cassetteai.com/v1"
        override suspend fun submit(): String {
            val body = JSONObject().put("prompt", req.prompt).put("duration", req.durationSec)
            return JSONObject(GenHttp.requestJson("POST", "$base/generate", GenHttp.bearer(req.apiKey), body)).getString("id")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/generate/$handle", GenHttp.bearer(req.apiKey)))
            return when (o.optString("status")) {
                "completed", "succeeded" -> firstUrlIn(o)?.let { JobStatus.Done(it) }
                    ?: JobStatus.Failed("Cassette returned no track.")
                "failed" -> JobStatus.Failed("Cassette generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    /** Suno/Udio via a user-supplied third-party wrapper. Base URL comes from [GenRequest.extra]. */
    private fun wrapper(req: GenRequest) = object : GenJob {
        val base = (req.extra["base_url"] ?: "").trimEnd('/')
            .ifBlank { throw GenException("Set the wrapper base URL in Settings for this provider.") }
        override suspend fun submit(): String {
            val body = JSONObject().put("prompt", req.prompt).put("model", req.model)
            val resp = GenHttp.requestJson("POST", "$base/generate", GenHttp.bearer(req.apiKey), body)
            val o = JSONObject(resp)
            return o.optString("id").ifBlank { o.optString("task_id") }
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", "$base/status/$handle", GenHttp.bearer(req.apiKey)))
            val status = o.optString("status").lowercase()
            return when {
                status.contains("complete") || status.contains("succeed") || status.contains("finish") ->
                    firstUrlIn(o)?.let { JobStatus.Done(it) } ?: JobStatus.Running()
                status.contains("fail") || status.contains("error") -> JobStatus.Failed("Wrapper generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    // ============================================================ AGGREGATORS

    private fun fal(req: GenRequest) = object : GenJob {
        val model = req.model.ifBlank { defaultFalModel(req.kind) }
        val hdr = mapOf("Authorization" to "Key ${req.apiKey}")
        override suspend fun submit(): String {
            val body = JSONObject().put("prompt", req.prompt)
            val resp = GenHttp.requestJson("POST", "https://queue.fal.run/$model", hdr, body)
            return JSONObject(resp).optString("status_url").ifBlank {
                "https://queue.fal.run/$model/requests/" + JSONObject(resp).getString("request_id") + "/status"
            }
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", handle, hdr))
            return when (o.optString("status")) {
                "COMPLETED" -> {
                    val responseUrl = o.optString("response_url").ifBlank { handle.removeSuffix("/status") }
                    val out = JSONObject(GenHttp.requestJson("GET", responseUrl, hdr))
                    firstUrlIn(out)?.let { JobStatus.Done(it) } ?: JobStatus.Failed("fal.ai returned no result.")
                }
                "FAILED", "ERROR" -> JobStatus.Failed("fal.ai generation failed.")
                else -> JobStatus.Running()
            }
        }
    }

    private fun defaultFalModel(kind: GenKind) = when (kind) {
        GenKind.IMAGE -> "fal-ai/flux/dev"
        GenKind.VIDEO -> "fal-ai/minimax/hailuo-02/standard/text-to-video"
        GenKind.MUSIC -> "fal-ai/stable-audio"
    }

    private fun replicate(req: GenRequest) = object : GenJob {
        val hdr = GenHttp.bearer(req.apiKey)
        override suspend fun submit(): String {
            val model = req.model.ifBlank { defaultReplicateModel(req.kind) }
            val input = JSONObject().put("prompt", req.prompt)
            // Use the model-scoped predictions endpoint so no explicit version is required.
            val body = JSONObject().put("input", input)
            val resp = GenHttp.requestJson(
                "POST", "https://api.replicate.com/v1/models/$model/predictions", hdr, body,
            )
            return JSONObject(resp).getJSONObject("urls").getString("get")
        }
        override suspend fun poll(handle: String): JobStatus {
            val o = JSONObject(GenHttp.requestJson("GET", handle, hdr))
            return when (o.optString("status")) {
                "succeeded" -> {
                    val out = o.get("output")
                    val url = when (out) {
                        is String -> out
                        is JSONArray -> out.optString(0)
                        is JSONObject -> firstUrlIn(out)
                        else -> null
                    }
                    if (url.isNullOrBlank()) JobStatus.Failed("Replicate returned no result.") else JobStatus.Done(url)
                }
                "failed", "canceled" -> JobStatus.Failed(o.optString("error", "Replicate generation failed."))
                else -> JobStatus.Running()
            }
        }
    }

    private fun defaultReplicateModel(kind: GenKind) = when (kind) {
        GenKind.IMAGE -> "black-forest-labs/flux-dev"
        GenKind.VIDEO -> "minimax/video-01"
        GenKind.MUSIC -> "meta/musicgen"
    }

    private fun ratio(req: GenRequest) = if (req.heightPx > req.widthPx) "720:1280" else "1280:720"

    /** Build a multipart body: (boundary, bytes) for simple text fields. */
    private fun multipart(fields: Map<String, String>): Pair<String, ByteArray> {
        val boundary = "----guillotinegen"
        val sb = StringBuilder()
        fields.forEach { (k, v) ->
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
            sb.append(v).append("\r\n")
        }
        sb.append("--").append(boundary).append("--\r\n")
        return boundary to sb.toString().toByteArray()
    }

    /** Multipart body with text [fields] plus one binary image file part (raw bytes). */
    private fun multipartWithImage(
        fields: Map<String, String>,
        image: ByteArray,
        fieldName: String = "image",
        filename: String = "image.png",
    ): Pair<String, ByteArray> {
        val boundary = "----guillotinegen"
        val out = java.io.ByteArrayOutputStream()
        fun w(s: String) = out.write(s.toByteArray())
        fields.forEach { (k, v) ->
            w("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\n\r\n$v\r\n")
        }
        w("--$boundary\r\nContent-Disposition: form-data; name=\"$fieldName\"; filename=\"$filename\"\r\nContent-Type: image/png\r\n\r\n")
        out.write(image)
        w("\r\n--$boundary--\r\n")
        return boundary to out.toByteArray()
    }
}
