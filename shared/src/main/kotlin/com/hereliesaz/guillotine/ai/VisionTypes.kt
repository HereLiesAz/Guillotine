package com.hereliesaz.guillotine.ai

data class BoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun width(): Float = right - left
    fun height(): Float = bottom - top
}

data class Detection(val label: String, val box: BoundingBox, val score: Float)
