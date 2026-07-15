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

        val dirs = listOf("kinetic-typography", "kinetic-typography-smart", "layer-effects", "layer-effects-scenery", "vegas-inspired")
        val loadedPlugins = mutableListOf<AzphaltPlugin>()

        for (dirName in dirs) {
            val dir = File(baseDir, dirName)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { pluginDir ->
                    val manifestFile = File(pluginDir, "manifest.json")
                    if (manifestFile.exists()) {
                        try {
                            val manifest = JSONObject(manifestFile.readText())
                            val id = manifest.optString("id", "")
                            val name = manifest.optString("name", "")
                            val desc = manifest.optString("description", "A powerful azphalt plugin.")
                            
                            val assets = manifest.optJSONArray("assets")
                            val tagsList = mutableListOf<String>()
                            if (assets != null && assets.length() > 0) {
                                val tagsArr = assets.getJSONObject(0).optJSONArray("tags")
                                if (tagsArr != null) {
                                    for (i in 0 until tagsArr.length()) {
                                        tagsList.add(tagsArr.getString(i))
                                    }
                                }
                            }
                            
                            loadedPlugins.add(
                                AzphaltPlugin(
                                    id = id,
                                    name = name,
                                    description = desc,
                                    category = dirName,
                                    tags = tagsList
                                )
                            )
                        } catch (e: Exception) {
                            // Skip invalid json
                        }
                    }
                }
            }
        }
        _plugins.value = loadedPlugins
    }
}
