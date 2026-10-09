package com.hereliesaz.guillotine.ai.gen

/**
 * Registry of **generation** providers — the AIs that create new media (video, music) that
 * the user brings their own key for. This parallels the agent-brain registry in
 * [com.hereliesaz.guillotine.ai.AiProviderType] / `ProviderMeta`, but is capability-typed by
 * [GenKind] so the app can *gate* what it offers: a category (and a provider within it) is only
 * shown/usable once the user has configured a key for it. Users can configure as many as they like.
 *
 * Nothing here does I/O — backends live in [com.hereliesaz.guillotine.ai.gen] as [GenJob]s driven by
 * [AsyncJobPoller]. This file is the pure catalog + gating logic, shared across Android and desktop.
 */

/**
 * The generation categories. Text-to-image was removed (Google Play Sexual Content / AI-Generated
 * Content policy); cloud image work is limited to Leonardo inpainting for object removal.
 */
enum class GenKind { VIDEO, MUSIC }

/**
 * Every generation provider the app knows about. Aggregators ([FAL], [REPLICATE]) serve more than
 * one [GenKind] through a single key. Suno/Udio have **no official API** — only third-party
 * account-pooling wrappers — so they're exposed via a disclaimed "wrapper key" field.
 */
enum class GenProviderType {
    // ---- inpainting only (object removal); serves no GenKind ----
    LEONARDO,
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
 * of models. [needsKey] is false only for the free keyless providers (Guillotine free). [disclaimer]
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
        // ---------------------------------------------------------------- inpainting
        GenProviderType.LEONARDO -> GenProviderMeta(
            this, emptySet(), "Leonardo.ai",
            "Inpainting for generative object removal. No text-to-image.",
            keyUrl = "https://app.leonardo.ai/api-access", needsKey = true,
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
            this, setOf(GenKind.VIDEO, GenKind.MUSIC), "fal.ai (aggregator)",
            "One key → many video/music models. Enter the fal model id as the model.",
            keyUrl = "https://fal.ai/dashboard/keys", needsKey = true,
            models = img(
                GenModel("fal-ai/kling-video/v2/master/text-to-video", "Kling 2 (video)"),
                GenModel("fal-ai/minimax/hailuo-02/standard/text-to-video", "Hailuo 02 (video)"),
                GenModel("fal-ai/luma-dream-machine", "Luma (video)"),
                GenModel("fal-ai/minimax-music", "MiniMax Music (music)"),
                GenModel("fal-ai/stable-audio", "Stable Audio (music)"),
            ),
        )
        GenProviderType.REPLICATE -> GenProviderMeta(
            this, setOf(GenKind.VIDEO, GenKind.MUSIC), "Replicate (aggregator)",
            "One key → any hosted model. Enter the Replicate model (owner/name) as the model.",
            keyUrl = "https://replicate.com/account/api-tokens", needsKey = true,
            models = img(
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
