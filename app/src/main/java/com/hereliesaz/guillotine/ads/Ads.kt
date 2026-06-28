package com.hereliesaz.guillotine.ads

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/** AdMob ad unit ids and the global "ads may run" flag (true once consent is resolved + SDK init). */
object AdsState {
    const val BANNER_UNIT = "ca-app-pub-7304740804770627/3621219803"
    const val RENDER_INTERSTITIAL_UNIT = "ca-app-pub-7304740804770627/6351366489"

    /** Flips true after consent is gathered and MobileAds is initialized; gates ad requests. */
    val ready = mutableStateOf(false)
}

/**
 * Shared frequency cap for the full-screen ("big") ads — the render interstitial and the app-open
 * ad. At most one full-screen ad is shown per [MIN_INTERVAL_MS]; when gated, the ad stays preloaded
 * for the next eligible moment. Uses [SystemClock.elapsedRealtime] so it's immune to wall-clock
 * changes; it resets on process restart (ads reload then anyway).
 */
object FullScreenAdGate {
    private const val MIN_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    @Volatile private var lastShownAtMs = 0L

    /** True if enough time has passed since the last full-screen ad was shown. */
    fun canShow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return lastShownAtMs == 0L || now - lastShownAtMs >= MIN_INTERVAL_MS
    }

    /** Record that a full-screen ad just displayed (call from onAdShowedFullScreenContent). */
    fun markShown() { lastShownAtMs = SystemClock.elapsedRealtime() }
}

/**
 * Google UMP (User Messaging Platform) consent gate. AdMob's "Privacy & messaging" form is
 * configured in the AdMob console; this requests the latest consent info and shows the form when
 * required (e.g. in the EEA/UK), then reports whether ads may be requested.
 */
class ConsentManager(context: Context) {
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(context)

    /** True once the user has given (or isn't required to give) consent for ad requests. */
    val canRequestAds: Boolean get() = info.canRequestAds()

    /** Update consent info and show the form if required; [onComplete] always runs afterwards. */
    fun gatherConsent(activity: Activity, onComplete: () -> Unit) {
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity,
            params,
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { onComplete() } },
            { onComplete() }, // on failure, proceed (canRequestAds reflects current state)
        )
    }
}

/**
 * The "render" interstitial: a full-screen ad shown when the user starts an export, covering the
 * initial render wait. Preloaded after consent; reloads itself after each show.
 */
class InterstitialAdManager(private val adUnitId: String) {
    private var ad: InterstitialAd? = null
    private var loading = false

    fun load(context: Context) {
        if (loading || ad != null || !AdsState.ready.value) return
        loading = true
        InterstitialAd.load(
            context,
            adUnitId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(loaded: InterstitialAd) { ad = loaded; loading = false }
                override fun onAdFailedToLoad(error: LoadAdError) { ad = null; loading = false }
            },
        )
    }

    /** Show the interstitial if one is ready; otherwise just preload for next time. */
    fun show(activity: Activity) {
        // Frequency cap: no more than one full-screen ad every 5 minutes (shared with the app-open
        // ad). When gated, keep one preloaded for the next eligible show.
        if (!FullScreenAdGate.canShow()) { if (ad == null) load(activity); return }
        val current = ad
        if (current == null) { load(activity); return }
        current.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() { FullScreenAdGate.markShown() }
            override fun onAdDismissedFullScreenContent() { ad = null; load(activity) }
            override fun onAdFailedToShowFullScreenContent(e: AdError) { ad = null; load(activity) }
        }
        current.show(activity)
    }
}

/** Bottom banner ad (adaptive width). Renders nothing until ads are ready (post-consent). */
@Composable
fun BannerAd(modifier: Modifier = Modifier, adUnitId: String = AdsState.BANNER_UNIT) {
    if (!AdsState.ready.value) return
    val widthDp = LocalConfiguration.current.screenWidthDp
    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { ctx ->
            AdView(ctx).apply {
                setAdSize(AdSize.getCurrentOrientationAnchoredAdaptiveBannerAdSize(ctx, widthDp))
                this.adUnitId = adUnitId
                loadAd(AdRequest.Builder().build())
            }
        },
        // Destroy the native AdView when the composable leaves composition — otherwise the
        // ad view (and its WebView) leaks every time this surface is torn down.
        onRelease = { it.destroy() },
    )
}
