package com.hereliesaz.guillotine.azphalt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

data class AzphaltPlugin(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val tags: List<String>,
    /** In-package preview-image bytes (decoded from the manifest `preview.image`), when bundled. */
    val previewImage: ByteArray? = null,
)

class AzphaltStoreState {
    private val _plugins = MutableStateFlow<List<AzphaltPlugin>>(emptyList())
    val plugins: StateFlow<List<AzphaltPlugin>> = _plugins.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    val categories = listOf("All", "vegas-inspired", "layer-effects", "layer-effects-scenery", "kinetic-typography", "kinetic-typography-smart", "companion-apps", "mcp-servers")

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    /**
     * Load every `.azp` under [baseDirPath] that this host may offer. Per the azphalt spec a package's
     * `targetApps` scopes it to specific hosts, so a package targeting *another* app is skipped —
     * [hostAppId] is this host's reverse-DNS id (empty ⇒ show everything, e.g. in a test harness).
     */
    fun loadPlugins(baseDirPath: String, hostAppId: String = "") {
        val baseDir = File(baseDirPath)
        if (!baseDir.exists()) return

        val loadedPlugins = mutableListOf<AzphaltPlugin>()

        baseDir.listFiles { _, name -> name.endsWith(".azp") }?.forEach { azpFile ->
            try {
                val bytes = azpFile.readBytes()
                val loaded = AzpPackage.load(bytes)
                val manifest = loaded.manifest

                // Honor host scoping: hide packages the author scoped to other apps.
                if (hostAppId.isNotEmpty() && !manifest.targetsApp(hostAppId)) return@forEach

                val id = manifest.id
                val name = manifest.name
                val desc = manifest.description

                val tagsList = manifest.assets.firstOrNull()?.tags ?: emptyList()

                var cat = "layer-effects"
                if (manifest.isApp) cat = "companion-apps"
                else if (manifest.isMcp) cat = "mcp-servers"
                else if (id.contains("vegas")) cat = "vegas-inspired"
                else if (id.contains("scenery")) cat = "layer-effects-scenery"
                else if (id.contains("smart")) cat = "kinetic-typography-smart"
                else if (id.contains("typography") || id.contains("type") || tagsList.contains("text")) cat = "kinetic-typography"

                // A bundled (in-package) preview still, if the manifest declares one. Remote (`https:`)
                // previews are left for a networked image loader; we only surface local bytes here.
                val previewPath = manifest.preview?.image
                val previewBytes = previewPath
                    ?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                    ?.let { loaded.payload[it] }

                loadedPlugins.add(
                    AzphaltPlugin(
                        id = id,
                        name = name,
                        description = desc ?: "A powerful azphalt plugin.",
                        category = cat,
                        tags = tagsList,
                        previewImage = previewBytes,
                    )
                )
            } catch (e: Exception) {
                // Skip invalid or unverifiable packages
            }
        }

        _plugins.value = loadedPlugins
    }
}
