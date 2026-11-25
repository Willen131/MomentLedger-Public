package com.momentledger.app.quickentry

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.momentledger.app.MomentLedgerApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-syncs the static quick entry notification after a reboot or app update.
 * Never starts a service — [QuickEntryNotificationHelper] only posts a notification.
 */
class QuickEntryBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                val app = MomentLedgerApp.from(context)
                val pendingResult = goAsync()
                app.appScope.launch(Dispatchers.IO) {
                    try {
                        val enabled = app.preferences.quickNotificationEnabled.value
                        QuickEntryNotificationHelper.syncNotification(app, enabled)
                        AccessibilityAutoRestore.tryRestoreIfNeeded(app)
                    } catch (_: Throwable) {
                        // Ignore boot-time notification sync failures.
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
