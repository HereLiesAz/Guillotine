package com.hereliesaz.guillotine.ai.agent

/**
 * Curated, known-good on-device LLM models for the assistant brain. All are `.task` files that load
 * with MediaPipe `LlmInference` (`tasks-genai`). Ungated repos download in-app with one tap; gated
 * ones (Gemma's license) link out to Hugging Face for a free sign-in, then the user pastes the path.
 *
 * Sizes/filenames verified against the litert-community repos. Direct download follows HF's
 * `resolve/main/<file>?download=true`, which 302s ungated files to a public CDN (no auth needed).
 */
/**
 * Which on-device runtime a model plugs into — drives where it's stored, how "Use" wires it, and
 * which group it appears under in the Model Manager. Only categories with a working runtime are shown
 * today; the rest are reserved for the roadmap features that add their engines.
 */
enum class ModelCategory {
    /** MediaPipe LlmInference `.task`/`.litertlm` — the assistant brain. */
    ASSISTANT_LLM,
    /** MediaPipe ImageEmbedder `.tflite` — "is this the same thing?" recognition. */
    RECOGNITION,
    /** Face-embedding `.tflite` — identifying a specific person. */
    FACE,
    /** Depth-estimation `.tflite` (image→image) for the "depth this frame" effect. */
    DEPTH,
    /** Super-resolution `.tflite` (image→image) for the "upscale this frame" effect. */
    SUPERRES,
    /** YAMNet audio-event `.tflite` — highlight / best-moment detection from a clip's audio. */
    AUDIO_EVENT,
    /** sherpa-onnx offline ASR model bundle — speech-to-text. */
    ASR,
    /** sherpa-onnx offline TTS voice bundle — text-to-speech. */
    TTS,
    /** Multimodal VLM `.task` (MediaPipe LlmInference + vision) — rich frame captioning. */
    VLM,
    /** sherpa-onnx pyannote segmentation model bundle — speaker diarization (who spoke when). */
    DIARIZE_SEG,
    /** sherpa-onnx speaker-embedding `.onnx` — the other half of diarization. */
    DIARIZE_EMBED,
    // --- reserved for upcoming runtimes (not yet shown in the picker) ---
    STYLE, STEM,
}

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
    /** Which runtime this model is for (drives storage dir + how "Use" wires it). */
    val category: ModelCategory = ModelCategory.ASSISTANT_LLM,
    /**
     * True if [downloadUrl] is a `.tar.bz2` bundle of several files (sherpa-onnx models) that must be
     * extracted into a per-model directory rather than used as a single file. When set, "Use" wires the
     * extracted *directory* path (not a single file) and installed-ness is checked via [archiveMarker].
     */
    val isArchive: Boolean = false,
    /** For archive models: a file, relative to the extracted directory, that must exist once installed. */
    val archiveMarker: String = "",
    /**
     * For single-file downloads: whether "installed" is verified by an exact byte-size match. Default
     * true. Set false when the exact size isn't known (e.g. a release `.onnx` whose size we couldn't
     * verify) — then a fully-downloaded, non-empty file counts as installed.
     */
    val verifyBySize: Boolean = true,
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
        license = "Gemma",
        gated = false,
        repoUrl = hfRepo("HereLiesAz/gemma3-1b-it"),
        downloadUrl = hfResolve("HereLiesAz/gemma3-1b-it", "gemma3-1b-it-int4.task"),
        abilities = "Compact and fast with good reasoning. Smallest download of the full-capability models.",
        limitations = "~0.55 GB. Mirrored from Google's Gemma release (Gemma Terms of Use apply).",
    ),
    OnDeviceModel(
        id = "deepseek-r1-qwen-1.5b-q8",
        label = "DeepSeek-R1 Distill Qwen 1.5B (q8) — reasoning",
        fileName = "deepseek_q8_ekv1280.task",
        sizeBytes = 1_860_686_856L,
        license = "MIT",
        gated = false,
        repoUrl = hfRepo("litert-community/DeepSeek-R1-Distill-Qwen-1.5B"),
        downloadUrl = hfResolve("litert-community/DeepSeek-R1-Distill-Qwen-1.5B", "deepseek_q8_ekv1280.task"),
        abilities = "Strong step-by-step reasoning for its size (distilled R1). Good for multi-step edits.",
        limitations = "1.86 GB download. Its reasoning traces can be verbose.",
    ),
    OnDeviceModel(
        id = "qwen3-0.6b-int4",
        label = "Qwen3 0.6B (int4) — light & modern",
        fileName = "qwen3_0_6b_mixed_int4.litertlm",
        sizeBytes = 497_664_000L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = hfRepo("litert-community/Qwen3-0.6B"),
        downloadUrl = hfResolve("litert-community/Qwen3-0.6B", "qwen3_0_6b_mixed_int4.litertlm"),
        abilities = "Small, fast, up-to-date Qwen3 assistant (mixed int4). A good lightweight default; ships as LiteRT-LM (.litertlm).",
        limitations = "~0.5 GB. Reasons less deeply than the 1.5 B+ options.",
    ),
    // Gemma-3n (VLM list) and this Qwen3 entry are both un-gated. Qwen3 ships as LiteRT-LM
    // (.litertlm), which the current MediaPipe runtime (0.10.35) loads directly.
)

/**
 * Recommended general image-embedding models for recognition ("is this the same specific thing?").
 * These are MediaPipe ImageEmbedder-compatible `.tflite` files; "Use" sets `idEmbedModelPath`.
 * Populated with verified Hugging Face downloads.
 */
val RECOMMENDED_RECOGNITION_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "mobilenet-v3-large-embed",
        label = "MobileNet-V3 Large — stronger recognition",
        fileName = "mobilenet_v3_large.tflite",
        sizeBytes = 10_889_458L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = "https://ai.google.dev/edge/mediapipe/solutions/vision/image_embedder",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_large/float32/1/mobilenet_v3_large.tflite",
        abilities = "A stronger, still-fast general embedder — better \"same thing?\" matching than the bundled small model. MediaPipe-native (has embedding metadata).",
        limitations = "Slightly larger/slower than the default.",
        category = ModelCategory.RECOGNITION,
    ),
    // (EfficientNet-Lite0 was dropped: the only public tflite is an image *classifier*, which MediaPipe's
    // ImageEmbedder rejects/mis-uses. MobileNet-V3-Small/Large are the official embedder models.)
    OnDeviceModel(
        id = "mobilenet-v3-small-embed",
        label = "MobileNet-V3 Small — the default embedder",
        fileName = "mobilenet_v3_small.tflite",
        sizeBytes = 4_117_670L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = "https://ai.google.dev/edge/mediapipe/solutions/vision/image_embedder",
        downloadUrl = "https://storage.googleapis.com/mediapipe-models/image_embedder/mobilenet_v3_small/float32/1/mobilenet_v3_small.tflite",
        abilities = "The lightweight reference embedder (same family as the bundled default) — handy if you want an explicit copy on disk.",
        limitations = "Lower quality than MobileNet-V3-Large.",
        category = ModelCategory.RECOGNITION,
    ),
)

/**
 * Recommended face-recognition models for identifying a specific person. "Use" sets `faceEmbedModelPath`.
 * These run through the raw-TFLite `FaceRecognizer` (NOT MediaPipe), so a plain face `.tflite` without
 * MediaPipe metadata is exactly what's wanted: detect a face → square-resize → (x−127.5)/128 → embed →
 * L2-normalize → cosine. Person concepts route here instead of the generic image embedder.
 */
val RECOMMENDED_FACE_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "mobilefacenet-192",
        label = "MobileFaceNet — face identity",
        fileName = "mobilefacenet.tflite",
        sizeBytes = 5_233_552L,
        license = "BSD-3-Clause",
        gated = false,
        repoUrl = "https://github.com/MCarlomagno/FaceRecognitionAuth",
        downloadUrl = "https://raw.githubusercontent.com/MCarlomagno/FaceRecognitionAuth/master/assets/mobilefacenet.tflite",
        abilities = "ArcFace-trained face embedder (112×112 → 192-d). Recognizes a specific *person* far better than the generic image embedder — pair it with a person concept for \"keep only shots with X\".",
        limitations = "Best with aligned faces; a plain detected-face crop works with somewhat lower accuracy. Weights: sirius-ai/MobileFaceNet_TF (Apache-2.0).",
        category = ModelCategory.FACE,
    ),
)

/**
 * Recommended depth-estimation `.tflite` models for the "depth this frame" effect. "Use" sets
 * `effectModelPaths["depth"]`. Single image in → single depth map out; [TfliteImageModel] normalizes the
 * map to a visible greyscale image.
 */
val RECOMMENDED_DEPTH_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "midas-small-256-fp16",
        label = "MiDaS-small — monocular depth",
        fileName = "midas_small_256_fp16.tflite",
        sizeBytes = 33_507_904L,
        license = "MIT / Apache-2.0",
        gated = false,
        repoUrl = hfRepo("litert-community/MiDaS-small"),
        downloadUrl = hfResolve("litert-community/MiDaS-small", "midas_small_256_fp16.tflite"),
        abilities = "Estimates a per-pixel depth map from a single frame (256×256). Clean drop-in — for depth-of-field, parallax, or a depth-map look.",
        limitations = "Relative (not metric) depth. 256×256 output is upscaled to the frame.",
        category = ModelCategory.DEPTH,
    ),
)

/**
 * Recommended super-resolution `.tflite` models for the "upscale / enhance this frame" effect. "Use"
 * sets `effectModelPaths["superres"]`. Single image in → single (larger) image out.
 */
val RECOMMENDED_SUPERRES_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "real-esrgan-x4v3",
        label = "Real-ESRGAN ×4 (general v3) — upscale",
        fileName = "realesr_general_x4v3.tflite",
        sizeBytes = 3_549_456L,
        license = "BSD-3-Clause",
        gated = false,
        repoUrl = hfRepo("litert-community/real-esrgan-x4v3-litert"),
        downloadUrl = hfResolve("litert-community/real-esrgan-x4v3-litert", "realesr_general_x4v3.tflite"),
        abilities = "4× super-resolution on a 128×128 tile (→512×512). Tiny (3.5 MB), good for sharpening a low-res still or a cropped frame.",
        limitations = "Operates on a 128×128 input tile; large frames are downscaled first. Best for stills, heavy for full video.",
        category = ModelCategory.SUPERRES,
    ),
)

// Style transfer intentionally has no recommended download: the only common style `.tflite` files are
// the two-input Magenta arbitrary-stylization pair (predict + transform), which don't fit the
// single-image-in/out TfliteImageModel runtime. Users can still point the style path at a compatible
// single-input model of their own.

/**
 * Recommended audio-event `.tflite` for highlight detection. "Use" sets `audioEventModelPath`. This is
 * the standard TF-Hub YAMNet classification export: a fixed 15600-sample (0.975 s @ 16 kHz) waveform in →
 * `[1,521]` AudioSet class scores out. `find_highlights` runs it frame-by-frame to locate exciting
 * moments (applause, cheering, laughter, music…).
 */
val RECOMMENDED_AUDIO_EVENT_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "yamnet-classification",
        label = "YAMNet — audio-event highlights",
        fileName = "lite-model_yamnet_classification_tflite_1.tflite",
        sizeBytes = 4_126_810L,
        license = "Apache-2.0",
        gated = false,
        repoUrl = hfRepo("thelou1s/yamnet"),
        downloadUrl = hfResolve("thelou1s/yamnet", "lite-model_yamnet_classification_tflite_1.tflite"),
        abilities = "Detects 521 audio events on-device. Powers \"find the highlights / best moments\" — applause, cheering, laughter, music, screaming, crowd.",
        limitations = "Tags sound, not beats; a noisy mix can blur events. ~1s time resolution.",
        category = ModelCategory.AUDIO_EVENT,
    ),
)

/**
 * Recommended offline ASR (speech-to-text) models for sherpa-onnx. Multi-file `.tar.bz2` bundles
 * extracted into a per-model directory; "Use" sets `asrModelPath` to that directory.
 */
val RECOMMENDED_ASR_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "sherpa-whisper-tiny-en",
        label = "Whisper tiny.en — accurate captions",
        fileName = "sherpa-onnx-whisper-tiny.en.tar.bz2",
        sizeBytes = 113_000_000L, // approximate compressed size (for the free-space check / progress)
        license = "MIT",
        gated = false,
        repoUrl = hfRepo("csukuangfj/sherpa-onnx-whisper-tiny.en"),
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.en.tar.bz2",
        abilities = "Word-level English transcription (OpenAI Whisper tiny) — sharper captions than the lightweight default recognizer.",
        limitations = "~100 MB. English only; runs offline via sherpa-onnx.",
        isArchive = true,
        archiveMarker = "tiny.en-encoder.int8.onnx",
        category = ModelCategory.ASR,
    ),
)

/**
 * Recommended offline TTS (text-to-speech) voices for sherpa-onnx. Multi-file `.tar.bz2` bundles;
 * "Use" sets `ttsModelPath` to the extracted directory.
 */
val RECOMMENDED_TTS_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "sherpa-piper-en-us-amy-low",
        label = "Piper — Amy (US English) voice",
        fileName = "vits-piper-en_US-amy-low.tar.bz2",
        sizeBytes = 30_000_000L, // approximate
        license = "MIT (verify the voice's dataset license before commercial use)",
        gated = false,
        repoUrl = hfRepo("csukuangfj/vits-piper-en_US-amy-low"),
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-amy-low.tar.bz2",
        abilities = "Offline neural text-to-speech for voiceover / narration (Piper VITS).",
        limitations = "~30 MB. One English voice; runs offline via sherpa-onnx.",
        isArchive = true,
        archiveMarker = "en_US-amy-low.onnx",
        category = ModelCategory.TTS,
    ),
)

/**
 * Recommended multimodal VLM `.task` models (MediaPipe `LlmInference` + vision) for rich frame
 * captioning. "Use" sets `vlmModelPath`. Gemma-3n is re-hosted (un-gated) in our own HF namespace —
 * mirrored from Google's Gemma release, so it downloads with no sign-in. (Subject to the Gemma Terms
 * of Use; see the in-app notice.)
 */
val RECOMMENDED_VLM_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "gemma-3n-e2b-it",
        label = "Gemma 3n E2B (vision) — frame captioning",
        fileName = "gemma-3n-E2B-it-int4.task",
        sizeBytes = 3_136_226_711L,
        license = "Gemma",
        gated = false,
        repoUrl = hfRepo("HereLiesAz/gemma-3n-e2b-it-litertlm"),
        downloadUrl = hfResolve("HereLiesAz/gemma-3n-e2b-it-litertlm", "gemma-3n-E2B-it-int4.task"),
        abilities = "Natively multimodal: looks at a frame and describes it in rich natural language. Powers \"describe / what's happening in this frame\".",
        limitations = "~2.9 GB. High-end device recommended. Mirrored from Google's Gemma release (Gemma Terms of Use apply).",
        category = ModelCategory.VLM,
    ),
    OnDeviceModel(
        id = "gemma-3n-e4b-it",
        label = "Gemma 3n E4B (vision) — higher quality",
        fileName = "gemma-3n-E4B-it-int4.task",
        sizeBytes = 4_405_655_031L,
        license = "Gemma",
        gated = false,
        repoUrl = hfRepo("HereLiesAz/gemma-3n-e4b-it-litertlm"),
        downloadUrl = hfResolve("HereLiesAz/gemma-3n-e4b-it-litertlm", "gemma-3n-E4B-it-int4.task"),
        abilities = "The larger, more capable multimodal Gemma-3n — sharper, more detailed frame descriptions.",
        limitations = "~4.1 GB. Needs a high-end device with plenty of storage. Mirrored from Google's Gemma release (Gemma Terms of Use apply).",
        category = ModelCategory.VLM,
    ),
)

/**
 * Recommended speaker-diarization models for sherpa-onnx — the SEGMENTATION half (pyannote). Multi-file
 * `.tar.bz2`; "Use" sets `diarizeSegModelPath` to the extracted directory. Pair with an embedding model.
 */
val RECOMMENDED_DIARIZE_SEG_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "sherpa-pyannote-seg-3-0",
        label = "Pyannote segmentation 3.0 — speaker turns",
        fileName = "sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        sizeBytes = 6_000_000L, // approximate
        license = "MIT (converted pyannote weights)",
        gated = false,
        repoUrl = hfRepo("csukuangfj/sherpa-onnx-pyannote-segmentation-3-0"),
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2",
        abilities = "Detects who is speaking when (speaker turns). Half of diarization — pair it with a speaker-embedding model.",
        limitations = "~6 MB. Needs a speaker-embedding model too.",
        isArchive = true,
        archiveMarker = "model.onnx",
        category = ModelCategory.DIARIZE_SEG,
    ),
)

/**
 * Recommended speaker-diarization models for sherpa-onnx — the EMBEDDING half (single `.onnx`). "Use"
 * sets `diarizeEmbedModelPath`. Pair with a segmentation model.
 */
val RECOMMENDED_DIARIZE_EMBED_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "3dspeaker-eres2net-base-16k",
        label = "3D-Speaker ERes2Net — speaker embeddings",
        fileName = "3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        sizeBytes = 26_000_000L, // approximate (exact size unverified → don't size-check)
        license = "Apache-2.0",
        gated = false,
        repoUrl = hfRepo("csukuangfj/speaker-embedding-models"),
        // Release tag is spelled "recongition" upstream — keep it verbatim or the download 404s.
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx",
        abilities = "Turns each speaker turn into a voiceprint so turns can be grouped by speaker. The other half of diarization.",
        limitations = "~26 MB. Needs a segmentation model too.",
        verifyBySize = false,
        category = ModelCategory.DIARIZE_EMBED,
    ),
)

/**
 * Recommended source-separation (stem) model for ONNX Runtime — Deezer Spleeter 2-stem. Multi-file
 * `.tar.bz2` (vocals + accompaniment `.onnx`); "Use" sets `stemModelPath` to the extracted directory.
 */
val RECOMMENDED_STEM_MODELS: List<OnDeviceModel> = listOf(
    OnDeviceModel(
        id = "spleeter-2stems",
        label = "Spleeter 2-stem — vocals / accompaniment",
        fileName = "sherpa-onnx-spleeter-2stems.tar.bz2",
        sizeBytes = 71_200_000L,
        license = "MIT (Deezer Spleeter)",
        gated = false,
        repoUrl = hfRepo("csukuangfj/sherpa-onnx-spleeter-2stems"),
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/source-separation-models/sherpa-onnx-spleeter-2stems.tar.bz2",
        abilities = "Separates a song into a vocals track and an instrumental (accompaniment) track — real ML stem splitting for remixes, karaoke, or isolating either part.",
        limitations = "~71 MB. Runs via ONNX Runtime; heavy (hundreds of MB of RAM) — best on a capable device and moderate clip lengths.",
        isArchive = true,
        archiveMarker = "vocals.onnx",
        category = ModelCategory.STEM,
    ),
)

/** All catalogs a given [ModelCategory] draws from (for the Model Manager). */
fun recommendedModelsFor(category: ModelCategory): List<OnDeviceModel> = when (category) {
    ModelCategory.ASSISTANT_LLM -> RECOMMENDED_ON_DEVICE_MODELS
    ModelCategory.RECOGNITION -> RECOMMENDED_RECOGNITION_MODELS
    ModelCategory.FACE -> RECOMMENDED_FACE_MODELS
    ModelCategory.DEPTH -> RECOMMENDED_DEPTH_MODELS
    ModelCategory.SUPERRES -> RECOMMENDED_SUPERRES_MODELS
    ModelCategory.AUDIO_EVENT -> RECOMMENDED_AUDIO_EVENT_MODELS
    ModelCategory.ASR -> RECOMMENDED_ASR_MODELS
    ModelCategory.TTS -> RECOMMENDED_TTS_MODELS
    ModelCategory.VLM -> RECOMMENDED_VLM_MODELS
    ModelCategory.DIARIZE_SEG -> RECOMMENDED_DIARIZE_SEG_MODELS
    ModelCategory.DIARIZE_EMBED -> RECOMMENDED_DIARIZE_EMBED_MODELS
    ModelCategory.STEM -> RECOMMENDED_STEM_MODELS
    else -> emptyList()
}
