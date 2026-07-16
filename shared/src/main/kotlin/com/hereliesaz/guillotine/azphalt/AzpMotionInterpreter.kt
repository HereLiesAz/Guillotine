package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.model.CubicBezier
import com.hereliesaz.guillotine.model.Keyframe
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.newId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/**
 * Parses `az-motion` format JSON data into native motion preset objects, which can then be applied
 * to Guillotine's caption tracks (kinetic typography) or generic layers (camera, 3D effects).
 * Supports both static keyframes and dynamic AI bindings.
 */
object AzpMotionInterpreter {

    @Serializable
    data class MotionKeyframe(
        val time: Float,
        val value: JsonElement,
        val easing: String = "linear"
    )

    @Serializable
    data class MotionBinding(
        val bind: String,
        val mapIn: List<Float>? = null,
        val mapOut: List<JsonElement>? = null
    )

    sealed class MotionTrack {
        data class Keyframes(val keyframes: List<MotionKeyframe>) : MotionTrack()
        data class Binding(val binding: MotionBinding) : MotionTrack()
    }

    data class MotionPreset(
        val tracks: Map<String, MotionTrack>
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse the raw bytes of a bundled or downloaded `az-motion` JSON file into a [MotionPreset].
     */
    fun parse(bytes: ByteArray): MotionPreset {
        val stringContent = bytes.decodeToString()
        val root = json.parseToJsonElement(stringContent).jsonObject
        
        val tracksObject = root["tracks"]?.jsonObject ?: return MotionPreset(emptyMap())
        
        val parsedTracks = mutableMapOf<String, MotionTrack>()
        
        for ((property, element) in tracksObject) {
            if (element is JsonArray) {
                // Parse as list of keyframes
                val keyframes = json.decodeFromJsonElement<List<MotionKeyframe>>(element)
                parsedTracks[property] = MotionTrack.Keyframes(keyframes)
            } else if (element is JsonObject && element.containsKey("bind")) {
                // Parse as a binding
                val binding = json.decodeFromJsonElement<MotionBinding>(element)
                parsedTracks[property] = MotionTrack.Binding(binding)
            }
        }
        
        return MotionPreset(parsedTracks)
    }

    /** Convenience: parse [bytes] then [bake] onto [clip]. */
    fun bakeFromBytes(bytes: ByteArray, clip: TimelineClip): List<Keyframe> = bake(parse(bytes), clip)

    /**
     * Turn a parsed [preset] into `generated` [Keyframe]s on a caption [clip]. This is the step that was
     * missing — the reason kinetic typography renders at all: the preset's normalized tracks become
     * ordinary keyframes on the standard channels (opacity/scale/offsetX/offsetY/rotation), which the
     * live preview and the exporter already evaluate on both platforms, so no new render code is needed
     * and the baked curve stays hand-editable.
     *
     * Track times are normalized `0..1` over the clip's duration. Values are resolved against the clip's
     * rest transform: `opacity` is absolute (0..1), `scale` is a multiplier of the clip's scale, and
     * `offsetX`/`offsetY`/`rotation` are additive deltas onto the clip's transform. `bind` tracks
     * (AI-driven) are skipped here — they have no static curve to bake.
     */
    fun bake(preset: MotionPreset, clip: TimelineClip): List<Keyframe> {
        val dur = clip.durationMs.coerceAtLeast(1L)
        // Collapse to one keyframe per (property, time); a preset with a duplicate instant keeps the last.
        val byKey = LinkedHashMap<Pair<KeyframeProperty, Long>, Keyframe>()
        for ((channel, track) in preset.tracks) {
            val property = propertyFor(channel) ?: continue
            val keys = (track as? MotionTrack.Keyframes)?.keyframes ?: continue // skip bindings
            for (k in keys) {
                val v = (k.value as? JsonPrimitive)?.floatOrNull ?: continue
                val timeMs = (k.time.coerceIn(0f, 1f) * dur).toLong().coerceIn(0L, dur)
                val value = resolve(property, v, clip)
                byKey[property to timeMs] = Keyframe(newId(), timeMs, value, property, easingFor(k.easing), generated = true)
            }
        }
        return byKey.values.sortedBy { it.timeMs }
    }

    private fun propertyFor(channel: String): KeyframeProperty? = when (channel.trim().lowercase()) {
        "opacity", "alpha" -> KeyframeProperty.OPACITY
        "scale" -> KeyframeProperty.SCALE
        "offsetx", "x", "translatex" -> KeyframeProperty.OFFSET_X
        "offsety", "y", "translatey" -> KeyframeProperty.OFFSET_Y
        "rotation", "rotate", "angle" -> KeyframeProperty.ROTATION
        else -> null
    }

    private fun resolve(property: KeyframeProperty, v: Float, clip: TimelineClip): Float = when (property) {
        KeyframeProperty.OPACITY -> v.coerceIn(0f, 1f)                 // absolute
        KeyframeProperty.SCALE -> (clip.scale * v).coerceAtLeast(0f)   // multiplier of rest scale
        KeyframeProperty.OFFSET_X -> clip.offsetX + v                  // additive delta
        KeyframeProperty.OFFSET_Y -> clip.offsetY + v
        KeyframeProperty.ROTATION -> clip.rotation + v
        else -> v
    }

    private fun easingFor(name: String): CubicBezier = when (name.trim().lowercase()) {
        "linear" -> CubicBezier(0f, 0f, 1f, 1f)
        "easeout", "ease-out" -> CubicBezier(0.16f, 1f, 0.3f, 1f)
        "easein", "ease-in" -> CubicBezier(0.5f, 0f, 0.75f, 0f)
        else -> CubicBezier() // ease / easeInOut — the smooth default
    }
}
