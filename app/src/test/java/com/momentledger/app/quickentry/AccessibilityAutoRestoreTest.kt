package com.momentledger.app.quickentry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAutoRestoreTest {

    private val full = "com.momentledger.app/com.momentledger.app.quickentry.ScreenCaptureAccessibilityService"
    private val short = "com.momentledger.app/.quickentry.ScreenCaptureAccessibilityService"
    private val gkd = "li.songe.gkd/li.songe.gkd.service.A11yService"
    private val talkback = "com.google.android.marvin.talkback/.TalkBackService"

    @Test
    fun containsServiceMatchesFullAndShortComponentNames() {
        assertFalse(AccessibilityServicesSettingCodec.containsService(null, full, short))
        assertFalse(AccessibilityServicesSettingCodec.containsService("", full, short))
        assertFalse(AccessibilityServicesSettingCodec.containsService(gkd, full, short))
        assertTrue(AccessibilityServicesSettingCodec.containsService("$gkd:$full", full, short))
        assertTrue(AccessibilityServicesSettingCodec.containsService("$short:$talkback", full, short))
    }

    @Test
    fun appendServicePreservesOtherServicesAndAvoidsDuplicates() {
        assertEquals(
            full,
            AccessibilityServicesSettingCodec.appendService(null, full, short)
        )
        assertEquals(
            "$gkd:$talkback:$full",
            AccessibilityServicesSettingCodec.appendService("$gkd:$talkback", full, short)
        )
        assertEquals(
            "$gkd:$talkback:$full",
            AccessibilityServicesSettingCodec.appendService("$gkd:$short:$talkback:$full", full, short)
        )
    }

    @Test
    fun removeServiceRemovesOnlySelfAndPreservesOthers() {
        assertEquals(
            "$gkd:$talkback",
            AccessibilityServicesSettingCodec.removeService("$gkd:$short:$talkback", full, short)
        )
        assertEquals(
            "",
            AccessibilityServicesSettingCodec.removeService(full, full, short)
        )
    }

    @Test
    fun adbGrantCommandUsesExpectedPackageAndPermission() {
        assertEquals(
            "adb shell pm grant com.momentledger.app android.permission.WRITE_SECURE_SETTINGS",
            AccessibilityAutoRestore.adbGrantCommand("com.momentledger.app")
        )
    }
}
