package com.paysync.gateway

import com.paysync.gateway.data.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun isNewer_comparesNumerically() {
        assertTrue(UpdateChecker.isNewer("1.1.1", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("2.0.0", "1.9.5"))
        assertFalse(UpdateChecker.isNewer("1.1.0", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.0.9", "1.1.0"))
    }

    @Test
    fun isNewer_handlesVPrefixAndPreReleases() {
        assertTrue(UpdateChecker.isNewer("v1.2.0", "1.1.0"))
        // Pre-releases never trigger the notice.
        assertFalse(UpdateChecker.isNewer("1.2.0-rc1", "1.1.0") && UpdateChecker.isNewer("1.1.0-rc1", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("1.1.0-rc1", "1.1.0"))
        assertTrue(UpdateChecker.isNewer("1.2.0-rc1", "1.1.0"))
    }

    @Test
    fun isNewer_garbageInput_isNeverNewer() {
        assertFalse(UpdateChecker.isNewer("", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("abc", "1.1.0"))
        assertFalse(UpdateChecker.isNewer("not.a.version", "1.1.0"))
    }
}
