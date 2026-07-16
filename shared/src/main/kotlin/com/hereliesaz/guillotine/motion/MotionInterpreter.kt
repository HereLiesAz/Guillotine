package com.hereliesaz.guillotine.motion

import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.model.Keyframe
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.newId

/**
 * Turns a declarative [MotionPreset] into ordinary [Keyframe]s on a caption clip.
 *
 * This is the whole reason kinetic typography can be a data-only *plugin*: the host owns exactly one
 * interpreter, and every preset — first-party or installed from a `.azp` — becomes keyframes on the
 * standard channels (opacity/scale/offset/rotation). Those are the same keyframes the live preview and
 * the exporter already evaluate via `TimelineMath.valueAt` on both app and desktop, so a new preset
 * renders everywhere with no new render code, and the user can hand-tune the baked curve afterward.
 *
 * Every emitted keyframe is flagged [Keyframe.generated] so the editor can strip/replace exactly one
 * preset's curve when the user switches or clears it, without disturbing hand-authored keyframes.
 */
object MotionInterpreter {

    // Sensible per-mode default easings when a key doesn't declare its own.
    private val EASE_OUT = CubicBezier(0.16f, 1f, 0.3f, 1f)   // snappy settle for entrances
    private val EASE_IN = CubicBezier(0.5f, 0f, 0.75f, 0f)    // accelerate away for exits
    private val SMOOTH = CubicBezier(0.25f, 0.1f, 0.25f, 1f)  // gentle in/out for sustained motion

    private const val MAX_SUSTAIN_KEYS = 64  // backstop so a pathological preset can't flood a clip

    /**
     * Bake [preset] onto [clip] at [intensity] (0..2, 1 = as authored), returning the generated
     * keyframes to merge into the clip. Values are resolved against the clip's rest transform, so the
     * caption animates around wherever it currently sits.
     */
    fun bake(preset: MotionPreset, clip: TimelineClip, intensity: Float = 1f): List<Keyframe> {
        val dur = clip.durationMs.coerceAtLeast(1L)
        val k = intensity.coerceIn(0f, 2f)
        // Clamp the windows so entrance + exit can't exceed the clip.
        val enterMs = (dur * preset.window.enter.coerceIn(0f, 1f)).toLong().coerceIn(0L, dur)
        val exitMs = (dur * preset.window.exit.coerceIn(0f, 1f)).toLong().coerceIn(0L, dur)

        val out = ArrayList<Keyframe>()
        for (track in preset.tracks) {
            if (track.keys.isEmpty()) continue
            val rest = restValue(track.channel, clip)
            val property = propertyFor(track.channel)
            when (track.mode) {
                MotionMode.IN -> emitWindow(out, track, property, rest, k, 0L, enterMs, EASE_OUT)
                MotionMode.OUT -> emitWindow(out, track, property, rest, k, dur - exitMs, dur, EASE_IN)
                MotionMode.SUSTAIN -> emitSustain(out, track, property, rest, k, dur)
            }
        }
        return out
    }

    /** Map one track's `t: 0..1` keys onto the time span `[startMs, endMs]`. */
    private fun emitWindow(
        out: MutableList<Keyframe>,
        track: MotionTrack,
        property: KeyframeProperty,
        rest: Float,
        k: Float,
        startMs: Long,
        endMs: Long,
        defaultEase: CubicBezier,
    ) {
        val span = (endMs - startMs).coerceAtLeast(0L)
        for (key in track.keys) {
            val t = key.t.coerceIn(0f, 1f)
            val timeMs = startMs + (t * span).toLong()
            out += kf(timeMs, resolve(track.channel, key.v, rest, k), property, key.bezier() ?: defaultEase)
        }
    }

    /** Repeat a track's key curve as an oscillation across the whole clip `[0, dur]`. */
    private fun emitSustain(
        out: MutableList<Keyframe>,
        track: MotionTrack,
        property: KeyframeProperty,
        rest: Float,
        k: Float,
        dur: Long,
    ) {
        // Auto-pick a repeat count (~one cycle per 800ms) so long captions breathe more than short ones.
        val repeats = if (track.cycles > 0) track.cycles else (dur / 800L).toInt().coerceIn(1, 12)
        var emitted = 0
        for (r in 0 until repeats) {
            for ((ki, key) in track.keys.withIndex()) {
                // Skip a repeat's closing key when it coincides with the next repeat's opening key, so we
                // don't stack two keyframes on the same time — except on the very last repeat.
                val isClosingDuplicate = ki == track.keys.lastIndex && r < repeats - 1 &&
                    key.t.coerceIn(0f, 1f) == 1f && track.keys.first().t.coerceIn(0f, 1f) == 0f
                if (isClosingDuplicate) continue
                val phase = (r + key.t.coerceIn(0f, 1f)) / repeats
                val timeMs = (phase * dur).toLong().coerceIn(0L, dur)
                out += kf(timeMs, resolve(track.channel, key.v, rest, k), property, key.bezier() ?: SMOOTH)
                if (++emitted >= MAX_SUSTAIN_KEYS) return
            }
        }
    }

    /** Resolve a preset value to an absolute keyframe value for [channel], applying [intensity]. */
    private fun resolve(channel: MotionChannel, v: Float, rest: Float, intensity: Float): Float = when (channel) {
        // Opacity is absolute and intensity-independent (a fade is a fade).
        MotionChannel.OPACITY -> v.coerceIn(0f, 1f)
        // Scale is a multiplier of rest; intensity scales the deviation from rest (1.0).
        MotionChannel.SCALE -> (rest * (1f + (v - 1f) * intensity)).coerceAtLeast(0f)
        // Offsets/rotation are additive deltas onto the rest transform; intensity scales the travel.
        MotionChannel.OFFSET_X, MotionChannel.OFFSET_Y, MotionChannel.ROTATION -> rest + v * intensity
    }

    private fun restValue(channel: MotionChannel, clip: TimelineClip): Float = when (channel) {
        MotionChannel.OPACITY -> 1f
        MotionChannel.SCALE -> clip.scale
        MotionChannel.OFFSET_X -> clip.offsetX
        MotionChannel.OFFSET_Y -> clip.offsetY
        MotionChannel.ROTATION -> clip.rotation
    }

    private fun propertyFor(channel: MotionChannel): KeyframeProperty = when (channel) {
        MotionChannel.OPACITY -> KeyframeProperty.OPACITY
        MotionChannel.SCALE -> KeyframeProperty.SCALE
        MotionChannel.OFFSET_X -> KeyframeProperty.OFFSET_X
        MotionChannel.OFFSET_Y -> KeyframeProperty.OFFSET_Y
        MotionChannel.ROTATION -> KeyframeProperty.ROTATION
    }

    private fun kf(timeMs: Long, value: Float, property: KeyframeProperty, easing: CubicBezier): Keyframe =
        Keyframe(id = newId(), timeMs = timeMs, value = value, property = property, easing = easing, generated = true)
}
