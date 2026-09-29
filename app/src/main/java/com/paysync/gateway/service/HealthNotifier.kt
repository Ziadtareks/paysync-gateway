package com.paysync.gateway.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.paysync.gateway.R
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.ui.MainActivity

/**
 * Rate-limited "Gateway is not verifying payments" alert.
 *
 * Raised when polling has been failing continuously for
 * [FAILURE_ALERT_AFTER_MS] (default 5 minutes) — either the foreground
 * service's 15 s loop or the 15-minute WorkManager poller observes the
 * failures, so a dead service is also covered. Auto-cleared on the first
 * successful poll. Re-notifies at most every [RE_NOTIFY_INTERVAL_MS]
 * (30 minutes) while the outage continues.
 *
 * Silent when the backend is not configured yet (setup phase — the UI
 * guides the user instead of alarming them).
 */
object HealthNotifier {

    const val ALERT_CHANNEL_ID = "gateway_alerts"
    const val ALERT_NOTIF_ID = 1002

    /** Continuous-failure duration before the first alert (spec default: 5 min). */
    const val FAILURE_ALERT_AFTER_MS = 5L * 60_000L

    /** Minimum spacing between repeat alerts during one ongoing outage. */
    const val RE_NOTIFY_INTERVAL_MS = 30L * 60_000L

    fun onPollSuccess(context: Context) {
        val settings = SettingsManager.get(context.applicationContext)
        settings.healthFirstFailureAt = 0L
        settings.healthLastAlertAt = 0L
        cancelAlert(context.applicationContext)
    }

    fun onPollFailure(context: Context, now: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val settings = SettingsManager.get(appContext)
        if (!settings.isConfigured()) return // setup phase, not an outage

        if (settings.healthFirstFailureAt == 0L) settings.healthFirstFailureAt = now
        val failingFor = now - settings.healthFirstFailureAt
        if (failingFor < FAILURE_ALERT_AFTER_MS) return
        if (now - settings.healthLastAlertAt < RE_NOTIFY_INTERVAL_MS) return

        settings.healthLastAlertAt = now
        postAlert(appContext, failingFor)
    }

    /** Clear the alert without touching failure tracking (e.g. service stop by user). */
    fun cancelAlert(context: Context) {
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        mgr?.cancel(ALERT_NOTIF_ID)
    }

    private fun postAlert(context: Context, failingForMs: Long) {
        createChannel(context)
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val minutes = (failingForMs / 60_000L).coerceAtLeast(1)
        val openApp = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.alert_title))
            .setContentText(context.getString(R.string.alert_text, minutes))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.alert_text, minutes)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        mgr.notify(ALERT_NOTIF_ID, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    context.getString(R.string.alert_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = context.getString(R.string.alert_channel_desc) }
            )
        }
    }
}
