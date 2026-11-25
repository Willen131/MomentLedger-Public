package com.momentledger.app.quickentry

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.momentledger.app.R

/**
 * Opens screenshot import by default, or captures the visible bill after the user opts in.
 */
class ScreenshotOcrTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            val captureReady = ScreenCaptureAccessibilityService.isEnabled(this@ScreenshotOcrTileService) &&
                (ScreenCaptureAccessibilityService.isConnected() ||
                    AccessibilityAutoRestore.hasWriteSecureSettings(this@ScreenshotOcrTileService))
            state = if (captureReady) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = getString(
                if (ScreenCaptureAccessibilityService.isEnabled(this@ScreenshotOcrTileService)) {
                    R.string.tile_capture_screen
                } else {
                    R.string.tile_screenshot_ocr
                }
            )
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { handleClickUnlocked() }
        } else {
            handleClickUnlocked()
        }
    }

    private fun handleClickUnlocked() {
        if (ScreenCaptureAccessibilityService.isEnabled(this)) {
            if (ScreenCaptureAccessibilityService.isConnected() ||
                AccessibilityAutoRestore.hasWriteSecureSettings(this)
            ) {
                ScreenCaptureAccessibilityService.requestFromPanel(this)
            } else {
                // Service is enabled in prefs but not yet authorized/connected:
                // collapse the shade and bring the user directly to Settings.
                launchAndCollapse(QuickEntryActions.ACTION_OPEN_SETTINGS, 2103)
            }
            return
        }
        launchAndCollapse(QuickEntryActions.ACTION_SCREENSHOT_OCR, 2102)
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchAndCollapse(action: String, requestCode: Int) {
        runCatching {
            val intent = QuickEntryActions.launchIntent(this, action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(
                    PendingIntent.getActivity(
                        this,
                        requestCode,
                        intent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        }
    }

    companion object {
        fun requestRefresh(context: Context) {
            runCatching {
                requestListeningState(
                    context.applicationContext,
                    ComponentName(context.applicationContext, ScreenshotOcrTileService::class.java)
                )
            }
        }
    }
}
