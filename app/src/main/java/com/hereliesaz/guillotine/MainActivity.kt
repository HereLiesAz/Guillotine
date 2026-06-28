package com.hereliesaz.guillotine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hereliesaz.guillotine.ads.ConsentManager
import com.hereliesaz.guillotine.ui.NleScreen
import com.hereliesaz.guillotine.ui.theme.GuillotineTheme

class MainActivity : ComponentActivity() {
    // POST_NOTIFICATIONS is now requested inside the onboarding flow (OnboardingDialog).

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Gather UMP consent (AdMob Privacy & messaging), then start ads once allowed.
        val app = application as GuillotineApplication
        val consent = ConsentManager(this)
        consent.gatherConsent(this) { if (consent.canRequestAds) app.startAdsAfterConsent() }
        if (consent.canRequestAds) app.startAdsAfterConsent() // already consented from a prior run
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            GuillotineTheme {
                NleScreen(widthClass = windowSizeClass.widthSizeClass)
            }
        }
    }
}
