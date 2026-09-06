package com.hereliesaz.guillotine.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.guillotine.azphalt.AzpExternalOpen
import com.hereliesaz.guillotine.azphalt.AzpHandoffInstaller
import com.hereliesaz.guillotine.azphalt.AzpInstallLink
import com.hereliesaz.guillotine.azphalt.AzpInstallSurfaces
import com.hereliesaz.guillotine.azphalt.AzpInstalledUi
import com.hereliesaz.guillotine.azphalt.AzpModelInstall
import com.hereliesaz.guillotine.azphalt.AzpPackage
import com.hereliesaz.guillotine.azphalt.AzpStateReport
import com.hereliesaz.guillotine.azphalt.AzpStorePreviewRenderer
import com.hereliesaz.guillotine.azphalt.AzphaltRegistry
import com.hereliesaz.guillotine.azphalt.AzphaltStoreHandoff
import com.hereliesaz.guillotine.azphalt.AzphaltTrust
import com.hereliesaz.guillotine.azphalt.AzpPublisherPins
import com.hereliesaz.guillotine.editor.EditorViewModel
import com.hereliesaz.guillotine.ui.theme.Neutral400
import com.hereliesaz.guillotine.ui.theme.Neutral500
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Guillotine's own Azphalt Store screen. As of 2026-08-12 (see docs/TODO.md) it builds and maintains
 * its own catalog browser again ([CatalogBrowser], over [AzphaltRegistry.browseAll]) rather than only
 * delegating to whichever Azphalt Store app is installed — that delegation-only design (2026-07-28)
 * turned out to have been the right call too early, not the right call permanently: it was built before
 * the catalog and its own trust/install machinery were mature enough to justify the maintenance cost,
 * and both have since grown well past that bar. The external Azphalt Store app and the web storefront
 * both still work — they're one tap away via the browser's own overflow menu ([showRoutes]) — but
 * they're a secondary route now, not the only one.
 *
 * Every install, regardless of route, still runs the full [AzpHandoffInstaller] gauntlet (integrity,
 * signature, trust-on-first-use publisher pinning) on the actual bytes received — browsing your own
 * catalog earns a package no more trust than a store app's or a random file would. Guillotine still
 * owns applying an installed package to the timeline either way, since no store app or catalog could do
 * that on its behalf.
 *
 * [incoming] is the other way in: a package handed to Guillotine from outside the app and routed here by
 * [AzpExternalOpen]. `spec/store-app.md` only specifies the Android app-to-app handoff and explicitly
 * leaves the web case unspecified, so this is the host half of it, in two forms —
 * [AzpExternalOpen.Incoming.File] for bytes already on the device (a browser download, a file manager, a
 * share sheet) and [AzpExternalOpen.Incoming.Link] for an `azphalt://install` deep link naming a package
 * for Guillotine to fetch from the registry itself. Both converge on the same [runInstall]; a link is
 * untrusted input like everything else here, so nothing skips verification because a web page vouched for
 * it. When [incoming] is non-null there is nothing to browse, so the catalog is never shown.
 */
@Composable
fun AzphaltStoreScreen(vm: EditorViewModel, incoming: AzpExternalOpen.Incoming? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extensionsDir = remember { File(context.filesDir, "extensions").absolutePath }
    // The same directory Settings → Advanced → Install AI model writes to, and the only one
    // ModelResolver scans. Landing models anywhere else is landing them nowhere.
    val modelsDir = remember { File(context.filesDir, "azp-models") }
    // Shared with the AI-model install flow (Sheets.kt) — trust-on-first-use pins are keyed by package
    // id, so both install paths enforcing the same publisher continuity is the point, not a collision.
    val publisherPins = remember { AzpPublisherPins(File(context.filesDir, "azp-publishers.json")) }

    // Null when idle; otherwise the line shown in the blocking progress dialog. One piece of state for
    // both slow steps, since a deep link downloads *then* verifies and the user should see which is which.
    var busy by remember { mutableStateOf<String?>(null) }
    // The "here's what you just got, here's where it lives" dialog. A Toast used to carry this, which is
    // the wrong surface for it: it vanishes on its own schedule, and the one thing a user needs after an
    // install — where the thing they installed actually turns up — is exactly what they'd miss.
    var notice by remember { mutableStateOf<InstalledNotice?>(null) }
    // A deep link that hasn't been confirmed yet. An unsolicited link shouldn't silently pull bytes.
    var pendingLink by remember { mutableStateOf<AzpInstallLink?>(null) }
    // A `.azp` handed in via the exported VIEW intent (a browser download, a file manager, a share
    // sheet) that hasn't been confirmed yet either. The bytes are already on-device — unlike a deep
    // link there's nothing to download — but they arrived the same unsolicited way a link does: some
    // other app decided to hand Guillotine a file, and that app choosing to do so is not the user
    // asking to install anything. Mirrors pendingLink's gate rather than calling runInstall directly.
    var pendingExternalFile by remember { mutableStateOf<PendingExternalFile?>(null) }
    var pendingBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pendingUntrusted by remember { mutableStateOf<String?>(null) }
    var pendingPublisherChange by remember { mutableStateOf<AzpHandoffInstaller.InstallResult.PublisherChanged?>(null) }
    // One dialog, every route out of it: get the app, use the web store, or back out. Splitting these
    // across two sequential dialogs made the web store look like a consolation prize you could only
    // reach by first bouncing off Play. awaitingPlayReturn tracks the Play round-trip across onResume.
    var showUnavailable by remember { mutableStateOf(false) }
    // Which way to acquire. Previously this screen jumped straight into the store app whenever one was
    // installed, so the marketplace at azphalt.store was only ever offered to people who had NO store app
    // — and what the store app shows a delegating host is HandoffPicker, a bare list built for the
    // handoff, not its browse UI. Both routes are real now that a downloaded .azp opens straight into the
    // editor (and azphalt://install is claimed), so both get offered.
    var showRoutes by remember { mutableStateOf(false) }
    var awaitingPlayReturn by remember { mutableStateOf(false) }
    // Built off the main thread when this screen opens: reading and parsing every installed .azp is
    // blocking I/O. Null until ready (and when nothing is installed), which is a conforming thing to
    // send — so a browse that happens before it resolves simply carries no inventory.
    var inventory by remember { mutableStateOf<String?>(null) }

    // Own catalog browser, primary again as of 2026-08-12 (see docs/TODO.md) — shown whenever there's
    // no incoming file/link to install directly. "Use the Store app" / "Browse the web store" survive
    // as a secondary route inside the browser's own overflow menu (showRoutes below), not the default.
    var showCatalog by remember { mutableStateOf(false) }

    fun finish(message: String? = null) {
        message?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
        onDismiss()
    }

    fun runInstall(bytes: ByteArray, allowUntrusted: Boolean = false, allowPublisherChange: Boolean = false) {
        busy = "Verifying…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                AzpHandoffInstaller.install(
                    bytes, extensionsDir,
                    trustedKeys = setOf(AzphaltTrust.FLAGSHIP_SIGNING_KEY),
                    pins = publisherPins,
                    allowUntrusted = allowUntrusted,
                    allowPublisherChange = allowPublisherChange,
                    hostAppId = context.packageName,
                )
            }
            busy = null
            when (result) {
                is AzpHandoffInstaller.InstallResult.Success -> {
                    // A model package needs a second step the extensions dir can't do for it: the model
                    // files have to be extracted (or fetched, for remoteUrl weights) into azp-models,
                    // which is the only place ModelResolver looks. Without this the package installed and
                    // the model stayed inert, and the notice had to admit as much.
                    val models = if (AzpInstallSurfaces.Surface.AI_MODEL in result.surfaces) {
                        busy = "Installing model…"
                        val outcome = withContext(Dispatchers.IO) {
                            runCatching {
                                AzpModelInstall.install(
                                    bytes,
                                    trustedKeys = setOf(AzphaltTrust.FLAGSHIP_SIGNING_KEY),
                                    modelsDir = modelsDir,
                                    // The trust gate already ran on these exact bytes moments ago, and the
                                    // user answered it. Re-prompting from here would ask the same question
                                    // twice, and there is no dialog in flight to answer it with.
                                    allowUntrusted = true,
                                    pins = publisherPins,
                                    allowPublisherChange = true,
                                ) { p ->
                                    val pct = p.bytesTotal?.takeIf { it > 0 }?.let { p.bytesDone * 100 / it }
                                    busy = when (p.phase) {
                                        AzpModelInstall.Phase.DOWNLOADING ->
                                            "Downloading ${p.model.filename}${pct?.let { " — $it%" } ?: ""}…"
                                        AzpModelInstall.Phase.VERIFYING -> "Verifying ${p.model.filename}…"
                                        AzpModelInstall.Phase.WRITING -> "Writing ${p.model.filename}…"
                                    }
                                }
                            }
                        }
                        busy = null
                        outcome.fold(
                            onSuccess = { r -> ModelOutcome.Installed(r.installed.size, r.installed.count { it.slot != null }) },
                            onFailure = { ModelOutcome.Failed(it.message ?: "the model could not be installed") },
                        )
                    } else {
                        null
                    }
                    val clipId = vm.uiState.value.selectedClipIds.firstOrNull()
                    if (clipId == null) {
                        notice = InstalledNotice(result.name, result.signed, result.signatureValid, InstallOutcome.NothingSelected, result.surfaces, models)
                        return@launch
                    }
                    // Actually apply it — installing alone doesn't render anything for most package kinds.
                    val outcome = withContext(Dispatchers.IO) { AzpPluginApplier.apply(context, vm, clipId, result.id) }
                    notice = InstalledNotice(
                        result.name, result.signed, result.signatureValid,
                        when (outcome) {
                            is AzpPluginApplier.Outcome.Applied -> InstallOutcome.Applied(outcome.surface)
                            is AzpPluginApplier.Outcome.Unsupported -> InstallOutcome.NotApplied(outcome.message)
                            // The package landed on disk either way, so this is still an install the user
                            // should be told about — just one where applying it went wrong. A genuine
                            // failure and an "unsupported" both keep their message; see NotApplied.
                            is AzpPluginApplier.Outcome.Failure -> InstallOutcome.Failed(outcome.message)
                        },
                        result.surfaces,
                        models,
                    )
                }
                is AzpHandoffInstaller.InstallResult.WrongHost -> finish(wrongHostMessage(result))
                is AzpHandoffInstaller.InstallResult.Incompatible -> finish(
                    "“${result.name}” needs azphalt ${result.required}, and this build of Guillotine " +
                        "provides ${result.hostVersion}. Nothing was installed.",
                )
                is AzpHandoffInstaller.InstallResult.Failure -> finish(result.message)
                is AzpHandoffInstaller.InstallResult.Untrusted -> {
                    pendingBytes = bytes
                    pendingUntrusted = result.reason
                }
                is AzpHandoffInstaller.InstallResult.PublisherChanged -> {
                    pendingBytes = bytes
                    pendingPublisherChange = result
                }
            }
        }
    }

    /**
     * The deep-link route: resolve the named package against the flagship registry, then hand the bytes
     * to the same [runInstall] every other route uses. A failure here is shown as itself — "paid package",
     * "no such version", "couldn't reach azphalt.store" — rather than the dialog just vanishing.
     */
    fun downloadAndInstall(link: AzpInstallLink) {
        busy = "Downloading…"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { AzphaltRegistry.download(link) } }
            busy = null
            bytes.fold(
                onSuccess = { runInstall(it) },
                onFailure = { finish(it.message ?: "Could not download that package from azphalt.store.") },
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            onDismiss()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                finish("Could not read the package the Azphalt Store handed back.")
            } else {
                runInstall(bytes)
            }
        }
    }

    // Three entry points, one install path. A package handed in from outside is already the thing we'd
    // have gone browsing for: a file is read straight off the device, a deep link asks first and then
    // downloads. With neither, launch into the store app, since there is no Guillotine-owned browsing UI
    // to show first — and no store app installed offers the ways to get one rather than falling back to a
    // catalog Guillotine fetches and renders itself.
    LaunchedEffect(Unit) {
        inventory = withContext(Dispatchers.IO) {
            runCatching { AzpStateReport.inventoryJson(File(extensionsDir), context.packageName) }.getOrNull()
        }
    }

    LaunchedEffect(incoming) {
        if (incoming is AzpExternalOpen.Incoming.File) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(incoming.uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                finish("Could not read that .azp package.")
            } else {
                // A best-effort name for the confirmation dialog only — a malformed or tampered package
                // still gets refused properly by runInstall's own verification once confirmed; a name
                // that can't be read here just falls back to a generic label.
                val name = withContext(Dispatchers.IO) {
                    runCatching { AzpPackage.read(bytes).manifest.name }.getOrNull()
                }
                pendingExternalFile = PendingExternalFile(bytes, name)
            }
        } else if (incoming is AzpExternalOpen.Incoming.Link) {
            pendingLink = incoming.link
        } else {
            showCatalog = true
        }
    }

    // After sending the user to Play, re-check on return: if the store app is now installed, go
    // straight into it. If not, put the same dialog back up — they still have the web store and
    // Cancel in front of them, rather than a second dialog that re-asks the question differently.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingPlayReturn) {
                awaitingPlayReturn = false
                if (AzphaltStoreHandoff.isAvailable(context.packageManager, context.packageName)) {
                    launcher.launch(AzphaltStoreHandoff.browseIntent(context.packageName, inventory))
                } else {
                    showUnavailable = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (showRoutes) {
        AlertDialog(
            onDismissRequest = { showRoutes = false },
            title = { Text("Other ways to add extensions") },
            text = {
                Text(
                    "This browser is Guillotine's own catalog. The web store at azphalt.store is the same " +
                        "catalog with previews; a package downloaded there opens straight back into " +
                        "Guillotine. An installed Azphalt Store app hands over its own list instead.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showRoutes = false
                        openWebStore(context)
                    }) { Text("Browse the web store") }
                    TextButton(onClick = {
                        showRoutes = false
                        if (AzphaltStoreHandoff.isAvailable(context.packageManager, context.packageName)) {
                            launcher.launch(AzphaltStoreHandoff.browseIntent(context.packageName, inventory))
                        } else {
                            showUnavailable = true
                        }
                    }) { Text("Use the Store app") }
                    TextButton(onClick = { showRoutes = false }) { Text("Cancel") }
                }
            },
        )
    }

    if (showUnavailable) {
        AlertDialog(
            onDismissRequest = { showUnavailable = false },
            title = { Text("Azphalt Store app isn't installed") },
            text = {
                Text(
                    "Guillotine's own catalog (the browser behind this) works without it. Only get this " +
                        "if you'd rather use a separate store app's list, or use the web store at " +
                        "azphalt.store instead.",
                )
            },
            // All three routes in one dialog, stacked: the labels are far too wide to sit in a row on
            // a phone, and M3's button flow-row would wrap them into an order nobody chose.
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showUnavailable = false
                        awaitingPlayReturn = true
                        openStoreAppListing(context)
                    }) { Text("Get the app") }
                    TextButton(onClick = {
                        showUnavailable = false
                        openWebStore(context)
                    }) { Text("Use the web store") }
                    TextButton(onClick = { showUnavailable = false }) { Text("Cancel") }
                }
            },
        )
    }

    // A link arrived from a web page nobody asked this app to trust, so it gets a confirmation before any
    // bytes move. The package id is shown verbatim: it's the only thing identifying what's about to be
    // downloaded, and the name inside the package can't be read until after the download.
    pendingLink?.let { link ->
        AlertDialog(
            onDismissRequest = { pendingLink = null; onDismiss() },
            title = { Text("Install from azphalt.store?") },
            text = {
                Text(
                    "A link asked Guillotine to install “${link.id}” " +
                        (link.version?.let { "version $it" } ?: "(latest version)") +
                        " from azphalt.store. It will be downloaded and verified before anything is " +
                        "installed — the link names the package, it doesn't vouch for it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    downloadAndInstall(link)
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { pendingLink = null; onDismiss() }) { Text("Cancel") } },
        )
    }

    // A `.azp` handed in via the exported VIEW intent gets the same treatment as a deep link: some
    // *other* app decided to open this one into Guillotine (a browser download, a file manager, a
    // share sheet), and that app's decision is not the user asking to install anything — see the
    // CRITICAL finding this closes: this route used to call runInstall directly with zero confirmation,
    // while the deep-link route right above it already asked first. Unlike a deep link the bytes are
    // already on-device, so there's nothing to download — only something to confirm before it's verified
    // and written to the extensions dir.
    pendingExternalFile?.let { pending ->
        AlertDialog(
            onDismissRequest = { pendingExternalFile = null; onDismiss() },
            title = {
                Text(pending.name?.let { "Install “$it” from an external app?" } ?: "Install from an external app?")
            },
            text = {
                Text(
                    "This package was opened from outside Guillotine — a browser download, a file " +
                        "manager, or another app's share sheet — not from the in-app Store. It will be " +
                        "verified before anything is installed; being opened doesn't vouch for it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = pending.bytes
                    pendingExternalFile = null
                    runInstall(bytes)
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { pendingExternalFile = null; onDismiss() }) { Text("Cancel") } },
        )
    }

    // Shown after the install lands, and it owns the dismissal: the flow stays on screen until the user
    // has actually read where their extension went, rather than closing out from under the news.
    notice?.let { n ->
        InstalledNoticeDialog(n) { notice = null; onDismiss() }
    }

    busy?.let { message ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(message)
                }
            },
        )
    }

    pendingUntrusted?.let { reason ->
        AlertDialog(
            onDismissRequest = { pendingUntrusted = null; onDismiss() },
            title = { Text("Not from a trusted publisher") },
            text = {
                Text(
                    "This package passed integrity verification, but it's not from a signer this app " +
                        "already trusts ($reason). Install it anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = pendingBytes
                    pendingUntrusted = null
                    if (bytes != null) runInstall(bytes, allowUntrusted = true)
                }) { Text("Install anyway") }
            },
            dismissButton = { TextButton(onClick = { pendingUntrusted = null; onDismiss() }) { Text("Cancel") } },
        )
    }

    pendingPublisherChange?.let {
        AlertDialog(
            onDismissRequest = { pendingPublisherChange = null; onDismiss() },
            title = { Text("Publisher changed") },
            text = {
                Text(
                    "This update was signed by a different publisher key than the version already " +
                        "installed. This could mean a legitimate key rotation — or a hijacked listing. " +
                        "Install this update anyway?",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val bytes = pendingBytes
                    pendingPublisherChange = null
                    if (bytes != null) runInstall(bytes, allowPublisherChange = true)
                }) { Text("Install anyway") }
            },
            dismissButton = { TextButton(onClick = { pendingPublisherChange = null; onDismiss() }) { Text("Cancel") } },
        )
    }

    if (showCatalog) {
        CatalogBrowser(
            hostAppId = context.packageName,
            extensionsDirPath = extensionsDir,
            onInstall = { entry ->
                busy = "Downloading “${entry.name}”…"
                scope.launch {
                    val bytes = withContext(Dispatchers.IO) {
                        runCatching { AzphaltRegistry.download(AzpInstallLink(entry.id, entry.latest)) }
                    }
                    busy = null
                    bytes.fold(
                        onSuccess = { runInstall(it) },
                        onFailure = { finish(it.message ?: "Could not download “${entry.name}” from azphalt.store.") },
                    )
                }
            },
            onOtherRoutes = { showRoutes = true },
            onDismiss = { showCatalog = false; onDismiss() },
        )
    }
}

/**
 * Guillotine's own catalog browser — search + category chips over [AzphaltRegistry.browseAll], each
 * entry a card with an Install button that hands bytes to the caller's [onInstall] (which runs the
 * exact same [AzpHandoffInstaller] verification every other route here does; browsing never earns a
 * package any trust). Fetches once per screen-open and filters entirely client-side (158 packages
 * live today is small enough to hold in memory — see [AzphaltRegistry.browseAll]'s own doc), so
 * typing in the search field or tapping a chip is instant, no re-query.
 *
 * [onOtherRoutes] opens the secondary dialog for the Azphalt Store app / web store — this browser is
 * the default now, not the fallback (see docs/TODO.md, 2026-08-12).
 */
@Composable@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun CatalogBrowser(
    hostAppId: String,
    extensionsDirPath: String,
    onInstall: (AzphaltRegistry.CatalogEntry) -> Unit,
    onOtherRoutes: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var catalog by remember { mutableStateOf<List<AzphaltRegistry.CatalogEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var showGuide by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        installedIds = withContext(Dispatchers.IO) {
            AzpInstalledUi.list(File(extensionsDirPath), hostAppId).map { it.packageId }.toSet()
        }
        val result = withContext(Dispatchers.IO) { runCatching { AzphaltRegistry.browseAll() } }
        result.fold(
            onSuccess = { catalog = it },
            onFailure = { loadError = it.message ?: "Couldn't reach azphalt.store." },
        )
    }

    val entries = catalog
    val filtered = remember(entries, query, category, installedIds) {
        entries?.filter { e ->
            e.targetsApp(hostAppId) &&
                // Browsing shouldn't offer an "Install" button for something that can only ever land
                // on AzpInstallSurfaces.Surface.NONE (code/app/mcp/pack packages — nothing in this
                // build applies them to anything). See AzpInstallSurfaces.hasKnownConsumer.
                AzpInstallSurfaces.hasKnownConsumer(e.types) &&
                (category == null || e.category == category) &&
                (query.isBlank() || e.name.contains(query, ignoreCase = true) || e.description.contains(query, ignoreCase = true))
        }?.sortedWith(compareBy({ it.id !in installedIds }, { it.name }))
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Extensions", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { showGuide = true }) {
                        Icon(Icons.Filled.HelpOutline, contentDescription = "What's in the store")
                    }
                    IconButton(onClick = onOtherRoutes) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "Other ways to add extensions")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    placeholder = { Text("Search shaders, LUTs, caption styles…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                )
                Row(
                    Modifier.fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    CATEGORY_CHIPS.forEach { (label, value) ->
                        AssistChip(
                            onClick = { category = if (category == value) null else value },
                            label = { Text(label) },
                            colors = if (category == value) {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors(
                                    containerColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                    labelColor = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                androidx.compose.material3.AssistChipDefaults.assistChipColors()
                            },
                        )
                    }
                }
                when {
                    loadError != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(loadError ?: "", color = Neutral400)
                            TextButton(onClick = onOtherRoutes) { Text("Use another way to add extensions") }
                        }
                    }
                    entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    filtered.isNullOrEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No extensions match.", color = Neutral400)
                    }
                    else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                        items(filtered, key = { it.id }) { entry ->
                            CatalogEntryCard(entry, installed = entry.id in installedIds, onInstall = { onInstall(entry) })
                            Spacer(Modifier.width(0.dp).padding(vertical = 4.dp))
                        }
                    }
                }
            }
        }
    }

    if (showGuide) {
        AzphaltStoreGuideDialog(onDismiss = { showGuide = false })
    }
}

/**
 * (label shown, filter value used against [AzphaltRegistry.CatalogEntry.category]; null = All).
 * Apps/MCP/Packs/Skills are deliberately absent — those kinds always fail
 * [AzpInstallSurfaces.hasKnownConsumer] (nothing in this build applies them to anything), so [filtered]
 * already excludes every entry a tap on one of those chips would have shown; keeping a chip that can
 * only ever return zero results is worse than not offering it.
 */
private val CATEGORY_CHIPS = listOf(
    "All" to null,
    "LUTs" to "lut",
    "Shaders" to "shader",
    "Kinetic type" to "motion",
    "AI models" to "onnx",
)

/**
 * A live preview of what this listing's LUT/shader actually looks like — the app's own icon run
 * through the real (not-yet-installed) effect asset, not a generic category icon. Lazily rendered
 * off the main thread and cached (see [AzpStorePreviewRenderer]); silently absent (falls back to
 * nothing, not a placeholder image) while loading, on a network/parse failure, or for asset types
 * with no still-image render path (motion, AI models).
 */
@Composable
private fun CatalogEntryPreviewThumbnail(entry: AzphaltRegistry.CatalogEntry) {
    val context = LocalContext.current
    val preview by produceState<ImageBitmap?>(null, entry.id, entry.latest) {
        value = AzpStorePreviewRenderer.render(context, entry)
    }
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        preview?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun CatalogEntryCard(entry: AzphaltRegistry.CatalogEntry, installed: Boolean, onInstall: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CatalogEntryPreviewThumbnail(entry)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, fontWeight = FontWeight.Bold)
                if (entry.description.isNotBlank()) {
                    Text(entry.description, color = Neutral400, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(
                        entry.category.replaceFirstChar { it.uppercase() },
                        if (entry.isFree) "Free" else "Paid",
                        entry.maturity.takeIf { it != "general" }?.replaceFirstChar { it.uppercase() },
                    ).joinToString(" · "),
                    color = Neutral500,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (installed) {
                Text("Installed", color = Neutral500)
            } else {
                Button(onClick = onInstall) { Text(if (entry.isFree) "Install" else "Install (paid)") }
            }
        }
    }
}

/**
 * A `.azp` handed to Guillotine via the exported VIEW intent, waiting on the user's explicit
 * confirmation before [AzpHandoffInstaller] ever sees its bytes. [name] is a best-effort manifest read
 * for the confirmation dialog only — null when the bytes couldn't even be parsed that far, in which
 * case the dialog falls back to a generic label and the real verification (and its real error message)
 * happens once the user confirms.
 */
private data class PendingExternalFile(val bytes: ByteArray, val name: String?)

/** What happened to a freshly installed package beyond landing on disk. */
private sealed interface InstallOutcome {
    /** Installed and applied to the clip that was selected, at [surface]. */
    data class Applied(val surface: AzpInstallSurfaces.Surface) : InstallOutcome

    /** Installed, but nothing was selected to apply it to. */
    object NothingSelected : InstallOutcome

    /** Installed, but applying it to the selected clip failed outright — [reason] is a real error. */
    data class Failed(val reason: String) : InstallOutcome

    /**
     * Installed, but not applied to what was selected. [reason] comes from the applier and IS shown: the
     * one message that made this untrustworthy — "needs the extension runtime" for a motion package on a
     * video clip — was fixed at its source rather than swallowed here, and dropping every reason took
     * true ones with it (a remotely-hosted asset explains itself, and nothing else would).
     */
    data class NotApplied(val reason: String) : InstallOutcome
}

/** What became of a model package's actual model files, beyond the `.azp` landing on disk. */
private sealed interface ModelOutcome {
    /** [count] models extracted into the models dir; [routed] of them matched a settings slot. */
    data class Installed(val count: Int, val routed: Int) : ModelOutcome

    /** The package installed but its models did not — [reason] is the real error, not a shrug. */
    data class Failed(val reason: String) : ModelOutcome
}

/** A completed install, and everything the user needs told about it. */
private data class InstalledNotice(
    val name: String,
    val signed: Boolean,
    val signatureValid: Boolean,
    val outcome: InstallOutcome,
    /** Where this particular package turns up — derived from its payload, not assumed. */
    val surfaces: List<AzpInstallSurfaces.Surface>,
    /** Non-null only for a package carrying models: what happened to them. */
    val models: ModelOutcome? = null,
)

/**
 * Where to find [surface], in the user's words, and whether *this* surface is the one that got applied.
 *
 * Every place named here was checked against what the app actually renders, twice, because the first
 * two attempts both named things that aren't there:
 *
 * - The asset panel is **not** called "Extensions" on screen. [AzpAssetContribution] declares that as its
 *   `title`, but [ClipPanelHost] never reads `title` — it calls `Content` directly and the section is
 *   drawn by [ClipPanelSection] with the *package's own name*.
 * - Nor is the containing panel reliably "Clip Properties". [AdvancedToolView] titles it that under the
 *   Select tool, but [InlineClipTools] also reaches it under Marquee, where the heading reads "Marquee
 *   Selection" — and the panel can be dragged shut entirely, in which case there is no heading at all.
 *   So the copy says "the clip panel", which is what it is under either tool.
 * - Only "Kinetic type" is quoted as a literal heading, because the motion picker really does pass that
 *   string to [ClipPanelSection].
 *
 * Controls are not promised either: a package with no `ui` schema renders an empty control area, so the
 * shader/LUT text mentions sliders as conditional rather than as something to go looking for.
 */
private fun whereToFind(
    surface: AzpInstallSurfaces.Surface,
    appliedHere: Boolean,
    models: ModelOutcome? = null,
): String = when (surface) {
    AzpInstallSurfaces.Surface.CLIP_EXTENSIONS ->
        if (appliedHere) {
            "With that clip selected, the clip panel now has a section named after it, with a Remove " +
                "button — and sliders for whatever the package chose to expose, which for a plain LUT is " +
                "nothing."
        } else {
            "Select a clip and the clip panel gets a section named after it, with a button to apply it " +
                "to that clip."
        }
    AzpInstallSurfaces.Surface.CAPTION_MOTION ->
        if (appliedHere) {
            "With that caption selected, it's under “Kinetic type” in the clip panel, where you can " +
                "switch or clear the animation."
        } else {
            "Select a caption — a text clip, not a video one — and it's listed under “Kinetic type” in " +
                "the clip panel. Tapping it animates that caption."
        }
    // This route used to stop at the .azp and tell the user to go and re-install it from Settings with a
    // file they might not even have. It now runs the same model install Settings does, so this says what
    // actually happened rather than handing over a chore.
    AzpInstallSurfaces.Surface.AI_MODEL -> when (models) {
        is ModelOutcome.Installed -> {
            val n = models.count
            val unrouted = n - models.routed
            "It carries ${if (n == 1) "an on-device AI model" else "$n on-device AI models"}, and " +
                (if (n == 1) "it's" else "they're") + " installed and ready — the editor picks " +
                (if (n == 1) "it" else "them") + " up automatically. " +
                if (unrouted > 0) {
                    "$unrouted of them didn't match a known settings slot, so you may need to point at " +
                        "${if (unrouted == 1) "it" else "them"} under Settings → Advanced."
                } else {
                    "You can see the slots under Settings → Advanced."
                }
        }
        is ModelOutcome.Failed ->
            "It carries an on-device AI model, but installing the model itself failed: ${models.reason}. " +
                "The package is saved; Settings → Advanced → Install AI model can retry it from the " +
                "original `.azp` file."
        null ->
            "It carries an on-device AI model. Settings → Advanced → Install AI model installs models " +
                "from a `.azp` file."
    }
    AzpInstallSurfaces.Surface.LISTED_NOT_APPLICABLE ->
        "It's listed in the clip panel, in a section named after it, whenever a clip is selected — but " +
            "Guillotine has no renderer for this asset type, so there's nothing to apply to a clip."
    AzpInstallSurfaces.Surface.NONE ->
        "Nothing in this build surfaces it yet: code extensions need the WASM sandbox (not shipped), and " +
            "companion-app, MCP and pack packages have no consumer here. It's saved, and it'll be picked " +
            "up when that lands."
}

/**
 * The post-install disclosure: **what** was installed, **whether it's applied**, and — the part that was
 * missing entirely — **where to find it**. An extension that installs successfully and then can't be
 * located is indistinguishable, from the user's side, from one that didn't install.
 *
 * azphalt's `spec/web-handoff.md` § Open questions names this gap from the ecosystem side: state
 * reporting "covers the statistic but not *show the user what they just installed*". That's a host's job
 * — no store app or registry can point at a surface inside this editor — so it's answered here.
 *
 * Provenance is restated rather than assumed: [InstalledNotice.signatureValid] means the Ed25519
 * signature actually verified, [InstalledNotice.signed] only means the package carried one. Saying
 * "signed" for the second would claim a check that didn't happen.
 */
@Composable
private fun InstalledNoticeDialog(notice: InstalledNotice, onDismiss: () -> Unit) {
    val provenance = when {
        notice.signatureValid -> "Signature verified."
        notice.signed -> "It carries a signature this device couldn't verify — integrity is confirmed, provenance isn't."
        else -> "It's unsigned: integrity is confirmed, provenance isn't."
    }
    // What happened to *this* install, then where the package lives. The second half is per-package: a
    // shader, a caption animation and a model all install the same way and then appear in three unrelated
    // places, so the surfaces come from the manifest rather than one sentence covering everything.
    val what = when (val outcome = notice.outcome) {
        is InstallOutcome.Applied -> "It's applied to the selected clip."
        is InstallOutcome.NothingSelected -> "Nothing was selected, so it isn't applied to anything yet."
        is InstallOutcome.NotApplied -> outcome.reason
        is InstallOutcome.Failed -> outcome.reason
    }
    // Per surface, not per install: a mixed package can reach two surfaces while only one of them was
    // applied, and saying "its controls are on the selected clip" about the other one would be a lie.
    val appliedSurface = (notice.outcome as? InstallOutcome.Applied)?.surface
    val where = notice.surfaces.joinToString("\n\n") { whereToFind(it, it == appliedSurface, notice.models) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (appliedSurface != null) "Applied “${notice.name}”" else "Installed “${notice.name}”") },
        // Scrollable: a mixed package reaches more than one surface, and this is the one dialog whose
        // text must not be cut off on a short screen — it's the only place the destination is stated.
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(listOf(what, where, provenance).filter { it.isNotBlank() }.joinToString("\n\n"))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}

/**
 * A package built for a different host, refused per azphalt `spec/web-handoff.md` § Host obligations (5):
 * a host absent from a non-empty `targetApps` MUST refuse and say so. Naming the hosts it *is* for turns
 * a dead end into something the user can act on.
 */
private fun wrongHostMessage(result: AzpHandoffInstaller.InstallResult.WrongHost): String =
    "“${result.name}” isn't built for Guillotine — it targets ${result.targetApps.joinToString(", ")}. " +
        "Nothing was installed."

/** Play Store listing for the reference Azphalt Store app, falling back to its web page if the Play Store app itself isn't present. */
private fun openStoreAppListing(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${AzphaltStoreHandoff.STORE_APP_PACKAGE}")))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${AzphaltStoreHandoff.STORE_APP_PACKAGE}")),
        )
    }
}

/**
 * Opens the azphalt.store web storefront — itself an installable PWA. If the user already added it
 * to their home screen (a Chrome WebAPK), this launches that standalone app directly instead of a
 * plain browser tab; otherwise it opens normally, and the browser's own install prompt (from the
 * site's manifest + service worker) is how a first-time visitor gets that same shortcut.
 */
private fun openWebStore(context: Context, categoryKey: String? = null) {
    val url = if (categoryKey != null) "${AzphaltTrust.STORE_WEB_URL}?category=$categoryKey" else AzphaltTrust.STORE_WEB_URL
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    AzphaltStoreHandoff.installedWebApkPackage(context.packageManager, url)
        ?.let { intent.setPackage(it) }
    context.startActivity(intent)
}
private data class AzphaltCategoryInfo(
    val key: String,
    val displayName: String,
    val description: String,
    val example: String,
)

private val AZPHALT_CATEGORIES = listOf(
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

@Composable
private fun AzphaltStoreGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
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
                        info.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        info.description,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Try: " + info.example,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { openWebStore(context, info.key) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Browse " + info.displayName)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}
