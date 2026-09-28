package com.paysync.gateway.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted app settings (AndroidX Security).
 *
 * Keys: [bot_api_url], [webhook_secret], [allowed_senders], [polling_interval_ms],
 * plus [verify_timeout_ms], [last_heartbeat], [service_enabled].
 *
 * Falls back to plain SharedPreferences if the Keystore is unavailable, so the
 * gateway keeps working on devices with broken keystore implementations.
 */
class SettingsManager private constructor(context: Context) {

    private val prefs: SharedPreferences = try {
        @Suppress("DEPRECATION")
        EncryptedSharedPreferences.create(
            context.applicationContext,
            FILE_NAME,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    var botApiUrl: String
        get() = prefs.getString(KEY_BOT_URL, DEFAULT_BOT_URL) ?: DEFAULT_BOT_URL
        set(value) { prefs.edit().putString(KEY_BOT_URL, value.trim().trimEnd('/')).apply() }

    var webhookSecret: String
        get() = prefs.getString(KEY_SECRET, "") ?: ""
        set(value) { prefs.edit().putString(KEY_SECRET, value).apply() }

    var pollingIntervalMs: Long
        get() = prefs.getLong(KEY_POLL_MS, DEFAULT_POLL_MS).coerceIn(5_000L, 300_000L)
        set(value) { prefs.edit().putLong(KEY_POLL_MS, value.coerceIn(5_000L, 300_000L)).apply() }

    var verifyTimeoutMs: Long
        get() = prefs.getLong(KEY_TIMEOUT_MS, DEFAULT_TIMEOUT_MS).coerceIn(15_000L, 900_000L)
        set(value) { prefs.edit().putLong(KEY_TIMEOUT_MS, value.coerceIn(15_000L, 900_000L)).apply() }

    var lastHeartbeat: Long
        get() = prefs.getLong(KEY_HEARTBEAT, 0L)
        set(value) { prefs.edit().putLong(KEY_HEARTBEAT, value).apply() }

    var lastPollMs: Long
        get() = prefs.getLong(KEY_LAST_POLL, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_POLL, value).apply() }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_SERVICE_ON, value).apply() }

    fun touchHeartbeat() {
        lastHeartbeat = System.currentTimeMillis()
    }

    /**
     * True once the user has pointed the app at their real backend: any
     * http(s) URL except the built-in placeholder. Without the parentheses
     * the `&&` would bind tighter than `||` and any http:// URL (including
     * the placeholder) would count as configured.
     */
    fun isConfigured(): Boolean {
        val url = botApiUrl
        return (url.startsWith("http://") || url.startsWith("https://")) &&
            !url.contains("api.mybot.com")
    }

    fun getSenders(): MutableSet<String> {
        val stored = prefs.getStringSet(KEY_SENDERS, null)
        return if (stored.isNullOrEmpty()) HashSet(DEFAULT_SENDERS) else HashSet(stored)
    }

    fun addSender(name: String): Set<String> {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "Sender name is empty" }
        require(clean.length <= 30) { "Sender name too long (max 30)" }
        val current = getSenders()
        current.add(clean)
        prefs.edit().putStringSet(KEY_SENDERS, HashSet(current)).apply()
        return current
    }

    fun removeSender(name: String): Set<String> {
        val current = getSenders()
        current.remove(name)
        prefs.edit().putStringSet(KEY_SENDERS, HashSet(current)).apply()
        return current
    }

    companion object {
        const val FILE_NAME = "paysync_secure_prefs"
        const val KEY_BOT_URL = "bot_api_url"
        const val KEY_SECRET = "webhook_secret"
        const val KEY_SENDERS = "allowed_senders"
        const val KEY_POLL_MS = "polling_interval_ms"
        const val KEY_TIMEOUT_MS = "verify_timeout_ms"
        const val KEY_HEARTBEAT = "last_heartbeat"
        const val KEY_LAST_POLL = "last_poll_ms"
        const val KEY_SERVICE_ON = "service_enabled"

        const val DEFAULT_BOT_URL = "https://api.mybot.com"
        const val DEFAULT_POLL_MS = 15_000L
        const val DEFAULT_TIMEOUT_MS = 120_000L
        val DEFAULT_SENDERS: Set<String> = setOf("VF-Cash", "BanK-AlAhly", "Vodafone")

        @Volatile
        private var instance: SettingsManager? = null

        fun get(context: Context): SettingsManager {
            return instance ?: synchronized(this) {
                instance ?: SettingsManager(context).also { instance = it }
            }
        }
    }
}
