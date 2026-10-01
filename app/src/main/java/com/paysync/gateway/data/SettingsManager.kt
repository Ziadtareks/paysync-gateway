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

    /** Start of the current continuous poll-failure streak (0 = healthy). */
    var healthFirstFailureAt: Long
        get() = prefs.getLong(KEY_HEALTH_FIRST_FAIL, 0L)
        set(value) { prefs.edit().putLong(KEY_HEALTH_FIRST_FAIL, value).apply() }

    /** Last time the "not verifying payments" alert was posted (rate limiting). */
    var healthLastAlertAt: Long
        get() = prefs.getLong(KEY_HEALTH_LAST_ALERT, 0L)
        set(value) { prefs.edit().putLong(KEY_HEALTH_LAST_ALERT, value).apply() }

    /** Last liveness tick written by the foreground service loop (0 = never run). */
    var serviceHeartbeatMs: Long
        get() = prefs.getLong(KEY_SERVICE_HEARTBEAT, 0L)
        set(value) { prefs.edit().putLong(KEY_SERVICE_HEARTBEAT, value).apply() }

    /** Opt-out update check (default ON). See PRIVACY.md for the disclosure. */
    var updateCheckEnabled: Boolean
        get() = prefs.getBoolean(KEY_UPDATE_CHECK, true)
        set(value) { prefs.edit().putBoolean(KEY_UPDATE_CHECK, value).apply() }

    /** Throttle: at most one GitHub releases lookup per day. */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply() }

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE_ON, false)
        set(value) { prefs.edit().putBoolean(KEY_SERVICE_ON, value).apply() }

    /**
     * When true (the historical default), a deposit with no reference hint can
     * be confirmed by provider + amount (±0.01 EGP). When false, only an exact
     * transaction-reference match confirms — safer at the cost of misses for
     * backends that never send reference_id_hint.
     */
    var amountFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_AMOUNT_FALLBACK, true)
        set(value) { prefs.edit().putBoolean(KEY_AMOUNT_FALLBACK, value).apply() }

    /**
     * Auto-confirm cap in EGP. 0.0 = disabled (historical default). When set
     * (> 0) and the matched amount exceeds it, the dispatch is NOT sent;
     * the deposit is logged for manual review and follows its normal timeout.
     */
    var maxAutoConfirmAmountEgp: Double
        get() = Double.fromBits(prefs.getLong(KEY_MAX_AMOUNT, 0L))
        set(value) { prefs.edit().putLong(KEY_MAX_AMOUNT, if (value > 0.0) value.toRawBits() else 0L).apply() }

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

    /**
     * Replaces the whole allowed-sender set in ONE write. Successive
     * per-item writes are less reliable with EncryptedSharedPreferences
     * StringSet caching, so bulk changes (and tests) should prefer this.
     */
    fun setSenders(senders: Collection<String>): Set<String> {
        val clean = senders.map { it.trim() }.filter { it.isNotEmpty() }
        prefs.edit().putStringSet(KEY_SENDERS, HashSet(clean)).apply()
        return HashSet(clean)
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
        const val KEY_AMOUNT_FALLBACK = "amount_fallback_enabled"
        const val KEY_MAX_AMOUNT = "max_auto_confirm_amount_bits"
        const val KEY_HEALTH_FIRST_FAIL = "health_first_failure_at"
        const val KEY_HEALTH_LAST_ALERT = "health_last_alert_at"
        const val KEY_UPDATE_CHECK = "update_check_enabled"
        const val KEY_SERVICE_HEARTBEAT = "service_heartbeat_ms"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check_at"

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
