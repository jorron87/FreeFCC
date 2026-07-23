package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Event-driven Auto FCC service.
 *
 * It opens no DJI socket while waiting. A localized Home Point event from the
 * original DJI Fly app triggers one complete FCC profile, then the service
 * re-arms for a later aircraft session.
 */
class FccKeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID = "fcc_keepalive"
        const val NOTIFICATION_ID = 9012
        const val ACTION_START = "com.freefcc.app.START_KEEPALIVE"
        const val ACTION_STOP = "com.freefcc.app.STOP_KEEPALIVE"
        private const val PREFS_NAME = "freefcc"
        private const val PREF_KEEPALIVE = "keepalive_running"
        private const val HOME_POINT_DEBOUNCE_MS = 30_000L
        private const val TAG = "FreeFCC-AutoFCC"
        private val homePointEvents = Channel<Long>(Channel.CONFLATED)

        @Volatile private var serviceArmed = false
        @Volatile private var lastAcceptedAtMs = 0L

        fun start(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, true).apply()
            if (!isDjiFlyTextAccessEnabled(context)) return
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            serviceArmed = false
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            context.startService(
                Intent(context, FccKeepaliveService::class.java).apply {
                    action = ACTION_STOP
                }
            )
        }

        fun isRunningFlagSet(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEEPALIVE, false)

        fun isDjiFlyTextAccessEnabled(context: Context): Boolean {
            val expected = ComponentName(context, DjiFlyAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }

        @Synchronized
        fun notifyHomePointDetected(): Boolean {
            if (!serviceArmed) return false
            val now = System.currentTimeMillis()
            if (now - lastAcceptedAtMs < HOME_POINT_DEBOUNCE_MS) return false
            if (!homePointEvents.trySend(now).isSuccess) return false
            lastAcceptedAtMs = now
            return true
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    private val transport = DumlTransport()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                serviceArmed = false
                worker?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!isDjiFlyTextAccessEnabled(this)) {
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    stopSelf()
                    return START_NOT_STICKY
                }
                createNotificationChannel()
                startForeground(NOTIFICATION_ID, createNotification())
                serviceArmed = true
                startWorker()
            }
            else -> {
                serviceArmed = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            while (serviceArmed) {
                homePointEvents.receive()
                if (!serviceArmed) break
                applyFullProfileOnce()
            }
        }
    }

    private fun applyFullProfileOnce() {
        val hardwareLease = HardwareLock.tryBegin()
        if (hardwareLease == null) {
            Log.w(TAG, "Home Point FCC skipped: controller hardware busy")
            return
        }
        val portLease = DumlPortSessionLock.tryBegin(DumlTransport.PORT)
        if (portLease == null) {
            hardwareLease.close()
            Log.w(TAG, "Home Point FCC skipped: port 40009 busy")
            return
        }
        try {
            val profile = Profiles.load(this, "fcc.json")
            val sent = transport.sendFrames(
                frames = profile.frames,
                rounds = profile.rounds,
                interFrameDelayMs = profile.interFrameDelay,
                interRoundDelayMs = profile.interRoundDelay,
                readWindowMs = profile.readWindowMs,
                port = DumlTransport.PORT,
                pinPort = true
            )
            Log.i(TAG, "Home Point FCC one-shot completed=$sent")
        } catch (e: Exception) {
            Log.e(TAG, "Home Point FCC failed", e)
        } finally {
            portLease.close()
            hardwareLease.close()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Auto FCC",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Waits for DJI Fly Home Point without opening a DUML socket"
                }
            )
        }
    }

    private fun createNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("FreeFCC Auto FCC")
            .setContentText("Armed; waiting for DJI Fly Home Point")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onDestroy() {
        serviceArmed = false
        worker?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

}
