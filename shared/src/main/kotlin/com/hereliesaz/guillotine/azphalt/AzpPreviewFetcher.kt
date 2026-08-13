package com.hereliesaz.guillotine.azphalt

/**
 * Fetches and extracts a renderable preview asset (a LUT or shader) for a **not-yet-installed**
 * catalog entry, so the store can show what the effect actually looks like instead of a generic
 * icon. Network + parse only — turning the result into a rendered pixel is each platform's own
 * job (`:shared` has no `Bitmap`/`BufferedImage` type to render into).
 *
 * There is no lighter "just the preview" endpoint on the registry (see [AzphaltRegistry]'s own doc
 * comment) — this downloads the exact same `.azp` a real install would and reads it with
 * [AzpPackage], but never writes anything to an extensions dir or marks the package installed.
 *
 * An entry whose only assets are motion/model/etc. ([AzpInstalledUi.RenderKind.OTHER]) returns
 * null — there is no meaningful still-image preview for those, and this says so rather than
 * fabricating one.
 */
object AzpPreviewFetcher {

    sealed class PreviewSource {
        data class Lut(val cubeText: String) : PreviewSource()
        data class Shader(val glslSource: String, val params: Map<String, Float>) : PreviewSource()
    }

    /**
     * Blocking network + parse — call off the main thread. Null on any failure (network,
     * malformed package) or when [id]'s latest version carries no LUT/shader asset.
     */
    fun fetch(id: String, version: String?): PreviewSource? = runCatching {
        val bytes = AzphaltRegistry.download(AzpInstallLink(id, version))
        val loaded = AzpPackage.read(bytes)
        val asset = loaded.manifest.assets.firstOrNull {
            AzpInstalledUi.renderKindOf(it.type) != AzpInstalledUi.RenderKind.OTHER
        } ?: return@runCatching null
        val assetBytes = loaded.payload[asset.path] ?: return@runCatching null
        when (AzpInstalledUi.renderKindOf(asset.type)) {
            AzpInstalledUi.RenderKind.LUT -> PreviewSource.Lut(assetBytes.decodeToString())
            AzpInstalledUi.RenderKind.SHADER -> {
                val defaults = asset.ui
                    ?.let { loaded.payload[it] }
                    ?.let { AzpUiSchema.parse(it.decodeToString()) }
                    ?.numericDefaults()
                    ?: emptyMap()
                PreviewSource.Shader(assetBytes.decodeToString(), defaults)
            }
            AzpInstalledUi.RenderKind.OTHER -> null
        }
    }.getOrNull()
}
