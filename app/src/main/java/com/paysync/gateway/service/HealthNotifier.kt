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
    const val UPDATE_STOPPED_NOTIF_ID = 1003
    const val START_FAILED_NOTIF_ID = 1004

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

    /**
     * Watchdog path: the toggle is ON but the service heartbeat is stale
     * (service killed — e.g. by an in-place app update). Same alert, channel
     * and rate limiting as the poll-failure path; the "dead since" clock is
     * the last heartbeat, so the 5-minute threshold is honored against the
     * real outage start.
     */
    fun onServiceDead(context: Context, deadSinceMs: Long, now: Long = System.currentTimeMillis()) {
        val appContext = context.applicationContext
        val settings = SettingsManager.get(appContext)
        if (!settings.isConfigured()) return // setup phase, not an outage
        // A zero/absent heartbeat means the service never ran since install —
        // treat it as dead for at least the full threshold already.
        val deadFor = if (deadSinceMs <= 0L) {
            FAILURE_ALERT_AFTER_MS
        } else {
            now - deadSinceMs
        }
        if (deadFor < FAILURE_ALERT_AFTER_MS) return
        if (now - settings.healthLastAlertAt < RE_NOTIFY_INTERVAL_MS) return

        settings.healthLastAlertAt = now
        createChannel(appContext)
        val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val minutes = (deadFor / 60_000L).coerceAtLeast(1)
        val openApp = PendingIntent.getActivity(
            appContext, 0,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(appContext.getString(R.string.alert_title))
            .setContentText(appContext.getString(R.string.alert_stopped_text, minutes))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(appContext.getString(R.string.alert_stopped_text, minutes)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        mgr.notify(ALERT_NOTIF_ID, notification)
    }

    /**
     * Fallback for the update-resume path: posted ONLY when starting the
     * service after MY_PACKAGE_REPLACED was denied. One tap reopens the app
     * where the Dashboard offers a one-tap restart.
     */
    fun postUpdateStoppedNotification(context: Context) {
        val appContext = context.applicationContext
        createChannel(appContext)
        val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = PendingIntent.getActivity(
            appContext, 1,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(appContext.getString(R.string.update_stopped_title))
            .setContentText(appContext.getString(R.string.update_stopped_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(appContext.getString(R.string.update_stopped_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        mgr.notify(UPDATE_STOPPED_NOTIF_ID, notification)
    }

    /**
     * The service could not enter the foreground (OS refused the start).
     * One tap reopens the app, whose foreground launch restarts it.
     */
    fun postStartFailedNotification(context: Context) {
        val appContext = context.applicationContext
        createChannel(appContext)
        val mgr = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val openApp = PendingIntent.getActivity(
            appContext, 2,
            Intent(appContext, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification = NotificationCompat.Builder(appContext, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(appContext.getString(R.string.start_failed_title))
            .setContentText(appContext.getString(R.string.start_failed_text))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(appContext.getString(R.string.start_failed_text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(openApp)
            .build()
        mgr.notify(START_FAILED_NOTIF_ID, notification)
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
