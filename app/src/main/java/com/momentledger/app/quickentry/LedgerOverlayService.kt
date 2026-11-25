package com.momentledger.app.quickentry

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.momentledger.app.MomentLedgerApp
import com.momentledger.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal class LedgerOverlayService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences get() = MomentLedgerApp.from(this).preferences
    private lateinit var overlay: LedgerOverlayWindow
    private var promoted = false
    private var unlockCheckJob: Job? = null

    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            overlay.closeMenu()
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    unlockCheckJob?.cancel()
                    unlockCheckJob = null
                    updateWindow()
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> scheduleUnlockChecks()
                else -> updateWindow()
            }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId != Display.DEFAULT_DISPLAY) return
            val state = getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.state
            if (state == Display.STATE_ON) {
                scheduleUnlockChecks()
            } else if (state == Display.STATE_OFF) {
                unlockCheckJob?.cancel()
                unlockCheckJob = null
                updateWindow()
            }
        }
    }

    private val permissionWatcher = AppOpsManager.OnOpChangedListener { _, changedPackage ->
        if (changedPackage == packageName) mainExecutorCompat { updateWindow() }
    }

    override fun onCreate() {
        super.onCreate()
        val display = requireNotNull(getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY))
        val displayContext = createDisplayContext(display)
        val overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayContext
        }
        overlay = LedgerOverlayWindow(overlayContext, ::addEntry, ::captureBill)
        ContextCompat.registerReceiver(this, screenEvents, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_EXPORTED)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, mainHandler)
        getSystemService(AppOpsManager::class.java)?.startWatchingMode(
            AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionWatcher
        )
        active = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            preferences.setOverlayMode(OverlayMode.OFF.id)
            stopSelf()
            return START_NOT_STICKY
        }
        if (OverlayMode.fromId(preferences.overlayMode.value) == OverlayMode.OFF || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!promoted) {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
            promoted = true
            scope.launch {
                combine(preferences.overlayMode, preferences.bubbleAppearance) { _, _ -> Unit }
                    .collect { updateWindow() }
            }
        } else {
            scheduleUnlockChecks()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateWindow()
    }

    override fun onDestroy() {
        if (active === this) active = null
        unlockCheckJob?.cancel()
        unlockCheckJob = null
        scope.cancel()
        runCatching { unregisterReceiver(screenEvents) }
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        getSystemService(AppOpsManager::class.java)?.stopWatchingMode(permissionWatcher)
        overlay.detach()
        super.onDestroy()
    }

    private fun isScreenUnlockedAndInteractive(): Boolean {
        val interactive = getSystemService(PowerManager::class.java)?.isInteractive == true
        val unlocked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
        return interactive && unlocked
    }

    private fun scheduleUnlockChecks() {
        updateWindow()
        if (isScreenUnlockedAndInteractive()) {
            unlockCheckJob?.cancel()
            unlockCheckJob = null
            return
        }
        unlockCheckJob?.cancel()
        unlockCheckJob = scope.launch {
            for (waitMs in longArrayOf(220L, 450L, 850L, 1400L)) {
                delay(waitMs)
                updateWindow()
                if (isScreenUnlockedAndInteractive()) break
            }
            unlockCheckJob = null
        }
    }

    private fun updateWindow() {
        if (!promoted) return
        val mode = OverlayMode.fromId(preferences.overlayMode.value)
        if (mode == OverlayMode.OFF || !Settings.canDrawOverlays(this)) {
            overlay.detach()
            stopSelf()
            return
        }
        val visible = isScreenUnlockedAndInteractive() && !captureHidden
        val appearance = preferences.bubbleAppearance.value
        try {
            overlay.render(mode, appearance, visible)
        } catch (_: IllegalArgumentException) {
            overlay.detach()
            runCatching { overlay.render(mode, appearance, visible) }
        } catch (_: WindowManager.BadTokenException) {
            stopSelf()
        } catch (_: SecurityException) {
            stopSelf()
        }
    }

    private fun addEntry() {
        runCatching { startActivity(QuickEntryActions.launchIntent(this, QuickEntryActions.ACTION_QUICK_ADD)) }
            .onFailure { showMessage("无法打开记账，请打开刻记重试") }
    }

    private fun captureBill() {
        when {
            !ScreenCaptureAccessibilityService.isSupported -> showMessage("一键识屏需要 Android 12 或更新版本")
            !ScreenCaptureAccessibilityService.isEnabled(this) -> {
                showMessage("请在刻记设置中启用并授权一键识屏")
                startActivity(QuickEntryActions.launchIntent(this, QuickEntryActions.ACTION_OPEN_SETTINGS))
            }
            else -> ScreenCaptureAccessibilityService.requestFromPanel(this)
        }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL, "悬浮快捷入口", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
        )
        val settings = PendingIntent.getActivity(
            this, 2200,
            QuickEntryActions.launchIntent(this, QuickEntryActions.ACTION_OPEN_SETTINGS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val close = PendingIntent.getService(
            this, 2201,
            Intent(this, LedgerOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("刻记 · 悬浮快捷入口")
            .setContentText("点此进入设置，或直接关闭悬浮入口")
            .setContentIntent(settings)
            .addAction(0, "关闭悬浮入口", close)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun showMessage(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun mainExecutorCompat(action: () -> Unit) {
        scope.launch { if (active === this@LedgerOverlayService) action() }
    }

    companion object {
        private const val CHANNEL = "floating_quick_entry"
        private const val NOTIFICATION_ID = 2200
        private const val ACTION_STOP = "com.momentledger.app.action.STOP_OVERLAY"
        private var active: LedgerOverlayService? = null
        private var captureHidden = false

        fun syncFromActivity(context: Context) {
            val mode = OverlayMode.fromId(MomentLedgerApp.from(context).preferences.overlayMode.value)
            val intent = Intent(context, LedgerOverlayService::class.java)
            if (mode == OverlayMode.OFF || !Settings.canDrawOverlays(context)) {
                if (active != null) context.stopService(intent)
            } else {
                val running = active
                if (running != null) {
                    running.scheduleUnlockChecks()
                } else {
                    try {
                        ContextCompat.startForegroundService(context, intent)
                    } catch (_: IllegalStateException) {
                        Toast.makeText(context, "暂时无法启动悬浮入口，请在刻记设置中重试", Toast.LENGTH_SHORT).show()
                    } catch (_: SecurityException) {
                        Toast.makeText(context, "系统限制了悬浮入口，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        fun hideForCapture(hidden: Boolean) {
            if (captureHidden == hidden) return
            captureHidden = hidden
            active?.let { service ->
                if (hidden) service.overlay.closeMenu()
                service.updateWindow()
            }
        }

        fun setMenuScalePreview(activePreview: Boolean) {
            active?.overlay?.setPreviewMenuPinned(activePreview)
        }
    }
}
