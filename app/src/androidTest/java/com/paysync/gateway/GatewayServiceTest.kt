package com.paysync.gateway

import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.service.HealthNotifier
import com.paysync.gateway.service.PaymentForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6c: the gateway foreground service starts into the foreground with
 * the configured type (specialUse on API 34+ — a wrong type or missing
 * manifest permission throws here), shows its ongoing notification, stops
 * cleanly, and the health-alert notifier posts/clears its alert.
 *
 * Not covered here and documented under "Requires manual device test":
 * Android 15 dataSync onTimeout() (only fires for dataSync-typed services on
 * targetSdk 35 — this app now uses specialUse, which has no timeout), real
 * OEM background killing, and 24h uptime.
 */
@RunWith(AndroidJUnit4::class)
class GatewayServiceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun service_startsIntoForeground_andStopsCleanly() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        SettingsManager.get(context).serviceEnabled = true
        PaymentForegroundService.start(context)

        val appeared = waitFor(15_000) {
            nm.activeNotifications.any { it.id == PaymentForegroundService.NOTIF_ID }
        }
        assertTrue("foreground notification did not appear (startForeground may have failed)", appeared)

        PaymentForegroundService.stop(context)
        val cleared = waitFor(10_000) {
            nm.activeNotifications.none { it.id == PaymentForegroundService.NOTIF_ID }
        }
        assertTrue("notification not removed after stop", cleared)
    }

    @Test
    fun healthNotifier_postsAndClearsAlert() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val s = SettingsManager.get(context)
        s.botApiUrl = "https://health-test.example"
        s.webhookSecret = "x"

        // Simulate a continuous failure streak that started 6 minutes ago:
        // first call opens the streak (no alert yet), second call crosses
        // the 5-minute threshold and must post the alert.
        HealthNotifier.onPollSuccess(context) // reset streak
        HealthNotifier.onPollFailure(context, System.currentTimeMillis() - 6 * 60_000L)
        HealthNotifier.onPollFailure(context, System.currentTimeMillis())
        val posted = waitFor(5_000) {
            nm.activeNotifications.any { it.id == HealthNotifier.ALERT_NOTIF_ID }
        }
        assertTrue("health alert was not posted after the failure threshold", posted)

        // Recovery clears it.
        HealthNotifier.onPollSuccess(context)
        val cleared = waitFor(5_000) {
            nm.activeNotifications.none { it.id == HealthNotifier.ALERT_NOTIF_ID }
        }
        assertTrue("health alert not cleared on recovery", cleared)
        assertEquals(0L, s.healthFirstFailureAt)
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(200)
        }
        return condition()
    }
}
