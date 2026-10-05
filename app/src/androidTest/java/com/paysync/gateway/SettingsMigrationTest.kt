package com.paysync.gateway

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.data.security.KeystoreEncryptedPrefs
import com.paysync.gateway.data.security.SettingsStorage
import com.paysync.gateway.data.security.SettingsStorage.Migration
import com.paysync.gateway.data.security.SettingsStorage.Stage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Settings storage on a real Keystore: the one-time migration from the
 * legacy AndroidX EncryptedSharedPreferences file, its failure/rollback and
 * retry paths, and tamper / lost-key behavior. Every test uses its own file
 * names and key alias, so the app's real settings are never touched.
 */
@RunWith(AndroidJUnit4::class)
class SettingsMigrationTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var names: SettingsStorage.Names

    private val secret = "legacy-secret-${UUID.randomUUID()}"

    @Before
    fun setUp() {
        val id = UUID.randomUUID().toString().take(8)
        names = SettingsStorage.Names(
            legacyFile = "t_legacy_$id",
            secureFile = "t_secure_$id",
            fallbackFile = "t_plain_$id",
            keyAlias = "t_key_$id"
        )
    }

    @After
    fun tearDown() {
        listOf(names.legacyFile, names.secureFile, names.fallbackFile).forEach { context.deleteSharedPreferences(it) }
        SettingsStorage.deleteKey(names.keyAlias)
    }

    /** Writes a realistic v1.1.x settings file with the legacy library. */
    private fun writeLegacy(url: String = "https://merchant.example") {
        SettingsStorage.openLegacy(context, names.legacyFile).edit()
            .putString(SettingsManager.KEY_BOT_URL, url)
            .putString(SettingsManager.KEY_SECRET, secret)
            .putStringSet(SettingsManager.KEY_SENDERS, hashSetOf("VF-Cash", "BanK-AlAhly"))
            .putLong(SettingsManager.KEY_POLL_MS, 30_000L)
            .putBoolean(SettingsManager.KEY_SERVICE_ON, true)
            .commit()
    }

    private fun legacyExists() = SettingsStorage.prefsFile(context, names.legacyFile).exists()

    private fun assertLegacyValues(p: android.content.SharedPreferences, url: String = "https://merchant.example") {
        assertEquals(url, p.getString(SettingsManager.KEY_BOT_URL, null))
        assertEquals(secret, p.getString(SettingsManager.KEY_SECRET, null))
        assertEquals(setOf("VF-Cash", "BanK-AlAhly"), p.getStringSet(SettingsManager.KEY_SENDERS, null))
        assertEquals(30_000L, p.getLong(SettingsManager.KEY_POLL_MS, 0L))
        assertTrue(p.getBoolean(SettingsManager.KEY_SERVICE_ON, false))
    }

    @Test
    fun legacySettings_migrateVerified_legacyDeleted_valuesEncryptedOnDisk() {
        writeLegacy()
        assertTrue(legacyExists())

        val opened = SettingsStorage.open(context, names)

        assertEquals(Migration.MIGRATED, opened.migration)
        assertTrue(opened.encrypted)
        assertTrue(opened.prefs is KeystoreEncryptedPrefs)
        assertLegacyValues(opened.prefs)
        assertFalse("legacy file must be deleted after a verified migration", legacyExists())

        // The secret is ciphertext on disk.
        val raw = SettingsStorage.prefsFile(context, names.secureFile).readText()
        assertFalse(raw.contains(secret))
        assertTrue(raw.contains("v1:"))

        // Next launch: nothing to do, values still there.
        val again = SettingsStorage.open(context, names)
        assertEquals(Migration.NOT_NEEDED, again.migration)
        assertLegacyValues(again.prefs)
    }

    @Test
    fun freshInstall_noLegacy_noMigration() {
        val opened = SettingsStorage.open(context, names)
        assertEquals(Migration.NOT_NEEDED, opened.migration)
        assertTrue(opened.encrypted)
        assertNull(opened.prefs.getString(SettingsManager.KEY_SECRET, null))
        assertFalse(legacyExists())
    }

    @Test
    fun failureAfterCopy_rollsBack_keepsLegacy_appRunsOnLegacy_thenRetrySucceeds() {
        writeLegacy()

        val failed = SettingsStorage.open(context, names, faultInjector = { if (it == Stage.AFTER_COPY) error("boom") })

        assertEquals(Migration.FAILED_ROLLED_BACK, failed.migration)
        assertTrue(legacyExists())
        assertLegacyValues(failed.prefs) // the app keeps working on the legacy store
        assertRolledBack()

        val retried = SettingsStorage.open(context, names)
        assertEquals(Migration.MIGRATED, retried.migration)
        assertLegacyValues(retried.prefs)
        assertFalse(legacyExists())
    }

    @Test
    fun failureAfterVerify_rollsBack_keepsLegacy() {
        writeLegacy()

        val failed = SettingsStorage.open(context, names, faultInjector = { if (it == Stage.AFTER_VERIFY) error("boom") })

        assertEquals(Migration.FAILED_ROLLED_BACK, failed.migration)
        assertTrue(legacyExists())
        assertRolledBack()
    }

    /** After a rollback the new store holds neither copied values nor the marker. */
    private fun assertRolledBack() {
        val secureRaw = context.getSharedPreferences(names.secureFile, Context.MODE_PRIVATE).all.keys
        assertFalse(secureRaw.contains(SettingsManager.KEY_SECRET))
        assertFalse(secureRaw.contains(SettingsStorage.MARKER_KEY))
    }

    @Test
    fun unreadableLegacy_isKept_newStoreUsed_laterMigrationKeepsNewerValues() {
        writeLegacy(url = "https://old.example")

        val unreadable = SettingsStorage.open(
            context, names, legacyOpener = { _, _ -> throw SecurityException("keystore hiccup") }
        )
        assertEquals(Migration.LEGACY_UNREADABLE, unreadable.migration)
        assertTrue(unreadable.prefs is KeystoreEncryptedPrefs)
        assertTrue(legacyExists())
        // The user re-enters the URL while the legacy file is unreadable.
        unreadable.prefs.edit().putString(SettingsManager.KEY_BOT_URL, "https://new.example").commit()

        val migrated = SettingsStorage.open(context, names)
        assertEquals(Migration.MIGRATED, migrated.migration)
        // New wins for what the user saved; everything else comes from legacy.
        assertLegacyValues(migrated.prefs, url = "https://new.example")
    }

    @Test
    fun tamperedValue_readsAsDefault_neverCrashes() {
        val opened = SettingsStorage.open(context, names)
        opened.prefs.edit().putString(SettingsManager.KEY_SECRET, secret).commit()

        val raw = context.getSharedPreferences(names.secureFile, Context.MODE_PRIVATE)
        val stored = raw.getString(SettingsManager.KEY_SECRET, null)!!
        raw.edit().putString(SettingsManager.KEY_SECRET, stored.dropLast(4) + "AAAA").commit()

        val reopened = SettingsStorage.open(context, names)
        assertNull(reopened.prefs.getString(SettingsManager.KEY_SECRET, null))
        assertTrue(
            (reopened.prefs as KeystoreEncryptedPrefs).undecryptableKeys.contains(SettingsManager.KEY_SECRET)
        )
    }

    @Test
    fun lostKeystoreKey_settingsReadAsDefaults_newKeyCreated_writesWorkAgain() {
        val opened = SettingsStorage.open(context, names)
        opened.prefs.edit().putString(SettingsManager.KEY_SECRET, secret).commit()

        SettingsStorage.deleteKey(names.keyAlias)

        val reopened = SettingsStorage.open(context, names)
        assertTrue(reopened.encrypted)
        assertNull(reopened.prefs.getString(SettingsManager.KEY_SECRET, null))
        reopened.prefs.edit().putString(SettingsManager.KEY_SECRET, "re-entered").commit()
        assertEquals(
            "re-entered",
            SettingsStorage.open(context, names).prefs.getString(SettingsManager.KEY_SECRET, null)
        )
    }

    @Test
    fun appSettings_useTheKeystoreStore() {
        val s = SettingsManager.get(context)
        assertTrue(s.isEncrypted)
        assertTrue(SettingsStorage.prefsFile(context, SettingsManager.SECURE_FILE_NAME).exists())
    }
}
