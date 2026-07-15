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
    val tags: List<String>
)

class AzphaltStoreState {
    private val _plugins = MutableStateFlow<List<AzphaltPlugin>>(emptyList())
    val plugins: StateFlow<List<AzphaltPlugin>> = _plugins.asStateFlow()

    private val _selectedCategory = MutableStateFlow("All")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    val categories = listOf("All", "vegas-inspired", "layer-effects", "layer-effects-scenery", "kinetic-typography", "kinetic-typography-smart")

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun loadPlugins(baseDirPath: String) {
        val baseDir = File(baseDirPath)
        if (!baseDir.exists()) return

        val loadedPlugins = mutableListOf<AzphaltPlugin>()

        baseDir.listFiles { _, name -> name.endsWith(".azp") }?.forEach { azpFile ->
            try {
                val bytes = azpFile.readBytes()
                val loaded = AzpPackage.load(bytes)
                val manifest = loaded.manifest
                
                val id = manifest.id
                val name = manifest.name
                val desc = manifest.description
                
                val tagsList = manifest.assets.firstOrNull()?.tags ?: emptyList()
                
                var cat = "layer-effects"
                if (id.contains("vegas")) cat = "vegas-inspired"
                else if (id.contains("scenery")) cat = "layer-effects-scenery"
                else if (id.contains("smart")) cat = "kinetic-typography-smart"
                else if (id.contains("typography") || id.contains("type") || tagsList.contains("text")) cat = "kinetic-typography"
                
                loadedPlugins.add(
                    AzphaltPlugin(
                        id = id,
                        name = name,
                        description = desc ?: "A powerful azphalt plugin.",
                        category = cat,
                        tags = tagsList
                    )
                )
            } catch (e: Exception) {
                // Skip invalid or unverifiable packages
            }
        }
        
        _plugins.value = loadedPlugins
    }
}
