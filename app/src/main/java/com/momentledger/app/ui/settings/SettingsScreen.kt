package com.momentledger.app.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momentledger.app.data.prefs.ThemePreference
import com.momentledger.app.quickentry.QuickEntryNotificationHelper
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.AppleLargeTopBar
import com.momentledger.app.ui.components.AppleSegmentedControl
import com.momentledger.app.ui.components.BocchiEmblem
import com.momentledger.app.ui.components.BocchiMotif
import com.momentledger.app.ui.components.BocchiStage
import com.momentledger.app.ui.components.BocchiSticker
import com.momentledger.app.ui.components.bocchiPageBackground
import com.momentledger.app.ui.theme.LocalAppleColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenCategoryManager: () -> Unit,
    onQuickAdd: () -> Unit = {},
    onPickScreenshots: () -> Unit = {},
    onPickBillFile: () -> Unit = {},
    modifier: Modifier = Modifier,
    showStatusSnackbar: Boolean = true
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalAppleColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var tileGuideOpen by remember { mutableStateOf(false) }
    var restoreConfirmOpen by remember { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val effective = granted && QuickEntryNotificationHelper.hasNotificationPermission(context)
        viewModel.refreshNotificationPermission(effective)
        if (effective) {
            QuickEntryNotificationHelper.syncNotification(context, enabled = true)
            viewModel.postStatusMessage("下滑通知栏快捷卡片已开启")
        } else {
            openAppNotificationSettings(context)
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val app = com.momentledger.app.MomentLedgerApp.from(context)
        app.appScope.launch {
            try {
                val json = viewModel.exportBackupJson()
                writeUtf8ToUri(app, uri, json, "无法写入目标文件")
                viewModel.postStatusMessage("本地 JSON 备份已导出")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                viewModel.postStatusMessage("导出备份失败：${e.message ?: "写入异常"}")
            }
        }
    }

    val backupRestoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val app = com.momentledger.app.MomentLedgerApp.from(context)
        app.appScope.launch {
            try {
                val json = withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)
                        ?.use { it.readText() }
                        ?.removePrefix("\uFEFF")
                        ?: error("无法读取备份文件")
                }
                viewModel.restoreBackupJson(json)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                viewModel.postStatusMessage("恢复失败：${e.message ?: "读取异常"}")
            }
        }
    }

    val csvExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val app = com.momentledger.app.MomentLedgerApp.from(context)
        app.appScope.launch {
            try {
                val csv = viewModel.exportCsv()
                writeUtf8ToUri(app, uri, csv, "无法写入 CSV 文件")
                viewModel.postStatusMessage("CSV 账单明细已导出")
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                viewModel.postStatusMessage("导出 CSV 失败：${e.message ?: "写入异常"}")
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshNotificationPermission(QuickEntryNotificationHelper.hasNotificationPermission(context))
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = QuickEntryNotificationHelper.hasNotificationPermission(context)
        viewModel.refreshNotificationPermission(granted)
        QuickEntryNotificationHelper.syncNotification(context, uiState.quickNotificationEnabled)
    }

    if (showStatusSnackbar) {
        LaunchedEffect(uiState.statusMessage) {
            val message = uiState.statusMessage ?: return@LaunchedEffect
            try {
                snackbarHostState.showSnackbar(
                    message = message,
                    duration = SnackbarDuration.Short
                )
            } finally {
                if (viewModel.uiState.value.statusMessage == message) {
                    viewModel.clearStatusMessage()
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .bocchiPageBackground(BocchiStage.SETTINGS)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            AppleLargeTopBar(
                title = "导入与设置",
                subtitle = "全程离线 · 本机记账",
                stage = BocchiStage.SETTINGS
            )

            if (uiState.isBusy) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                        .clip(CircleShape),
                    color = colors.blue,
                    trackColor = colors.barTrack
                )
            }

            // Hero 3-Action Entry Card
            AppleCard(
                contentPadding = PaddingValues(14.dp),
                accent = colors.red
            ) {
                Text(
                    text = "快速记账与整理",
                    color = colors.subText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    HeroActionTile(
                        title = "记一笔",
                        subtitle = "极速手记",
                        icon = Icons.Rounded.AddCircleOutline,
                        motif = BocchiMotif.HAIRCLIPS,
                        accent = colors.blue,
                        onClick = onQuickAdd,
                        modifier = Modifier.weight(1f)
                    )
                    HeroActionTile(
                        title = "截图识账",
                        subtitle = "离线 OCR",
                        icon = Icons.Rounded.DocumentScanner,
                        motif = BocchiMotif.PEDAL,
                        accent = colors.green,
                        onClick = onPickScreenshots,
                        modifier = Modifier.weight(1f)
                    )
                    HeroActionTile(
                        title = "导入账单",
                        subtitle = "CSV/XLSX/PDF",
                        icon = Icons.Rounded.UploadFile,
                        motif = BocchiMotif.BASS,
                        accent = colors.orange,
                        onClick = onPickBillFile,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            ScreenCaptureSettingsCard()
            Spacer(modifier = Modifier.height(16.dp))

            LedgerOverlaySettingsCard()
            Spacer(modifier = Modifier.height(16.dp))

            SettingsSectionLabel(text = "快捷记账入口", motif = BocchiMotif.DRUM)
            AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                val notifSubtitle = if (uiState.quickNotificationEnabled && !uiState.notificationPermissionGranted) {
                    "需允许通知权限才能在通知栏显示静态快捷卡片"
                } else {
                    "静态通知卡片，提供记一笔、截图识账和可选的一键识屏"
                }
                SettingsSwitchRow(
                    title = "下滑通知栏快捷卡片",
                    subtitle = notifSubtitle,
                    checked = uiState.quickNotificationEnabled,
                    onCheckedChange = { enabled ->
                        viewModel.setQuickNotificationEnabled(enabled)
                        if (enabled) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else if (!QuickEntryNotificationHelper.hasNotificationPermission(context)) {
                                openAppNotificationSettings(context)
                            } else {
                                QuickEntryNotificationHelper.syncNotification(context, enabled = true)
                            }
                        } else {
                            QuickEntryNotificationHelper.cancelNotification(context)
                        }
                    }
                )
                if (uiState.quickNotificationEnabled) {
                    SettingsRowDivider()
                    SettingsSwitchRow(
                        title = "通知栏默认展开快捷按钮",
                        subtitle = if (uiState.quickNotificationExpanded) {
                            "已展开：下拉通知栏直接显示记一笔、截图识账、一键识屏按钮"
                        } else {
                            "已折叠：收起为精简单行通知，手动展开后显示快捷按钮"
                        },
                        checked = uiState.quickNotificationExpanded,
                        onCheckedChange = { expanded ->
                            viewModel.setQuickNotificationExpanded(expanded)
                            QuickEntryNotificationHelper.syncNotification(
                                context = context,
                                enabled = true,
                                forceRefresh = true
                            )
                        }
                    )
                }
                SettingsRowDivider()

                SettingsActionRow(
                    title = "下拉控制中心快捷磁贴",
                    subtitle = "添加记一笔和截图识账磁贴。启用一键识屏后，截图磁贴会直接识屏。",
                    trailingText = "添加步骤",
                    trailingColor = colors.blue,
                    onClick = { tileGuideOpen = true }
                )
                SettingsRowDivider()

                SettingsActionRow(
                    title = "系统分享截图直达识账",
                    subtitle = "微信 / 支付宝 / 淘宝 / 京东 / 美团 / 饿了么截屏后直接点「分享 → 刻记」",
                    trailingText = "立即选图",
                    trailingColor = colors.green,
                    onClick = onPickScreenshots
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 2: 外观与分类整理
            SettingsSectionLabel(text = "外观与分类整理", motif = BocchiMotif.HAIRCLIPS)
            AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(
                        text = "外观模式",
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    AppleSegmentedControl(
                        items = listOf(
                            ThemePreference.SYSTEM to "跟随系统",
                            ThemePreference.LIGHT to "浅色",
                            ThemePreference.DARK to "深色"
                        ),
                        selected = uiState.themePreference,
                        onSelect = viewModel::setThemePreference
                    )
                }
                SettingsRowDivider()

                SettingsActionRow(
                    title = "分类管理",
                    subtitle = "支出 ${uiState.expenseCategories.size} 项 · 收入 ${uiState.incomeCategories.size} 项（新增 / 排序 / 图标 / 色板）",
                    trailingText = "管理",
                    trailingColor = colors.blue,
                    onClick = onOpenCategoryManager
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Section 3: 本地数据与隐私
            SettingsSectionLabel(text = "本地备份与隐私", motif = BocchiMotif.VINYL)
            AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp)) {
                SettingsActionRow(
                    title = "导入软件与银行账单文件",
                    subtitle = "支持微信(.xlsx/.csv)、支付宝(.csv)、银行流水(.pdf)及消费软件表格",
                    trailingText = "选择文件",
                    trailingColor = colors.blue,
                    onClick = onPickBillFile
                )
                SettingsRowDivider()

                SettingsActionRow(
                    title = "导出本地全量备份 (JSON)",
                    subtitle = "包含全部账单、自定义分类与商户记忆规则",
                    trailingText = "导出",
                    trailingColor = colors.subText,
                    onClick = {
                        val dateStamp = LocalDate.now().toString().replace("-", "")
                        backupExportLauncher.launch("momentledger-backup-$dateStamp.json")
                    }
                )
                SettingsRowDivider()

                SettingsActionRow(
                    title = "从本地 JSON 备份恢复",
                    subtitle = "选择本机 JSON 备份文件覆盖恢复账本",
                    trailingText = "恢复",
                    trailingColor = colors.subText,
                    onClick = { restoreConfirmOpen = true }
                )
                SettingsRowDivider()

                SettingsActionRow(
                    title = "导出账单明细 (CSV)",
                    subtitle = "UTF-8 BOM 编码，可直接用 Excel / Numbers 打开",
                    trailingText = "导出",
                    trailingColor = colors.subText,
                    onClick = {
                        val dateStamp = LocalDate.now().toString().replace("-", "")
                        csvExportLauncher.launch("momentledger-bills-$dateStamp.csv")
                    }
                )
            }
        }

        if (showStatusSnackbar) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) { data ->
                Snackbar(
                    shape = RoundedCornerShape(14.dp),
                    containerColor = colors.card,
                    contentColor = colors.text
                ) {
                    Text(
                        text = data.visuals.message,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (tileGuideOpen) {
        QuickTileGuideSheet(onDismiss = { tileGuideOpen = false })
    }

    if (restoreConfirmOpen) {
        AlertDialog(
            onDismissRequest = { restoreConfirmOpen = false },
            containerColor = colors.card,
            title = {
                Text(
                    text = "从本地备份恢复",
                    color = colors.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
            },
            text = {
                Text(
                    text = "恢复本地 JSON 备份会替换当前的账单、分类与学习规则。建议先导出一份当前备份后再继续。",
                    color = colors.subText,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreConfirmOpen = false
                        backupRestoreLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }
                ) {
                    Text("选择备份文件", color = colors.blue, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreConfirmOpen = false }) {
                    Text("取消", color = colors.subText)
                }
            }
        )
    }
}

@Composable
private fun HeroActionTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    motif: BocchiMotif,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    AppleCard(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 14.dp),
        onClick = onClick,
        accent = accent,
        shape = RoundedCornerShape(20.dp),
        glass = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BocchiEmblem(
                glyph = icon,
                motif = motif,
                tint = accent,
                modifier = Modifier.size(38.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = title,
                color = colors.text,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.subText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickTileGuideSheet(onDismiss: () -> Unit) {
    val colors = LocalAppleColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.card,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "添加控制中心快捷磁贴",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "从系统磁贴主动打开相应记账入口：",
                color = colors.subText,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = "1. 从屏幕顶部下滑展开「控制中心 / 快捷开关面板」，点击右上角编辑（铅笔）图标。",
                color = colors.text,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "2. 将「刻记·记一笔」或「刻记·截图识账」拖到常用区。打开并授权一键识屏后，截图磁贴会改为直接识屏。",
                color = colors.text,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "3. 也可以在桌面长按「刻记」图标，直接呼出记一笔、截图识账和导入账单快捷入口。",
                color = colors.subText,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("知道了", color = colors.blue, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionLabel(text: String, motif: BocchiMotif) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        BocchiSticker(motif.charm, Modifier.size(19.dp))
        Text(text = text, color = colors.subText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SettingsRowDivider() {
    val colors = LocalAppleColors.current
    HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    trailingText: String,
    trailingColor: Color,
    onClick: () -> Unit
) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.subText,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = trailingText,
            color = trailingColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.subText,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = colors.subText,
                fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = colors.green,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = colors.subText.copy(alpha = 0.34f),
                uncheckedBorderColor = colors.separator
            )
        )
    }
}

private suspend fun writeUtf8ToUri(
    context: Context,
    uri: Uri,
    content: String,
    errorMessage: String
) {
    withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: error(errorMessage)
    }
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    }
    val fallback = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null)
    )
    for (candidate in listOf(intent, fallback)) {
        if (context !is android.app.Activity) {
            candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(candidate) }.isSuccess) return
    }
}
