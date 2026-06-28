package com.hereliesaz.guillotine.operation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the process alive while one long operation runs and renders its
 * progress in an ongoing notification (Pause/Resume for pausable ops, Cancel always). It is a thin
 * shell over [OperationController]: it owns no work — it just mirrors [OperationController.state]
 * into the notification and forwards the notification's action buttons back to the controller.
 * When the controller goes idle (state == null) the service removes the notification and stops.
 */
class OperationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collector: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> { OperationController.pause(); return START_NOT_STICKY }
            ACTION_RESUME -> { OperationController.resume(); return START_NOT_STICKY }
            ACTION_CANCEL -> { OperationController.cancel(); return START_NOT_STICKY }
        }

        // Promote to foreground immediately with whatever the current state is (idle → a generic
        // "working" placeholder; the collector replaces it on the next emission). If the platform
        // refuses the promotion (operation kicked off while the app was backgrounded — see below),
        // bail out cleanly: the work runs on OperationController's own scope, not ours, so stopping
        // here just drops the notification/wakelock, not the operation.
        if (!startForegroundWith(OperationController.state.value)) {
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWakeLock()

        if (collector == null) {
            collector = scope.launch {
                OperationController.state.collectLatest { state ->
                    if (state == null) {
                        stopEverything()
                    } else {
                        notificationManager().notify(NOTIF_ID, buildNotification(state))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Promote to foreground. Returns false (without crashing) if the platform refuses the start.
     *
     * `mediaProcessing` is a background-start-restricted FGS type: when the service is launched while
     * the app is in the background (e.g. an external MCP tool triggering generative removal while the
     * app is backgrounded), Android 14+ strips the requested type down to `none` and — because
     * targetSdk ≥ 34 — throws InvalidForegroundServiceTypeException ("Starting FGS with type none …
     * has been prohibited"). That throw lands here on the main thread, so OperationController's
     * runCatching around startForegroundService() can't catch it; without this guard it crashes the
     * whole app. ForegroundServiceStartNotAllowedException is the older-shape variant of the same.
     */
    private fun startForegroundWith(state: OperationState?): Boolean {
        if (started) return true
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else 0
        return try {
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(state), type)
            started = true
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun stopEverything() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        collector?.cancel()
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun buildNotification(state: OperationState?): Notification {
        val title = state?.label ?: "Working…"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Guillotine")
            .setContentText(if (state?.paused == true) "Paused — $title" else title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val progress = state?.progress
        if (progress != null) builder.setProgress(100, (progress * 100).toInt().coerceIn(0, 100), false)
        else builder.setProgress(0, 0, true) // indeterminate

        if (state != null) {
            if (state.pausable) {
                if (state.paused) {
                    builder.addAction(0, "Resume", servicePendingIntent(ACTION_RESUME))
                } else {
                    builder.addAction(0, "Pause", servicePendingIntent(ACTION_PAUSE))
                }
            }
            builder.addAction(0, "Cancel", servicePendingIntent(ACTION_CANCEL))
        }
        return builder.build()
    }

    private fun servicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, OperationService::class.java).setAction(action)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getService(this, action.hashCode(), intent, flags)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guillotine:operation").apply {
            setReferenceCounted(false)
            acquire(MAX_WAKELOCK_MS)
        }
    }

    private fun releaseWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        wakeLock = null
    }

    private fun notificationManager() =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Processing", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Progress of running video operations"
            setShowBadge(false)
        }
        notificationManager().createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "guillotine_operations"
        const val NOTIF_ID = 0x6111
        const val ACTION_PAUSE = "com.hereliesaz.guillotine.operation.PAUSE"
        const val ACTION_RESUME = "com.hereliesaz.guillotine.operation.RESUME"
        const val ACTION_CANCEL = "com.hereliesaz.guillotine.operation.CANCEL"
        private const val MAX_WAKELOCK_MS = 6 * 60 * 60 * 1000L // 6h safety cap, matches FGS limit
    }
}
