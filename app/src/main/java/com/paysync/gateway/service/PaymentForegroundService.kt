package com.paysync.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import com.paysync.gateway.util.AppLog
import androidx.core.app.NotificationCompat
import com.paysync.gateway.PaySyncApp
import com.paysync.gateway.R
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.ui.MainActivity
import com.paysync.gateway.work.DispatchWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 24/7 gateway service (foregroundServiceType="dataSync"):
 *  - Ongoing notification (POST_NOTIFICATIONS requested in UI on Android 13+;
 *    FGS notifications are still delivered without it).
 *  - Polls GET /transactions/pending every [SettingsManager.pollingIntervalMs]
 *    (default 15s) and sweeps expired verifies into timeout dispatches.
 *  - NetworkCallback flushes the dispatch queue when connectivity returns.
 */
class PaymentForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            SettingsManager.get(this).serviceEnabled = false
            HealthNotifier.cancelAlert(this) // deliberate stop is not an outage
            return START_NOT_STICKY
        }
        SettingsManager.get(this).serviceEnabled = true

        val notification = buildNotification(getString(R.string.notif_starting))
        try {
            when {
                // specialUse (API 34+): no dataSync 6h/24h timeout, allowed
                // from BOOT_COMPLETED on Android 15+ — required for a 24/7
                // gateway. Must match the manifest foregroundServiceType.
                Build.VERSION.SDK_INT >= 34 ->
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else ->
                    startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "startForeground failed", e)
        }
        SettingsManager.get(this).serviceHeartbeatMs = System.currentTimeMillis()
        startPollLoop()
        // Flush any backlog from downtime/reboot, then keep the periodic net.
        runCatching { DispatchWorker.enqueueDrain(applicationContext) }
        runCatching { DispatchWorker.schedulePeriodicDrain(applicationContext) }
        AppLog.i(TAG, "Gateway service started")
        return START_STICKY
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun shutdown() {
        pollJob?.cancel()
        pollJob = null
        unregisterNetworkCallback()
        scope.cancel()
    }

    // ---------- 15s poll loop ----------

    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val container = (applicationContext as? PaySyncApp)?.container
            if (container == null) {
                AppLog.w(TAG, "DI container missing; poll loop aborted")
                return@launch
            }
            while (isActive) {
                // Liveness truth for the UI and the WorkManager watchdog.
                SettingsManager.get(applicationContext).serviceHeartbeatMs =
                    System.currentTimeMillis()
                try {
                    container.repo.pollPending()
                    val pending = container.db.pendingVerifyDao().liveOnce().size
                    val queued = container.db.dispatchQueueDao().pendingOnce(1_000).size
                    // Sequential drain trigger: worker is CONNECTED-constrained,
                    // so this is a no-op offline and a flush online.
                    if (queued > 0 && container.api.isOnline(applicationContext)) {
                        DispatchWorker.enqueueDrain(applicationContext)
                    }
                    val online = container.api.isOnline(applicationContext)
                    // Health truth: a silent offline poll is still a failure to verify.
                    if (online && container.settings.isConfigured()) {
                        HealthNotifier.onPollSuccess(applicationContext)
                    } else {
                        HealthNotifier.onPollFailure(applicationContext)
                    }
                    val prefix = if (online) "" else "Offline • "
                    updateNotification(prefix + getString(R.string.notif_format, pending, queued))
                } catch (e: Exception) {
                    if (isActive) {
                        AppLog.w(TAG, "poll tick failed: ${e.message}")
                        HealthNotifier.onPollFailure(applicationContext)
                    }
                }
                delay(SettingsManager.get(applicationContext).pollingIntervalMs)
            }
        }
    }

    // ---------- notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
                    .apply { description = getString(R.string.channel_desc) }
            )
        }
    }

    private fun buildNotification(subtitle: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, PaymentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(subtitle)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_stop), stopIntent)
            .build()
    }

    private fun updateNotification(subtitle: String) {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { mgr.notify(NOTIF_ID, buildNotification(subtitle)) }
    }

    // ---------- connectivity ----------

    private fun registerNetworkCallback() {
        try {
            connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    scope.launch {
                        val container = (applicationContext as? PaySyncApp)?.container ?: return@launch
                        runCatching { container.repo.pollPending() }
                        DispatchWorker.enqueueDrain(applicationContext)
                    }
                }
            }
            networkCallback = cb
            connectivityManager?.registerNetworkCallback(request, cb)
        } catch (e: Exception) {
            AppLog.w(TAG, "NetworkCallback registration failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {
        } finally {
            networkCallback = null
        }
    }

    companion object {
        private const val TAG = "GatewayService"
        const val ACTION_START = "com.paysync.gateway.START"
        const val ACTION_STOP = "com.paysync.gateway.STOP"
        const val CHANNEL_ID = "paysync_gateway"
        const val NOTIF_ID = 1001

        fun start(context: Context) {
            val i = Intent(context.applicationContext, PaymentForegroundService::class.java)
                .setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context.applicationContext, i)
        }

        fun stop(context: Context) {
            val i = Intent(context.applicationContext, PaymentForegroundService::class.java)
                .setAction(ACTION_STOP)
            context.applicationContext.startService(i)
        }
    }
}
