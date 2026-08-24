package com.hereliesaz.guillotine.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.hereliesaz.guillotine.model.MediaItem
import com.hereliesaz.guillotine.model.MediaKind
import com.hereliesaz.guillotine.model.newId
import java.io.File

/**
 * A plain solid-color rectangle, generated on-device as a real image file — the "shape layer" a
 * transparent title/caption clip (see `PreviewPlayer.VideoSlot`'s `ClipType.TEXT` branch, which
 * bakes in no background of its own) can be stacked on top of when it needs to read legibly over
 * bright footage, or just a plain colored background/wipe on its own. Instant and offline (no
 * provider/key), unlike [com.hereliesaz.guillotine.ai.gen.GenController]'s cloud generation — this
 * writes straight to disk and returns a ready [MediaItem] that lands via the identical `addMedia`
 * path as any imported or generated image, so it's a normal `ClipType.VIDEO`/`MediaKind.IMAGE` clip:
 * no special type, no special rendering, no marking.
 */
object ShapeLayer {

    /**
     * [color] is any [Color.parseColor]-compatible string: a hex code (`"#RRGGBB"`/`"#AARRGGBB"`) or
     * a CSS name (`"black"`, `"cornflowerblue"`, …). [opacity] (0..1) multiplies the color's own
     * alpha (so `"#000000"` at `opacity = 0.6` is the same 60%-black scrim the old caption overlay
     * used to bake into every text clip — now an explicit, separate, user-controlled layer instead).
     * Throws [IllegalArgumentException] for an unparseable color, matching this app's other
     * user-input validation.
     */
    fun generate(context: Context, color: String, opacity: Float, widthPx: Int, heightPx: Int): MediaItem {
        val base = try {
            Color.parseColor(color)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(
                "Unrecognized color \"$color\" — use a hex code like #000000 or #000000AA, or a name like \"black\".",
            )
        }
        val alpha = ((Color.alpha(base) / 255f) * opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        val argb = Color.argb(alpha, Color.red(base), Color.green(base), Color.blue(base))

        val bmp = Bitmap.createBitmap(widthPx.coerceAtLeast(2), heightPx.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        bmp.eraseColor(argb)
        // The app cache dir, same as AndroidGenSink — this is disposable, regenerable content, not
        // something that needs to survive an app data wipe or be user-visible in a file browser.
        val file = File(context.cacheDir, "shape_${System.currentTimeMillis()}.png")
        file.outputStream().use { out -> bmp.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bmp.recycle()

        return MediaItem(newId(), Uri.fromFile(file).toString(), "Shape ($color)", MediaKind.IMAGE, 0L)
    }
}
