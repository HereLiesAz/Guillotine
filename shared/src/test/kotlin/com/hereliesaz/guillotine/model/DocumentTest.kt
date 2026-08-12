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

    // ---- clampedToRegion: "Render Loop Region Only" pre-processing ----

    @Test fun clampedToRegion_drops_clips_entirely_outside_the_window() {
        val doc = Document(clips = listOf(clip("a", 0, 1000), clip("b", 5000, 1000)))
        val r = doc.clampedToRegion(2000, 3000)
        assertEquals(emptyList<TimelineClip>(), r.clips)
    }

    @Test fun clampedToRegion_keeps_a_fully_contained_clip_and_shifts_it_to_zero() {
        val doc = Document(clips = listOf(clip("a", 3000, 1000)))
        val r = doc.clampedToRegion(2000, 5000)
        val c = r.clips.single()
        assertEquals(1000L, c.startTimeMs) // 3000 - 2000
        assertEquals(1000L, c.durationMs)  // untouched
        assertEquals(0L, c.trimStartMs)    // untouched
    }

    @Test fun clampedToRegion_trims_the_left_edge_of_a_clip_straddling_the_start() {
        // clip [0,2000), region [500,3000) -> keep [500,2000), shifted to [0,1500)
        val doc = Document(clips = listOf(clip("a", 0, 2000)))
        val r = doc.clampedToRegion(500, 3000)
        val c = r.clips.single()
        assertEquals(0L, c.startTimeMs)
        assertEquals(500L, c.trimStartMs) // advanced by the 500ms trimmed off the left
        assertEquals(1500L, c.durationMs) // 2000 - 500
    }

    @Test fun clampedToRegion_trims_the_right_edge_of_a_clip_straddling_the_end() {
        // clip [1000,4000), region [0,2000) -> keep [1000,2000), shifted to [1000,2000) (start unaffected)
        val doc = Document(clips = listOf(clip("a", 1000, 3000)))
        val r = doc.clampedToRegion(0, 2000)
        val c = r.clips.single()
        assertEquals(1000L, c.startTimeMs)
        assertEquals(0L, c.trimStartMs)
        assertEquals(1000L, c.durationMs) // 2000 - 1000
    }

    @Test fun clampedToRegion_shifts_keyframes_when_trimming_the_left_edge_and_drops_stale_ones() {
        val kf = listOf(
            Keyframe("k1", 100, 0f, KeyframeProperty.OPACITY),  // before the cut -> dropped
            Keyframe("k2", 600, 1f, KeyframeProperty.OPACITY),  // survives, shifts to 100
        )
        val c = TimelineClip(id = "a", mediaId = "m", type = ClipType.VIDEO, trackId = "V1", startTimeMs = 0, trimStartMs = 0, durationMs = 2000, keyframes = kf)
        val doc = Document(clips = listOf(c))
        val r = doc.clampedToRegion(500, 3000)
        val out = r.clips.single()
        assertEquals(listOf("k2"), out.keyframes.map { it.id })
        assertEquals(100L, out.keyframes.single().timeMs)
    }

    @Test fun clampedToRegion_drops_keyframes_past_a_right_edge_trim() {
        val kf = listOf(
            Keyframe("k1", 500, 0f, KeyframeProperty.OPACITY),  // survives (within the kept window)
            Keyframe("k2", 1500, 1f, KeyframeProperty.OPACITY), // past the new duration -> dropped
        )
        val c = TimelineClip(id = "a", mediaId = "m", type = ClipType.VIDEO, trackId = "V1", startTimeMs = 0, trimStartMs = 0, durationMs = 2000, keyframes = kf)
        val doc = Document(clips = listOf(c))
        val r = doc.clampedToRegion(0, 1000)
        val out = r.clips.single()
        assertEquals(listOf("k1"), out.keyframes.map { it.id })
    }
}
