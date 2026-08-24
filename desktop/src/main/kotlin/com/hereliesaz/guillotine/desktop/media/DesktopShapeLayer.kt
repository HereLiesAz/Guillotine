package com.hereliesaz.guillotine.desktop.media

import com.hereliesaz.guillotine.desktop.platform.DesktopStorage
import com.hereliesaz.guillotine.model.MediaItem
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * A plain solid-color rectangle, generated on-device as a real image file — the desktop analogue of
 * the Android app's `ShapeLayer`. The "shape layer" a transparent title/caption clip (see
 * `DesktopPreviewPlayer.VideoSlot`'s `ClipType.TEXT` branch, which bakes in no background of its
 * own) can be stacked on top of when it needs to read legibly over bright footage, or just a plain
 * colored background/wipe on its own. Instant and offline (no provider/key), unlike
 * `GenController`'s cloud generation — writes straight to disk and probes the result through
 * [DesktopMediaImport.probe], the exact same path any imported or generated file takes, so it's a
 * normal `ClipType.VIDEO`/`MediaKind.IMAGE` clip: no special type, no special rendering, no marking.
 */
object DesktopShapeLayer {

    private val dir = File(DesktopStorage.dataDir, "shapes").apply { mkdirs() }

    /** The 16 standard CSS/HTML color keywords, plus a handful of extras common in casual usage.
     *  Not a full CSS name table (AWT has no built-in one) — anything else needs a hex code. */
    private val namedColors = mapOf(
        "black" to Color(0, 0, 0), "white" to Color(255, 255, 255), "silver" to Color(192, 192, 192),
        "gray" to Color(128, 128, 128), "grey" to Color(128, 128, 128), "red" to Color(255, 0, 0),
        "maroon" to Color(128, 0, 0), "yellow" to Color(255, 255, 0), "olive" to Color(128, 128, 0),
        "lime" to Color(0, 255, 0), "green" to Color(0, 128, 0), "aqua" to Color(0, 255, 255),
        "cyan" to Color(0, 255, 255), "teal" to Color(0, 128, 128), "blue" to Color(0, 0, 255),
        "navy" to Color(0, 0, 128), "fuchsia" to Color(255, 0, 255), "magenta" to Color(255, 0, 255),
        "purple" to Color(128, 0, 128), "orange" to Color(255, 165, 0), "pink" to Color(255, 192, 203),
        "brown" to Color(165, 42, 42), "transparent" to Color(0, 0, 0, 0),
    )

    /**
     * [color] is a hex code (`"#RRGGBB"`/`"#AARRGGBB"`) or one of [namedColors]. [opacity] (0..1)
     * multiplies the color's own alpha. Throws [IllegalArgumentException] for an unparseable color,
     * matching this app's other user-input validation. Returns null if the written file couldn't be
     * re-probed (should not happen for a PNG this function just wrote itself).
     */
    fun generate(color: String, opacity: Float, widthPx: Int, heightPx: Int): MediaItem? {
        val base = parseColor(color)
            ?: throw IllegalArgumentException(
                "Unrecognized color \"$color\" — use a hex code like #000000 or #000000AA, or a name like \"black\".",
            )
        val alpha = ((base.alpha / 255f) * opacity.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)
        val argb = Color(base.red, base.green, base.blue, alpha)

        val img = BufferedImage(widthPx.coerceAtLeast(2), heightPx.coerceAtLeast(2), BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = argb
        g.fillRect(0, 0, img.width, img.height)
        g.dispose()

        val file = File(dir, "shape_${System.currentTimeMillis()}.png")
        ImageIO.write(img, "png", file)
        return DesktopMediaImport.probe(file)?.copy(name = "Shape ($color)")
    }

    private fun parseColor(color: String): Color? {
        val c = color.trim()
        namedColors[c.lowercase()]?.let { return it }
        if (!c.startsWith("#")) return null
        return runCatching {
            when (c.length) {
                7 -> Color(Integer.parseInt(c.substring(1), 16) or (0xFF shl 24), true) // #RRGGBB
                9 -> { // #AARRGGBB
                    val argb = java.lang.Long.parseLong(c.substring(1), 16).toInt()
                    Color(argb, true)
                }
                else -> null
            }
        }.getOrNull()
    }
}
