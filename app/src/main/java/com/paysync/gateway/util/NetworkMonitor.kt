package com.paysync.gateway.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.paysync.gateway.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Real-time connectivity observer using [ConnectivityManager.NetworkCallback].
 *
 * Updates a [MutableStateFlow] instantly on network loss / gain so that any
 * collector on `Dispatchers.Main.immediate` triggers recomposition in the
 * exact same frame the network state changes.
 *
 * Call [start] once (from Application.onCreate) and [stop] on teardown.
 */
class NetworkMonitor(appContext: Context) {

    private val cm: ConnectivityManager? =
        appContext.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _transport = MutableStateFlow(checkTransport())
    val transport: StateFlow<String> = _transport.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {

        override fun onAvailable(network: Network) {
            val caps = cm?.getNetworkCapabilities(network)
            if (caps != null) {
                refreshFromCaps(caps)
            } else {
                refresh()
            }
        }

        override fun onLost(network: Network) {
            // Instant disconnect — flip MutableStateFlow.value so the UI
            // recomposes in the exact same frame the network drops.
            _isOnline.value = false
            _transport.value = TRANSPORT_NONE

            // Check for a cellular/ethernet fallback already active
            val active = cm?.activeNetwork
            if (active != null && active != network) {
                val fallbackCaps = cm?.getNetworkCapabilities(active)
                if (fallbackCaps != null) {
                    refreshFromCaps(fallbackCaps)
                }
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            refreshFromCaps(caps)
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (blocked) {
                _isOnline.value = false
                _transport.value = TRANSPORT_NONE
            } else {
                refresh()
            }
        }

        override fun onUnavailable() {
            _isOnline.value = false
            _transport.value = TRANSPORT_NONE
        }
    }

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        refresh()
        try {
            // registerDefaultNetworkCallback follows the system default route (API 24+).
            cm?.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm?.registerNetworkCallback(request, callback)
            } catch (e: Exception) {
                AppLog.w(TAG, "registerNetworkCallback fallback failed", e)
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        try {
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) { /* already unregistered */ }
    }

    /** Snapshot query — no flow collection needed. */
    fun isOnlineNow(): Boolean = _isOnline.value

    // ─── Internals ────────────────────────────────────────────────────

    private fun refreshFromCaps(caps: NetworkCapabilities) {
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        _isOnline.value = hasInternet && isValidated
        _transport.value = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> TRANSPORT_WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TRANSPORT_CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TRANSPORT_ETHERNET
            else -> TRANSPORT_OTHER
        }
    }

    fun refresh() {
        _isOnline.value = checkOnline()
        _transport.value = checkTransport()
    }

    private fun checkOnline(): Boolean {
        return try {
            val network = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            false
        }
    }

    private fun checkTransport(): String {
        return try {
            val network = cm?.activeNetwork ?: return TRANSPORT_NONE
            val caps = cm.getNetworkCapabilities(network) ?: return TRANSPORT_NONE
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> TRANSPORT_WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TRANSPORT_CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TRANSPORT_ETHERNET
                else -> TRANSPORT_OTHER
            }
        } catch (_: Exception) {
            TRANSPORT_NONE
        }
    }

    companion object {
        private const val TAG = "NetworkMonitor"
        const val TRANSPORT_WIFI     = "Wi-Fi"
        const val TRANSPORT_CELLULAR = "Cellular"
        const val TRANSPORT_ETHERNET = "Ethernet"
        const val TRANSPORT_OTHER    = "Other"
        const val TRANSPORT_NONE     = "None"
    }
}
