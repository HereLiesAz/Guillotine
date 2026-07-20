package com.hereliesaz.guillotine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hereliesaz.guillotine.azphalt.AzphaltPlugin
import com.hereliesaz.guillotine.azphalt.AzphaltStoreState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AzphaltStoreScreen(
    onApplyPlugin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val storeState = remember { AzphaltStoreState() }
    val plugins by storeState.plugins.collectAsState()
    val loading by storeState.loading.collectAsState()
    val error by storeState.error.collectAsState()
    val selectedCategory by storeState.selectedCategory.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Per-package install-in-flight flags, so a card shows a spinner while its bytes download + verify.
    val installing = remember { mutableStateMapOf<String, Boolean>() }
    val extensionsDir = remember { File(context.filesDir, "extensions").absolutePath }

    // Browse the hosted storefront, scoped to this host so packages for other apps are hidden.
    // Guard against overlapping fetches (e.g. rapid Retry taps launching concurrent requests).
    fun refresh() {
        if (loading) return
        scope.launch { withContext(Dispatchers.IO) { storeState.loadCatalog(context.packageName) } }
    }
    LaunchedEffect(Unit) { refresh() }

    fun install(plugin: AzphaltPlugin) {
        if (installing[plugin.id] == true) return
        installing[plugin.id] = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { storeState.install(plugin, extensionsDir) }
            installing[plugin.id] = false
            when (result) {
                is AzphaltStoreState.InstallResult.Success -> {
                    val note = if (!result.signed) " (unsigned — integrity verified, provenance not)" else ""
                    snackbar.showMessage("Installed “${plugin.name}”$note")
                    onApplyPlugin(plugin.id) // apply to the selected clip and close, as before
                }
                is AzphaltStoreState.InstallResult.Failure ->
                    snackbar.showMessage(result.message)
            }
        }
    }

    val filteredPlugins = if (selectedCategory == "All") plugins else plugins.filter { it.category == selectedCategory }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            "Azphalt Store",
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.primary
                    )
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            containerColor = MaterialTheme.colorScheme.surface
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Category Chips (M3 Expressive uses large rounded chips or buttons)
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(storeState.categories) { category ->
                        val isSelected = selectedCategory == category
                        val displayName = when(category) {
                            "vegas-inspired" -> "Vegas FX"
                            "layer-effects" -> "Layer FX"
                            "layer-effects-scenery" -> "Scenery"
                            "kinetic-typography" -> "Kinetic Type"
                            "kinetic-typography-smart" -> "Smart Type"
                            "companion-apps" -> "Apps"
                            "mcp-servers" -> "MCP"
                            else -> category
                        }

                        FilterChip(
                            selected = isSelected,
                            onClick = { storeState.setCategory(category) },
                            label = {
                                Text(
                                    displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(vertical = 8.dp)
                                )
                            },
                            shape = RoundedCornerShape(16.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }

                when {
                    // Initial load with nothing yet: a centered spinner.
                    loading && plugins.isEmpty() -> CenteredBox {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                    // Failed with nothing to show: the error plus a retry.
                    error != null && plugins.isEmpty() -> CenteredBox {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                error ?: "Could not reach the Azphalt store.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 32.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = { refresh() }) { Text("Retry") }
                        }
                    }
                    else -> LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(220.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalItemSpacing = 16.dp
                    ) {
                        items(filteredPlugins) { plugin ->
                            PluginCard(
                                plugin = plugin,
                                installing = installing[plugin.id] == true,
                                onInstall = { install(plugin) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun SnackbarHostState.showMessage(message: String) {
    currentSnackbarData?.dismiss()
    showSnackbar(message)
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
fun PluginCard(plugin: AzphaltPlugin, installing: Boolean, onInstall: () -> Unit) {
    // M3 Expressive Card with large rounded corners and playful colors
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            plugin.category == "companion-apps" -> Icons.Default.Apps
                            plugin.category == "mcp-servers" -> Icons.Default.Hub
                            plugin.category.contains("kinetic") -> Icons.Default.AutoFixHigh
                            else -> Icons.Default.Extension
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                // Price badge (Free / $X.XX) pinned to the right of the header.
                Text(
                    text = plugin.priceLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = plugin.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (plugin.author.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "by ${plugin.author}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = plugin.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )

            // Rating + download count, when the registry reports them.
            if (plugin.rating != null || plugin.downloads > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (plugin.rating != null) {
                        Icon(
                            Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "%.1f".format(plugin.rating),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (plugin.downloads > 0) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = formatCount(plugin.downloads),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // M3 Tonal Button — downloads, verifies on-device, and installs.
            Button(
                onClick = onInstall,
                enabled = !installing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors()
            ) {
                if (installing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Installing…", style = MaterialTheme.typography.titleMedium)
                } else {
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Get", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

/** Compact download counts: 8900 → "8.9k", 1_200_000 → "1.2M". */
private fun formatCount(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
