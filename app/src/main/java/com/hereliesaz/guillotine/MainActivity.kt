package com.hereliesaz.guillotine

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hereliesaz.guillotine.ads.ConsentManager
import com.hereliesaz.guillotine.editor.AndroidEditorViewModel
import com.hereliesaz.guillotine.ui.NleScreen
import com.hereliesaz.guillotine.ui.theme.GuillotineTheme

class MainActivity : ComponentActivity() {
    // POST_NOTIFICATIONS is now requested inside the onboarding flow (OnboardingDialog).

    // Same instance NleScreen resolves via viewModel<AndroidEditorViewModel>() (both use this
    // Activity's ViewModelStore), so the volume-key transport drives the live editor.
    private val editorVm: AndroidEditorViewModel by viewModels()

    // Volume-Up + Volume-Down pressed together = play / stop (stop returns the playhead to where the
    // run began). Tracked so a single volume key still adjusts volume normally; only the simultaneous
    // press is hijacked, and it fires once per combo (not on auto-repeat).
    private var volUpDown = false
    private var volDownDown = false
    private var comboActive = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = true else volDownDown = true
                    if (volUpDown && volDownDown) {
                        if (!comboActive) {
                            comboActive = true
                            editorVm.editor.playOrStop()
                        }
                        return true // swallow while the combo is held
                    }
                    return super.dispatchKeyEvent(event) // single key → normal volume
                }
                KeyEvent.ACTION_UP -> {
                    val wasCombo = comboActive
                    if (code == KeyEvent.KEYCODE_VOLUME_UP) volUpDown = false else volDownDown = false
                    if (!volUpDown && !volDownDown) comboActive = false
                    // Swallow the release(s) that were part of a combo so they don't bump the volume.
                    return if (wasCombo) true else super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

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
