package com.hereliesaz.guillotine.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** Short unique id for media/clips/keyframes. */
fun newId(): String = UUID.randomUUID().toString()

/** What kind of source media this is. Drives which clip type(s) it produces. */
@Serializable
enum class MediaKind { VIDEO, AUDIO, IMAGE }

/** A timeline track carries picture, sound, or an overlaid text/caption. */
@Serializable
enum class ClipType { VIDEO, AUDIO, TEXT }

/**
 * A per-clip parameter that can be animated with keyframes. Each carries its UI range (for the
 * envelope + sliders) and whether it's an audio param. [staticValue] is the clip's current value
 * when the property isn't keyframed (and the default fed to [TimelineMath.valueAt]); keyframes store
 * absolute values. Color params compose into one per-frame color matrix; transform params into the
 * per-frame geometry; volume/pan into the audio path.
 */
@Serializable
enum class KeyframeProperty(val uiRange: ClosedFloatingPointRange<Float>, val isAudio: Boolean = false) {
    OPACITY(0f..1f),
    SCALE(0f..6f),
    VOLUME(0f..2f, isAudio = true),
    ROTATION(-180f..180f),
    OFFSET_X(-1.5f..1.5f),
    OFFSET_Y(-1.5f..1.5f),
    BRIGHTNESS(0f..2f),
    CONTRAST(0f..2f),
    SATURATION(0f..2f),
    HUE(0f..360f),
    SEPIA(0f..100f),
    PAN(-1f..1f, isAudio = true),
    SPEED(0.1f..10f);

    /** The clip's current value for this property (default when unkeyframed; what the diamond records). */
    fun staticValue(clip: TimelineClip): Float = when (this) {
        OPACITY -> 1f
        SCALE -> clip.scale
        ROTATION -> clip.rotation
        OFFSET_X -> clip.offsetX
        OFFSET_Y -> clip.offsetY
        VOLUME -> clip.filters.volume
        PAN -> clip.filters.pan
        BRIGHTNESS -> clip.filters.brightness
        CONTRAST -> clip.filters.contrast
        SATURATION -> clip.filters.saturation
        HUE -> clip.filters.hueRotate
        SEPIA -> clip.filters.sepia
        SPEED -> clip.filters.speed
    }

    companion object {
        /** Color adjustments that compose into one per-frame color matrix. */
        val COLOR = setOf(BRIGHTNESS, CONTRAST, SATURATION, HUE, SEPIA)
        /** Geometry params animated via the per-frame transform. */
        val TRANSFORM = setOf(SCALE, ROTATION, OFFSET_X, OFFSET_Y)
    }
}

/** Typeface for [ClipType.TEXT] clips. Mapped to a Compose FontFamily in the UI layer. */
@Serializable
enum class TextFont { SANS, SERIF, MONO, CURSIVE }

/**
 * A one-tap look for a text/caption clip: typeface + placement ([offsetY], fraction of the frame
 * below center) + [scale]. Applied to the selected clip's [TimelineClip] fields, so the result stays
 * fully editable afterward (crop tool, font row). Shared so app and desktop offer identical presets.
 */
data class TextStylePreset(
    val label: String,
    val font: TextFont,
    val offsetY: Float,
    val scale: Float,
)

/** Curated text/caption looks surfaced in the Text tool. */
val TEXT_STYLE_PRESETS: List<TextStylePreset> = listOf(
    TextStylePreset("Subtitle", TextFont.SANS, 0.30f, 1.0f),   // lower-third caption
    TextStylePreset("Title", TextFont.SERIF, 0.0f, 1.7f),      // big, centered
    TextStylePreset("Lower third", TextFont.SANS, 0.40f, 0.8f), // small label near the bottom
    TextStylePreset("Caption", TextFont.MONO, 0.30f, 0.9f),    // monospace lower-third
)

@Serializable
enum class EditAction { KEEP, REMOVE }

@Serializable
enum class AspectRatio { RATIO_16_9, RATIO_9_16, RATIO_1_1, ORIGINAL }

@Serializable
enum class Quality {
    ORIGINAL, UHD_4K, FHD_1080P, HD_720P;

    /**
     * Output height in pixels, or null for [ORIGINAL] (keep the source's own size).
     *
     * This existed as a setting the user could change and the exporter never read — `Transformer` was
     * built with a video MIME type and nothing else, so picking 720p produced a full-resolution file.
     * Width is deliberately not fixed: the export applies this through a height-only presentation so the
     * frame's aspect ratio (already letterboxed by [GlobalSettings.aspectRatio]) is preserved.
     */
    val targetHeight: Int?
        get() = when (this) {
            ORIGINAL -> null
            UHD_4K -> 2160
            FHD_1080P -> 1080
            HD_720P -> 720
        }
}

/** Imported source media. [durationMs] is probed on import (5s default for images). */
@Serializable
data class MediaItem(
    val id: String,
    val uri: String,
    val name: String,
    val kind: MediaKind,
    val durationMs: Long,
    /** True if a VIDEO file also carries an audio stream (→ auto-add a paired audio clip). */
    val hasAudio: Boolean = false,
    /** User-defined keywords (e.g. "b-roll", "interview") — the Media Bin's search matches these
     *  alongside [name], and its category chips filter by them, so tagging one clip "b-roll" makes
     *  every future search for that tag find it automatically (Vegas B.6's "Smart Bin"). */
    val tags: List<String> = emptyList(),
)

/** Cubic-bezier easing control points (P1, P2); endpoints are fixed at (0,0)/(1,1). */
@Serializable
data class CubicBezier(
    val x1: Float = 0.25f,
    val y1: Float = 0.1f,
    val x2: Float = 0.25f,
    val y2: Float = 1f,
)

@Serializable
data class Keyframe(
    val id: String,
    /** Time within the clip, in milliseconds from the clip's start. */
    val timeMs: Long,
    val value: Float,
    val property: KeyframeProperty,
    val easing: CubicBezier = CubicBezier(),
    /**
     * True to hold this keyframe's [value] constant (no interpolation) across the segment to the next
     * keyframe, then jump — Vegas Pro's envelope "Hold" curve, the one interpolation shape a cubic
     * bezier can't express (every bezier still eases smoothly from A to B; a hold stays flat at A's
     * value until the instant B's time arrives). [easing] is ignored on a held segment.
     */
    val hold: Boolean = false,
    /**
     * True when this keyframe was baked by a kinetic-typography motion preset (see [AzpMotionInterpreter])
     * rather than authored by the user. Lets the editor strip/replace exactly the preset's curve when the
     * user switches or clears a caption's animation, leaving hand-made keyframes on the same channels
     * intact. Default false — user keyframes and old projects deserialize as authored.
     */
    val generated: Boolean = false,
)

/** An AI (or heuristic) suggested keep/remove range, in source-media milliseconds. */
@Serializable
data class EditSegment(
    val startMs: Long,
    val endMs: Long,
    val action: EditAction,
    val reason: String = "",
)

/** Per-clip visual + audio adjustments. Defaults are identity (no effect). */
@Serializable
data class ClipFilters(
    val brightness: Float = 1f,
    val contrast: Float = 1f,
    val saturation: Float = 1f,
    val sepia: Float = 0f,        // 0..100 (%)
    val blur: Float = 0f,         // px
    val volume: Float = 1f,       // 0..2 (gain)
    val pan: Float = 0f,          // -1..1
    val normalize: Boolean = false,
    val hueRotate: Float = 0f,    // degrees
    val invert: Float = 0f,       // 0..100 (%)
    val grayscale: Float = 0f,    // 0..100 (%)
    val speed: Float = 1f,        // 0.1..10.0x
    /**
     * Frame decimation ("keep every Nth frame"). 1 = every frame (off); 2 = every other frame; N = keep
     * one of every N. Produces a choppy/stutter look at the SAME duration (audio is untouched, so it stays
     * in sync). Quantizes relative to the project frame rate ([GlobalSettings.fps]): desktop's frame-grab
     * preview/export snap the source time to the kept-frame grid, and Android drops to `fps / frameStep`
     * via a Media3 FrameDropEffect. This is the live, on-device, ffmpeg-free path; `apply_ffmpeg_filter`
     * with `framestep=N` is the bake equivalent.
     */
    val frameStep: Int = 1,
    /** On-device subject segmentation: keep the foreground, drop the background so a lower layer shows through. */
    val removeBackground: Boolean = false,
    /** On-device face detection: blur every detected face for privacy/anonymization. */
    val blurFaces: Boolean = false,
    /** On-device subject segmentation: keep the subject sharp and blur the background (portrait bokeh). */
    val bokeh: Boolean = false,
    /**
     * Deprecated single-slot LUT path — kept ONLY so a project saved before [fxChain] existed still
     * loads and renders identically. New code should never write these three legacy fields; write
     * [fxChain] instead and read effects via [ClipFilters.effectiveFxChain]. Superseded (2026-08-12) by
     * an ordered, stackable chain — see [FxLayer]'s doc for why one shader/LUT slot each stopped being
     * enough.
     */
    val lutPath: String = "",
    /** See [lutPath]'s deprecation note. */
    val shaderPath: String = "",
    /** See [lutPath]'s deprecation note. */
    val shaderParams: Map<String, Float> = emptyMap(),
    /**
     * Ordered stack of shader/LUT layers applied to this clip, each rendered in list order (index 0
     * first) — the "VST-style hosting" azphalt asset packages now get: applying a second shader/LUT no
     * longer silently replaces the first, it stacks on top of it, and the stack can be reordered or have
     * any single layer removed independently. Empty on a fresh clip; [effectiveFxChain] falls back to
     * the legacy [lutPath]/[shaderPath] pair for a project saved before this field existed.
     */
    val fxChain: List<FxLayer> = emptyList(),
) {
    /**
     * The chain actually rendered: [fxChain] verbatim once anything has ever been written to it,
     * otherwise synthesized from the legacy single-slot fields (LUT before shader, matching the old
     * fixed render order) so an already-saved project renders pixel-identical to before this field
     * existed. A project is never silently migrated on disk by this — it's a read-time fallback only,
     * recomputed from whichever fields are actually populated.
     */
    val effectiveFxChain: List<FxLayer>
        get() = if (fxChain.isNotEmpty()) {
            fxChain
        } else {
            buildList {
                if (lutPath.isNotBlank()) add(FxLayer(kind = FxLayer.KIND_LUT, path = lutPath))
                if (shaderPath.isNotBlank()) add(FxLayer(kind = FxLayer.KIND_SHADER, path = shaderPath, params = shaderParams))
            }
        }
}

/**
 * One layer in a [ClipFilters.fxChain] — either a `.cube` LUT or a GLSL/ISF shader, azphalt's two
 * natively-rendered asset kinds (see `AzpAssetApplier`). Before this existed a clip had exactly one
 * shader slot and one LUT slot ([ClipFilters.shaderPath]/[ClipFilters.lutPath]); applying a second
 * shader or LUT silently overwrote the first, which is the opposite of how a real plugin chain (VST,
 * OFX) behaves — stacking, reordering, and removing one layer without disturbing the others.
 */
@kotlinx.serialization.Serializable
data class FxLayer(
    /** Stable per-layer id (independent of [path], so two layers can share the same asset file). */
    val id: String = newId(),
    /** [KIND_LUT] or [KIND_SHADER] — which renderer applies [path]. */
    val kind: String,
    /** Path to the `.cube` LUT or shader (`.isf`/`.fs`/`.glsl`) file on disk. */
    val path: String,
    /** Shader-only: per-layer overrides for its scalar uniforms; ignored for a LUT layer. */
    val params: Map<String, Float> = emptyMap(),
    /** Off without removing the layer — keeps its position/params for a quick A/B toggle. */
    val enabled: Boolean = true,
) {
    companion object {
        const val KIND_LUT = "lut"
        const val KIND_SHADER = "shader"
    }
}

@Serializable
data class TimelineClip(
    val id: String,
    val mediaId: String,
    val type: ClipType,
    val trackId: String,
    /** Position on the timeline, in milliseconds. */
    val startTimeMs: Long,
    /** Offset into the source media where this clip begins, in milliseconds. */
    val trimStartMs: Long,
    /** Length of the clip on the timeline, in milliseconds. */
    val durationMs: Long,
    val prompt: String = "",
    val edits: List<EditSegment> = emptyList(),
    val keyframes: List<Keyframe> = emptyList(),
    val filters: ClipFilters = ClipFilters(),
    val isAnalyzing: Boolean = false,
    val error: String? = null,
    /** Clips sharing a non-null [groupId] select/move/delete together. */
    val groupId: String? = null,
    /**
     * For the AUDIO clip auto-created from a video import: the id of the VIDEO clip it shadows.
     * Such a clip is a visual waveform of the video's own audio (which plays/exports from the
     * video clip itself) — it is NOT a second playback, so it is skipped in preview/export.
     */
    val linkedClipId: String? = null,
    /** Caption/title text for [ClipType.TEXT] clips (empty for video/audio). */
    val text: String = "",
    /** Typeface for text clips. */
    val font: TextFont = TextFont.SANS,
    /** Crop-tool transform: scale, normalized offset (fraction of frame) from center, rotation°. */
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
    /** The `.azp` plugin ID applied to this clip, if any (kinetic typography, layer effects, etc.) */
    val azpPluginId: String? = null,
) {
    val endTimeMs: Long get() = startTimeMs + durationMs
}

@Serializable
data class Crop(
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 100f,
    val h: Float = 100f,
)

/** Whole-track (timeline) settings keyed by track id. Absent = defaults. */
@Serializable
data class TrackSettings(
    val volume: Float = 1f,      // audio/video
    val opacity: Float = 1f,     // video/text
    val muted: Boolean = false,  // audio/video
    val disabled: Boolean = false, // hide/disable the whole track
    /** Display name shown instead of the raw track id ("V1"/"A1") once set; blank = show the id. */
    val name: String = "",
    /** Header/clip accent color as "#RRGGBB"; blank = the default neutral color. */
    val colorHex: String = "",
    /** Collapsed to a thin strip in the timeline (a visual/vertical-space toggle only — clips, edits,
     *  and every other setting on this track are completely unaffected; unlike [disabled] this never
     *  touches preview or export). */
    val minimized: Boolean = false,
)

@Serializable
data class GlobalSettings(
    val aspectRatio: AspectRatio = AspectRatio.ORIGINAL,
    val quality: Quality = Quality.ORIGINAL,
    val crop: Crop = Crop(),
    val fps: Int = 30,
) {
    val frameDurationMs: Double get() = 1000.0 / fps
}

/**
 * The full undoable editor document. Transient UI state (playhead, zoom,
 * selection) is intentionally NOT part of this — only content is undoable.
 */
@Serializable
data class Document(
    /** User-facing project name (set via Save → Name; the project itself is always autosaved). */
    val name: String = "",
    val mediaItems: List<MediaItem> = emptyList(),
    val clips: List<TimelineClip> = emptyList(),
    /** Track lists in stacking order (top of panel = top layer). Text is just a clip on a video track. */
    val videoTracks: List<String> = listOf("V1"),
    val audioTracks: List<String> = listOf("A1"),
    val trackSettings: Map<String, TrackSettings> = emptyMap(),
    val settings: GlobalSettings = GlobalSettings(),
    /** Recent AI prompts used in this project (most-recent first); inline hint + history dropdown. */
    val promptHistory: List<String> = emptyList(),
    /**
     * Persisted horizontal zoom (pixels per second) so a project resumes at the user's last zoom
     * level. Null on a fresh project — the editor picks a fit-all default at load time. NOT
     * undoable: [EditorViewModel] treats writes to this field as transient view state and
     * preserves the live UI zoom across undo/redo instead of restoring stale values from history.
     */
    val pixelsPerSecond: Float? = null,
) {
    /** End of the last clip on the timeline, in milliseconds. */
    val totalDurationMs: Long
        get() = clips.maxOfOrNull { it.endTimeMs } ?: 0L

    fun mediaFor(clip: TimelineClip): MediaItem? = mediaItems.firstOrNull { it.id == clip.mediaId }

    fun trackSettingsFor(trackId: String): TrackSettings = trackSettings[trackId] ?: TrackSettings()

    /** Track ids whose whole track is disabled/hidden. */
    val disabledTrackIds: Set<String> get() = trackSettings.filterValues { it.disabled }.keys

    /**
     * A copy of this document restricted to the `[startMs, endMs)` timeline window — the "Render Loop
     * Region Only" export option (Vegas J.4). Clips entirely outside the window are dropped; a clip that
     * only partially overlaps is trimmed to the window, exactly like a manual edge trim
     * ([com.hereliesaz.guillotine.editor.EditorViewModel.trimClipStart]/`trimClipEnd`): trimming the left
     * edge advances [TimelineClip.trimStartMs] and shifts keyframes back by the same delta (dropping any
     * that land before the new start); trimming the right edge shortens [TimelineClip.durationMs] and
     * drops keyframes past it. Every surviving clip is then shifted so [startMs] becomes the new timeline
     * zero. The exporters run entirely unchanged against the result — this is a pre-processing step, not
     * a change to either render pipeline.
     */
    fun clampedToRegion(startMs: Long, endMs: Long): Document {
        require(endMs > startMs) { "endMs ($endMs) must be after startMs ($startMs)" }
        val newClips = clips.mapNotNull { clip ->
            if (clip.endTimeMs <= startMs || clip.startTimeMs >= endMs) return@mapNotNull null
            var c = clip
            if (c.startTimeMs < startMs) {
                val d = startMs - c.startTimeMs
                c = c.copy(
                    startTimeMs = 0L,
                    trimStartMs = c.trimStartMs + d,
                    durationMs = c.durationMs - d,
                    keyframes = c.keyframes.map { k -> k.copy(timeMs = k.timeMs - d) }.filter { k -> k.timeMs >= 0 },
                )
            } else {
                c = c.copy(startTimeMs = c.startTimeMs - startMs)
            }
            val windowMs = endMs - startMs
            if (c.endTimeMs > windowMs) {
                val nd = windowMs - c.startTimeMs
                c = c.copy(durationMs = nd, keyframes = c.keyframes.filter { k -> k.timeMs <= nd })
            }
            c
        }
        return copy(clips = newClips)
    }
}
