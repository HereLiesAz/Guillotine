package com.hereliesaz.guillotine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The project crop was applied in export and nowhere in preview, so the editor showed the whole source
 * while the file came out cropped. These pin the transform that closes that gap — a preview that doesn't
 * show what will be rendered isn't a preview.
 */
class PreviewGeometryTest {

    private val eps = 0.0001f

    @Test fun aFullFrameCropIsNoTransformAtAll() {
        assertNull(PreviewGeometry.forCrop(Crop()))
        assertNull(PreviewGeometry.forCrop(Crop(0f, 0f, 100f, 100f)))
    }

    @Test fun aCentredHalfCropScalesTwoWithNoTranslation() {
        val l = PreviewGeometry.forCrop(Crop(x = 25f, y = 25f, w = 50f, h = 50f))!!
        assertEquals(2f, l.scaleX, eps)
        assertEquals(2f, l.scaleY, eps)
        // Already centred, so nothing to move.
        assertEquals(0f, l.translationXFraction, eps)
        assertEquals(0f, l.translationYFraction, eps)
    }

    @Test fun anOffCentreCropIsBroughtToTheCentre() {
        // Top-left quadrant: centre is at (0.25, 0.25), needs shifting right/down by a quarter frame,
        // scaled by 2 because the transform happens after the scale.
        val l = PreviewGeometry.forCrop(Crop(x = 0f, y = 0f, w = 50f, h = 50f))!!
        assertEquals(2f, l.scaleX, eps)
        assertEquals(0.5f, l.translationXFraction, eps)
        assertEquals(0.5f, l.translationYFraction, eps)
    }

    @Test fun theCropCentreLandsAtTheFrameCentre() {
        // The property that actually matters, checked by replaying the transform: a point at the crop's
        // centre must map to 0.5 for any crop.
        for (c in listOf(
            Crop(0f, 0f, 50f, 50f),
            Crop(50f, 50f, 50f, 50f),
            Crop(10f, 70f, 80f, 30f),
            Crop(33f, 0f, 33f, 100f),
        )) {
            val l = PreviewGeometry.forCrop(c)!!
            val cx = (c.x + c.w / 2f) / 100f
            val cy = (c.y + c.h / 2f) / 100f
            val mappedX = 0.5f + (cx - 0.5f) * l.scaleX + l.translationXFraction
            val mappedY = 0.5f + (cy - 0.5f) * l.scaleY + l.translationYFraction
            assertEquals("crop $c centre X", 0.5f, mappedX, eps)
            assertEquals("crop $c centre Y", 0.5f, mappedY, eps)
        }
    }

    @Test fun theCropEdgesLandOnTheFrameEdges() {
        // The other half of "fills the frame": the crop's own edges must map to 0 and 1.
        val c = Crop(x = 20f, y = 10f, w = 40f, h = 60f)
        val l = PreviewGeometry.forCrop(c)!!
        fun mapX(p: Float) = 0.5f + (p - 0.5f) * l.scaleX + l.translationXFraction
        fun mapY(p: Float) = 0.5f + (p - 0.5f) * l.scaleY + l.translationYFraction
        assertEquals(0f, mapX(c.x / 100f), eps)
        assertEquals(1f, mapX((c.x + c.w) / 100f), eps)
        assertEquals(0f, mapY(c.y / 100f), eps)
        assertEquals(1f, mapY((c.y + c.h) / 100f), eps)
    }

    @Test fun nonsensicalCropsShowAnUncroppedFrameRatherThanAnEmptyOne() {
        // A bad crop must not blank the preview — null means "draw it normally".
        for (bad in listOf(
            Crop(w = 0f), Crop(h = 0f), Crop(w = -10f),
            Crop(x = -5f), Crop(y = -5f),
            Crop(x = 80f, w = 50f), Crop(y = 80f, h = 50f),
            Crop(w = 150f), Crop(h = 150f),
        )) {
            assertNull("expected null for $bad", PreviewGeometry.forCrop(bad))
        }
    }
}
