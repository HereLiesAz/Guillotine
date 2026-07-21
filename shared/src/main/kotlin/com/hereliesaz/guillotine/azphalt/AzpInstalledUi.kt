package com.hereliesaz.guillotine.azphalt

import java.io.File

/**
 * Reads the [AzpUiSchema]s of installed asset packages — the discovery half of rendering azphalt
 * control panels in a host. It walks the extensions dir (`filesDir/extensions` on Android,
 * `DesktopStorage.dataDir/extensions` on desktop), and for each `.azp` scoped to this host that carries
 * an asset with a `ui` schema, loads and parses that schema. Pure JVM, so `:app` and `:desktop` share it.
 *
 * This only *renders declarations*; it never runs extension code — applying a panel's `params` to media
 * is the extension runtime (azphalt jobs #2–#5), still ahead. See docs/PLUGIN_PANELS.md.
 */
object AzpInstalledUi {

    /** One renderable control panel from an installed package: its owner and the parsed schema. */
    data class Panel(
        val packageId: String,
        val packageName: String,
        /** The `role`/`type` of the asset the schema configures, for labeling (e.g. `shader`, `lut`). */
        val assetType: String,
        val schema: AzpUiSchema,
    )

    /**
     * Enumerate the control panels of installed asset packages in [extensionsDir], scoped to [hostAppId]
     * (packages targeting other hosts are skipped). Blocking I/O — call off the main thread. Invalid or
     * schema-less packages are silently skipped; the result is package-name-sorted for a stable UI.
     */
    fun list(extensionsDir: File, hostAppId: String): List<Panel> {
        val files = extensionsDir.listFiles { _, name -> name.endsWith(".azp") } ?: return emptyList()
        val out = ArrayList<Panel>()
        for (f in files) {
            runCatching {
                val loaded = AzpPackage.read(f.readBytes())
                if (!loaded.manifest.targetsApp(hostAppId)) return@runCatching
                for (asset in loaded.manifest.assets) {
                    val uiPath = asset.ui ?: continue
                    val schemaBytes = loaded.payload[uiPath] ?: continue
                    val schema = AzpUiSchema.parse(schemaBytes.decodeToString()) ?: continue
                    if (schema.controls.isNotEmpty()) {
                        out.add(Panel(loaded.manifest.id, loaded.manifest.name, asset.type, schema))
                    }
                }
            }
        }
        return out.sortedBy { it.packageName.lowercase() }
    }
}
