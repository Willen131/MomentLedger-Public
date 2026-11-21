package com.momentledger.app.quickentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.momentledger.app.MomentLedgerApp

/**
 * Re-syncs the static quick entry notification after a reboot or app update.
 * Never starts a service — [QuickEntryNotificationHelper] only posts a notification.
 */
class QuickEntryBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                runCatching {
                    val enabled = MomentLedgerApp.from(context)
                        .preferences
                        .quickNotificationEnabled
                        .value
                    QuickEntryNotificationHelper.syncNotification(context, enabled)
                }
            }
        }
    }
}
