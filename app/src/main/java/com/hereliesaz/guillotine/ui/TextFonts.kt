package com.hereliesaz.guillotine.ui

import androidx.compose.ui.text.font.FontFamily
import com.hereliesaz.guillotine.model.TextFont

/** Map a model [TextFont] to a Compose [FontFamily] (kept out of the model layer). */
fun TextFont.fontFamily(): FontFamily = when (this) {
    TextFont.SANS -> FontFamily.SansSerif
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CURSIVE -> FontFamily.Cursive
}

/** Short label for a font, for pickers. */
fun TextFont.label(): String = when (this) {
    TextFont.SANS -> "Sans"
    TextFont.SERIF -> "Serif"
    TextFont.MONO -> "Mono"
    TextFont.CURSIVE -> "Cursive"
}
