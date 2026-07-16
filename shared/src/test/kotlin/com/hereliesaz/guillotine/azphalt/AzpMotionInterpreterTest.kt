package com.hereliesaz.guillotine.azphalt

import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TimelineClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AzpMotionInterpreterTest {

    private fun caption(durationMs: Long = 1000L, scale: Float = 1f, offsetY: Float = 0f, rotation: Float = 0f) =
        TimelineClip(
            id = "c1", mediaId = "", type = ClipType.TEXT, trackId = "V1",
            startTimeMs = 0, trimStartMs = 0, durationMs = durationMs,
            text = "hi", scale = scale, offsetY = offsetY, rotation = rotation,
        )

    @Test fun bakesNormalizedTracksIntoKeyframes() {
        val json = """
            { "tracks": {
                "opacity": [ {"time":0.0,"value":0.0,"easing":"linear"}, {"time":1.0,"value":1.0} ],
                "scale":   [ {"time":0.0,"value":0.5}, {"time":1.0,"value":1.0} ]
            } }
        """.trimIndent().toByteArray()

        // scale=2 so the multiplier semantics are observable.
        val kfs = AzpMotionInterpreter.bakeFromBytes(json, caption(durationMs = 1000L, scale = 2f))

        // Every baked keyframe is flagged generated and sorted by time.
        assertTrue(kfs.all { it.generated })
        assertEquals(kfs.map { it.timeMs }, kfs.map { it.timeMs }.sorted())

        val opacity = kfs.filter { it.property == KeyframeProperty.OPACITY }.sortedBy { it.timeMs }
        assertEquals(2, opacity.size)
        assertEquals(0L, opacity[0].timeMs); assertEquals(0f, opacity[0].value)
        assertEquals(1000L, opacity[1].timeMs); assertEquals(1f, opacity[1].value)

        val scale = kfs.filter { it.property == KeyframeProperty.SCALE }.sortedBy { it.timeMs }
        assertEquals(2, scale.size)
        assertEquals(1.0f, scale[0].value)   // rest 2 * 0.5
        assertEquals(2.0f, scale[1].value)   // rest 2 * 1.0
    }

    @Test fun offsetAndRotationAreAdditiveDeltas() {
        val json = """
            { "tracks": {
                "offsetY":  [ {"time":0.0,"value":0.3}, {"time":1.0,"value":0.0} ],
                "rotation": [ {"time":0.0,"value":-90.0}, {"time":1.0,"value":0.0} ]
            } }
        """.trimIndent().toByteArray()

        val kfs = AzpMotionInterpreter.bakeFromBytes(json, caption(offsetY = 0.3f, rotation = 10f))
        val oy = kfs.filter { it.property == KeyframeProperty.OFFSET_Y }.sortedBy { it.timeMs }
        assertEquals(0.6f, oy[0].value)   // rest 0.3 + delta 0.3
        assertEquals(0.3f, oy[1].value)   // rest 0.3 + delta 0.0
        val rot = kfs.filter { it.property == KeyframeProperty.ROTATION }.sortedBy { it.timeMs }
        assertEquals(-80f, rot[0].value)  // rest 10 + delta -90
        assertEquals(10f, rot[1].value)
    }

    @Test fun emptyOrBindingOnlyPresetBakesNothing() {
        val json = """{ "tracks": { "opacity": { "bind": "audio.rms" } } }""".toByteArray()
        assertTrue(AzpMotionInterpreter.bakeFromBytes(json, caption()).isEmpty())
        assertTrue(AzpMotionInterpreter.bakeFromBytes("{}".toByteArray(), caption()).isEmpty())
    }
}
