package com.hereliesaz.guillotine.model

/**
 * The transform the preview must apply so the frame on screen is the frame that will be rendered.
 *
 * The project-level [Crop] was applied in export (`VideoEffects.geometry`) and nowhere in preview, so
 * the editor showed the whole source while the file came out cropped. That is not a preview — it is a
 * different picture that happens to share a timeline.
 *
 * Pure maths, in `:shared`, so it can be unit-tested without Compose or a device.
 */
object PreviewGeometry {

    /**
     * A scale-about-centre plus a translation, both as fractions of the frame. A caller multiplies
     * [translationXFraction] by the frame width and [translationYFraction] by its height.
     */
    data class CropLayer(
        val scaleX: Float,
        val scaleY: Float,
        val translationXFraction: Float,
        val translationYFraction: Float,
    )

    /**
     * The layer for [crop], or **null** when it selects the whole frame and there is nothing to do.
     *
     * [Crop] is in percent (`x`/`y` top-left, `w`/`h` size). Scaling by `100/w` makes the cropped region
     * fill the frame; the translation then brings the crop's centre to the frame's centre, because
     * scaling alone is about the frame centre and would leave an off-centre crop off-centre.
     *
     * Degenerate or nonsensical values (zero/negative size, anything outside 0–100) return null rather
     * than a transform that would blank the preview — a bad crop should show an uncropped frame, not an
     * empty one.
     */
    fun forCrop(crop: Crop): CropLayer? {
        if (crop.w <= 0f || crop.h <= 0f) return null
        if (crop.w > 100f || crop.h > 100f) return null
        if (crop.x < 0f || crop.y < 0f) return null
        if (crop.x + crop.w > 100f || crop.y + crop.h > 100f) return null
        if (crop.x == 0f && crop.y == 0f && crop.w == 100f && crop.h == 100f) return null

        val scaleX = 100f / crop.w
        val scaleY = 100f / crop.h
        // Centre of the crop as a fraction of the frame.
        val cx = (crop.x + crop.w / 2f) / 100f
        val cy = (crop.y + crop.h / 2f) / 100f
        // A point at fraction p lands at 0.5 + (p - 0.5) * scale after scaling about the centre; solving
        // for "crop centre ends up at 0.5" gives this offset.
        return CropLayer(
            scaleX = scaleX,
            scaleY = scaleY,
            translationXFraction = (0.5f - cx) * scaleX,
            translationYFraction = (0.5f - cy) * scaleY,
        )
    }
}
