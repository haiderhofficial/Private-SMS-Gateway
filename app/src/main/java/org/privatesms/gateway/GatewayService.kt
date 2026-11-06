package org.privatesms.gateway

import android.app.Notification
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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Foreground service that keeps polling the user's server and sending SMS. */
class GatewayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private lateinit var store: SettingsStore

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore.get(this)
        AppLog.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            store.enabled = false
            stopSelf()
            return START_NOT_STICKY
        }

        // Must call startForeground promptly after startForegroundService().
        startInForeground("Starting…")

        if (intent == null && !store.enabled) {
            // Sticky restart after the user had switched the gateway off.
            stopSelf()
            return START_NOT_STICKY
        }

        if (job == null) {
            GatewayState.running.value = true
            AppLog.i("Gateway started")
            job = scope.launch { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        GatewayState.running.value = false
        GatewayState.lastPoll.value = "Stopped"
        AppLog.i("Gateway stopped")
        super.onDestroy()
    }

    private suspend fun loop() {
        var errors = 0
        while (true) {
            var waitSec = 15L
            try {
                val cfg = store.load()
                applyWakeLock(cfg.wakeLock)
                val interval = cfg.pollIntervalSec.coerceAtLeast(3)
                waitSec = interval.toLong()

                Reporter.flush(this)
                val r = GatewayEngine.pollOnce(this, cfg)
                GatewayState.lastPoll.value = "${fmtClock()} · ${r.text}"

                if (r.ok) {
                    errors = 0
                    if (r.count > 0) waitSec = 1 // more may be waiting
                } else {
                    errors++
                    val backoff = interval.toLong() shl minOf(errors, 6)
                    waitSec = minOf(backoff, cfg.maxBackoffSec.toLong().coerceAtLeast(interval.toLong()))
                    AppLog.w("Poll failed: ${r.text} (retrying in ${waitSec}s)")
                }
                updateNotification(r.text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("Unexpected error: ${e.message ?: e.javaClass.simpleName}")
                waitSec = 30
            }
            delay(waitSec * 1000L)
        }
    }

    private fun applyWakeLock(want: Boolean) {
        val held = wakeLock?.isHeld == true
        if (want && !held) {
            val pm = getSystemService(PowerManager::class.java)
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PrivateSmsGateway:poll").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!want && held) {
            wakeLock?.release()
        }
    }

    private fun startInForeground(text: String) {
        val n = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setContentTitle("Private SMS Gateway")
            .setContentText(text)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "gateway"
        const val ACTION_STOP = "org.privatesms.gateway.STOP"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            SettingsStore.get(ctx).enabled = true
            ContextCompat.startForegroundService(ctx, Intent(ctx, GatewayService::class.java))
        }

        fun stop(ctx: Context) {
            SettingsStore.get(ctx).enabled = false
            ctx.stopService(Intent(ctx, GatewayService::class.java))
        }
    }
}
