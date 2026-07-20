package com.hereliesaz.guillotine.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.hereliesaz.guillotine.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Self-update for the direct-download (github) build. Compares this build's [BuildConfig.VERSION_NAME]
 * against the `.apk` asset attached to the latest GitHub Release; if newer, downloads it and hands it
 * to the system package installer. The Play build never uses this (Play policy forbids self-updating
 * APKs, and Play delivers its own updates). All network work runs off the main thread and fails soft.
 */
object AndroidUpdater {

    /** A newer release available to install. */
    data class Available(
        val version: String,
        val notes: String,
        val htmlUrl: String,
        val asset: ReleaseAsset,
    )

    /** The latest release's APK, if it is strictly newer than this build; else null. */
    suspend fun check(): Available? = withContext(Dispatchers.IO) {
        val release = UpdateChecker.fetchLatest() ?: return@withContext null
        val apk = release.asset { it.endsWith(".apk", ignoreCase = true) } ?: return@withContext null
        val latest = UpdateChecker.versionIn(apk.name) ?: return@withContext null
        if (UpdateChecker.isNewer(current = BuildConfig.VERSION_NAME, latest = latest)) {
            Available(latest, release.notes, release.htmlUrl, apk)
        } else {
            null
        }
    }

    /**
     * Download [a]'s APK into `cacheDir/updates` (cleared first so old APKs don't pile up), reporting
     * progress in 0..1. Returns the downloaded file, or null on failure.
     */
    suspend fun download(context: Context, a: Available, onProgress: (Float) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val dest = File(dir, a.asset.name.ifBlank { "guillotine-update.apk" })
            val ok = UpdateChecker.download(a.asset.downloadUrl, dest) { read, total ->
                if (total > 0) onProgress((read.toFloat() / total).coerceIn(0f, 1f))
            }
            if (ok) dest else null
        }

    /** True if the OS will let us launch a package-install intent without first visiting Settings. */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Open the system "install unknown apps" screen for this app so the user can grant the permission. */
    fun requestInstallPermission(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** Hand [file] to the system package installer via the update FileProvider. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
