package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.model.KeyframeProperty

/**
 * Argument validation for the `set_clip_filter` MCP tool, kept pure so it is unit-testable.
 *
 * `set_clip_filter` writes a clip's STATIC value for a [KeyframeProperty]. Every property has a static
 * slot except [KeyframeProperty.OPACITY] (opacity exists only as keyframes), which used to be accepted
 * and silently ignored; it is now refused with a pointer to `add_keyframe`. Values are rejected when
 * not finite and clamped into the property's [KeyframeProperty.uiRange] — the same range the UI sliders
 * allow — so e.g. `speed = 0` or `volume = 50` can't reach the renderer.
 */
object ClipFilterValidation {

    /** The properties `set_clip_filter` can set (everything with a static slot on the clip). */
    val SUPPORTED: Set<KeyframeProperty> = KeyframeProperty.entries.toSet() - KeyframeProperty.OPACITY

    /** A validated request: the property, the clamped value, and whether clamping changed it. */
    data class Validated(val property: KeyframeProperty, val value: Float, val clamped: Boolean)

    /** Parse and validate; throws [IllegalArgumentException] with a caller-facing message. */
    fun validate(property: String, value: Float): Validated {
        val prop = KeyframeProperty.entries.firstOrNull { it.name.equals(property.trim(), ignoreCase = true) }
            ?: throw IllegalArgumentException(
                "Unknown property: $property. Supported: ${SUPPORTED.joinToString { it.name.lowercase() }}.",
            )
        require(prop in SUPPORTED) {
            "\"${prop.name.lowercase()}\" has no static value — animate it with add_keyframe instead."
        }
        require(value.isFinite()) { "Value must be a finite number." }
        val clampedValue = value.coerceIn(prop.uiRange.start, prop.uiRange.endInclusive)
        return Validated(prop, clampedValue, clampedValue != value)
    }
}
