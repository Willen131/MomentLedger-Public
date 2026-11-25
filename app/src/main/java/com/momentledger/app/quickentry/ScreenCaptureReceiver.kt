package com.momentledger.app.quickentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The immutable notification action is the only external entry to this private receiver. */
class ScreenCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != QuickEntryActions.ACTION_CAPTURE_SCREEN) return
        if (!ScreenCaptureAccessibilityService.isConnected() &&
            AccessibilityAutoRestore.hasWriteSecureSettings(context)
        ) {
            val pendingResult = goAsync()
            ScreenCaptureAccessibilityService.requestFromPanel(context) {
                runCatching { pendingResult.finish() }
            }
        } else {
            ScreenCaptureAccessibilityService.requestFromPanel(context)
        }
    }
}
