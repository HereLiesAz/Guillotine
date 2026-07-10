package com.hereliesaz.guillotine

import android.content.Context
import android.media.AudioManager
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

    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // Volume-Up + Volume-Down pressed together = play / stop (stop returns the playhead to where the
    // run began). We consume ALL volume-key presses so the combo never leaks a volume tick and fires
    // responsively on the second key-down. A single volume key still works, but its step is applied on
    // key-UP (deferring the press is what lets us tell a lone tap from a combo) and won't auto-repeat.
    private var volUpDown = false
    private var volDownDown = false
    private var comboFired = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val isUp = code == KeyEvent.KEYCODE_VOLUME_UP
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (isUp) volUpDown = true else volDownDown = true
                    if (volUpDown && volDownDown && !comboFired) {
                        comboFired = true
                        editorVm.editor.playOrStop()
                    }
                    return true // consume the press; a lone key's volume step is applied on release
                }
                KeyEvent.ACTION_UP -> {
                    val partOfCombo = comboFired // this release belongs to a combo → don't bump volume
                    if (isUp) volUpDown = false else volDownDown = false
                    if (!volUpDown && !volDownDown) comboFired = false
                    if (!partOfCombo) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            if (isUp) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI,
                        )
                    }
                    return true
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
