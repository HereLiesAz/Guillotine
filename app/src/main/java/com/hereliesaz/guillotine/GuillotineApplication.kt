package com.hereliesaz.guillotine

import android.app.Application
import com.hereliesaz.guillotine.crash.CrashReporter
import com.hereliesaz.guillotine.ui.AzpAssetContribution
import com.hereliesaz.guillotine.ui.ClipPanelContributions
import com.hereliesaz.guillotine.ui.KineticTypographyContribution

/**
 * Application entry point. Installs the crash reporter and flushes any crash captured on the
 * previous run to the configured relay (which opens a GitHub issue).
 */
class GuillotineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CrashReporter.flushPending(this)
        // Register the built-in clip-properties-panel contributions (the plugin host seam). Azphalt
        // UI-schema sections will register here too once that runtime lands — see docs/PLUGIN_PANELS.md.
        ClipPanelContributions.register(KineticTypographyContribution())
        ClipPanelContributions.register(AzpAssetContribution())
    }
}
