package com.hereliesaz.guillotine.ai

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Builds the on-device half of generative object removal: a black/white inpainting mask where WHITE marks
 * the region the cloud inpainter should regenerate. We only have bounding boxes (no on-device fine
 * segmentation), so the mask is the object's box slightly expanded — the repaint region is rectangular.
 */
object InpaintMask {
    fun fromBoxes(width: Int, height: Int, boxes: List<RectF>, padFrac: Float = 0.06f): Bitmap {
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(mask)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.WHITE; isAntiAlias = false }
        val padX = width * padFrac
        val padY = height * padFrac
        for (b in boxes) {
            canvas.drawRect(
                (b.left - padX).coerceAtLeast(0f),
                (b.top - padY).coerceAtLeast(0f),
                (b.right + padX).coerceAtMost(width.toFloat()),
                (b.bottom + padY).coerceAtMost(height.toFloat()),
                paint,
            )
        }
        return mask
    }
}
