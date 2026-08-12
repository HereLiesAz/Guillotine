package com.hereliesaz.guillotine.model

/**
 * Curated subset of FFmpeg `xfade` transition types (see `apply_transition` in `McpTools`/
 * `DesktopMcpTools`, which accepts the full catalog — this is the shorter list shown in a picker UI,
 * not every type `xfade` supports) — id (the literal `xfade` type string) to a human label.
 */
object TransitionCatalog {
    val TYPES: List<Pair<String, String>> = listOf(
        "fade" to "Fade",
        "dissolve" to "Dissolve",
        "wipeleft" to "Wipe left",
        "wiperight" to "Wipe right",
        "wipeup" to "Wipe up",
        "wipedown" to "Wipe down",
        "slideleft" to "Slide left",
        "slideright" to "Slide right",
        "circleopen" to "Circle open",
        "circleclose" to "Circle close",
        "pixelize" to "Pixelize",
        "radial" to "Radial",
    )
}
