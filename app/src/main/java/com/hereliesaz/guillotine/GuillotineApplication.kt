package com.hereliesaz.guillotine

import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.hereliesaz.guillotine.ads.AdsState
import com.hereliesaz.guillotine.ads.AppOpenAdManager
import com.hereliesaz.guillotine.ads.InterstitialAdManager
import com.hereliesaz.guillotine.crash.CrashReporter

/**
 * Application entry point. Installs the crash reporter, flushes any crash captured on the
 * previous run to the configured relay (which opens a GitHub issue), and sets up AdMob.
 *
 * Ad requests are gated on consent: the ad managers only track lifecycle here; actual loading
 * starts via [startAdsAfterConsent], called from the Activity once UMP consent is resolved.
 */
class GuillotineApplication : Application() {
    lateinit var appOpenAdManager: AppOpenAdManager
        private set
    lateinit var interstitialAdManager: InterstitialAdManager
        private set

    private var adsStarted = false

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CrashReporter.flushPending(this)
        appOpenAdManager = AppOpenAdManager(this).also { it.register() }
        interstitialAdManager = InterstitialAdManager(AdsState.RENDER_INTERSTITIAL_UNIT)
    }

    /** Initialize the Ads SDK and begin loading (idempotent). Call only after consent is resolved. */
    fun startAdsAfterConsent() {
        if (adsStarted) return
        adsStarted = true
        MobileAds.initialize(this) {
            AdsState.ready.value = true
            appOpenAdManager.startLoading()
            interstitialAdManager.load(this)
        }
    }
}
