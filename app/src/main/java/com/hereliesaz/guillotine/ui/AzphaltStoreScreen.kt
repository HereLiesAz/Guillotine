package com.hereliesaz.guillotine.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hereliesaz.guillotine.azphalt.AzpExternalOpen
import com.hereliesaz.guillotine.azphalt.AzpHandoffInstaller
import com.hereliesaz.guillotine.azphalt.AzpInstallLink
import com.hereliesaz.guillotine.azphalt.AzpInstallSurfaces
import com.hereliesaz.guillotine.azphalt.AzphaltRegistry
import com.hereliesaz.guillotine.azphalt.AzphaltStoreHandoff
import com.hereliesaz.guillotine.azphalt.AzphaltTrust
import com.hereliesaz.guillotine.azphalt.AzpPublisherPins
import com.hereliesaz.guillotine.editor.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Extensions come from the **Azphalt Store**, not from a browsing UI Guillotine builds and maintains
 * itself. This delegates browsing and acquiring to whichever Azphalt Store app is installed, over the
 * acquisition handoff azphalt `spec/store-app.md` specifies: launch `store.azphalt.action.BROWSE` for
 * result, and the store app returns a package it has already fetched and checked as a content URI.
 *
 * Guillotine still re-verifies every byte it gets back through [AzpHandoffInstaller] — the spec is
 * explicit that a store app is a convenience, never a trust anchor ("a lying store app gains nothing")
 * — and still owns applying an installed package to the timeline, the one part no store app can do on
 * its behalf.
 *
 * [incoming] is the other way in: a package handed to Guillotine from outside the app and routed here by
 * [AzpExternalOpen]. `spec/store-app.md` only specifies the Android app-to-app handoff and explicitly
 * leaves the web case unspecified, so this is the host half of it, in two forms —
 * [AzpExternalOpen.Incoming.File] for bytes already on the device (a browser download, a file manager, a
 * share sheet) and [AzpExternalOpen.Incoming.Link] for an `azphalt://install` deep link naming a package
 * for Guillotine to fetch from the registry itself. Both converge on the same [runInstall]; a link is
 * untrusted input like everything else here, so nothing skips verification because a web page vouched for
 * it. When [incoming] is non-null there is nothing to browse, so the store app is never launched.
 */
@Composable
fun AzphaltStoreScreen(vm: EditorViewModel, incoming: AzpExternalOpen.Incoming? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val extensionsDir = remember { File(context.filesDir, "extensions").absolutePath }
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
                    val clipId = vm.uiState.value.selectedClipIds.firstOrNull()
                    if (clipId == null) {
                        notice = InstalledNotice(result.name, result.signed, result.signatureValid, InstallOutcome.NothingSelected, result.surfaces)
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
                    )
                }
                is AzpHandoffInstaller.InstallResult.WrongHost -> finish(wrongHostMessage(result))
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
    LaunchedEffect(incoming) {
        if (incoming is AzpExternalOpen.Incoming.File) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(incoming.uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) finish("Could not read that .azp package.") else runInstall(bytes)
        } else if (incoming is AzpExternalOpen.Incoming.Link) {
            pendingLink = incoming.link
        } else if (AzphaltStoreHandoff.isAvailable(context.packageManager, context.packageName)) {
            showRoutes = true
        } else {
            showUnavailable = true
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
                    launcher.launch(AzphaltStoreHandoff.browseIntent(context.packageName))
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
            onDismissRequest = { showRoutes = false; onDismiss() },
            title = { Text("Add an extension") },
            text = {
                Text(
                    "The web store at azphalt.store is the full marketplace — search, categories, " +
                        "previews. Downloading a package there opens it straight back into Guillotine. " +
                        "The Store app is quicker but hands over a plain list.",
                )
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = {
                        showRoutes = false
                        openWebStore(context)
                        onDismiss()
                    }) { Text("Browse the web store") }
                    TextButton(onClick = {
                        showRoutes = false
                        launcher.launch(AzphaltStoreHandoff.browseIntent(context.packageName))
                    }) { Text("Use the Store app") }
                    TextButton(onClick = { showRoutes = false; onDismiss() }) { Text("Cancel") }
                }
            },
        )
    }

    if (showUnavailable) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Store isn't installed") },
            text = {
                Text(
                    "Extensions — shaders, LUTs, caption styles, companion apps — come from the Azphalt " +
                        "Store. Install the app to browse and add them, or use the web store at " +
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
                        onDismiss()
                    }) { Text("Use the web store") }
                    TextButton(onClick = { showUnavailable = false; onDismiss() }) { Text("Cancel") }
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
}

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

/** A completed install, and everything the user needs told about it. */
private data class InstalledNotice(
    val name: String,
    val signed: Boolean,
    val signatureValid: Boolean,
    val outcome: InstallOutcome,
    /** Where this particular package turns up — derived from its payload, not assumed. */
    val surfaces: List<AzpInstallSurfaces.Surface>,
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
private fun whereToFind(surface: AzpInstallSurfaces.Surface, appliedHere: Boolean): String = when (surface) {
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
    AzpInstallSurfaces.Surface.AI_MODEL ->
        "This package carries an on-device AI model, and installing it here only saved the package — it " +
            "does not put the model to use. That's a separate flow, Settings → Advanced → Install AI " +
            "model, and it picks the `.azp` file itself, so you'll need the file: if it came from a " +
            "link rather than a download, download it from azphalt.store first."
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
    val where = notice.surfaces.joinToString("\n\n") { whereToFind(it, appliedHere = it == appliedSurface) }
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
private fun openWebStore(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AzphaltTrust.STORE_WEB_URL))
    AzphaltStoreHandoff.installedWebApkPackage(context.packageManager, AzphaltTrust.STORE_WEB_URL)
        ?.let { intent.setPackage(it) }
    context.startActivity(intent)
}
