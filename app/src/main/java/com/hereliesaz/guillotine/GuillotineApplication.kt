package com.hereliesaz.guillotine

import android.app.Application
import com.hereliesaz.guillotine.ads.AppOpenAdManager
import com.hereliesaz.guillotine.crash.CrashReporter

/**
 * Application entry point. Installs the crash reporter, flushes any crash captured on the
 * previous run to the configured relay (which opens a GitHub issue), and starts the AdMob
 * app-open ad manager.
 */
class GuillotineApplication : Application() {
    lateinit var appOpenAdManager: AppOpenAdManager
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CrashReporter.flushPending(this)
        appOpenAdManager = AppOpenAdManager(this).also { it.initialize() }
    }
}
