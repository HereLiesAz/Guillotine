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

@Serializable
enum class KeyframeProperty { OPACITY, SCALE, VOLUME }

/** Typeface for [ClipType.TEXT] clips. Mapped to a Compose FontFamily in the UI layer. */
@Serializable
enum class TextFont { SANS, SERIF, MONO, CURSIVE }

@Serializable
enum class EditAction { KEEP, REMOVE }

@Serializable
enum class AspectRatio { RATIO_16_9, RATIO_9_16, RATIO_1_1, ORIGINAL }

@Serializable
enum class Quality { ORIGINAL, UHD_4K, FHD_1080P, HD_720P }

/** Imported source media. [durationMs] is probed on import (5s default for images). */
@Serializable
data class MediaItem(
    val id: String,
    val uri: String,
    val name: String,
    val kind: MediaKind,
    val durationMs: Long,
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
    /** On-device subject segmentation: keep the foreground, drop the background so a lower layer shows through. */
    val removeBackground: Boolean = false,
)

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
    /** Caption/title text for [ClipType.TEXT] clips (empty for video/audio). */
    val text: String = "",
    /** Typeface for text clips. */
    val font: TextFont = TextFont.SANS,
    /** Crop-tool transform: scale, normalized offset (fraction of frame) from center, rotation°. */
    val scale: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val rotation: Float = 0f,
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
)

@Serializable
data class GlobalSettings(
    val aspectRatio: AspectRatio = AspectRatio.ORIGINAL,
    val quality: Quality = Quality.ORIGINAL,
    val crop: Crop = Crop(),
)

/**
 * The full undoable editor document. Transient UI state (playhead, zoom,
 * selection) is intentionally NOT part of this — only content is undoable.
 */
@Serializable
data class Document(
    val mediaItems: List<MediaItem> = emptyList(),
    val clips: List<TimelineClip> = emptyList(),
    /** Track lists in stacking order (top of panel = top layer). Text is just a clip on a video track. */
    val videoTracks: List<String> = listOf("V1"),
    val audioTracks: List<String> = listOf("A1"),
    val trackSettings: Map<String, TrackSettings> = emptyMap(),
    val settings: GlobalSettings = GlobalSettings(),
    /** Recent AI prompts used in this project (most-recent first); inline hint + history dropdown. */
    val promptHistory: List<String> = emptyList(),
) {
    /** End of the last clip on the timeline, in milliseconds. */
    val totalDurationMs: Long
        get() = clips.maxOfOrNull { it.endTimeMs } ?: 0L

    fun mediaFor(clip: TimelineClip): MediaItem? = mediaItems.firstOrNull { it.id == clip.mediaId }

    fun trackSettingsFor(trackId: String): TrackSettings = trackSettings[trackId] ?: TrackSettings()

    /** Track ids whose whole track is disabled/hidden. */
    val disabledTrackIds: Set<String> get() = trackSettings.filterValues { it.disabled }.keys
}
