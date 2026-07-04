package com.hereliesaz.guillotine.ai.agent

/**
 * Curated, known-good on-device LLM models for the assistant brain. All are `.task` files that load
 * with MediaPipe `LlmInference` (`tasks-genai`). Ungated repos download in-app with one tap; gated
 * ones (Gemma's license) link out to Hugging Face for a free sign-in, then the user pastes the path.
 *
 * Sizes/filenames verified against the litert-community repos. Direct download follows HF's
 * `resolve/main/<file>?download=true`, which 302s ungated files to a public CDN (no auth needed).
 */
data class OnDeviceModel(
    val id: String,
    val label: String,
    val fileName: String,
    val sizeBytes: Long,
    val license: String,
    /** Gated repos can't be fetched unattended — we link out instead of downloading. */
    val gated: Boolean,
    /** Hugging Face repo page (used for gated link-out and the "details" link). */
    val repoUrl: String,
    /** Direct download URL — null for gated or bundled models. */
    val downloadUrl: String?,
    /** What this model is good at. Shown in onboarding and settings. */
    val abilities: String = "",
    /** What this model struggles with. Shown in onboarding and settings. */
    val limitations: String = "",
    /** True if the model ships inside the APK and is extracted on first launch. */
    val bundled: Boolean = false,
) {
    /** Human-readable size, e.g. "1.46 GB" or "167 MB". */
    val sizeLabel: String get() {
        val gb = sizeBytes / 1_000_000_000.0
        return if (gb >= 1.0) "%.2f GB".format(gb) else "${sizeBytes / 1_000_000} MB"
    }
}

private fun hfResolve(repo: String, file: String) =
    "https://huggingface.co/$repo/resolve/main/$file?download=true"

private fun hfRepo(repo: String) = "https://huggingface.co/$repo"

/** Recommended models, bundled starter first. */
val RECOMMENDED_ON_DEVICE_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "smollm-135m-q8",
        label = "SmolLM 135M Instruct (q8) — bundled",
        fileName = "SmolLM-135M-Instruct_multi-prefill-seq_q8_ekv1280.task",
        sizeBytes = 166_754_726L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = hfRepo("litert-community/SmolLM-135M-Instruct"),
        downloadUrl = null,
        abilities = "Instant startup, basic text completion, simple tool calls. Works offline with no download.",
        limitations = "Very limited reasoning. Struggles with multi-step instructions and complex edits.",
        bundled = true,
    ),
    OnDeviceModel(
        id = "qwen2.5-0.5b-q8",
        label = "Qwen2.5 0.5B Instruct (q8)",
        fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        sizeBytes = 546_660_344L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = hfRepo("litert-community/Qwen2.5-0.5B-Instruct"),
        downloadUrl = hfResolve(
            "litert-community/Qwen2.5-0.5B-Instruct",
            "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        ),
        abilities = "Good reasoning for its size. Handles tool calls and understands editing context well.",
        limitations = "Slower than the starter. Weaker than 1.5B+ models on complex creative tasks.",
    ),
    OnDeviceModel(
        id = "qwen2.5-1.5b-q8",
        label = "Qwen2.5 1.5B Instruct (q8)",
        fileName = "Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
        sizeBytes = 1_567_364_648L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = hfRepo("litert-community/Qwen2.5-1.5B-Instruct"),
        downloadUrl = hfResolve(
            "litert-community/Qwen2.5-1.5B-Instruct",
            "Qwen2.5-1.5B-Instruct_seq128_q8_ekv1280.task",
        ),
        abilities = "Strong reasoning and tool use. Best balance of quality and download size.",
        limitations = "1.57 GB download. May be slow on older devices.",
    ),
    OnDeviceModel(
        id = "phi4-mini-q8",
        label = "Phi-4 mini Instruct (q8)",
        fileName = "phi4_q8_ekv1280.task",
        sizeBytes = 3_944_280_650L,
        license = "MIT",
        gated = false,
        repoUrl = hfRepo("litert-community/Phi-4-mini-instruct"),
        downloadUrl = hfResolve("litert-community/Phi-4-mini-instruct", "phi4_q8_ekv1280.task"),
        abilities = "Most capable on-device model. Excellent reasoning and instruction following.",
        limitations = "3.94 GB download. Needs a high-end device with plenty of storage.",
    ),
    OnDeviceModel(
        id = "gemma3-1b-int4",
        label = "Gemma 3 1B Instruct (int4)",
        fileName = "gemma3-1b-it-int4.task",
        sizeBytes = 554_661_243L,
        license = "Gemma (free sign-in)",
        gated = true,
        repoUrl = hfRepo("litert-community/Gemma3-1B-IT"),
        downloadUrl = null,
        abilities = "Compact and fast with good reasoning. Smallest download of the full-capability models.",
        limitations = "Requires a free Hugging Face sign-in to download (Gemma license).",
    ),
)
