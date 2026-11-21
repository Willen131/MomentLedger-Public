package com.momentledger.app.quickentry

import android.content.Context
import android.content.Intent
import com.momentledger.app.MainActivity

/**
 * Shared actions and intent factory for every zero-power quick entry surface
 * (notification, quick settings tiles, static app shortcuts).
 */
object QuickEntryActions {

    const val EXTRA_QUICK_ACTION = "extra_quick_action"

    const val ACTION_QUICK_ADD = "com.momentledger.app.action.QUICK_ADD"
    const val ACTION_SCREENSHOT_OCR = "com.momentledger.app.action.SCREENSHOT_OCR"
    const val ACTION_IMPORT_BILL = "com.momentledger.app.action.IMPORT_BILL"

    fun launchIntent(context: Context, action: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            this.action = action
            putExtra(EXTRA_QUICK_ACTION, action)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
}
