package com.paysync.gateway.data.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.annotation.VisibleForTesting
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.paysync.gateway.data.SettingsManager
import com.paysync.gateway.util.AppLog
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Opens the settings store and performs the one-time, crash-safe migration
 * from the legacy AndroidX `EncryptedSharedPreferences` file.
 *
 * Storage (v2): [KeystoreEncryptedPrefs] — AES-256-GCM, key generated in
 * and never leaving the Android Keystore ([Names.keyAlias]), ciphertext in
 * a normal prefs file ([Names.secureFile]). The deprecated
 * `androidx.security:security-crypto` library is used ONLY to read the
 * legacy file once; nothing is ever written with it again.
 *
 * Migration (runs on open until it has succeeded once):
 *  1. read every legacy entry (legacy file untouched);
 *  2. copy entries the new store does not have yet ("new wins": values the
 *     user saved while a previous migration attempt was failing are kept);
 *  3. re-read the new file from disk, decrypt and compare every copied value;
 *  4. only then write the "migrated" marker and delete the legacy file.
 * Any failure before step 4 removes the copied entries again (rollback) and
 * leaves the legacy file intact, and the app keeps running on the legacy
 * store for this launch; the next launch retries. A crash mid-way is the
 * same as a failure: no marker, legacy intact, retried.
 *
 * Degraded modes: new Keystore key unusable → legacy store if readable,
 * else a separate PLAIN fallback file ([Names.fallbackFile], encrypted=false,
 * the UI warns).
 */
object SettingsStorage {

    data class Names(
        val legacyFile: String = SettingsManager.FILE_NAME,
        val secureFile: String = SettingsManager.SECURE_FILE_NAME,
        val fallbackFile: String = SettingsManager.FALLBACK_FILE_NAME,
        val keyAlias: String = "paysync_settings_aes_gcm_v1"
    )

    enum class Migration {
        /** Fresh install or already migrated earlier. */
        NOT_NEEDED,
        /** Legacy settings copied, verified, legacy file deleted. */
        MIGRATED,
        /** Legacy file exists but could not be read; kept for a later retry. */
        LEGACY_UNREADABLE,
        /** Copy or verification failed; rolled back, running on the legacy store. */
        FAILED_ROLLED_BACK,
        /** New encrypted store unavailable (Keystore broken). */
        STORE_UNAVAILABLE
    }

    data class Opened(val prefs: SharedPreferences, val encrypted: Boolean, val migration: Migration)

    /** Test hook: thrown-into stages of the migration to prove rollback. */
    enum class Stage { AFTER_COPY, AFTER_VERIFY }

    const val MARKER_KEY = KeystoreEncryptedPrefs.INTERNAL_PREFIX + "migrated_from_legacy"
    private const val TAG = "SettingsStorage"

    fun open(context: Context): Opened = open(context, Names())

    @VisibleForTesting
    fun open(
        context: Context,
        names: Names,
        legacyOpener: (Context, String) -> SharedPreferences = ::openLegacy,
        faultInjector: (Stage) -> Unit = {}
    ): Opened {
        val app = context.applicationContext
        val secure = openSecure(app, names)
        val legacyExists = prefsFile(app, names.legacyFile).exists()

        if (secure == null) {
            // Keystore key unusable: the legacy store (own master key) may still work.
            if (legacyExists) {
                // getAll() proves every value decrypts — never hand out a store whose reads throw.
                runCatching { legacyOpener(app, names.legacyFile).also { it.all } }.getOrNull()?.let {
                    AppLog.w(TAG, "New secure store unavailable — using legacy encrypted store")
                    return Opened(it, encrypted = true, migration = Migration.STORE_UNAVAILABLE)
                }
            }
            AppLog.w(TAG, "Keystore unavailable — using unencrypted fallback settings")
            return Opened(
                app.getSharedPreferences(names.fallbackFile, Context.MODE_PRIVATE),
                encrypted = false, migration = Migration.STORE_UNAVAILABLE
            )
        }

        if (secure.getBoolean(MARKER_KEY, false)) {
            // Already migrated; finish a legacy delete that failed last time.
            if (legacyExists) app.deleteSharedPreferences(names.legacyFile)
            return Opened(secure, encrypted = true, migration = Migration.NOT_NEEDED)
        }
        if (!legacyExists) {
            secure.edit().putBoolean(MARKER_KEY, true).commit()
            return Opened(secure, encrypted = true, migration = Migration.NOT_NEEDED)
        }

        // Step 1: read. A legacy store that cannot be fully read is never
        // handed to the app (its reads would throw); the new store is used.
        val legacy: SharedPreferences
        val entries: Map<String, Any>
        try {
            legacy = legacyOpener(app, names.legacyFile)
            entries = legacy.all.filterValues { it != null }.mapValues { it.value!! }
        } catch (e: Exception) {
            AppLog.w(TAG, "Legacy settings unreadable; keeping the file for a later retry", e)
            return Opened(secure, encrypted = true, migration = Migration.LEGACY_UNREADABLE)
        }

        val copied = ArrayList<String>()
        return try {
            val ed = secure.edit()
            for ((k, v) in entries) {
                if (secure.contains(k)) continue // new wins
                when (v) {
                    is String -> ed.putString(k, v)
                    is Long -> ed.putLong(k, v)
                    is Int -> ed.putInt(k, v)
                    is Boolean -> ed.putBoolean(k, v)
                    is Float -> ed.putFloat(k, v)
                    is Set<*> -> ed.putStringSet(k, v.mapTo(HashSet()) { it as String })
                    else -> continue
                }
                copied += k
            }
            check(ed.commit()) { "writing migrated settings failed" }
            faultInjector(Stage.AFTER_COPY)

            // Verify the STORED ciphertext through a fresh decrypting view
            // (not the in-memory cache of the store that wrote it).
            val reread = KeystoreEncryptedPrefs(
                app.getSharedPreferences(names.secureFile, Context.MODE_PRIVATE),
                codec(names.keyAlias)
            )
            for (k in copied) {
                check(reread.all[k] == entries[k]) { "migrated value for '$k' did not verify" }
            }
            faultInjector(Stage.AFTER_VERIFY)

            check(secure.edit().putBoolean(MARKER_KEY, true).commit()) { "writing migration marker failed" }
            if (!app.deleteSharedPreferences(names.legacyFile)) {
                AppLog.w(TAG, "Migrated, but the legacy file could not be deleted")
            }
            AppLog.i(TAG, "Migrated ${copied.size} setting(s) to the Keystore store")
            Opened(secure, encrypted = true, migration = Migration.MIGRATED)
        } catch (e: Exception) {
            AppLog.e(TAG, "Settings migration failed; rolled back, legacy kept", e)
            runCatching {
                val rb = secure.edit()
                copied.forEach { rb.remove(it) }
                rb.remove(MARKER_KEY)
                rb.commit()
            }
            Opened(legacy, encrypted = true, migration = Migration.FAILED_ROLLED_BACK)
        }
    }

    /** New store + a full encrypt/decrypt self-test; null when the Keystore is unusable. */
    private fun openSecure(app: Context, names: Names): KeystoreEncryptedPrefs? = try {
        val codec = codec(names.keyAlias)
        val probe = codec.encrypt("__probe", "ok")
        check(codec.decrypt("__probe", probe) == "ok") { "Keystore AES-GCM self-test failed" }
        KeystoreEncryptedPrefs(app.getSharedPreferences(names.secureFile, Context.MODE_PRIVATE), codec)
    } catch (e: Exception) {
        AppLog.w(TAG, "Keystore-backed settings unavailable", e)
        null
    }

    private fun codec(alias: String): AesGcmValueCodec {
        val key = getOrCreateKey(alias)
        return AesGcmValueCodec { key }
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return gen.generateKey()
    }

    /** READ-ONLY use of the deprecated library: opening the legacy file for migration. */
    @Suppress("DEPRECATION")
    fun openLegacy(context: Context, fileName: String): SharedPreferences =
        EncryptedSharedPreferences.create(
            context,
            fileName,
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

    @VisibleForTesting
    fun prefsFile(context: Context, name: String): File =
        File(File(context.applicationInfo.dataDir, "shared_prefs"), "$name.xml")

    @VisibleForTesting
    fun deleteKey(alias: String) {
        runCatching { KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(alias) }
    }

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
}
