package com.hereliesaz.guillotine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Quality] drives the export's output resolution. It spent its whole existence as a control that
 * changed nothing — `Transformer` was built with a video MIME type and no resolution configuration —
 * so these pin the mapping the exporter now reads.
 */
class QualityTest {

    @Test fun originalKeepsTheSourceSize() {
        assertNull("ORIGINAL must not force a height", Quality.ORIGINAL.targetHeight)
    }

    @Test fun namedQualitiesMapToTheirHeights() {
        assertEquals(2160, Quality.UHD_4K.targetHeight)
        assertEquals(1080, Quality.FHD_1080P.targetHeight)
        assertEquals(720, Quality.HD_720P.targetHeight)
    }

    @Test fun everyQualityIsAccountedFor() {
        // A new enum constant with no height would silently export at source resolution, which is the
        // exact bug this replaced. Fail here instead.
        val unmapped = Quality.entries.filter { it != Quality.ORIGINAL && it.targetHeight == null }
        assertEquals("qualities with no target height: $unmapped", emptyList<Quality>(), unmapped)
    }

    @Test fun heightsDescendWithQuality() {
        val ordered = listOf(Quality.UHD_4K, Quality.FHD_1080P, Quality.HD_720P).map { it.targetHeight!! }
        assertEquals(ordered.sortedDescending(), ordered)
    }

    @Test fun defaultsAreUnchanged() {
        // The default project must still be "whatever the source is, at 30 fps" — wiring these settings
        // up must not quietly start downscaling every existing project.
        val settings = GlobalSettings()
        assertEquals(Quality.ORIGINAL, settings.quality)
        assertNull(settings.quality.targetHeight)
        assertEquals(30, settings.fps)
    }
}
