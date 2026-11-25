package com.momentledger.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momentledger.app.MomentLedgerApp
import com.momentledger.app.R
import com.momentledger.app.quickentry.AccessibilityAutoRestore
import com.momentledger.app.quickentry.QuickEntryNotificationHelper
import com.momentledger.app.quickentry.ScreenCaptureAccessibilityService
import com.momentledger.app.quickentry.ScreenshotOcrTileService
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.theme.LocalAppleColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScreenCaptureSettingsCard() {
    val context = LocalContext.current
    val app = remember(context) { MomentLedgerApp.from(context) }
    val prefs = app.preferences
    val enabled by prefs.screenCaptureEnabled.collectAsStateWithLifecycle()
    val excludeFromRecents by prefs.excludeFromRecents.collectAsStateWithLifecycle()
    val quickNotificationEnabled by prefs.quickNotificationEnabled.collectAsStateWithLifecycle()
    val connected by ScreenCaptureAccessibilityService.connected.collectAsStateWithLifecycle()
    val supported = ScreenCaptureAccessibilityService.isSupported
    var systemAuthorized by remember(context, connected) {
        mutableStateOf(ScreenCaptureAccessibilityService.isSystemAuthorized(context))
    }
    var autoRestoreGranted by remember(context) {
        mutableStateOf(AccessibilityAutoRestore.hasWriteSecureSettings(context))
    }
    var batteryIgnored by remember(context) {
        mutableStateOf(AccessibilityAutoRestore.isIgnoringBatteryOptimizations(context))
    }
    var showAdbGuide by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val colors = LocalAppleColors.current
    val adbCommand = remember(context) { AccessibilityAutoRestore.adbGrantCommand(context.packageName) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        systemAuthorized = ScreenCaptureAccessibilityService.isSystemAuthorized(context)
        autoRestoreGranted = AccessibilityAutoRestore.hasWriteSecureSettings(context)
        batteryIgnored = AccessibilityAutoRestore.isIgnoringBatteryOptimizations(context)
    }

    fun openPermissionSettings() {
        try {
            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "请在系统设置中打开无障碍", Toast.LENGTH_SHORT).show()
        }
    }

    fun copyAdbCommand() {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard?.setPrimaryClip(ClipData.newPlainText("momentledger_adb_grant", adbCommand))
        Toast.makeText(context, "已复制授权命令", Toast.LENGTH_SHORT).show()
    }

    AppleCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(colors.blue.copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.PhotoCamera,
                    contentDescription = null,
                    tint = colors.blue,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("一键识屏", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (supported) "在账单页面点按通知栏或磁贴。仅当次截屏，离线识别，不保存相册。"
                    else "通知栏一键识屏需要 Android 12 或更新版本。",
                    color = colors.subText,
                    fontSize = 12.sp
                )
            }
            Switch(
                checked = supported && enabled,
                enabled = supported,
                modifier = Modifier.semantics { contentDescription = "启用一键识屏" },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.subText.copy(alpha = 0.34f),
                    uncheckedBorderColor = colors.separator
                ),
                onCheckedChange = { checked ->
                    prefs.setScreenCaptureEnabled(checked)
                    if (!checked) ScreenCaptureAccessibilityService.disable(context)
                    QuickEntryNotificationHelper.syncNotification(
                        context = context,
                        enabled = prefs.quickNotificationEnabled.value,
                        forceRefresh = true
                    )
                    ScreenshotOcrTileService.requestRefresh(context)
                    if (checked && !connected) {
                        if (AccessibilityAutoRestore.hasWriteSecureSettings(context)) {
                            scope.launch(Dispatchers.IO) {
                                AccessibilityAutoRestore.tryRestoreIfNeeded(app)
                            }
                        } else {
                            openPermissionSettings()
                        }
                    }
                }
            )
        }

        if (supported && enabled) {
            if (connected) {
                Text("已启用 · 等待主动点按", color = colors.green, fontSize = 12.sp)
                if (!quickNotificationEnabled) {
                    Text(
                        "提示：通知栏快捷记账已关闭，你仍可使用下拉控制中心的「一键识屏」磁贴。",
                        color = colors.subText,
                        fontSize = 11.sp
                    )
                }
            } else {
                Text(
                    if (systemAuthorized) {
                        "系统已授权，但后台关闭刻记后无障碍服务已断开，请重新连接或启用下方防掉权方案。"
                    } else {
                        "无障碍服务未开启（国产系统划掉后台卡片会连带关闭无障碍权限）。"
                    },
                    color = colors.orange,
                    fontSize = 12.sp
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = ::openPermissionSettings) {
                        Text(if (systemAuthorized) "重新连接无障碍" else "去系统授权")
                    }
                    if (autoRestoreGranted) {
                        TextButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    AccessibilityAutoRestore.tryRestoreIfNeeded(app)
                                }
                            }
                        ) {
                            Text("立即自动重连")
                        }
                    }
                }
            }

            HorizontalDivider(color = colors.separator)

            // Layer 1: Exclude from recents (prevents accidental swipe-away in task switcher)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("从最近任务隐藏", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        "避免在多任务页一键清理或误划掉刻记导致无障碍断开（可从桌面图标、通知栏或磁贴重新进入）。",
                        color = colors.subText,
                        fontSize = 11.sp
                    )
                }
                Switch(
                    checked = excludeFromRecents,
                    modifier = Modifier.semantics { contentDescription = "从最近任务隐藏" },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = colors.green,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = colors.subText.copy(alpha = 0.34f),
                        uncheckedBorderColor = colors.separator
                    ),
                    onCheckedChange = { hide ->
                        prefs.setExcludeFromRecents(hide)
                        AccessibilityAutoRestore.applyExcludeFromRecents(context, hide)
                    }
                )
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { AccessibilityAutoRestore.openBatteryOptimizationSettings(context) }) {
                    Text(if (batteryIgnored) "电池无限制：已开启" else "去开启电池无限制")
                }
                TextButton(onClick = { AccessibilityAutoRestore.openOemBackgroundSettings(context) }) {
                    Text("自启动 / 后台管理")
                }
                TextButton(onClick = { AccessibilityAutoRestore.openAppDetailsSettings(context) }) {
                    Text("应用信息")
                }
            }

            // Layer 2: Optional one-time ADB/Shizuku WRITE_SECURE_SETTINGS self-healing
            if (autoRestoreGranted) {
                Text(
                    "无感防掉权已就绪（WRITE_SECURE_SETTINGS）：划掉后台或重启后，点按通知栏/磁贴或重新打开刻记将自动恢复无障碍。",
                    color = colors.green,
                    fontSize = 11.sp
                )
            } else {
                TextButton(onClick = { showAdbGuide = !showAdbGuide }) {
                    Text(
                        if (showAdbGuide) "收起：免 Root 自动防掉权教程"
                        else "彻底解决划掉后台掉权（免 Root · ADB/Shizuku 一次配置）"
                    )
                }
                if (showAdbGuide) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(colors.segBg, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "国产系统划掉后台会强停进程并关闭无障碍，普通应用无法自行写回系统开关。参考 GKD / KeyMapper 方案，通过电脑 ADB 或手机端无线调试 / Shizuku 执行一次下方命令，授权永久生效（划掉后台和重启均不失效）：",
                            color = colors.subText,
                            fontSize = 11.sp
                        )
                        Text(
                            text = adbCommand,
                            color = colors.text,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = ::copyAdbCommand) {
                                Text("复制命令")
                            }
                            TextButton(
                                onClick = {
                                    autoRestoreGranted = AccessibilityAutoRestore.hasWriteSecureSettings(context)
                                    Toast.makeText(
                                        context,
                                        if (autoRestoreGranted) "检测到授权成功，自动防掉权已激活" else "暂未检测到授权，请执行上述命令后重试",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    if (autoRestoreGranted && !connected) {
                                        scope.launch(Dispatchers.IO) {
                                            AccessibilityAutoRestore.tryRestoreIfNeeded(app)
                                        }
                                    }
                                }
                            ) {
                                Text("检测授权状态")
                            }
                        }
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !connected) {
                Text(
                    "Android 13+ 侧载安装若无法开启无障碍，请先点「应用信息 → 右上角更多 → 允许受限设置」。",
                    color = colors.subText,
                    fontSize = 11.sp
                )
            }
        }
    }
}
