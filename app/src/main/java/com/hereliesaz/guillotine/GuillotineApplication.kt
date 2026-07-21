package com.hereliesaz.guillotine

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.hereliesaz.guillotine.ads.AdsState
import com.hereliesaz.guillotine.ads.AppOpenAdManager
import com.hereliesaz.guillotine.ads.InterstitialAdManager
import com.hereliesaz.guillotine.crash.CrashReporter
import com.hereliesaz.guillotine.ui.ClipPanelContributions
import com.hereliesaz.guillotine.ui.KineticTypographyContribution

/**
 * Application entry point. Installs the crash reporter, flushes any crash captured on the
 * previous run to the configured relay (which opens a GitHub issue), and sets up AdMob.
 *
 * Ads exist only in the `play` distribution ([BuildConfig.ADS_ENABLED]); the `github` build has no
 * ad SDK activity at all — the managers are never created, so the whole ad path is inert there.
 * Where ads are enabled, requests are still gated on consent: the managers only track lifecycle
 * here; actual loading starts via [startAdsAfterConsent], called from the Activity once UMP consent
 * is resolved. Both manager fields are nullable so the rest of the app can null-safely no-op them.
 */
class GuillotineApplication : Application() {
    var appOpenAdManager: AppOpenAdManager? = null
        private set
    var interstitialAdManager: InterstitialAdManager? = null
        private set

    private var adsStarted = false

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CrashReporter.flushPending(this)
        // Register the built-in clip-properties-panel contributions (the plugin host seam). Azphalt
        // UI-schema sections will register here too once that runtime lands — see docs/PLUGIN_PANELS.md.
        ClipPanelContributions.register(KineticTypographyContribution())
        if (BuildConfig.ADS_ENABLED) {
            appOpenAdManager = AppOpenAdManager(this).also { it.register() }
            interstitialAdManager = InterstitialAdManager(AdsState.RENDER_INTERSTITIAL_UNIT)
        }
    }

    /** Initialize the Ads SDK and begin loading (idempotent). Call only after consent is resolved. */
    fun startAdsAfterConsent() {
        if (!BuildConfig.ADS_ENABLED || adsStarted) return
        adsStarted = true
        MobileAds.initialize(this) {
            AdsState.ready.value = true
            appOpenAdManager?.startLoading()
            interstitialAdManager?.load(this)
        }
    }
}
