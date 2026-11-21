package com.momentledger.app.quickentry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.momentledger.app.R

/**
 * Static, zero-power quick entry notification.
 *
 * Deliberately contains NO ForegroundService: the notification is posted once and
 * lives until the user toggles it off, so there is no process to keep alive and no
 * background battery cost.
 */
object QuickEntryNotificationHelper {

    const val CHANNEL_ID = "quick_entry_shortcut"
    const val NOTIFICATION_ID = 2001

    fun hasNotificationPermission(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun syncNotification(context: Context, enabled: Boolean) {
        val appContext = context.applicationContext
        if (!enabled) {
            cancelNotification(appContext)
            return
        }
        if (!hasNotificationPermission(appContext)) {
            return
        }

        ensureChannel(appContext)

        val contentIntent = PendingIntent.getActivity(
            appContext,
            NOTIFICATION_ID,
            QuickEntryActions.launchIntent(appContext, QuickEntryActions.ACTION_QUICK_ADD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val quickAddIntent = PendingIntent.getActivity(
            appContext,
            2002,
            QuickEntryActions.launchIntent(appContext, QuickEntryActions.ACTION_QUICK_ADD),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val screenshotIntent = PendingIntent.getActivity(
            appContext,
            2003,
            QuickEntryActions.launchIntent(appContext, QuickEntryActions.ACTION_SCREENSHOT_OCR),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("刻记 · 快捷记账")
            .setContentText("点击记一笔，零后台耗电")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)
            .addAction(0, "记一笔", quickAddIntent)
            .addAction(0, "截图识账", screenshotIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            NotificationManagerCompat.from(appContext).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // Permission revoked concurrently by the user.
        }
    }

    fun cancelNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "快捷记账入口",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
