package com.hereliesaz.guillotine.motion

import com.hereliesaz.guillotine.model.CubicBezier
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A **kinetic-typography preset** — the declarative, data-only description of how a caption moves.
 *
 * This is the host model of the azphalt `motion` asset ("keyframe/animation preset",
 * `spec/extension-manifest.md § video-catalog assets`). A preset is authored as JSON (`az-motion`
 * format) and shipped either as a first-party default ([MotionCatalog.BUILT_IN]) or inside a `.azp`
 * package a user installs — the *plugin* surface for kinetic typography. It carries **no code**; the
 * host's one [MotionInterpreter] turns it into ordinary [com.hereliesaz.guillotine.model.Keyframe]s on
 * a caption, so preview and export (app + desktop) animate it with no new render code, and the baked
 * keyframes stay fully hand-editable.
 *
 * Values in a [MotionKey] are **relative to the caption's rest transform**, so one preset works on any
 * caption regardless of where it sits or how big it is:
 * - `opacity` — absolute `0..1` (rest = 1); not scaled by intensity.
 * - `scale` — a multiplier where `1` = rest; the deviation from 1 is scaled by intensity.
 * - `offsetX` / `offsetY` — an additive delta in frame fractions; scaled by intensity.
 * - `rotation` — additive degrees; scaled by intensity.
 */
@Serializable
data class MotionPreset(
    /** Wire-format tag; only `"az-motion"` is understood. */
    val format: String = FORMAT,
    /** Format revision. */
    val version: String = "1",
    /** Reverse-DNS preset id (the installing `.azp`'s package id for plugins; set for built-ins too). */
    val id: String = "",
    /** Human label for the picker. */
    val name: String = "",
    /** One-line description for the picker. */
    val blurb: String = "",
    /** Loose grouping for the picker: `entrance` | `emphasis` | `exit` (open vocabulary). */
    val category: String = "entrance",
    /** How much of each caption's life the entrance/exit occupy (fractions of the clip's duration). */
    val window: MotionWindow = MotionWindow(),
    /** The animation channels this preset drives. */
    val tracks: List<MotionTrack> = emptyList(),
) {
    companion object {
        const val FORMAT = "az-motion"

        /** Lenient parser: tolerant of unknown keys so a newer preset still loads on an older host. */
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** Parse one `az-motion` preset from JSON text, or null if it isn't a valid/understood preset. */
        fun parse(text: String): MotionPreset? = try {
            json.decodeFromString(serializer(), text).takeIf { it.format == FORMAT && it.tracks.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }
}

/** Entrance/exit durations as fractions (`0..1`) of the caption's on-timeline duration. */
@Serializable
data class MotionWindow(
    /** Entrance length as a fraction of the clip (drives `in`-mode tracks). */
    @SerialName("in") val enter: Float = 0.28f,
    /** Exit length as a fraction of the clip (drives `out`-mode tracks); 0 = no exit. */
    @SerialName("out") val exit: Float = 0f,
)

/** Which caption channel a track animates. */
@Serializable
enum class MotionChannel {
    @SerialName("opacity") OPACITY,
    @SerialName("scale") SCALE,
    @SerialName("offsetX") OFFSET_X,
    @SerialName("offsetY") OFFSET_Y,
    @SerialName("rotation") ROTATION,
}

/** When within a caption's life a track's keys play. */
@Serializable
enum class MotionMode {
    /** Maps the track's `t: 0..1` keys onto the entrance window `[0, in·dur]`. */
    @SerialName("in") IN,
    /** Maps them onto the exit window `[dur − out·dur, dur]`. */
    @SerialName("out") OUT,
    /** Repeats the keys as an oscillation across the whole clip (see [MotionTrack.cycles]). */
    @SerialName("sustain") SUSTAIN,
}

/** One animated channel: a small curve of [keys] played according to [mode]. */
@Serializable
data class MotionTrack(
    val channel: MotionChannel,
    val mode: MotionMode = MotionMode.IN,
    /**
     * For `sustain` only: how many times the key curve repeats across the clip. `0` (default) lets the
     * interpreter choose a count from the clip's duration so a long caption breathes more than a short one.
     */
    val cycles: Int = 0,
    /** The channel's key curve, `t` in `0..1` within the mode's window; must be non-empty. */
    val keys: List<MotionKey> = emptyList(),
)

/** One keyframe of a [MotionTrack]: value [v] at normalized time [t], with optional cubic-bezier [ease]. */
@Serializable
data class MotionKey(
    /** Normalized time `0..1` within the track's window. */
    val t: Float,
    /** The channel value at [t] (see [MotionPreset] for per-channel meaning). */
    val v: Float,
    /** Cubic-bezier control points `[x1,y1,x2,y2]` for the segment leaving this key; null = mode default. */
    val ease: List<Float>? = null,
) {
    /** [ease] as a [CubicBezier], or null when unset/malformed (caller supplies a default). */
    fun bezier(): CubicBezier? = ease?.takeIf { it.size == 4 }?.let { CubicBezier(it[0], it[1], it[2], it[3]) }
}
