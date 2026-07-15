package com.hereliesaz.guillotine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure-JVM tests for the Document/TimelineClip computed logic used across preview + export. */
class DocumentTest {

    private fun clip(id: String, start: Long, dur: Long, mediaId: String = "m", track: String = "V1") =
        TimelineClip(
            id = id, mediaId = mediaId, type = ClipType.VIDEO, trackId = track,
            startTimeMs = start, trimStartMs = 0, durationMs = dur,
        )

    @Test fun endTimeMs_is_start_plus_duration() {
        assertEquals(3500L, clip("a", 1500, 2000).endTimeMs)
    }

    @Test fun totalDuration_is_zero_when_empty() {
        assertEquals(0L, Document().totalDurationMs)
    }

    @Test fun totalDuration_is_latest_clip_end() {
        val doc = Document(clips = listOf(clip("a", 0, 1000), clip("b", 5000, 2000), clip("c", 1000, 500)))
        assertEquals(7000L, doc.totalDurationMs) // b ends latest, at 7000
    }

    @Test fun mediaFor_matches_by_id_or_null() {
        val m = MediaItem("m1", "uri", "name", MediaKind.VIDEO, 1000)
        val doc = Document(mediaItems = listOf(m), clips = listOf(clip("a", 0, 1000, mediaId = "m1")))
        assertEquals(m, doc.mediaFor(doc.clips[0]))
        assertNull(doc.mediaFor(clip("x", 0, 1, mediaId = "missing")))
    }

    @Test fun trackSettingsFor_defaults_when_absent_and_returns_stored() {
        assertEquals(TrackSettings(), Document().trackSettingsFor("V1"))
        val ts = TrackSettings(volume = 0.5f, muted = true)
        assertEquals(ts, Document(trackSettings = mapOf("A1" to ts)).trackSettingsFor("A1"))
    }

    @Test fun disabledTrackIds_lists_only_disabled() {
        val doc = Document(
            trackSettings = mapOf(
                "V1" to TrackSettings(disabled = true),
                "V2" to TrackSettings(disabled = false),
                "A1" to TrackSettings(disabled = true),
            ),
        )
        assertEquals(setOf("V1", "A1"), doc.disabledTrackIds)
    }
}
