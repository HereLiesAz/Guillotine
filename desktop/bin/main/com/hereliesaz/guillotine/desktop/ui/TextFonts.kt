package com.hereliesaz.guillotine.desktop.ui

import androidx.compose.ui.text.font.FontFamily
import com.hereliesaz.guillotine.model.TextFont

fun TextFont.fontFamily(): FontFamily = when (this) {
    TextFont.SANS -> FontFamily.SansSerif
    TextFont.SERIF -> FontFamily.Serif
    TextFont.MONO -> FontFamily.Monospace
    TextFont.CURSIVE -> FontFamily.Cursive
}

fun TextFont.label(): String = when (this) {
    TextFont.SANS -> "Sans"
    TextFont.SERIF -> "Serif"
    TextFont.MONO -> "Mono"
    TextFont.CURSIVE -> "Cursive"
}
