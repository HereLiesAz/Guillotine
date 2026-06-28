package com.hereliesaz.guillotine.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd

/** App-open ad unit. */
private const val AD_UNIT_ID = "ca-app-pub-7304740804770627/7759775364"

/** App-open ads expire ~4 hours after load; reload past that. */
private const val AD_TIMEOUT_MS = 4 * 60 * 60 * 1000L

/**
 * Loads and shows an AdMob **App Open** ad when the app is brought to the foreground (cold
 * start and resume), following Google's recommended AppOpenAdManager pattern: it tracks the
 * current Activity via [Application.ActivityLifecycleCallbacks] and shows the ad on the
 * process's `ON_START` (foreground) — never over another full-screen ad, and only when one is
 * loaded and not yet expired. The first cold start usually has no ad ready, so the ad typically
 * appears from the next foreground onward.
 */
class AppOpenAdManager(private val application: Application) :
    Application.ActivityLifecycleCallbacks, DefaultLifecycleObserver {

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var loadTimeMs = 0L
    private var currentActivity: Activity? = null

    /** Track activity/foreground early (Application.onCreate). Does not request ads yet. */
    fun register() {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /** Begin requesting ads — call after consent is resolved and MobileAds is initialized. */
    fun startLoading() {
        loadAd()
    }

    private fun isAdAvailable(): Boolean =
        appOpenAd != null && (System.currentTimeMillis() - loadTimeMs) < AD_TIMEOUT_MS

    private fun loadAd() {
        if (!AdsState.ready.value || isLoadingAd || isAdAvailable()) return
        isLoadingAd = true
        AppOpenAd.load(
            application,
            AD_UNIT_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAd = false
                    loadTimeMs = System.currentTimeMillis()
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                }
            },
        )
    }

    private fun showAdIfAvailable() {
        if (isShowingAd) return
        // Frequency cap: no more than one full-screen ad every 5 minutes (shared with the render
        // interstitial). When gated, keep the loaded ad for the next eligible foreground.
        if (!FullScreenAdGate.canShow()) return
        val activity = currentActivity ?: return
        val ad = appOpenAd
        if (ad == null || !isAdAvailable()) {
            loadAd()
            return
        }
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                loadAd() // preload the next one
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                appOpenAd = null
                isShowingAd = false
                loadAd()
            }

            override fun onAdShowedFullScreenContent() {
                isShowingAd = true
                FullScreenAdGate.markShown()
            }
        }
        ad.show(activity)
    }

    // --- foreground detection ---
    override fun onStart(owner: LifecycleOwner) {
        showAdIfAvailable()
    }

    // --- current-activity tracking (don't switch target while an ad is showing) ---
    override fun onActivityStarted(activity: Activity) {
        if (!isShowingAd) currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        if (!isShowingAd) currentActivity = activity
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        // Drop the reference to a destroyed activity so we never hold (leak) a dead Activity
        // or try to show an ad over one.
        if (currentActivity === activity) currentActivity = null
    }
}
