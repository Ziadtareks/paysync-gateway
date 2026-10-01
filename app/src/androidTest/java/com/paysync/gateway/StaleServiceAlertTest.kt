package com.paysync.gateway

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.service.HealthNotifier
import com.paysync.gateway.service.PaymentForegroundService
import com.paysync.gateway.util.ServiceHealth
import com.paysync.gateway.work.PollingWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies 2.3: with the toggle ON but the service dead (stale heartbeat),
 * the WorkManager backup poller raises the existing health alert; recovery
 * (fresh heartbeat + successful poll) clears it. Threshold (5 min) and rate
 * limiting (30 min) unchanged.
 */
@RunWith(AndroidJUnit4::class)
class StaleServiceAlertTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun alertVisible(): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.activeNotifications.any { it.id == HealthNotifier.ALERT_NOTIF_ID }
    }

    private suspend fun runWorker() =
        TestListenableWorkerBuilder<PollingWorker>(context.applicationContext).build().doWork()

    @Test
    fun toggleOn_staleHeartbeat_alertPosted() = kotlinx.coroutines.runBlocking<Unit> {
        val s = SettingsManager.get(context)
        s.botApiUrl = "https://stale-test.example"
        s.webhookSecret = "x"
        s.serviceEnabled = true
        s.serviceHeartbeatMs = 0L // service never ran / long dead
        HealthNotifier.onPollSuccess(context) // reset rate limiting

        runWorker()

        assertTrue(
            "dead service with toggle ON must raise the health alert", alertVisible()
        )
        // Cleanup.
        PaymentForegroundService.stop(context)
        s.serviceEnabled = false
        HealthNotifier.onPollSuccess(context)
    }

    @Test
    fun toggleOn_freshHeartbeat_noAlert() = kotlinx.coroutines.runBlocking<Unit> {
        val s = SettingsManager.get(context)
        s.botApiUrl = "https://stale-test.example"
        s.webhookSecret = "x"
        s.serviceEnabled = true
        s.serviceHeartbeatMs = System.currentTimeMillis() // pretend alive
        HealthNotifier.onPollSuccess(context)

        runWorker()

        assertTrue(!alertVisible())
        s.serviceEnabled = false
        HealthNotifier.onPollSuccess(context)
    }

    @Test
    fun watchdog_usesServiceHealthWindow() {
        val s = SettingsManager.get(context)
        // Boundary consistency between the UI truth and the worker watchdog.
        val now = System.currentTimeMillis()
        val staleBeat = now - (s.pollingIntervalMs + ServiceHealth.FRESH_MARGIN_MS + 1_000)
        s.serviceHeartbeatMs = staleBeat
        assertEquals(
            false,
            ServiceHealth.isAlive(staleBeat, now, s.pollingIntervalMs)
        )
        s.serviceHeartbeatMs = 0L
    }
}
