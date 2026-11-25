package com.momentledger.app.quickentry

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.momentledger.app.MomentLedgerApp
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
    const val CHANNEL_ID_EXPANDED = "quick_entry_shortcut_expanded"
    const val NOTIFICATION_ID = 2001
    private const val REVIEW_READY_CHANNEL_ID = "screen_review_ready"
    private const val REVIEW_READY_NOTIFICATION_ID = 2005

    fun hasNotificationPermission(context: Context): Boolean {
        val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return runtimeGranted &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun syncNotification(
        context: Context,
        enabled: Boolean,
        forceRefresh: Boolean = false
    ) {
        val appContext = context.applicationContext
        if (!enabled) {
            cancelNotification(appContext)
            return
        }
        if (!hasNotificationPermission(appContext)) {
            return
        }

        val expanded = MomentLedgerApp.from(appContext).preferences.quickNotificationExpanded.value
        val channelId = if (expanded) CHANNEL_ID_EXPANDED else CHANNEL_ID
        ensureChannels(appContext)
        if (forceRefresh) {
            cancelNotification(appContext)
        }

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
        val captureEnabled = ScreenCaptureAccessibilityService.isEnabled(appContext)
        val captureIntent = if (captureEnabled) {
            PendingIntent.getBroadcast(
                appContext,
                2004,
                Intent(appContext, ScreenCaptureReceiver::class.java)
                    .setAction(QuickEntryActions.ACTION_CAPTURE_SCREEN),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            null
        }

        val builder = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("刻记 · 快捷记账")
            .setContentText(if (captureEnabled) "记一笔 · 截图识账 · 一键识屏" else "记一笔 · 截图识账")
            .setPriority(if (expanded) NotificationCompat.PRIORITY_DEFAULT else NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setAutoCancel(false)
            .setContentIntent(contentIntent)

        if (expanded) {
            val remoteViews = RemoteViews(appContext.packageName, R.layout.notification_quick_entry_expanded).apply {
                setOnClickPendingIntent(R.id.btn_quick_add, quickAddIntent)
                setOnClickPendingIntent(R.id.btn_screenshot_ocr, screenshotIntent)
                if (captureIntent != null) {
                    setViewVisibility(R.id.btn_capture_screen, View.VISIBLE)
                    setOnClickPendingIntent(R.id.btn_capture_screen, captureIntent)
                } else {
                    setViewVisibility(R.id.btn_capture_screen, View.GONE)
                }
            }
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(remoteViews)
                .setCustomBigContentView(remoteViews)
        } else {
            builder
                .addAction(0, "记一笔", quickAddIntent)
                .addAction(0, "截图识账", screenshotIntent)
            if (captureIntent != null) {
                builder.addAction(0, "一键识屏", captureIntent)
            }
        }

        val notification = builder.build()

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

    fun showScreenReviewReadyNotification(
        context: Context,
        token: String,
        itemCount: Int
    ): Boolean {
        val appContext = context.applicationContext
        if (!hasNotificationPermission(appContext)) return false
        ensureReviewReadyChannel(appContext)
        val launchIntent = QuickEntryActions.launchIntent(
            appContext,
            QuickEntryActions.ACTION_SCREEN_REVIEW
        ).putExtra(QuickEntryActions.EXTRA_SCREEN_REVIEW_TOKEN, token)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            REVIEW_READY_NOTIFICATION_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val countText = if (itemCount > 1) "已识别到 $itemCount 笔账单" else "已识别到 1 笔账单"
        val notification = NotificationCompat.Builder(appContext, REVIEW_READY_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("刻记 · $countText")
            .setContentText("点击进入核对并入账（2 分钟内有效）")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setTimeoutAfter(ScreenReviewHandoff.NOTIFICATION_FALLBACK_TTL_MILLIS)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        return try {
            NotificationManagerCompat.from(appContext).notify(REVIEW_READY_NOTIFICATION_ID, notification)
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun cancelScreenReviewReadyNotification(context: Context) {
        runCatching {
            NotificationManagerCompat.from(context.applicationContext).cancel(REVIEW_READY_NOTIFICATION_ID)
        }
    }

    private fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val compactChannel = NotificationChannel(
            CHANNEL_ID,
            "快捷记账入口（折叠）",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        val expandedChannel = NotificationChannel(
            CHANNEL_ID_EXPANDED,
            "快捷记账入口（展开按钮）",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(compactChannel)
        manager.createNotificationChannel(expandedChannel)
    }

    private fun ensureReviewReadyChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            REVIEW_READY_CHANNEL_ID,
            "识屏结果提醒",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }
}
