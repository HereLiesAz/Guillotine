package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.update.ReleaseAsset
import com.hereliesaz.guillotine.update.UpdateChecker
import com.hereliesaz.guillotine.update.UpdateVerification
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.util.Properties

/**
 * Self-update for the desktop installers. Compares this build's version (baked into the classpath as
 * `/guillotine-version.properties` by the Gradle `writeVersionResource` task) against the host-OS
 * installer attached to the latest GitHub Release — `.deb` on Linux, `.dmg` on macOS, `.msi` on
 * Windows. When newer, it downloads the installer to a temp dir and launches it through the desktop's
 * file association (jpackage's per-user MSI / the DMG / the .deb software-installer GUI). Every path
 * fails soft: a failed check must never disturb the editor.
 */
object DesktopUpdater {

    /** A newer host-OS installer available to run. */
    data class Available(
        val version: String,
        val notes: String,
        val htmlUrl: String,
        val asset: ReleaseAsset,
    )

    /** This build's version, read from the generated resource; "0" when absent (e.g. a raw `:desktop:run`). */
    val currentVersion: String by lazy {
        runCatching {
            DesktopUpdater::class.java.getResourceAsStream("/guillotine-version.properties")?.use { stream ->
                Properties().apply { load(stream) }.getProperty("version", "0")
            } ?: "0"
        }.getOrDefault("0")
    }

    /** The installer-file suffix for the host OS, or null if we don't recognise it. */
    fun installerSuffix(): String? {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> ".msi"
            os.contains("mac") || os.contains("darwin") -> ".dmg"
            os.contains("nux") || os.contains("nix") || os.contains("aix") -> ".deb"
            else -> null
        }
    }

    /** The latest release's host-OS installer, if strictly newer than this build; else null. */
    fun check(): Available? {
        val suffix = installerSuffix() ?: return null
        val release = UpdateChecker.fetchLatest() ?: return null
        val asset = release.asset { it.endsWith(suffix, ignoreCase = true) } ?: return null
        val latest = UpdateChecker.versionIn(asset.name) ?: return null
        return if (UpdateChecker.isNewer(current = currentVersion, latest = latest)) {
            Available(latest, release.notes, release.htmlUrl, asset)
        } else {
            null
        }
    }

    /**
     * Download the installer into a temp dir (cleared first), reporting progress 0..1.
     *
     * Returns the file only if it passes [UpdateChecker.verify]. A file that fails is deleted rather
     * than left in the temp dir, because the next run clears that dir *before* downloading and a
     * lingering bad installer is exactly the thing this is meant to keep away from the OS.
     */
    fun download(a: Available, onProgress: (Float) -> Unit): File? {
        val dir = File(System.getProperty("java.io.tmpdir"), "guillotine-update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val dest = File(dir, a.asset.name.ifBlank { "guillotine-installer${installerSuffix() ?: ""}" })
        val ok = UpdateChecker.download(a.asset.downloadUrl, dest) { read, total ->
            if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
        }
        if (!ok) return null
        return when (val v = UpdateChecker.verify(dest, a.asset)) {
            is UpdateVerification.Failed -> {
                lastVerification = v
                dest.delete()
                null
            }
            else -> {
                lastVerification = v
                dest
            }
        }
    }

    /**
     * How the last [download] verified, for the UI to report. `SizeOnly` means the release published no
     * digest — the caller may install, but must not claim the download was verified.
     */
    @Volatile
    var lastVerification: UpdateVerification? = null
        private set

    /** Launch the downloaded installer via the OS file association. Returns true if it launched. */
    fun launchInstaller(file: File): Boolean = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            Desktop.getDesktop().open(file)
            true
        } else {
            false
        }
    }.getOrDefault(false)

    /** Open [url] in the default browser — the fallback when we can't launch the installer directly. */
    fun openInBrowser(url: String): Boolean = runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
