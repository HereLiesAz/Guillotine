@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.hereliesaz.guillotine.export

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import com.hereliesaz.guillotine.model.KeyframeProperty
import com.hereliesaz.guillotine.model.TextFont
import com.hereliesaz.guillotine.model.TimelineClip
import com.hereliesaz.guillotine.model.TimelineMath

/**
 * Burns a text clip's caption into the export as a timed [TextOverlay]: the styled text
 * shows only during the clip's window and is placed/scaled/rotated by its crop transform.
 *
 * When the clip has keyframes, overlay settings are evaluated per-frame so animated scale,
 * position, rotation, and opacity export correctly. Un-keyframed clips still use a single
 * cached settings object (no per-frame allocation).
 *
 * One instance is attached per base export item; [timelineStartMs] is that item's start on the
 * original timeline, so `timelineMs = timelineStartMs + presentationTimeUs/1000` stays accurate
 * even after AI 'remove' ranges are physically cut.
 */
class CaptionOverlay(
    private val clip: TimelineClip,
    private val timelineStartMs: Long,
) : TextOverlay() {

    private val empty = SpannableString("")
    private val styled = SpannableString(clip.text).apply {
        if (isNotEmpty()) {
            // Transparent glyphs only — no baked-in background. Matches the preview (PreviewPlayer's
            // VideoSlot ClipType.TEXT branch), keeping export WYSIWYG. A background, if wanted, is a
            // separate shape-layer clip stacked underneath, not something this overlay bakes in itself.
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            setSpan(AbsoluteSizeSpan(64), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            setSpan(TypefaceSpan(typefaceName(clip.font)), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    private val hasKeyframes = clip.keyframes.isNotEmpty()

    // Pre-sorted keyframe lists for the hot path (one sort at construction, not per frame).
    private val scaleKfs = clip.keyframes.filter { it.property == KeyframeProperty.SCALE }.sortedBy { it.timeMs }
    private val rotKfs = clip.keyframes.filter { it.property == KeyframeProperty.ROTATION }.sortedBy { it.timeMs }
    private val oxKfs = clip.keyframes.filter { it.property == KeyframeProperty.OFFSET_X }.sortedBy { it.timeMs }
    private val oyKfs = clip.keyframes.filter { it.property == KeyframeProperty.OFFSET_Y }.sortedBy { it.timeMs }
    private val opKfs = clip.keyframes.filter { it.property == KeyframeProperty.OPACITY }.sortedBy { it.timeMs }

    // Cached for un-keyframed clips — zero per-frame allocation.
    private val staticSettings: StaticOverlaySettings? = if (!hasKeyframes) {
        StaticOverlaySettings.Builder()
            .setScale(clip.scale, clip.scale)
            .setRotationDegrees(clip.rotation)
            .setBackgroundFrameAnchor(anchorX(clip.offsetX), anchorY(clip.offsetY))
            .build()
    } else null

    override fun getText(presentationTimeUs: Long): SpannableString {
        val t = timelineStartMs + presentationTimeUs / 1000
        if (clip.text.isBlank() || t < clip.startTimeMs || t >= clip.endTimeMs) return empty
        if (!hasKeyframes) return styled

        val relMs = (t - clip.startTimeMs).coerceIn(0, clip.durationMs)
        val opacity = TimelineMath.interpolateSorted(opKfs, relMs, 1f)
        if (opacity <= 0.01f) return empty

        // Rebuild with alpha when opacity is keyframed
        if (opKfs.isNotEmpty()) {
            val alpha = (opacity * 255).toInt().coerceIn(0, 255)
            val s = SpannableString(clip.text)
            s.setSpan(ForegroundColorSpan(Color.argb(alpha, 255, 255, 255)), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            s.setSpan(AbsoluteSizeSpan(64), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            s.setSpan(TypefaceSpan(typefaceName(clip.font)), 0, s.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            return s
        }
        return styled
    }

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings {
        staticSettings?.let { return it }

        val t = timelineStartMs + presentationTimeUs / 1000
        val relMs = (t - clip.startTimeMs).coerceIn(0, clip.durationMs)

        val scale = TimelineMath.interpolateSorted(scaleKfs, relMs, clip.scale)
        val rot = TimelineMath.interpolateSorted(rotKfs, relMs, clip.rotation)
        val ox = TimelineMath.interpolateSorted(oxKfs, relMs, clip.offsetX)
        val oy = TimelineMath.interpolateSorted(oyKfs, relMs, clip.offsetY)

        return StaticOverlaySettings.Builder()
            .setScale(scale, scale)
            .setRotationDegrees(rot)
            .setBackgroundFrameAnchor(anchorX(ox), anchorY(oy))
            .build()
    }

    // The model's offsetX/offsetY are a fraction of the FULL frame from center (matches the preview,
    // which offsets by ox*frameWidth, and the video geometry, which translates by ox*2 in NDC). Media3's
    // background-frame anchor is [-1,1] over the frame, so a fraction f maps to f*2 (offsetX 0.5 = right
    // edge = anchor +1). The old code passed the raw offset, landing captions at HALF their position.
    private fun anchorX(offsetX: Float): Float = (offsetX * 2f).coerceIn(-1f, 1f)
    private fun anchorY(offsetY: Float): Float = (-offsetY * 2f).coerceIn(-1f, 1f)

    private fun typefaceName(f: TextFont): String = when (f) {
        TextFont.SANS -> "sans-serif"
        TextFont.SERIF -> "serif"
        TextFont.MONO -> "monospace"
        TextFont.CURSIVE -> "cursive"
    }
}
