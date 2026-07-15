package com.hereliesaz.guillotine.ai.gen

/**
 * Registry of **generation** providers — the AIs that create new media (images, video, music) that
 * the user brings their own key for. This parallels the agent-brain registry in
 * [com.hereliesaz.guillotine.ai.AiProviderType] / `ProviderMeta`, but is capability-typed by
 * [GenKind] so the app can *gate* what it offers: a category (and a provider within it) is only
 * shown/usable once the user has configured a key for it. Users can configure as many as they like.
 *
 * Nothing here does I/O — backends live in [com.hereliesaz.guillotine.ai.gen] as [GenJob]s driven by
 * [AsyncJobPoller]. This file is the pure catalog + gating logic, shared across Android and desktop.
 */

/** The three generation categories. */
enum class GenKind { IMAGE, VIDEO, MUSIC }

/**
 * Every generation provider the app knows about. Aggregators ([FAL], [REPLICATE]) serve more than
 * one [GenKind] through a single key. Suno/Udio have **no official API** — only third-party
 * account-pooling wrappers — so they're exposed via a disclaimed "wrapper key" field.
 */
enum class GenProviderType {
    // ---- image ----
    POLLINATIONS, LEONARDO, OPENAI_IMAGE, STABILITY_IMAGE, BFL_FLUX, GEMINI_IMAGEN, IDEOGRAM, RECRAFT,
    // ---- video ----
    GUILLOTINE_FREE, RUNWAY, LUMA, GEMINI_VEO, MINIMAX, OPENAI_SORA, KLING, PIKA, STABILITY_VIDEO,
    // ---- music / audio ----
    ELEVENLABS, STABILITY_AUDIO, GEMINI_LYRIA, MUSICGEN_REPLICATE, MUBERT, BEATOVEN, LOUDLY, CASSETTE,
    SUNO_WRAPPER, UDIO_WRAPPER,
    // ---- aggregators (many models across kinds via one key) ----
    FAL, REPLICATE,
}

/** A selectable model within a provider. [id] is the wire id sent to the API; [name] is the label. */
data class GenModel(val id: String, val name: String)

/**
 * Static metadata for a provider: which categories it serves, how to get a key, and a fallback list
 * of models. [needsKey] is false only for the free keyless providers (Pollinations). [disclaimer]
 * surfaces caveats (e.g. Suno/Udio "no official API") inline in the settings row.
 */
data class GenProviderMeta(
    val type: GenProviderType,
    val kinds: Set<GenKind>,
    val label: String,
    val blurb: String,
    val keyUrl: String?,
    val needsKey: Boolean,
    val models: List<GenModel> = emptyList(),
    val disclaimer: String? = null,
) {
    /** First model id, used as the default when the user hasn't picked one. */
    val defaultModel: String? get() = models.firstOrNull()?.id
    fun serves(kind: GenKind): Boolean = kind in kinds
}

private fun img(vararg m: GenModel) = m.toList()

val GenProviderType.meta: GenProviderMeta
    get() = when (this) {
        // ---------------------------------------------------------------- image
        GenProviderType.POLLINATIONS -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Pollinations (free)",
            "No key, works instantly. Great for quick placeholder images.",
            keyUrl = null, needsKey = false,
            models = img(GenModel("flux", "Flux"), GenModel("turbo", "Turbo")),
        )
        GenProviderType.LEONARDO -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Leonardo.ai",
            "High-quality image generation and inpainting (used for generative object removal).",
            keyUrl = "https://app.leonardo.ai/api-access", needsKey = true,
            models = img(
                GenModel("de7d3faf-762f-48e0-b3b7-9d0ac3a3fcf3", "Leonardo Phoenix 1.0"),
                GenModel("b2614463-296c-462a-9586-aafdb8f00e36", "FLUX.1 Dev (Precision)"),
                GenModel("1dd50843-d653-4516-a8e3-f0238ee453ff", "FLUX.1 Schnell (Speed)"),
                GenModel("aa77f04e-3eec-4034-9c07-d0f619684628", "Leonardo Kino XL (cinematic)"),
            ),
        )
        GenProviderType.OPENAI_IMAGE -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "OpenAI Images",
            "gpt-image-1 / DALL·E 3. Strong prompt adherence.",
            keyUrl = "https://platform.openai.com/api-keys", needsKey = true,
            models = img(
                GenModel("gpt-image-1", "gpt-image-1"),
                GenModel("dall-e-3", "DALL·E 3"),
                GenModel("dall-e-2", "DALL·E 2"),
            ),
        )
        GenProviderType.STABILITY_IMAGE -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Stability AI (image)",
            "Stable Image Ultra/Core and SD 3.5.",
            keyUrl = "https://platform.stability.ai/account/keys", needsKey = true,
            models = img(
                GenModel("sd3.5-large", "SD 3.5 Large"),
                GenModel("sd3.5-large-turbo", "SD 3.5 Large Turbo"),
                GenModel("sd3.5-medium", "SD 3.5 Medium"),
                GenModel("ultra", "Stable Image Ultra"),
                GenModel("core", "Stable Image Core"),
            ),
        )
        GenProviderType.BFL_FLUX -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Black Forest Labs (FLUX)",
            "FLUX.1/1.1 and FLUX.1 Kontext (prompt-based editing). Free key, pay-per-image.",
            keyUrl = "https://docs.bfl.ai", needsKey = true,
            models = img(
                GenModel("flux-pro-1.1", "FLUX 1.1 [pro]"),
                GenModel("flux-pro-1.1-ultra", "FLUX 1.1 [pro] Ultra"),
                GenModel("flux-pro", "FLUX.1 [pro]"),
                GenModel("flux-dev", "FLUX.1 [dev]"),
                GenModel("flux-kontext-pro", "FLUX.1 Kontext [pro] (edit)"),
                GenModel("flux-kontext-max", "FLUX.1 Kontext [max] (edit)"),
            ),
        )
        GenProviderType.GEMINI_IMAGEN -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Google Imagen",
            "Imagen 3/4 via a Gemini API key (the same key can drive the editor).",
            keyUrl = "https://aistudio.google.com/app/apikey", needsKey = true,
            models = img(
                GenModel("imagen-4.0-generate-001", "Imagen 4"),
                GenModel("imagen-3.0-generate-002", "Imagen 3"),
            ),
        )
        GenProviderType.IDEOGRAM -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Ideogram",
            "Best-in-class text rendering in images.",
            keyUrl = "https://developer.ideogram.ai", needsKey = true,
            models = img(GenModel("V_3", "Ideogram 3.0"), GenModel("V_2", "Ideogram 2.0"),
                GenModel("V_2_TURBO", "Ideogram 2.0 Turbo")),
        )
        GenProviderType.RECRAFT -> GenProviderMeta(
            this, setOf(GenKind.IMAGE), "Recraft",
            "Raster and vector/SVG output, brand styles.",
            keyUrl = "https://www.recraft.ai/profile/api", needsKey = true,
            models = img(GenModel("recraftv3", "Recraft V3"), GenModel("recraftv2", "Recraft V2")),
        )
        // ---------------------------------------------------------------- video
        GenProviderType.GUILLOTINE_FREE -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Guillotine (free)",
            "No key — runs an open text-to-video model on Guillotine's free Hugging Face Space. " +
                "Short, low-res clips; shared free GPU means it can queue at busy times. Only your text " +
                "prompt is sent — never your media.",
            keyUrl = null, needsKey = false,
            models = img(GenModel("ltx-video", "LTX-Video (fast)")),
            disclaimer = "Community free tier on shared GPU (Hugging Face ZeroGPU) — expect short clips " +
                "and occasional queueing. For longer/higher-quality video, add a key for a paid provider.",
        )
        GenProviderType.RUNWAY -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Runway",
            "Gen-4 / Gen-4 Turbo text- and image-to-video.",
            keyUrl = "https://dev.runwayml.com", needsKey = true,
            models = img(GenModel("gen4_turbo", "Gen-4 Turbo"), GenModel("gen3a_turbo", "Gen-3 Alpha Turbo")),
        )
        GenProviderType.LUMA -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Luma Dream Machine",
            "Ray2 / Ray3 text- and image-to-video.",
            keyUrl = "https://lumalabs.ai/dream-machine/api", needsKey = true,
            models = img(GenModel("ray-2", "Ray2"), GenModel("ray-flash-2", "Ray2 Flash"),
                GenModel("ray-1-6", "Ray 1.6")),
        )
        GenProviderType.GEMINI_VEO -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Google Veo",
            "Veo 3.1 (with native audio) via a Gemini API key.",
            keyUrl = "https://aistudio.google.com/app/apikey", needsKey = true,
            models = img(
                GenModel("veo-3.1-generate-preview", "Veo 3.1"),
                GenModel("veo-3.1-fast-generate-preview", "Veo 3.1 Fast"),
                GenModel("veo-2.0-generate-001", "Veo 2"),
            ),
        )
        GenProviderType.MINIMAX -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "MiniMax / Hailuo",
            "Hailuo text- and image-to-video.",
            keyUrl = "https://platform.minimax.io", needsKey = true,
            models = img(GenModel("MiniMax-Hailuo-02", "Hailuo 02"), GenModel("video-01", "Video-01"),
                GenModel("I2V-01", "I2V-01")),
        )
        GenProviderType.OPENAI_SORA -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "OpenAI Sora",
            "Sora 2 video with synced audio.",
            keyUrl = "https://platform.openai.com/api-keys", needsKey = true,
            models = img(GenModel("sora-2", "Sora 2"), GenModel("sora-2-pro", "Sora 2 Pro")),
        )
        GenProviderType.KLING -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Kling",
            "Kling text- and image-to-video (also reachable via the aggregators).",
            keyUrl = "https://app.klingai.com", needsKey = true,
            models = img(GenModel("kling-v2", "Kling 2.0"), GenModel("kling-v1-6", "Kling 1.6")),
        )
        GenProviderType.PIKA -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Pika",
            "Pika 2.2 text- and image-to-video.",
            keyUrl = "https://pika.art", needsKey = true,
            models = img(GenModel("pika-2.2", "Pika 2.2")),
        )
        GenProviderType.STABILITY_VIDEO -> GenProviderMeta(
            this, setOf(GenKind.VIDEO), "Stability AI (video)",
            "Image-to-video (Stable Video Diffusion lineage).",
            keyUrl = "https://platform.stability.ai/account/keys", needsKey = true,
            models = img(GenModel("stable-video-diffusion", "Stable Video Diffusion")),
        )
        // ---------------------------------------------------------------- music
        GenProviderType.ELEVENLABS -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "ElevenLabs",
            "Eleven Music (full songs), Sound Effects, and TTS. The most BYO-friendly audio API.",
            keyUrl = "https://elevenlabs.io/app/settings/api-keys", needsKey = true,
            models = img(GenModel("music", "Eleven Music"), GenModel("sound_effects", "Sound Effects")),
        )
        GenProviderType.STABILITY_AUDIO -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Stability Audio",
            "Stable Audio 2.0 — music and sound effects.",
            keyUrl = "https://platform.stability.ai/account/keys", needsKey = true,
            models = img(GenModel("stable-audio-2.0", "Stable Audio 2.0")),
        )
        GenProviderType.GEMINI_LYRIA -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Google Lyria",
            "Lyria music generation via a Gemini API key.",
            keyUrl = "https://aistudio.google.com/app/apikey", needsKey = true,
            models = img(GenModel("lyria-002", "Lyria 2")),
        )
        GenProviderType.MUSICGEN_REPLICATE -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "MusicGen (Replicate)",
            "Meta MusicGen text-to-music through your Replicate key.",
            keyUrl = "https://replicate.com/account/api-tokens", needsKey = true,
            models = img(GenModel("meta/musicgen", "MusicGen")),
        )
        GenProviderType.MUBERT -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Mubert",
            "Royalty-free, mood/genre/duration adaptive music.",
            keyUrl = "https://mubert.com/business/api", needsKey = true,
            models = img(GenModel("default", "Mubert")),
        )
        GenProviderType.BEATOVEN -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Beatoven.ai",
            "Mood-based background/bed music.",
            keyUrl = "https://www.beatoven.ai", needsKey = true,
            models = img(GenModel("default", "Beatoven")),
        )
        GenProviderType.LOUDLY -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Loudly",
            "Genre/mood music generation.",
            keyUrl = "https://www.loudly.com/developers", needsKey = true,
            models = img(GenModel("default", "Loudly")),
        )
        GenProviderType.CASSETTE -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Cassette",
            "AI music generation API.",
            keyUrl = "https://cassetteai.com", needsKey = true,
            models = img(GenModel("default", "Cassette")),
        )
        GenProviderType.SUNO_WRAPPER -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Suno (via wrapper)",
            "Full songs with vocals.",
            keyUrl = null, needsKey = true,
            models = img(GenModel("chirp-v4", "Suno v4")),
            disclaimer = "Suno has no official API. This uses a third-party wrapper you supply a " +
                "key and base URL for — reliability and terms are outside our control.",
        )
        GenProviderType.UDIO_WRAPPER -> GenProviderMeta(
            this, setOf(GenKind.MUSIC), "Udio (via wrapper)",
            "Full songs with vocals.",
            keyUrl = null, needsKey = true,
            models = img(GenModel("udio-130", "Udio")),
            disclaimer = "Udio has no official API. This uses a third-party wrapper you supply a " +
                "key and base URL for — reliability and terms are outside our control.",
        )
        // ---------------------------------------------------------------- aggregators
        GenProviderType.FAL -> GenProviderMeta(
            this, setOf(GenKind.IMAGE, GenKind.VIDEO, GenKind.MUSIC), "fal.ai (aggregator)",
            "One key → many image/video/music models. Enter the fal model id as the model.",
            keyUrl = "https://fal.ai/dashboard/keys", needsKey = true,
            models = img(
                GenModel("fal-ai/flux/dev", "FLUX.1 [dev] (image)"),
                GenModel("fal-ai/flux-pro/v1.1", "FLUX 1.1 [pro] (image)"),
                GenModel("fal-ai/kling-video/v2/master/text-to-video", "Kling 2 (video)"),
                GenModel("fal-ai/minimax/hailuo-02/standard/text-to-video", "Hailuo 02 (video)"),
                GenModel("fal-ai/luma-dream-machine", "Luma (video)"),
                GenModel("fal-ai/minimax-music", "MiniMax Music (music)"),
                GenModel("fal-ai/stable-audio", "Stable Audio (music)"),
            ),
        )
        GenProviderType.REPLICATE -> GenProviderMeta(
            this, setOf(GenKind.IMAGE, GenKind.VIDEO, GenKind.MUSIC), "Replicate (aggregator)",
            "One key → any hosted model. Enter the Replicate model (owner/name) as the model.",
            keyUrl = "https://replicate.com/account/api-tokens", needsKey = true,
            models = img(
                GenModel("black-forest-labs/flux-dev", "FLUX.1 [dev] (image)"),
                GenModel("stability-ai/stable-diffusion-3.5-large", "SD 3.5 Large (image)"),
                GenModel("kwaivgi/kling-v2.1", "Kling 2.1 (video)"),
                GenModel("minimax/video-01", "MiniMax Video (video)"),
                GenModel("meta/musicgen", "MusicGen (music)"),
            ),
        )
    }

/** All providers that can produce [kind] (regardless of configuration). */
fun providersFor(kind: GenKind): List<GenProviderType> =
    GenProviderType.entries.filter { it.meta.serves(kind) }

/** Non-extension accessor for [meta] — lets call sites that already import a different `meta`
 *  extension (e.g. the UI, which imports `AiProviderType.meta`) reach a provider's metadata. */
fun genMeta(p: GenProviderType): GenProviderMeta = p.meta
