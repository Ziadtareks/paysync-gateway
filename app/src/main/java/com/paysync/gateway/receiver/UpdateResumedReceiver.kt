package com.paysync.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.service.HealthNotifier
import com.paysync.gateway.service.PaymentForegroundService
import com.paysync.gateway.util.AppLog
import com.paysync.gateway.work.PollingWorker

/**
 * Restarts the gateway after an in-place app update (installed via
 * `adb install -r`, a file-manager install, or any sideload update path).
 *
 * Android kills the foreground service during a package update while the
 * persisted toggle stays ON — the user would otherwise believe payments are
 * being verified when nothing is running.
 *
 * Starting an FGS from `ACTION_MY_PACKAGE_REPLACED` is explicitly EXEMPT from
 * the Android 12+ background-start restrictions (same exemption clause as
 * ACTION_BOOT_COMPLETED — developer.android.com/develop/background-work/
 * services/fgs/restrictions-bg-start), and our `specialUse` type is not in
 * the Android 15 BOOT_COMPLETED type-restriction list.
 *
 * Defensive fallback (same pattern as [BootReceiver]): if the FGS start is
 * ever denied, schedule the WorkManager pollers and post a high-priority
 * "Gateway stopped after update, tap to resume" notification instead of
 * failing silently.
 */
class UpdateResumedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val appContext = context.applicationContext
        val enabled = runCatching {
            SettingsManager.get(appContext).serviceEnabled
        }.getOrDefault(false)
        if (!enabled) {
            AppLog.i(TAG, "Update resume: gateway was disabled, not restarting")
            return
        }
        try {
            val svc = Intent(appContext, PaymentForegroundService::class.java)
                .setAction(PaymentForegroundService.ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(appContext, svc)
            AppLog.i(TAG, "Update resume: gateway restarted after update")
        } catch (e: Exception) {
            if (e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                AppLog.w(TAG, "Update resume: FGS start denied; WorkManager fallback engaged")
                runCatching { PollingWorker.schedule(appContext) }
                runCatching { HealthNotifier.postUpdateStoppedNotification(appContext) }
            } else {
                AppLog.e(TAG, "Update resume failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "UpdateResume"
    }
}
