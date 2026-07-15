package com.hereliesaz.guillotine.desktop.media

/**
 * Process-wide render configuration that the export/preview render paths read but that isn't part of
 * the document model — currently just the on-device subject-segmentation model path used to matte a
 * `removeBackground` clip. Set once from the settings observer (see `NleScreen`); read by the renderer.
 * A tiny holder avoids threading a model path through every render-function signature.
 */
object DesktopRenderConfig {
    /** Path to an ONNX subject-segmentation model, or blank when none is configured. */
    @Volatile
    var segModelPath: String = ""
}
