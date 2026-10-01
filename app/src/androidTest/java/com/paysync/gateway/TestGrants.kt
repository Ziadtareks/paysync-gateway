package com.paysync.gateway

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Grants POST_NOTIFICATIONS for the target package via the instrumentation's
 * UiAutomation. Notification-asserting tests must call this in @Before: a
 * fresh app install has no runtime grants, and without POST_NOTIFICATIONS the
 * system silently drops every notification on API 33+.
 */
fun grantPostNotifications() {
    InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
        "pm grant com.paysync.gateway android.permission.POST_NOTIFICATIONS"
    ).close()
}
