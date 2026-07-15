package com.hereliesaz.guillotine.azphalt

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
}
