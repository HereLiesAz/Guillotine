package com.hereliesaz.guillotine.azphalt

import java.io.File

/**
 * Reads installed azphalt asset packages and resolves, for each, the control panel ([AzpUiSchema]) it
 * ships plus everything the host needs to **apply** it to a clip. It walks the extensions dir
 * (`filesDir/extensions` on Android, `DesktopStorage.dataDir/extensions` on desktop) and, for each
 * `.azp` scoped to this host that carries an asset with a `ui` schema, records the parsed schema and
 * the asset's render target. Pure JVM, so `:app` and `:desktop` share it.
 *
 * Rendering the schema and applying an asset the host already knows how to run (a LUT `.cube`, a GLSL
 * shader) needs no sandbox — the asset is declarative data. Running `code`-kind extensions is the
 * separate WASM runtime (see [AzpCodeRuntime]). See docs/PLUGIN_PANELS.md.
 */
object AzpInstalledUi {

    /** How an asset maps onto Guillotine's render pipeline. */
    enum class RenderKind { SHADER, LUT, OTHER }

    /** One installed asset's control panel plus how to apply it. */
    data class Panel(
        val packageId: String,
        val packageName: String,
        /** The asset's declared `type` (e.g. `shader`, `lut`). */
        val assetType: String,
        val renderKind: RenderKind,
        val schema: AzpUiSchema,
        /** The `.azp` on disk, re-read (and re-verified) at apply time. */
        val sourceFile: File,
        /** Payload key of the asset's bytes inside the package (empty when the asset is remote). */
        val assetPath: String,
        /** Numeric control defaults, used to seed a shader's params when the asset is first applied. */
        val defaultParams: Map<String, Float>,
    ) {
        /** Stable on-disk filename for this package's applied asset (see AzpAssetApplier). */
        fun installFileName(extension: String): String =
            packageId.replace(Regex("[^A-Za-z0-9._-]"), "_").take(120) + extension
    }

    /** How Guillotine renders [assetType], or [RenderKind.OTHER] when it has no native render path. */
    fun renderKindOf(assetType: String): RenderKind = when (assetType.trim().lowercase()) {
        "shader", "isf", "glsl" -> RenderKind.SHADER
        "lut", "cube" -> RenderKind.LUT
        else -> RenderKind.OTHER
    }

    /**
     * Enumerate the panels of installed asset packages in [extensionsDir], scoped to [hostAppId]
     * (packages targeting other hosts are skipped). Blocking I/O — call off the main thread. Invalid
     * packages are skipped; a package's asset that ships **no** `ui` schema still gets a panel (an empty
     * one, no controls) rather than being dropped — a static LUT or fixed-look shader has nothing to
     * adjust, and requiring a schema anyway would make every ui-less asset silently unappliable (this
     * is, in practice, every current real catalog package — none of them ship a `ui` file). The result
     * is package-name-sorted for a stable UI.
     */
    fun list(extensionsDir: File, hostAppId: String): List<Panel> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".azp") } ?: return emptyList()
        val out = ArrayList<Panel>()
        for (f in files) {
            runCatching {
                val loaded = AzpPackage.read(f.readBytes())
                if (!loaded.manifest.targetsApp(hostAppId)) return@runCatching
                for (asset in loaded.manifest.assets) {
                    val schema = asset.ui
                        ?.let { loaded.payload[it] }
                        ?.let { AzpUiSchema.parse(it.decodeToString()) }
                        ?: AzpUiSchema(emptyList())
                    out.add(
                        Panel(
                            packageId = loaded.manifest.id,
                            packageName = loaded.manifest.name,
                            assetType = asset.type,
                            renderKind = renderKindOf(asset.type),
                            schema = schema,
                            sourceFile = f,
                            assetPath = asset.path,
                            defaultParams = schema.numericDefaults(),
                        ),
                    )
                }
            }
        }
        return out.sortedBy { it.packageName.lowercase() }
    }
}
