package com.momentledger.app.quickentry

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.momentledger.app.MomentLedgerApp
import com.momentledger.app.domain.model.ImportReviewBatch
import com.momentledger.app.domain.parser.ScreenshotBillParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.time.ZoneId
import kotlin.coroutines.resumeWithException

/** Receives no accessibility events. Capture is requested explicitly from a quick entry. */
class ScreenCaptureAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var captureJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!isSupported) {
            disableSelf()
            return
        }
        val app = MomentLedgerApp.from(this)
        if (!app.preferences.screenCaptureEnabled.value) {
            // System explicitly connected this service after user authorization;
            // keep the app preference in sync instead of self-disabling the permission.
            app.preferences.setScreenCaptureEnabled(true)
        }
        serviceInfo?.let { info ->
            info.eventTypes = 0
            serviceInfo = info
        }
        instance = this
        _connected.value = true
        QuickEntryNotificationHelper.syncNotification(this, app.preferences.quickNotificationEnabled.value)
        ScreenshotOcrTileService.requestRefresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        scope.cancel()
        super.onDestroy()
    }

    private fun disconnect() {
        captureJob?.cancel()
        if (instance === this) {
            instance = null
            _connected.value = false
            ScreenshotOcrTileService.requestRefresh(this)
        }
    }

    private fun screenAvailable(): Boolean =
        getSystemService(PowerManager::class.java)?.isInteractive == true &&
            getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    @RequiresApi(Build.VERSION_CODES.S)
    private fun captureFromPanel() {
        if (captureJob?.isActive == true) return
        if (!screenAvailable()) {
            showMessage(this, "请先解锁手机并打开账单详情")
            return
        }

        captureJob = scope.launch {
            val app = MomentLedgerApp.from(this@ScreenCaptureAccessibilityService)
            try {
                val batch = withTimeout(30_000L) {
                    LedgerOverlayService.hideForCapture(true)
                    val dismissed = runCatching {
                        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
                    }.getOrDefault(false)
                    // The global action returns when accepted, before the shade animation finishes.
                    // Some OEMs return false when the shade is already collapsing; still wait briefly and capture.
                    delay(if (dismissed) 480L else 180L)
                    check(screenAvailable()) { "请先解锁手机并打开账单详情" }
                    recognizeCurrentScreen(app)
                }
                if (!isEnabled(this@ScreenCaptureAccessibilityService) || !screenAvailable()) return@launch
                val token = app.screenReviews.publish(batch)
                val itemCount = batch.items.size
                val launchFailed = try {
                    startActivity(
                        QuickEntryActions.launchIntent(
                            this@ScreenCaptureAccessibilityService,
                            QuickEntryActions.ACTION_SCREEN_REVIEW
                        ).putExtra(QuickEntryActions.EXTRA_SCREEN_REVIEW_TOKEN, token)
                    )
                    false
                } catch (_: RuntimeException) {
                    true
                }

                app.appScope.launch {
                    if (!launchFailed) {
                        delay(BAL_FALLBACK_CHECK_DELAY_MS)
                    }
                    if (app.screenReviews.isPending(token)) {
                        // On Android 14-15 (targetSdk 35) and OEM ROMs, Background Activity Launch
                        // may be silently blocked without throwing. Fall back to a clickable notification.
                        app.screenReviews.extendTtl(token, ScreenReviewHandoff.NOTIFICATION_FALLBACK_TTL_MILLIS)
                        val notified = QuickEntryNotificationHelper.showScreenReviewReadyNotification(
                            context = app,
                            token = token,
                            itemCount = itemCount
                        )
                        if (!notified) {
                            withContext(Dispatchers.Main) {
                                showMessage(app, "已识别到账单，请允许刻记后台弹出界面或通知权限后重试")
                            }
                        }
                        delay(ScreenReviewHandoff.NOTIFICATION_FALLBACK_TTL_MILLIS)
                        if (app.screenReviews.isPending(token)) {
                            app.screenReviews.discard(token)
                            QuickEntryNotificationHelper.cancelScreenReviewReadyNotification(app)
                        }
                    } else {
                        delay(ScreenReviewHandoff.TTL_MILLIS)
                        app.screenReviews.discard(token)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                showMessage(this@ScreenCaptureAccessibilityService, "本次识屏超时，请重试")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: CaptureFailure) {
                showMessage(this@ScreenCaptureAccessibilityService, error.message.orEmpty())
            } catch (error: IllegalStateException) {
                showMessage(this@ScreenCaptureAccessibilityService, error.message ?: "本次识屏未完成")
            } catch (_: OutOfMemoryError) {
                showMessage(this@ScreenCaptureAccessibilityService, "当前屏幕分辨率过高，请改用截图识账")
            } catch (_: Exception) {
                showMessage(this@ScreenCaptureAccessibilityService, "本次识屏未完成，请重试或改用截图识账")
            } finally {
                LedgerOverlayService.hideForCapture(false)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun recognizeCurrentScreen(app: MomentLedgerApp): ImportReviewBatch {
        val screenshot = takeFrame()
        val capturedAt = System.currentTimeMillis()
        val buffer = screenshot.hardwareBuffer
        try {
            LedgerOverlayService.hideForCapture(false)
            return withContext(Dispatchers.IO) {
                val rawBitmap = buffer.use {
                    val hardwareBitmap = Bitmap.wrapHardwareBuffer(it, screenshot.colorSpace)
                        ?: throw CaptureFailure("无法读取当前屏幕")
                    try {
                        hardwareBitmap.copy(Bitmap.Config.ARGB_8888, true)
                            ?: throw CaptureFailure("无法读取当前屏幕")
                    } finally {
                        hardwareBitmap.recycle()
                    }
                }
                val preparedBitmap = maskSystemBarsInPlace(rawBitmap)
                val zoneId = ZoneId.systemDefault()
                val parsed = try {
                    val input = app.screenshotOcrEngine.recognizeScreenRetained(preparedBitmap, capturedAt)
                    var result = ScreenshotBillParser.parse(input, zoneId)
                    if (result.detectedMode == "DETAIL" && result.candidates.singleOrNull()?.needsVerification == true) {
                        val retriedLines = try {
                            app.screenshotOcrEngine.retryPaymentTimeFromBitmap(preparedBitmap, input)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            emptyList()
                        }
                        if (retriedLines.isNotEmpty()) {
                            result = ScreenshotBillParser.parse(input, zoneId, retriedLines)
                        }
                    }
                    result
                } finally {
                    if (!preparedBitmap.isRecycled) preparedBitmap.recycle()
                }
                if (parsed.candidates.isEmpty()) {
                    throw CaptureFailure("当前屏幕未识别到账单，请打开完整账单详情后重试")
                }
                app.repository.prepareImportReview(
                    items = parsed.candidates.map { it.payment },
                    sourceTitle = QuickEntryActions.SCREEN_REVIEW_TITLE,
                    rawOcrText = parsed.rawText,
                    needsVerificationFlags = parsed.candidates.map { it.needsVerification || !it.payment.hasTimeOfDay }
                )
            }
        } finally {
            if (!buffer.isClosed) buffer.close()
        }
    }

    /**
     * Masks out status bar and navigation bar pixels with the adjacent content background color
     * WITHOUT cropping height, so strip Y-boundaries stay pixel-aligned with saved gallery screenshots.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun maskSystemBarsInPlace(bitmap: Bitmap): Bitmap {
        if (!bitmap.isMutable || bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        val (topInset, bottomInset) = resolveSystemBarInsets(bitmap.height)
        if (topInset <= 0 && bottomInset <= 0) return bitmap
        val canvas = Canvas(bitmap)
        val paint = Paint()
        if (topInset in 1 until (bitmap.height - 24)) {
            val sampleY = (topInset + 16).coerceIn(0, bitmap.height - 1)
            paint.color = bitmap.getPixel(bitmap.width / 2, sampleY)
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), topInset.toFloat(), paint)
        }
        if (bottomInset in 1 until (bitmap.height - 24)) {
            val sampleY = (bitmap.height - bottomInset - 16).coerceIn(0, bitmap.height - 1)
            paint.color = bitmap.getPixel(bitmap.width / 2, sampleY)
            canvas.drawRect(
                0f,
                (bitmap.height - bottomInset).toFloat(),
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                paint
            )
        }
        return bitmap
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun resolveSystemBarInsets(bitmapHeight: Int): Pair<Int, Int> = runCatching {
        val wm = getSystemService(WindowManager::class.java) ?: return@runCatching 0 to 0
        val metrics = wm.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.statusBars() or WindowInsets.Type.displayCutout()
        )
        val navInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.navigationBars()
        )
        val screenHeight = metrics.bounds.height().coerceAtLeast(1)
        val top = (insets.top.toLong() * bitmapHeight / screenHeight).toInt()
            .coerceIn(0, bitmapHeight / 8)
        val bottom = (navInsets.bottom.toLong() * bitmapHeight / screenHeight).toInt()
            .coerceIn(0, bitmapHeight / 8)
        top to bottom
    }.getOrDefault(0 to 0)

    @RequiresApi(Build.VERSION_CODES.S)
    private suspend fun takeFrame(): ScreenshotResult = suspendCancellableCoroutine { continuation ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(screenshot: ScreenshotResult) {
                continuation.resume(screenshot) { _, result, _ -> result.hardwareBuffer.close() }
            }

            @Suppress("SwitchIntDef")
            override fun onFailure(errorCode: Int) {
                val message = when (errorCode) {
                    ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "当前页面受系统保护，无法识屏"
                    ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "请在系统无障碍设置中开启刻记一键识屏"
                    ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "识屏操作太快，请稍后重试"
                    else -> "系统无法截取当前画面，请改用截图识账"
                }
                continuation.resumeWithException(CaptureFailure(message))
            }
        })
    }

    private class CaptureFailure(message: String) : Exception(message)

    companion object {
        private const val BAL_FALLBACK_CHECK_DELAY_MS = 1_200L
        private var instance: ScreenCaptureAccessibilityService? = null
        private val _connected = MutableStateFlow(false)
        internal val connected = _connected.asStateFlow()

        internal val isSupported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        internal fun isConnected(): Boolean = instance != null

        internal fun isSystemAuthorized(context: Context): Boolean {
            if (!isSupported) return false
            val appContext = context.applicationContext
            val expectedComponent = ComponentName(appContext, ScreenCaptureAccessibilityService::class.java)
            val am = appContext.getSystemService(AccessibilityManager::class.java)
            val listedInManager = runCatching {
                am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                    ?.any { info ->
                        val si = info.resolveInfo?.serviceInfo ?: return@any false
                        si.packageName == expectedComponent.packageName &&
                            si.name == expectedComponent.className
                    } == true
            }.getOrDefault(false)
            if (listedInManager) return true

            val rawSetting = runCatching {
                Settings.Secure.getString(
                    appContext.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            if (rawSetting.isBlank()) return false
            val flattened = expectedComponent.flattenToString()
            val shortFlattened = expectedComponent.flattenToShortString()
            return rawSetting.split(':').any { entry ->
                entry.equals(flattened, ignoreCase = true) ||
                    entry.equals(shortFlattened, ignoreCase = true)
            }
        }

        internal fun isEnabled(context: Context): Boolean =
            isSupported && MomentLedgerApp.from(context).preferences.screenCaptureEnabled.value

        internal fun requestFromPanel(
            context: Context,
            onRestoreAttemptFinished: (() -> Unit)? = null
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !isEnabled(context)) {
                onRestoreAttemptFinished?.invoke()
                return
            }
            val service = instance
            if (service != null) {
                service.captureFromPanel()
                onRestoreAttemptFinished?.invoke()
                return
            }
            val app = MomentLedgerApp.from(context)
            if (AccessibilityAutoRestore.hasWriteSecureSettings(app)) {
                app.appScope.launch(Dispatchers.IO) {
                    try {
                        val restored = AccessibilityAutoRestore.tryRestoreIfNeeded(app)
                        withContext(Dispatchers.Main) {
                            val active = instance
                            if (restored && active != null) {
                                active.captureFromPanel()
                            } else {
                                showMessage(app, "无障碍重连超时，请打开刻记或重试一键识屏")
                            }
                        }
                    } finally {
                        onRestoreAttemptFinished?.invoke()
                    }
                }
            } else {
                showMessage(context, "后台关闭后无障碍已断开，请在刻记设置中重连或配置自动恢复")
                onRestoreAttemptFinished?.invoke()
            }
        }

        internal fun disable(context: Context) {
            instance?.let {
                it.disconnect()
                it.disableSelf()
            }
            MomentLedgerApp.from(context).screenReviews.clear()
            QuickEntryNotificationHelper.cancelScreenReviewReadyNotification(context)
            ScreenshotOcrTileService.requestRefresh(context)
        }

        private fun showMessage(context: Context, message: String) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }
}
