package com.paysync.gateway.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.paysync.gateway.AppContainer
import com.paysync.gateway.R
import com.paysync.gateway.data.DispatchLog
import com.paysync.gateway.data.GatewayRepository
import com.paysync.gateway.util.LocaleHelper
import com.paysync.gateway.util.NetworkMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── UI Event Channel ─────────────────────────────────────────────────

sealed interface UiEvent {
    data class Message(val resId: Int, val arg: String? = null) : UiEvent
}

// ─── UI State ─────────────────────────────────────────────────────────

data class UiState(
    /** Persisted toggle (the user's intent that the gateway run). */
    val isRunning: Boolean = false,
    /** Whether the foreground service is ACTUALLY alive (fresh heartbeat). */
    val serviceAlive: Boolean = false,
    val pendingCount: Int = 0,
    val queueDepth: Int = 0,
    val heartbeatMs: Long = 0L,
    val lastPollMs: Long = 0L,
    val logs: List<DispatchLog> = emptyList(),
    val isPolling: Boolean = false,
    val botUrl: String = "",
    val pollSeconds: Long = 15L,
    // ─── device truth ───
    val isOnline: Boolean = true,
    val networkLabel: String = NetworkMonitor.TRANSPORT_NONE,
    /** 0..100, or -1 when unreadable. */
    val batteryPct: Int = -1,
    val isCharging: Boolean = false
)

// ─── Internal combine helpers (avoid >5-arity combine) ────────────────

private data class GatewayHead(
    val isRunning: Boolean,
    val serviceAlive: Boolean,
    val pendingCount: Int,
    val queueDepth: Int,
    val heartbeatMs: Long,
    val lastPollMs: Long,
    val logs: List<DispatchLog>
)

private data class GatewayTail(
    val isPolling: Boolean,
    val botUrl: String,
    val pollSeconds: Long
)

private data class DeviceHead(
    val isOnline: Boolean,
    val networkLabel: String,
    val batteryPct: Int,
    val isCharging: Boolean
)

// ═══════════════════════════════════════════════════════════════════════
// MainViewModel
// ═══════════════════════════════════════════════════════════════════════

class MainViewModel(private val container: AppContainer) : ViewModel() {

    private val settings get() = container.settings
    private val repo get() = container.repo
    private val monitor get() = container.networkMonitor

    // ─── Room-backed live counts ──────────────────────────────────────

    val pendingCount: StateFlow<Int> =
        repo.pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val queueDepth: StateFlow<Int> =
        repo.queueDepth.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val recentDispatches: StateFlow<List<DispatchLog>> =
        repo.recentDispatches.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ─── Network truth (collected on Main.immediate for zero-latency) ─

    private val _isOnline = MutableStateFlow(monitor.isOnlineNow())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _networkLabel = MutableStateFlow(monitor.transport.value)
    val networkLabel: StateFlow<String> = _networkLabel.asStateFlow()

    // ─── Permission truth (pushed by MainActivity via ON_RESUME) ──────

    val smsGranted = MutableStateFlow(false)
    val notifGranted = MutableStateFlow(false)
    val batteryExempt = MutableStateFlow(false)

    /**
     * Called on every ON_RESUME + permission-result callback from [MainActivity].
     * Immediately updates MutableStateFlows so the UI recomposes without delay.
     */
    fun refreshPermissions(sms: Boolean, notif: Boolean, battery: Boolean) {
        smsGranted.value = sms
        notifGranted.value = notif
        batteryExempt.value = battery
        refreshBattery()
    }

    // ─── Settings-backed state ────────────────────────────────────────

    val botUrl = MutableStateFlow(settings.botApiUrl)
    val secret = MutableStateFlow(settings.webhookSecret)
    /** Legacy X-Gateway-Secret header (default ON for existing backends). */
    val legacySecretHeader = MutableStateFlow(settings.sendLegacySecretHeader)
    /** False when the Keystore failed and settings are stored unencrypted. */
    val settingsEncrypted: Boolean get() = settings.isEncrypted
    val pollSeconds = MutableStateFlow((settings.pollingIntervalMs / 1000L).toString())
    val providers = MutableStateFlow<List<String>>(settings.getSenders().sorted())
    val newProvider = MutableStateFlow("")
    private val serviceOn = MutableStateFlow(settings.serviceEnabled)

    /** Liveness truth: persisted toggle alone can lie after an app update. */
    val serviceAlive = MutableStateFlow(computeServiceAlive())

    /**
     * Ground truth for the Dashboard: a fresh heartbeat AND the service
     * actually present in this app's running-service list. The running check
     * corrects the small window right after a kill where the heartbeat has
     * not gone stale yet (e.g. force-stop, then relaunch).
     */
    fun computeServiceAlive(): Boolean {
        val fresh = com.paysync.gateway.util.ServiceHealth.isAlive(
            settings.serviceHeartbeatMs, System.currentTimeMillis(), settings.pollingIntervalMs
        )
        if (!fresh) return false
        val am = container.appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        @Suppress("DEPRECATION")
        return am?.getRunningServices(200)?.any {
            it.service.className == com.paysync.gateway.service.PaymentForegroundService::class.java.name
        } ?: false
    }
    val heartbeatMs = MutableStateFlow(settings.lastHeartbeat)
    val lastPollMs = MutableStateFlow(settings.lastPollMs)

    // ── Matching safety settings ──────────────────────────────────────
    /** Amount-fallback toggle (default ON = historical behavior). */
    val amountFallback = MutableStateFlow(settings.amountFallbackEnabled)
    /** Max auto-confirm amount as text; empty = disabled. */
    val maxAutoAmount = MutableStateFlow(formatAmountInput(settings.maxAutoConfirmAmountEgp))

    // ── Update checker (opt-out; see PRIVACY.md) ──────────────────────

    val updateAvailable = MutableStateFlow<com.paysync.gateway.data.UpdateChecker.UpdateInfo?>(null)
    val updateCheckEnabled = MutableStateFlow(settings.updateCheckEnabled)

    fun setUpdateCheckEnabled(enabled: Boolean) {
        updateCheckEnabled.value = enabled
        settings.updateCheckEnabled = enabled
        if (!enabled) updateAvailable.value = null
    }

    /**
     * At most one anonymous GitHub releases lookup per 24 h. Silent on
     * offline / rate-limit errors; never downloads anything.
     */
    fun maybeCheckForUpdate() {
        if (!updateCheckEnabled.value) return
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheckAt < 24L * 60 * 60 * 1000) return
        settings.lastUpdateCheckAt = now
        viewModelScope.launch(Dispatchers.IO) {
            val info = runCatching {
                com.paysync.gateway.data.UpdateChecker().checkLatestRelease()
            }.getOrNull() ?: return@launch
            if (com.paysync.gateway.data.UpdateChecker.isNewer(info.latestVersion)) {
                updateAvailable.value = info
            }
        }
    }

    // ─── Battery ──────────────────────────────────────────────────────

    private val _batteryPct = MutableStateFlow(readBatteryPct())
    val batteryPct: StateFlow<Int> = _batteryPct.asStateFlow()

    private val _isCharging = MutableStateFlow(readIsCharging())
    val isCharging: StateFlow<Boolean> = _isCharging.asStateFlow()

    // ─── UI transient state ───────────────────────────────────────────

    val isSaving = MutableStateFlow(false)
    val saveConfirmed = MutableStateFlow(false)
    val isPolling = MutableStateFlow(false)
    val appLang = MutableStateFlow(LocaleHelper.currentTag())

    // ─── Derived flows ────────────────────────────────────────────────

    private val pollSecondsLong: StateFlow<Long> = pollSeconds
        .map { text -> text.toLongOrNull()?.coerceIn(5L, 300L) ?: FALLBACK_POLL_SECONDS }
        .stateIn(viewModelScope, SharingStarted.Eagerly, initialPollSeconds())

    /**
     * Single combined [UiState] for the Dashboard.
     * Uses three intermediate data classes to stay within Kotlin's 5-arity combine limit.
     */
    val uiState: StateFlow<UiState> = combine(
        headFlow(), tailFlow(), deviceFlow()
    ) { head: GatewayHead, tail: GatewayTail, device: DeviceHead ->
        UiState(
            isRunning = head.isRunning,
            serviceAlive = head.serviceAlive,
            pendingCount = head.pendingCount,
            queueDepth = head.queueDepth,
            heartbeatMs = head.heartbeatMs,
            lastPollMs = head.lastPollMs,
            logs = head.logs,
            isPolling = tail.isPolling,
            botUrl = tail.botUrl,
            pollSeconds = tail.pollSeconds,
            isOnline = device.isOnline,
            networkLabel = device.networkLabel,
            batteryPct = device.batteryPct,
            isCharging = device.isCharging
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UiState(
            serviceAlive = computeServiceAlive(),
            isOnline = monitor.isOnlineNow(),
            networkLabel = monitor.transport.value,
            batteryPct = _batteryPct.value,
            isCharging = _isCharging.value,
            heartbeatMs = heartbeatMs.value,
            lastPollMs = lastPollMs.value
        )
    )

    private fun headFlow(): Flow<GatewayHead> = combine(
        serviceOn, pendingCount, queueDepth, heartbeatMs, lastPollMs, recentDispatches, serviceAlive
    ) { args: Array<Any> ->
        @Suppress("UNCHECKED_CAST")
        GatewayHead(
            isRunning = args[0] as Boolean,
            serviceAlive = args[6] as Boolean,
            pendingCount = args[1] as Int,
            queueDepth = args[2] as Int,
            heartbeatMs = args[3] as Long,
            lastPollMs = args[4] as Long,
            logs = args[5] as List<DispatchLog>
        )
    }

    private fun tailFlow(): Flow<GatewayTail> = combine(
        isPolling, botUrl, pollSecondsLong
    ) { polling: Boolean, url: String, secs: Long ->
        GatewayTail(isPolling = polling, botUrl = url, pollSeconds = secs)
    }

    private fun deviceFlow(): Flow<DeviceHead> = combine(
        _isOnline, _networkLabel, _batteryPct, _isCharging
    ) { online: Boolean, label: String, pct: Int, charging: Boolean ->
        DeviceHead(isOnline = online, networkLabel = label, batteryPct = pct, isCharging = charging)
    }

    private fun initialPollSeconds(): Long =
        (settings.pollingIntervalMs / 1000L).coerceIn(5L, 300L)

    // ─── One-shot event channel ───────────────────────────────────────

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // ─── Init: collect network flows on Main.immediate ────────────────

    init {
        // Collect the NetworkMonitor StateFlow explicitly on Dispatchers.Main.immediate
        // so UI recomposes in the exact same frame the network drops.
        viewModelScope.launch(Dispatchers.Main.immediate) {
            monitor.isOnline.collect { online ->
                _isOnline.value = online
            }
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            monitor.transport.collect { transport ->
                _networkLabel.value = transport
            }
        }

        // Periodic background poll for battery + system timestamps
        viewModelScope.launch {
            while (true) {
                heartbeatMs.value = settings.lastHeartbeat
                lastPollMs.value = settings.lastPollMs
                serviceOn.value = settings.serviceEnabled
                serviceAlive.value = computeServiceAlive()
                refreshBattery()
                delay(5_000L)
            }
        }
    }

    // ─── Battery Helpers ──────────────────────────────────────────────

    private fun batteryIntent(): Intent? = try {
        container.appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    } catch (_: Exception) {
        null
    }

    fun refreshBattery() {
        _batteryPct.value = readBatteryPct()
        _isCharging.value = readIsCharging()
    }

    private fun readBatteryPct(): Int = try {
        val bm = container.appContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val cap = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        if (cap in 0..100) cap
        else {
            val i = batteryIntent()
            val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        }
    } catch (_: Exception) {
        -1
    }

    private fun readIsCharging(): Boolean = try {
        val status = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Exception) {
        false
    }

    // ─── User Actions ─────────────────────────────────────────────────

    fun refresh() {
        providers.value = settings.getSenders().sorted()
        serviceOn.value = settings.serviceEnabled
        serviceAlive.value = computeServiceAlive()
        heartbeatMs.value = settings.lastHeartbeat
        lastPollMs.value = settings.lastPollMs
        amountFallback.value = settings.amountFallbackEnabled
        maxAutoAmount.value = formatAmountInput(settings.maxAutoConfirmAmountEgp)
        refreshBattery()
        appLang.value = LocaleHelper.currentTag()
    }

    fun setLanguage(tag: String) {
        if (tag == appLang.value) return
        LocaleHelper.setLanguage(tag)
        appLang.value = LocaleHelper.currentTag()
    }

    fun saveConfig() {
        if (isSaving.value) return
        val url = botUrl.value.trim()
        if (url.isNotBlank() && !url.startsWith("http://") && !url.startsWith("https://")) {
            emit(R.string.msg_invalid_url)
            return
        }
        // Release builds never talk cleartext: the network security config
        // blocks http:// anyway, so refuse it up front with a clear message.
        if (!com.paysync.gateway.BuildConfig.DEBUG && url.startsWith("http://")) {
            emit(R.string.msg_https_required)
            return
        }
        // Without a secret the app sends NO authentication at all — any
        // backend accepting that would let anyone confirm deposits.
        if (secret.value.isBlank()) {
            emit(R.string.msg_secret_required)
            return
        }
        // Max auto-confirm amount: empty disables, otherwise must parse > 0.
        val maxText = maxAutoAmount.value.trim()
        val maxEgp = when {
            maxText.isEmpty() -> 0.0
            else -> maxText.toDoubleOrNull()?.takeIf { it > 0.0 }
        }
        if (maxText.isNotEmpty() && maxEgp == null) {
            emit(R.string.msg_invalid_amount)
            return
        }
        isSaving.value = true
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (url.isNotBlank()) settings.botApiUrl = url
                settings.webhookSecret = secret.value
                settings.sendLegacySecretHeader = legacySecretHeader.value
                // The field only accepts digits; out-of-range values are
                // clamped to 5..300 s and shown back, never silently dropped.
                pollSeconds.value.toLongOrNull()?.let { secs ->
                    settings.pollingIntervalMs = secs.coerceIn(5L, 300L) * 1000L
                }
                pollSeconds.value = (settings.pollingIntervalMs / 1000L).toString()
                settings.amountFallbackEnabled = amountFallback.value
                settings.maxAutoConfirmAmountEgp = maxEgp ?: 0.0
                runCatching { repo.pollPending() }
                lastPollMs.value = settings.lastPollMs
            }
            isSaving.value = false
            saveConfirmed.value = true
            emit(R.string.msg_config_saved)
            viewModelScope.launch {
                delay(3_000L)
                saveConfirmed.value = false
            }
        }
    }

    fun addProvider() {
        val name = newProvider.value.trim()
        if (name.isEmpty()) {
            emit(R.string.msg_enter_sender)
            return
        }
        try {
            settings.addSender(name)
            newProvider.value = ""
            refresh()
            emit(R.string.msg_provider_added, name)
        } catch (_: Exception) {
            emit(R.string.msg_enter_sender)
        }
    }

    fun removeProvider(name: String) {
        settings.removeSender(name)
        refresh()
        emit(R.string.msg_provider_removed, name)
    }

    fun setServiceOn(on: Boolean) {
        serviceOn.value = on
    }

    fun pollNow() {
        if (isPolling.value) return
        isPolling.value = true
        viewModelScope.launch {
            val result = runCatching { repo.pollPending() }
            isPolling.value = false
            lastPollMs.value = settings.lastPollMs
            result
                .onSuccess {
                    when (it) {
                        GatewayRepository.PollResult.OK -> emit(R.string.msg_polled)
                        GatewayRepository.PollResult.NOT_CONFIGURED -> emit(R.string.msg_poll_not_configured)
                        GatewayRepository.PollResult.OFFLINE -> emit(R.string.msg_poll_offline)
                    }
                }
                .onFailure { emit(R.string.msg_poll_failed, it.message ?: "?") }
        }
    }

    fun clearLogs() {
        viewModelScope.launch {
            runCatching { repo.clearLogs() }
        }
    }

    private fun emit(resId: Int, arg: String? = null) {
        viewModelScope.launch { _events.send(UiEvent.Message(resId, arg)) }
    }

    companion object {
        private const val FALLBACK_POLL_SECONDS = 15L

        /** "0" / "0.0" render as empty (disabled); anything else keeps user-friendly text. */
        fun formatAmountInput(egp: Double): String =
            if (egp > 0.0) {
                if (egp % 1.0 == 0.0) egp.toLong().toString() else egp.toString()
            } else ""
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Factory (manual DI — no Hilt/Koin)
// ═══════════════════════════════════════════════════════════════════════

class MainViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == MainViewModel::class.java) {
            "MainViewModelFactory only creates MainViewModel, got ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return MainViewModel(container) as T
    }
}
