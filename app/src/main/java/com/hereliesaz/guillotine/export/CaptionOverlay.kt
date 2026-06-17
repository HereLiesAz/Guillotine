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
import com.hereliesaz.guillotine.model.TextFont
import com.hereliesaz.guillotine.model.TimelineClip

/**
 * Burns a text clip's caption into the export as a timed [TextOverlay]: the styled text
 * shows only during the clip's window and is placed/scaled/rotated by its crop transform.
 *
 * Timing is relative to the base video item, so it's accurate for the common single-base
 * composite; long edits with many cut ranges may drift (see Exporter notes).
 */
class CaptionOverlay(
    private val clip: TimelineClip,
    private val baseStartMs: Long,
) : TextOverlay() {

    private val empty = SpannableString("")
    private val styled = SpannableString(clip.text).apply {
        if (isNotEmpty()) {
            setSpan(ForegroundColorSpan(Color.WHITE), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            setSpan(AbsoluteSizeSpan(64), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            setSpan(TypefaceSpan(typefaceName(clip.font)), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    override fun getText(presentationTimeUs: Long): SpannableString {
        val t = baseStartMs + presentationTimeUs / 1000
        return if (clip.text.isNotBlank() && t >= clip.startTimeMs && t < clip.endTimeMs) styled else empty
    }

    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings =
        StaticOverlaySettings.Builder()
            .setScale(clip.scale, clip.scale)
            .setRotationDegrees(clip.rotation)
            .setBackgroundFrameAnchor(clip.offsetX.coerceIn(-1f, 1f), (-clip.offsetY).coerceIn(-1f, 1f))
            .build()

    private fun typefaceName(f: TextFont): String = when (f) {
        TextFont.SANS -> "sans-serif"
        TextFont.SERIF -> "serif"
        TextFont.MONO -> "monospace"
        TextFont.CURSIVE -> "cursive"
    }
}
