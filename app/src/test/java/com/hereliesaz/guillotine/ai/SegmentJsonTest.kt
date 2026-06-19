package com.hereliesaz.guillotine.ai

import com.hereliesaz.guillotine.model.EditAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the shared AI-output parser every provider routes through. */
class SegmentJsonTest {

    @Test fun parses_bare_array_and_converts_seconds_to_ms() {
        val out = SegmentJson.parse("""[{"start":1.0,"end":2.5,"action":"remove","reason":"ad"}]""")
        assertEquals(1, out.size)
        assertEquals(1000L, out[0].startMs)
        assertEquals(2500L, out[0].endMs)
        assertEquals(EditAction.REMOVE, out[0].action)
        assertEquals("ad", out[0].reason)
    }

    @Test fun parses_segments_object_form() {
        val out = SegmentJson.parse("""{"segments":[{"start":0,"end":1,"action":"keep"}]}""")
        assertEquals(1, out.size)
        assertEquals(EditAction.KEEP, out[0].action)
        assertEquals("", out[0].reason) // reason omitted → empty
    }

    @Test fun strips_code_fences() {
        val out = SegmentJson.parse("```json\n[{\"start\":0,\"end\":1,\"action\":\"keep\"}]\n```")
        assertEquals(1, out.size)
        assertEquals(0L, out[0].startMs)
        assertEquals(1000L, out[0].endMs)
    }

    @Test fun skips_zero_and_negative_length_segments() {
        val out = SegmentJson.parse(
            """[{"start":2,"end":2,"action":"keep"},{"start":3,"end":1,"action":"keep"},{"start":1,"end":2,"action":"keep"}]""",
        )
        assertEquals(1, out.size)
        assertEquals(1000L, out[0].startMs)
        assertEquals(2000L, out[0].endMs)
    }

    @Test fun action_is_case_insensitive_and_defaults_to_keep() {
        val out = SegmentJson.parse("""[{"start":0,"end":1,"action":"REMOVE"},{"start":1,"end":2}]""")
        assertEquals(2, out.size)
        assertEquals(EditAction.REMOVE, out[0].action)
        assertEquals(EditAction.KEEP, out[1].action) // missing action → keep
    }

    @Test fun object_without_segments_is_empty() {
        assertTrue(SegmentJson.parse("""{"foo":1}""").isEmpty())
    }
}
