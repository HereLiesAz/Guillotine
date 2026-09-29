package com.hereliesaz.guillotine.mcp

import com.hereliesaz.guillotine.model.ClipType
import com.hereliesaz.guillotine.model.Document
import com.hereliesaz.guillotine.model.TimelineClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextJobToolsTest {

    private fun clip(id: String, type: ClipType, start: Long, text: String = "", group: String? = "g") =
        TimelineClip(id = id, mediaId = "", type = type, trackId = "V1", startTimeMs = start, trimStartMs = 0,
            durationMs = 1000, text = text, groupId = group)

    private val doc = Document(clips = listOf(
        clip("v", ClipType.VIDEO, 0),
        clip("c2", ClipType.TEXT, 2000, "second"),
        clip("c1", ClipType.TEXT, 1000, "first"),
        clip("x", ClipType.TEXT, 500, "other", group = "h"),
    ))

    @Test fun captionsAreTheGroupsTextClipsInTimeOrder() {
        assertEquals(listOf("c1", "c2"), TextJobTools.captionsFor(doc, "v").map { it.id })
        assertEquals(listOf("c1", "c2"), TextJobTools.captionsFor(doc, "c2").map { it.id })
    }

    @Test fun rewriteRoundTripsByNumber() {
        assertEquals(listOf("uno", "dos"), TextJobTools.parseRewrite("Here you go:\n2. dos\n1) uno\n", 2))
    }

    @Test fun rewriteMissingALineIsRejected() {
        assertNull(TextJobTools.parseRewrite("1. uno", 2))
    }
}
