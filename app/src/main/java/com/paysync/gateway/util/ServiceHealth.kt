package com.paysync.gateway.util

/**
 * Pure liveness math for the gateway foreground service (unit-testable).
 *
 * The service writes a heartbeat timestamp to settings at start and on every
 * poll-loop tick. The UI and the WorkManager poller treat the service as
 * ALIVE only when that timestamp is fresh — one poll interval plus a fixed
 * margin — so a killed service (e.g. after an in-place app update) is
 * detected instead of trusting the persisted toggle.
 */
object ServiceHealth {

    /** Extra time on top of one poll interval before a heartbeat is stale. */
    const val FRESH_MARGIN_MS = 20_000L

    fun freshnessWindowMs(pollIntervalMs: Long): Long =
        pollIntervalMs.coerceAtLeast(5_000L) + FRESH_MARGIN_MS

    /** True only when a heartbeat exists (non-zero) and is fresh for [pollIntervalMs]. */
    fun isAlive(lastHeartbeatMs: Long, nowMs: Long, pollIntervalMs: Long): Boolean {
        if (lastHeartbeatMs <= 0L) return false
        val age = nowMs - lastHeartbeatMs
        return age >= 0 && age <= freshnessWindowMs(pollIntervalMs)
    }
}
