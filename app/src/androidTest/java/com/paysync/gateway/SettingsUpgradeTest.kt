package com.paysync.gateway

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paysync.gateway.data.SettingsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase-6e: settings upgrade. An existing installation's stored URL/secret/
 * senders must keep working after the update, and the NEW settings must
 * default to the historical behavior (amount fallback ON, max amount
 * disabled, update check on). Key names are unchanged (R5c) — additive keys
 * only; the legacy file is migrated once (SettingsMigrationTest).
 */
@RunWith(AndroidJUnit4::class)
class SettingsUpgradeTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun upgrade_oldConfigPreserved_newDefaultsAreBehaviorPreserving() {
        val s = SettingsManager.get(context)

        // Simulate the state of an existing v1.0.0 install.
        s.botApiUrl = "https://merchant.example.bot/"
        s.webhookSecret = "legacy-secret-123"
        s.addSender("VF-Cash")
        s.serviceEnabled = true

        // New settings were never written by the old version — they must
        // come back with the behavior-preserving defaults.
        assertTrue("amount fallback must default ON", s.amountFallbackEnabled)
        assertEquals("max auto-confirm must default disabled", 0.0, s.maxAutoConfirmAmountEgp, 0.0)
        assertTrue("update check default ON (opt-out)", s.updateCheckEnabled)
        assertTrue("legacy secret header default ON (existing backends)", s.sendLegacySecretHeader)

        // Old values survive unchanged.
        assertEquals("https://merchant.example.bot", s.botApiUrl) // trailing / trimmed, as before
        assertEquals("legacy-secret-123", s.webhookSecret)
        assertTrue(s.getSenders().contains("VF-Cash"))
        assertTrue(s.serviceEnabled)

        // New settings persist once written.
        s.amountFallbackEnabled = false
        s.maxAutoConfirmAmountEgp = 2500.0
        assertFalse(s.amountFallbackEnabled)
        assertEquals(2500.0, s.maxAutoConfirmAmountEgp, 0.0)
    }

    @Test
    fun encryptedPrefsFileAndKeyNames_areUnchanged() {
        // R5c: existing users' stored URL/secret/senders must keep working.
        // The LEGACY file name is what the one-time migration reads (see
        // SettingsMigrationTest); key names are carried over unchanged.
        assertEquals("paysync_secure_prefs", SettingsManager.FILE_NAME)
        assertEquals("paysync_settings_v2", SettingsManager.SECURE_FILE_NAME)
        assertEquals("bot_api_url", SettingsManager.KEY_BOT_URL)
        assertEquals("webhook_secret", SettingsManager.KEY_SECRET)
        assertEquals("allowed_senders", SettingsManager.KEY_SENDERS)
    }
}
