package com.hereliesaz.guillotine

import android.app.Application
import com.hereliesaz.guillotine.crash.CrashReporter

/**
 * Application entry point. Installs the crash reporter as early as possible and flushes any
 * crash captured on the previous run to the configured relay (which opens a GitHub issue).
 */
class GuillotineApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        CrashReporter.flushPending(this)
    }
}
