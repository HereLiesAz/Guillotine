package com.hereliesaz.guillotine.crash

import android.content.Context
import android.os.Build
import java.io.BufferedReader
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Plain (non-secret) config for crash reporting: the URL of the user's relay endpoint.
 * Kept in ordinary prefs so the crash handler can read it cheaply and early, before any
 * encrypted store or Compose is up.
 */
object CrashConfig {
    private const val PREFS = "guillotine_crash"
    private const val KEY_RELAY = "relay_url"

    fun relayUrl(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RELAY, "").orEmpty()

    fun setRelayUrl(context: Context, url: String) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RELAY, url.trim()).apply()
}

/**
 * Captures uncaught exceptions to a file and, on the next launch, POSTs the report to the
 * user's relay (a small server that holds a GitHub token and opens an issue). We don't send
 * during the crash itself — the process is dying and a network call would be unreliable — so
 * the trace + recent logcat are persisted synchronously, then flushed next time the app runs.
 */
object CrashReporter {

    private const val PENDING_DIR = "pending_crashes"
    private const val LEGACY_FILE = "pending_crash.txt"
    private const val MAX_PENDING = 20

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeReport(appContext, thread, throwable) }
            // Let the platform finish crashing (show the dialog / restart as usual).
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Send any previously-captured crash reports (best-effort) once a relay is configured. Each
     * crash is its own file, so two crashes before a flush no longer clobber each other. Stops at
     * the first failure (e.g. offline) so the rest are retried next launch.
     */
    fun flushPending(context: Context) {
        val appContext = context.applicationContext
        val relay = CrashConfig.relayUrl(appContext)
        if (relay.isBlank()) return // hold reports until a relay is set
        thread(isDaemon = true) {
            // All disk I/O (existence/listing/read) stays off the main thread.
            val dir = File(appContext.filesDir, PENDING_DIR)
            val legacy = File(appContext.filesDir, LEGACY_FILE)
            val files = buildList {
                if (legacy.exists()) add(legacy) // migrate a report written by an older version
                dir.listFiles()?.sortedBy { it.name }?.let { addAll(it) }
            }
            for (file in files) {
                val report = runCatching { file.readText() }.getOrNull() ?: run { file.delete(); continue }
                val title = report.lineSequence().firstOrNull { it.startsWith("FINGERPRINT: ") }
                    ?.removePrefix("FINGERPRINT: ")?.take(200) ?: "App crash"
                val ok = runCatching { post(relay, title, report) }.getOrDefault(false)
                if (ok) file.delete() else break // keep the rest for next launch
            }
        }
    }

    private fun writeReport(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val trace = sw.toString()
        val top = throwable.stackTrace.firstOrNull()?.let { " @ ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" } ?: ""
        val fingerprint = "${throwable.javaClass.simpleName}: ${throwable.message?.take(120).orEmpty()}$top"

        val report = buildString {
            appendLine("FINGERPRINT: $fingerprint")
            appendLine("App: ${context.packageName} ${appVersion(context)}")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine("---- Stack trace ----")
            appendLine(trace)
            appendLine("---- Recent logcat ----")
            appendLine(readLogcat())
        }
        val dir = File(context.filesDir, PENDING_DIR).apply { mkdirs() }
        // Cap stored reports so they can't grow without bound if no relay is ever configured.
        dir.listFiles()?.sortedBy { it.name }?.let { existing ->
            val over = existing.size - (MAX_PENDING - 1)
            if (over > 0) existing.take(over).forEach { it.delete() }
        }
        File(dir, "crash_${System.currentTimeMillis()}_${System.nanoTime()}.txt").writeText(report)
    }

    private fun appVersion(context: Context): String = runCatching {
        val pi = context.packageManager.getPackageInfo(context.packageName, 0)
        "${pi.versionName} (${pi.longVersionCodeCompat()})"
    }.getOrDefault("?")

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()

    /** An app can read its OWN process logs via logcat without special permission. */
    private fun readLogcat(): String = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-v", "time", "-t", "400"))
        process.inputStream.bufferedReader().use(BufferedReader::readText).takeLast(20_000)
    }.getOrDefault("(logcat unavailable)")

    private fun post(relayUrl: String, title: String, body: String): Boolean {
        // The report bundles a stack trace and recent logcat — never send it in the clear.
        if (!relayUrl.startsWith("https://", ignoreCase = true)) return false
        val conn = (URL(relayUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
        }
        return try {
            val payload = org.json.JSONObject().apply {
                put("title", title)
                put("body", body)
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }
            conn.responseCode in 200..299
        } finally {
            conn.disconnect()
        }
    }
}
