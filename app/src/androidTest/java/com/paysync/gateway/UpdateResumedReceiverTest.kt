package com.paysync.gateway

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.receiver.UpdateResumedReceiver
import com.paysync.gateway.service.PaymentForegroundService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the 2.1 fix: after an in-place app update (the ACTION_MY_PACKAGE_REPLACED
 * scenario), the gateway service resumes by itself when the persisted toggle is
 * ON, and stays off when the user had it OFF. The receiver is invoked directly
 * with the exact intent the system delivers after an update.
 */
@RunWith(AndroidJUnit4::class)
class UpdateResumedReceiverTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun serviceRunning(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return am.getRunningServices(200).any { it.service.className == PaymentForegroundService::class.java.name }
    }

    private fun awaitService(running: Boolean, timeoutMs: Long = 15_000): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (serviceRunning() == running) return true
            Thread.sleep(250)
        }
        return serviceRunning() == running
    }

    @Test
    fun toggleOn_afterUpdate_serviceResumes() {
        // Simulate the post-update state: toggle persisted ON, no service.
        PaymentForegroundService.stop(context)
        assertTrue(awaitService(running = false))
        SettingsManager.get(context).serviceEnabled = true
        SettingsManager.get(context).serviceHeartbeatMs = 0L

        UpdateResumedReceiver().onReceive(
            context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName)
        )

        assertTrue("service did not resume after MY_PACKAGE_REPLACED with toggle ON", awaitService(running = true))
        // Cleanup for other tests.
        PaymentForegroundService.stop(context)
        assertTrue(awaitService(running = false))
    }

    @Test
    fun toggleOff_afterUpdate_serviceStaysOff() {
        PaymentForegroundService.stop(context)
        assertTrue(awaitService(running = false))
        SettingsManager.get(context).serviceEnabled = false

        UpdateResumedReceiver().onReceive(
            context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED).setPackage(context.packageName)
        )
        Thread.sleep(3_000)

        assertFalse("service started even though the toggle was OFF", serviceRunning())
    }

    @Test
    fun wrongAction_ignored() {
        SettingsManager.get(context).serviceEnabled = true
        UpdateResumedReceiver().onReceive(
            context, Intent(Intent.ACTION_BOOT_COMPLETED).setPackage(context.packageName)
        )
        Thread.sleep(2_000)
        // This test's receiver handles only MY_PACKAGE_REPLACED; BOOT_COMPLETED
        // belongs to BootReceiver.
        assertFalse(serviceRunning())
        SettingsManager.get(context).serviceEnabled = false
    }
}
