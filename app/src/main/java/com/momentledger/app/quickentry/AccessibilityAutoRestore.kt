package com.momentledger.app.quickentry

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure-Kotlin codec for safely reading and updating Android's colon-delimited
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES string without disturbing other apps' services.
 */
internal object AccessibilityServicesSettingCodec {

    fun parseEntries(raw: String?): List<String> =
        raw.orEmpty()
            .split(':')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun containsService(raw: String?, fullName: String, shortName: String): Boolean =
        parseEntries(raw).any { entry -> matchesTarget(entry, fullName, shortName) }

    fun removeService(raw: String?, fullName: String, shortName: String): String =
        parseEntries(raw)
            .filterNot { entry -> matchesTarget(entry, fullName, shortName) }
            .joinToString(":")

    fun appendService(raw: String?, fullName: String, shortName: String): String {
        val others = parseEntries(raw).filterNot { entry -> matchesTarget(entry, fullName, shortName) }
        return (others + fullName).joinToString(":")
    }

    private fun matchesTarget(entry: String, fullName: String, shortName: String): Boolean =
        entry.equals(fullName, ignoreCase = true) || entry.equals(shortName, ignoreCase = true)
}

/**
 * Keeps ScreenCaptureAccessibilityService resilient against OEM task killers via:
 * 1. Optional WRITE_SECURE_SETTINGS self-healing (one-time ADB or Shizuku grant, zero root).
 * 2. Optional exclude-from-recents toggle to prevent accidental swipe-away.
 * 3. Direct shortcuts to battery optimization whitelist and OEM auto-start / background settings.
 */
internal object AccessibilityAutoRestore {

    private val restoreMutex = Mutex()
    private const val REBIND_TOGGLE_PAUSE_MS = 120L
    private const val REBIND_WAIT_TIMEOUT_MS = 1_800L

    fun hasWriteSecureSettings(context: Context): Boolean =
        context.applicationContext.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun adbGrantCommand(packageName: String): String =
        "adb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS"

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.applicationContext.getSystemService(PowerManager::class.java)
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    fun applyExcludeFromRecents(context: Context, exclude: Boolean) {
        runCatching {
            val am = context.getSystemService(ActivityManager::class.java)
            am?.appTasks?.forEach { task ->
                task.setExcludeFromRecents(exclude)
            }
        }
    }

    /**
     * Automatically re-enables (or cleanly toggles to force rebind if OEM left it disconnected)
     * [ScreenCaptureAccessibilityService] when the user opted in and granted WRITE_SECURE_SETTINGS.
     * Returns true if the service is connected by the time this call completes.
     */
    suspend fun tryRestoreIfNeeded(context: Context): Boolean {
        val appContext = context.applicationContext
        if (!ScreenCaptureAccessibilityService.isEnabled(appContext)) return false
        if (ScreenCaptureAccessibilityService.isConnected()) return true
        if (!hasWriteSecureSettings(appContext)) return false

        return restoreMutex.withLock {
            if (ScreenCaptureAccessibilityService.isConnected()) return@withLock true
            val component = ComponentName(appContext, ScreenCaptureAccessibilityService::class.java)
            val fullName = component.flattenToString()
            val shortName = component.flattenToShortString()
            val resolver = appContext.contentResolver

            val writeSucceeded = runCatching {
                val current = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                if (AccessibilityServicesSettingCodec.containsService(current, fullName, shortName)) {
                    // Service is listed in Settings.Secure but unbound (OEM zombie state):
                    // briefly remove then re-add to force AccessibilityManagerService to rebind.
                    val withoutSelf = AccessibilityServicesSettingCodec.removeService(current, fullName, shortName)
                    Settings.Secure.putString(
                        resolver,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        withoutSelf
                    )
                    delay(REBIND_TOGGLE_PAUSE_MS)
                }
                val latest = Settings.Secure.getString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
                val restored = AccessibilityServicesSettingCodec.appendService(latest, fullName, shortName)
                Settings.Secure.putString(
                    resolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    restored
                )
                Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }.getOrDefault(false)

            if (!writeSucceeded) return@withLock false
            if (ScreenCaptureAccessibilityService.isConnected()) return@withLock true

            withTimeoutOrNull(REBIND_WAIT_TIMEOUT_MS) {
                ScreenCaptureAccessibilityService.connected.first { it }
            } == true
        }
    }

    fun openBatteryOptimizationSettings(context: Context) {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            openAppDetailsSettings(context)
        }
    }

    fun openOemBackgroundSettings(context: Context) {
        val candidates = oemAutoStartCandidates(Build.MANUFACTURER.orEmpty())
        for (component in candidates) {
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val started = runCatching {
                context.startActivity(intent)
                true
            }.getOrDefault(false)
            if (started) return
        }
        openAppDetailsSettings(context)
    }

    fun openAppDetailsSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "请在系统设置中打开刻记的应用信息", Toast.LENGTH_SHORT).show()
        }
    }

    private fun oemAutoStartCandidates(manufacturer: String): List<ComponentName> {
        val lower = manufacturer.lowercase()
        return when {
            "xiaomi" in lower || "redmi" in lower || "poco" in lower -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            )
            "oppo" in lower || "oneplus" in lower || "realme" in lower -> listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            )
            "vivo" in lower || "iqoo" in lower -> listOf(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            )
            "huawei" in lower || "honor" in lower -> listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )
            else -> emptyList()
        }
    }
}
