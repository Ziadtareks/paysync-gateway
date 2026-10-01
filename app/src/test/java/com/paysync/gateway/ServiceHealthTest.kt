package com.paysync.gateway

import com.paysync.gateway.util.ServiceHealth
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceHealthTest {

    private val interval = 15_000L

    @Test
    fun neverStarted_heartbeatZero_isNotAlive() {
        assertFalse(ServiceHealth.isAlive(0L, 1_000_000L, interval))
        assertFalse(ServiceHealth.isAlive(-1L, 1_000_000L, interval))
    }

    @Test
    fun freshHeartbeat_isAlive() {
        val now = 1_000_000L
        assertTrue(ServiceHealth.isAlive(now - 1_000L, now, interval))
        assertTrue(ServiceHealth.isAlive(now, now, interval))
    }

    @Test
    fun withinIntervalPlusMargin_isAlive() {
        val now = 1_000_000L
        // Exactly at the window boundary (interval + margin) is still alive.
        assertTrue(ServiceHealth.isAlive(now - (interval + ServiceHealth.FRESH_MARGIN_MS), now, interval))
    }

    @Test
    fun beyondIntervalPlusMargin_isStale() {
        val now = 1_000_000L
        assertFalse(
            ServiceHealth.isAlive(now - (interval + ServiceHealth.FRESH_MARGIN_MS + 1), now, interval)
        )
    }

    @Test
    fun fiveSecondPollInterval_usesMinimumWindow() {
        val now = 1_000_000L
        // Window floor: 5s interval + 20s margin = 25s.
        assertTrue(ServiceHealth.isAlive(now - 25_000L, now, 5_000L))
        assertFalse(ServiceHealth.isAlive(now - 25_001L, now, 5_000L))
    }

    @Test
    fun longPollInterval_scalesWindow() {
        val now = 1_000_000L
        // 300s poll interval → 320s window.
        assertTrue(ServiceHealth.isAlive(now - 320_000L, now, 300_000L))
        assertFalse(ServiceHealth.isAlive(now - 320_001L, now, 300_000L))
    }

    @Test
    fun clockSkew_heartbeatInFuture_isNotAlive() {
        // A heartbeat stamped in the future must not count as alive.
        assertFalse(ServiceHealth.isAlive(2_000_000L, 1_000_000L, interval))
    }
}
