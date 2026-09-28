package com.paysync.gateway.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.service.PaymentForegroundService
import com.paysync.gateway.work.PollingWorker

/**
 * Restarts the gateway after reboot if the user had it enabled.
 *
 * Android 12+ (API 31) forbids starting foreground services from the
 * background ([android.app.ForegroundServiceStartNotAllowedException]).
 * A reboot receiver runs in the background, so the FGS start can be denied
 * on Android 12–14. In that case we fall back to the 15-minute WorkManager
 * poller (degraded but functional) and keep [SettingsManager.serviceEnabled]
 * set, so the next foreground app launch starts the full service.
 * The exception type is matched by class NAME (not a direct catch) so this
 * file stays warning-free on minSdk 26 while remaining correct on API 31+.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) return

        val enabled = runCatching {
            SettingsManager.get(context.applicationContext).serviceEnabled
        }.getOrDefault(false)
        if (!enabled) {
            Log.i(TAG, "Boot: service was disabled, not restarting")
            return
        }
        try {
            val svc = Intent(context.applicationContext, PaymentForegroundService::class.java)
                .setAction(PaymentForegroundService.ACTION_START)
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context.applicationContext, svc)
            } else {
                context.applicationContext.startService(svc)
            }
            Log.i(TAG, "Boot: gateway restarted")
        } catch (e: Exception) {
            if (e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException") {
                Log.w(TAG, "Boot: FGS start denied in background; WorkManager fallback engaged")
                runCatching { PollingWorker.schedule(context.applicationContext) }
            } else {
                Log.e(TAG, "Boot restart failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
