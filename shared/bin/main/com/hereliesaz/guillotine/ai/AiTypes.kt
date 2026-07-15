package com.hereliesaz.guillotine.ai

import com.hereliesaz.guillotine.ai.gen.GenKind
import com.hereliesaz.guillotine.ai.gen.GenProviderType
import com.hereliesaz.guillotine.ai.gen.meta
import com.hereliesaz.guillotine.ai.gen.providersFor

enum class AiProviderType { LOCAL, MLKIT, GEMINI, OPENAI, ANTHROPIC, OPENROUTER, GROQ, XAI, MISTRAL }

data class ProviderMeta(
    val label: String,
    val blurb: String,
    val keyUrl: String? = null,
    val openAiCompatUrl: String? = null,
    val defaultModel: String? = null,
)

val AiProviderType.meta: ProviderMeta
    get() = when (this) {
        AiProviderType.LOCAL -> ProviderMeta(
            "Local (free, on-device)",
            "On-device silence cut. No key, works offline.",
        )
        AiProviderType.MLKIT -> ProviderMeta(
            "On-device vision (free)",
            "On-device faces & objects, no key — keep/remove by what's on screen.",
        )
        AiProviderType.GEMINI -> ProviderMeta(
            "Gemini",
            "Google · drives the editor via tools (no video sent).",
            keyUrl = "https://aistudio.google.com/app/apikey",
            defaultModel = "gemini-2.5-flash",
        )
        AiProviderType.OPENAI -> ProviderMeta(
            "OpenAI",
            "GPT · drives the editor via tools (no video sent).",
            keyUrl = "https://platform.openai.com/api-keys",
            defaultModel = "gpt-4o",
        )
        AiProviderType.ANTHROPIC -> ProviderMeta(
            "Anthropic",
            "Claude · drives the editor via tools (no video sent).",
            keyUrl = "https://console.anthropic.com/settings/keys",
            defaultModel = "claude-opus-4-8",
        )
        AiProviderType.OPENROUTER -> ProviderMeta(
            "OpenRouter",
            "One key, many models · drives the editor via tools.",
            keyUrl = "https://openrouter.ai/keys",
            openAiCompatUrl = "https://openrouter.ai/api/v1/chat/completions",
            defaultModel = "openai/gpt-4o-mini",
        )
        AiProviderType.GROQ -> ProviderMeta(
            "Groq",
            "Fast Llama · drives the editor via tools.",
            keyUrl = "https://console.groq.com/keys",
            openAiCompatUrl = "https://api.groq.com/openai/v1/chat/completions",
            defaultModel = "meta-llama/llama-4-scout-17b-16e-instruct",
        )
        AiProviderType.XAI -> ProviderMeta(
            "xAI (Grok)",
            "Grok · drives the editor via tools.",
            keyUrl = "https://console.x.ai",
            openAiCompatUrl = "https://api.x.ai/v1/chat/completions",
            defaultModel = "grok-2-vision-1212",
        )
        AiProviderType.MISTRAL -> ProviderMeta(
            "Mistral",
            "Mistral · drives the editor via tools.",
            keyUrl = "https://console.mistral.ai/api-keys",
            openAiCompatUrl = "https://api.mistral.ai/v1/chat/completions",
            defaultModel = "pixtral-12b-2409",
        )
    }

val byoProviders: List<AiProviderType> = AiProviderType.entries.filter { it.meta.keyUrl != null }

data class LeonardoModel(val id: String, val name: String)

val LeonardoModels: List<LeonardoModel> = listOf(
    LeonardoModel("de7d3faf-762f-48e0-b3b7-9d0ac3a3fcf3", "Leonardo Phoenix 1.0"),
    LeonardoModel("6b645e3a-d64f-4341-a6d8-7a3690fbf042", "Leonardo Phoenix 0.9"),
    LeonardoModel("b2614463-296c-462a-9586-aafdb8f00e36", "FLUX.1 Dev (Precision)"),
    LeonardoModel("1dd50843-d653-4516-a8e3-f0238ee453ff", "FLUX.1 Schnell (Speed)"),
    LeonardoModel("b24e16ff-06e3-43eb-8d33-4416c2d75876", "Leonardo Lightning XL"),
    LeonardoModel("e71a1c2f-4f80-4800-934f-2c68979d8cc8", "Leonardo Anime XL"),
    LeonardoModel("aa77f04e-3eec-4034-9c07-d0f619684628", "Leonardo Kino XL (cinematic)"),
    LeonardoModel("5c232a9e-9061-4777-980a-ddc8e65647c6", "Leonardo Vision XL"),
    LeonardoModel("1e60896f-3c26-4296-8ecc-53e2afecc132", "Leonardo Diffusion XL"),
    LeonardoModel("2067ae52-33fd-4a82-bb92-c2c55e7d2786", "AlbedoBase XL"),
    LeonardoModel("16e7060a-803e-4df3-97ee-edcfa5dc9cc8", "SDXL 1.0"),
)

val LeonardoDefaultModel: String = LeonardoModels.first().id

data class AiSettings(
    val provider: AiProviderType = AiProviderType.MLKIT,
    val keys: Map<AiProviderType, String> = emptyMap(),
    val models: Map<AiProviderType, String> = emptyMap(),
    val leonardoKey: String = "",
    val leonardoModel: String = LeonardoDefaultModel,
    val frameAnalysisCacheSize: Int = FrameAnalysisCache.DEFAULT_MAX_ENTRIES,
    /** Optional path to an `ffmpeg` executable for baking FFmpeg/Frei0r filtergraphs onto a clip (blank =
     *  feature off). Desktop-first; on Android, point at a bundled/downloaded ffmpeg binary. */
    val ffmpegPath: String = "",
    /** Bring-your-own keys for image/video/music generation providers. */
    val genKeys: Map<GenProviderType, String> = emptyMap(),
    /** Chosen model id per generation provider (blank → provider default). */
    val genModels: Map<GenProviderType, String> = emptyMap(),
    /** Provider-specific extra (e.g. a Suno/Udio wrapper base URL). */
    val genExtras: Map<GenProviderType, String> = emptyMap(),
    /** Preferred provider per category, remembered across sessions. */
    val genDefaults: Map<GenKind, GenProviderType> = emptyMap(),
) {
    fun keyFor(p: AiProviderType): String = keys[p].orEmpty()
    fun modelFor(p: AiProviderType): String =
        models[p]?.takeIf { it.isNotBlank() } ?: p.meta.defaultModel.orEmpty()
    fun withKey(p: AiProviderType, key: String): AiSettings = copy(keys = keys + (p to key))

    // ---- generation providers (image / video / music) ----------------------

    /** The user's key for a generation provider (Leonardo falls back to the legacy field). */
    fun genKeyFor(p: GenProviderType): String = when (p) {
        GenProviderType.LEONARDO -> genKeys[p]?.takeIf { it.isNotBlank() } ?: leonardoKey
        else -> genKeys[p].orEmpty()
    }

    fun genModelFor(p: GenProviderType): String {
        genModels[p]?.takeIf { it.isNotBlank() }?.let { return it }
        return if (p == GenProviderType.LEONARDO) leonardoModel else p.meta.defaultModel.orEmpty()
    }

    fun genExtraFor(p: GenProviderType): String = genExtras[p].orEmpty()

    /** True once the provider is usable (keyless, or a key is configured). */
    fun genProviderAvailable(p: GenProviderType): Boolean =
        !p.meta.needsKey || genKeyFor(p).isNotBlank()

    /** Configured providers for a category — the gate for what the app offers. */
    fun availableGenProviders(kind: GenKind): List<GenProviderType> =
        providersFor(kind).filter { genProviderAvailable(it) }

    /** A whole category is offered only when at least one of its providers is configured. */
    fun isKindOffered(kind: GenKind): Boolean = availableGenProviders(kind).isNotEmpty()

    /** The provider to use for a category by default (remembered pick, else first available). */
    fun defaultGenProvider(kind: GenKind): GenProviderType? =
        genDefaults[kind]?.takeIf { it in availableGenProviders(kind) }
            ?: availableGenProviders(kind).firstOrNull()

    fun withGenKey(p: GenProviderType, key: String): AiSettings = copy(genKeys = genKeys + (p to key))
}
