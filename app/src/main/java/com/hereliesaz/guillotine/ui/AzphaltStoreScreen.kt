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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.hereliesaz.guillotine.ai.ApiKeyStore
import com.hereliesaz.guillotine.azphalt.AzphaltPlugin
import com.hereliesaz.guillotine.azphalt.AzphaltRepository
import com.hereliesaz.guillotine.azphalt.AzphaltStoreState
import com.hereliesaz.guillotine.azphalt.AzpPublisherPins
import com.hereliesaz.guillotine.editor.EditorViewModel
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What lives in each store category, and what a user could ask the AI assistant for once it's
 * installed — the single source of truth for both the category chip labels and the store guide
 * ([AzphaltStoreGuideDialog]), so the two can't drift out of sync.
 */
private data class AzphaltCategoryInfo(
    val key: String,
    val displayName: String,
    val description: String,
    val example: String,
)

private val AZPHALT_CATEGORIES = listOf(
    AzphaltCategoryInfo(
        "vegas-inspired", "Vegas FX",
        "Layer-blend and transition effects in the style of classic NLE compositing tools.",
        "\"add a vegas-style crossfade to this cut\"",
    ),
    AzphaltCategoryInfo(
        "layer-effects", "Layer FX",
        "Color grades (LUTs), shaders, and filters that apply to a single clip or layer.",
        "\"give this clip a teal and orange grade\"",
    ),
    AzphaltCategoryInfo(
        "layer-effects-scenery", "Scenery",
        "Background/backdrop effects and generated-scenery looks for composited layers.",
        "\"put a generated sunset behind the subject\"",
    ),
    AzphaltCategoryInfo(
        "kinetic-typography", "Kinetic Type",
        "Animated caption and title styles — text that moves with the words being spoken.",
        "\"make my captions animate like they're being typed\"",
    ),
    AzphaltCategoryInfo(
        "kinetic-typography-smart", "Smart Type",
        "Kinetic typography styles that react to the audio — beat- or syllable-driven text motion.",
        "\"animate the captions to grow on each syllable\"",
    ),
    AzphaltCategoryInfo(
        "companion-apps", "Apps",
        "Standalone companion apps that work alongside Guillotine (not installed into the timeline).",
        "an app you launch separately, listed here for discovery.",
    ),
    AzphaltCategoryInfo(
        "mcp-servers", "MCP",
        "Extra tools for the AI assistant to call — expands what you can ask it to do.",
        "unlocks new commands the assistant can run for you.",
    ),
)

private fun categoryDisplayName(category: String): String =
    AZPHALT_CATEGORIES.find { it.key == category }?.displayName ?: category

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AzphaltStoreScreen(
    vm: EditorViewModel,
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
    // Shared with the AI-model install flow (Sheets.kt) — trust-on-first-use pins are keyed by package
    // id, so both install paths enforcing the same publisher continuity is the point, not a collision.
    val publisherPins = remember { AzpPublisherPins(File(context.filesDir, "azp-publishers.json")) }
    // A package that verified but isn't from a trusted signer (or is unsigned) — confirm before installing.
    var pendingUntrusted by remember { mutableStateOf<Pair<AzphaltPlugin, String>?>(null) }
    // A package whose id was previously installed from a different publisher key — confirm before overwriting.
    var pendingPublisherChange by remember {
        mutableStateOf<Pair<AzphaltPlugin, AzphaltStoreState.InstallResult.PublisherChanged>?>(null)
    }
    // What's-in-the-store walkthrough: shown automatically the first time this screen opens, and
    // reachable afterward via the help icon in the top bar.
    val keyStore = remember { ApiKeyStore(context) }
    var showGuide by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!keyStore.azphaltStoreGuideSeen) showGuide = true }
    fun dismissGuide() {
        showGuide = false
        keyStore.markAzphaltStoreGuideSeen()
    }

    // Browse the hosted storefront, scoped to this host so packages for other apps are hidden.
    // Guard against overlapping fetches (e.g. rapid Retry taps launching concurrent requests).
    fun refresh() {
        if (loading) return
        scope.launch { withContext(Dispatchers.IO) { storeState.loadCatalog(context.packageName) } }
    }
    LaunchedEffect(Unit) { refresh() }

    fun install(plugin: AzphaltPlugin, allowUntrusted: Boolean = false, allowPublisherChange: Boolean = false) {
        if (installing[plugin.id] == true) return
        installing[plugin.id] = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                storeState.install(
                    plugin, extensionsDir,
                    trustedKeys = setOf(AzphaltRepository.FLAGSHIP_SIGNING_KEY),
                    pins = publisherPins,
                    allowUntrusted = allowUntrusted,
                    allowPublisherChange = allowPublisherChange,
                )
            }
            installing[plugin.id] = false
            when (result) {
                is AzphaltStoreState.InstallResult.Success -> {
                    val note = if (!result.signed) " (unsigned — integrity verified, provenance not)" else ""
                    val clipId = vm.uiState.value.selectedClipIds.firstOrNull()
                    if (clipId == null) {
                        snackbar.showMessage(
                            "Installed “${plugin.name}”$note. Select a clip, then reopen the store to apply it.",
                        )
                        return@launch
                    }
                    // Actually apply it — install() alone used to only stamp an unread id and close, which
                    // for every kind but kinetic-typography motion meant nothing ever rendered.
                    when (val outcome = withContext(Dispatchers.IO) { AzpPluginApplier.apply(context, vm, clipId, plugin.id) }) {
                        is AzpPluginApplier.Outcome.Applied -> {
                            snackbar.showMessage("Applied “${plugin.name}”$note to the selected clip.")
                            onDismiss()
                        }
                        is AzpPluginApplier.Outcome.Unsupported -> snackbar.showMessage(outcome.message)
                        is AzpPluginApplier.Outcome.Failure -> snackbar.showMessage(outcome.message)
                    }
                }
                is AzphaltStoreState.InstallResult.Failure ->
                    snackbar.showMessage(result.message)
                is AzphaltStoreState.InstallResult.Untrusted ->
                    pendingUntrusted = plugin to result.reason
                is AzphaltStoreState.InstallResult.PublisherChanged ->
                    pendingPublisherChange = plugin to result
            }
        }
    }

    val filteredPlugins = if (selectedCategory == "All") plugins else plugins.filter { it.category == selectedCategory }

    if (showGuide) {
        AzphaltStoreGuideDialog(onDismiss = { dismissGuide() })
    }

    pendingUntrusted?.let { (plugin, reason) ->
        AlertDialog(
            onDismissRequest = { pendingUntrusted = null },
            title = { Text("Not from a trusted publisher") },
            text = {
                Text(
                    "“${plugin.name}” passed integrity verification, but it's not from a signer this " +
                        "app already trusts ($reason). Install it anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingUntrusted = null
                    install(plugin, allowUntrusted = true)
                }) { Text("Install anyway") }
            },
            dismissButton = { TextButton(onClick = { pendingUntrusted = null }) { Text("Cancel") } },
        )
    }

    pendingPublisherChange?.let { (plugin, _) ->
        AlertDialog(
            onDismissRequest = { pendingPublisherChange = null },
            title = { Text("Publisher changed") },
            text = {
                Text(
                    "“${plugin.name}” was previously installed from a different publisher key than this " +
                        "update carries. This could mean a legitimate key rotation — or a hijacked " +
                        "listing. Install this update anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingPublisherChange = null
                    install(plugin, allowPublisherChange = true)
                }) { Text("Install anyway") }
            },
            dismissButton = { TextButton(onClick = { pendingPublisherChange = null }) { Text("Cancel") } },
        )
    }

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
                    actions = {
                        IconButton(onClick = { showGuide = true }) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "What's in the store")
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
                        val displayName = if (category == "All") "All" else categoryDisplayName(category)

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

/**
 * A one-time (then help-icon-reachable) tour of what the store carries, so a first-time visitor
 * doesn't have to guess what "Vegas FX" or "MCP" mean from the category chip alone. Content comes
 * from [AZPHALT_CATEGORIES] — the same list that drives the chip labels — so this can't drift.
 */
@Composable
private fun AzphaltStoreGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's in the store") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "Everything here installs on-device and shows up as something you can ask the AI " +
                        "assistant for. Browse by category, or just describe what you want:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AZPHALT_CATEGORIES.forEachIndexed { index, info ->
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Spacer(Modifier.height(if (index == 0) 12.dp else 0.dp))
                    Text(
                        text = info.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = info.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Try: ${info.example}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
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
