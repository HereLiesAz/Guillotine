package com.hereliesaz.guillotine

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.hereliesaz.guillotine.ads.ConsentManager
import com.hereliesaz.guillotine.azphalt.AzpExternalOpen
import com.hereliesaz.guillotine.azphalt.AzpInstallLink
import com.hereliesaz.guillotine.editor.AndroidEditorViewModel
import com.hereliesaz.guillotine.ui.NleScreen
import com.hereliesaz.guillotine.ui.theme.GuillotineTheme
import com.hereliesaz.guillotine.update.UpdatePrompt

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

    /**
     * A package handed to Guillotine from outside, by either of the two routes the manifest advertises.
     *
     * A `.azp` **file** — the azphalt.store download tapped in the browser, a file manager, another app's
     * share sheet — arrives as a VIEW/SEND URI whose bytes are already on the device.
     *
     * An **`azphalt://install?id=…&version=…` deep link** arrives from a web page that has no way to push
     * bytes anywhere, so it names a package instead and leaves the fetching to whichever conforming host
     * claims the scheme. Guillotine parses and validates the link here ([AzpInstallLink] refuses anything
     * malformed) and lets the editor's install flow do the downloading; a link that doesn't parse is
     * dropped rather than acted on. Together these are what make Guillotine offerable as the
     * "Azphalt-conforming host" the web store can currently only ask the user to go and find.
     *
     * Either way the editor verifies what it ends up with exactly as it verifies a package the store app
     * hands over — a link names a package, it does not vouch for one. Anything else (the launcher intent)
     * is ignored.
     *
     * `singleTask` means an open that arrives while the editor is already running lands in *this*
     * instance via [onNewIntent], so the package installs into the project on screen.
     */
    private fun routeAzpOpen(intent: Intent?) {
        val uri: Uri = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return

        if (uri.scheme.equals(AzpInstallLink.SCHEME, ignoreCase = true)) {
            AzpInstallLink.parse(uri.toString())?.let { AzpExternalOpen.offer(it) }
        } else {
            AzpExternalOpen.offer(uri)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeAzpOpen(intent)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Only on a cold/new start — a configuration change re-delivers the same intent, and
        // re-offering it would re-run an install the user already dealt with.
        if (savedInstanceState == null) routeAzpOpen(intent)

        // Ads exist only in the Play distribution. Gather UMP consent (AdMob Privacy & messaging),
        // then start ads once allowed. The github build skips all of this (BuildConfig.ADS_ENABLED).
        if (BuildConfig.ADS_ENABLED) {
            val app = application as GuillotineApplication
            val consent = ConsentManager(this)
            consent.gatherConsent(this) { if (consent.canRequestAds) app.startAdsAfterConsent() }
            if (consent.canRequestAds) app.startAdsAfterConsent() // already consented from a prior run
        }
        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            GuillotineTheme {
                NleScreen(widthClass = windowSizeClass.widthSizeClass)
                // Direct-download (github) build: check GitHub Releases on launch and offer to
                // self-update. No-op in the Play build (BuildConfig.UPDATER_ENABLED == false).
                if (BuildConfig.UPDATER_ENABLED) UpdatePrompt()
            }
        }
    }
}
