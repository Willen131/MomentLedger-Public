package com.momentledger.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momentledger.app.MomentLedgerApp
import com.momentledger.app.data.prefs.OverlayAppearance
import com.momentledger.app.quickentry.LedgerOverlayService
import com.momentledger.app.quickentry.OverlayMode
import com.momentledger.app.quickentry.OverlayPet
import com.momentledger.app.quickentry.PetTapFeedback
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.BocchiSticker
import com.momentledger.app.ui.theme.LocalAppleColors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LedgerOverlaySettingsCard() {
    val context = LocalContext.current
    val preferences = MomentLedgerApp.from(context).preferences
    val modeId by preferences.overlayMode.collectAsStateWithLifecycle()
    val petId by preferences.overlayPet.collectAsStateWithLifecycle()
    val mode = OverlayMode.fromId(modeId)
    val pet = OverlayPet.fromId(petId)
    val colors = LocalAppleColors.current
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var permissionPageUnavailable by remember { mutableStateOf(false) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    val permissionPage = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        allowed = Settings.canDrawOverlays(context)
    }
    fun askPermission() {
        permissionPageUnavailable = runCatching {
            permissionPage.launch(Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", context.packageName, null)
            ))
        }.isFailure
    }

    AppleCard(accent = colors.indigo) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("悬浮快捷入口", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (mode != OverlayMode.OFF) {
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                    Text(
                        if (detailsExpanded) "收起设置 ▴" else "展开设置 ▾",
                        color = colors.blue,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Text("在其他应用上点按即可记一笔或一键识屏。桌宠可拖动，靠边时会探出半身。",
            color = colors.subText, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OverlayMode.entries.forEach { choice ->
                FilterChip(selected = mode == choice, label = { Text(choice.label) }, onClick = {
                    val switchingFromOff = mode == OverlayMode.OFF && choice != OverlayMode.OFF
                    preferences.setOverlayMode(choice.id)
                    if (switchingFromOff) {
                        detailsExpanded = true
                    } else if (choice == OverlayMode.OFF) {
                        detailsExpanded = false
                    }
                    allowed = Settings.canDrawOverlays(context)
                    if (choice != OverlayMode.OFF && !allowed) askPermission()
                    LedgerOverlayService.syncFromActivity(context)
                })
            }
        }
        AnimatedVisibility(visible = mode != OverlayMode.OFF && detailsExpanded) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (mode == OverlayMode.PET) {
                    Text("桌宠角色", color = colors.subText, fontSize = 12.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OverlayPet.entries.forEach { choice ->
                            FilterChip(selected = pet == choice, label = { Text(choice.label) },
                                leadingIcon = { BocchiSticker(choice.portrait, Modifier.size(24.dp)) },
                                onClick = { preferences.setOverlayPet(choice.id) })
                        }
                    }
                }
                val isPet = mode == OverlayMode.PET
                val appearanceFlow = if (isPet) preferences.petAppearance else preferences.bubbleAppearance
                val appearance by appearanceFlow.collectAsStateWithLifecycle()
                Spacer(Modifier.height(4.dp))
                OverlayAppearanceControls(
                    isPet = isPet,
                    pet = pet,
                    appearance = appearance,
                    onPreview = { preferences.previewOverlayAppearance(isPet, it) },
                    onSave = { preferences.saveOverlayAppearance(isPet) }
                )
            }
        }
        if (mode != OverlayMode.OFF && !allowed) {
            Text("请在系统设置中为刻记开启「显示在其他应用上层」。",
                color = colors.subText, fontSize = 12.sp)
            TextButton(onClick = ::askPermission) { Text("授予悬浮窗权限") }
        }
        if (permissionPageUnavailable && mode != OverlayMode.OFF && !allowed) {
            Text("无法打开授权页，请在系统设置的「特殊应用权限」中授权。",
                color = colors.subText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OverlayAppearanceControls(
    isPet: Boolean,
    pet: OverlayPet,
    appearance: OverlayAppearance,
    onPreview: (OverlayAppearance) -> Unit,
    onSave: () -> Unit
) {
    val colors = LocalAppleColors.current
    val context = LocalContext.current
    val view = LocalView.current
    val hapticPreview = remember(context) { PetTapFeedback(context) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("悬浮大小", color = colors.subText, fontSize = 12.sp)
        Text(
            "${(appearance.scale * 100f).roundToInt()}%",
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
    Slider(
        value = appearance.scale,
        onValueChange = { onPreview(appearance.copy(scale = it)) },
        onValueChangeFinished = onSave,
        valueRange = OverlayAppearance.MIN_SCALE..OverlayAppearance.MAX_SCALE,
        steps = 0,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "悬浮大小" },
        colors = SliderDefaults.colors(
            thumbColor = colors.blue,
            activeTrackColor = colors.blue,
            inactiveTrackColor = colors.barTrack
        )
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("弹出卡片大小", color = colors.subText, fontSize = 12.sp)
        Text(
            "${(appearance.menuScale * 100f).roundToInt()}%",
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
    DisposableEffect(Unit) {
        onDispose { LedgerOverlayService.setMenuScalePreview(false) }
    }

    Slider(
        value = appearance.menuScale,
        onValueChange = {
            LedgerOverlayService.setMenuScalePreview(true)
            onPreview(appearance.copy(menuScale = it))
        },
        onValueChangeFinished = {
            onSave()
            LedgerOverlayService.setMenuScalePreview(false)
        },
        valueRange = OverlayAppearance.MIN_MENU_SCALE..OverlayAppearance.MAX_MENU_SCALE,
        steps = 0,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "弹出卡片大小" },
        colors = SliderDefaults.colors(
            thumbColor = colors.blue,
            activeTrackColor = colors.blue,
            inactiveTrackColor = colors.barTrack
        )
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("不透明度", color = colors.subText, fontSize = 12.sp)
        Text(
            "${(appearance.opacity * 100f).roundToInt()}%",
            color = colors.text,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
    Slider(
        value = appearance.opacity,
        onValueChange = { onPreview(appearance.copy(opacity = it)) },
        onValueChangeFinished = onSave,
        valueRange = OverlayAppearance.MIN_OPACITY..1f,
        steps = 0,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "不透明度" },
        colors = SliderDefaults.colors(
            thumbColor = colors.blue,
            activeTrackColor = colors.blue,
            inactiveTrackColor = colors.barTrack
        )
    )
    if (isPet) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "点按震动",
                    color = colors.text,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "轻触桌宠时触发角色专属清脆触感震动。",
                    color = colors.subText,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = appearance.tapHaptic,
                modifier = Modifier.semantics { contentDescription = "点按震动" },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.subText.copy(alpha = 0.34f),
                    uncheckedBorderColor = colors.separator
                ),
                onCheckedChange = { checked ->
                    onPreview(appearance.copy(tapHaptic = checked))
                    onSave()
                    if (checked) {
                        hapticPreview.preview(view, pet, appearance.tapHapticStrength)
                    }
                }
            )
        }
        if (appearance.tapHaptic) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("震动强度", color = colors.subText, fontSize = 12.sp)
                Text(
                    "${(appearance.tapHapticStrength * 100f).roundToInt()}%",
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Slider(
                value = appearance.tapHapticStrength,
                onValueChange = { onPreview(appearance.copy(tapHapticStrength = it)) },
                onValueChangeFinished = {
                    onSave()
                    hapticPreview.preview(view, pet, appearance.tapHapticStrength)
                },
                valueRange = OverlayAppearance.MIN_TAP_HAPTIC_STRENGTH..OverlayAppearance.MAX_TAP_HAPTIC_STRENGTH,
                steps = 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "震动强度" },
                colors = SliderDefaults.colors(
                    thumbColor = colors.blue,
                    activeTrackColor = colors.blue,
                    inactiveTrackColor = colors.barTrack
                )
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "桌宠动效",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = appearance.motion,
                modifier = Modifier.semantics { contentDescription = "桌宠动效" },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.subText.copy(alpha = 0.34f),
                    uncheckedBorderColor = colors.separator
                ),
                onCheckedChange = { checked ->
                    onPreview(appearance.copy(motion = checked))
                    onSave()
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "四周吸附后播放动画",
                    color = if (appearance.motion) colors.text else colors.subText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "开启后贴边探头时仍继续呼吸、飘发与眨眼；关闭则吸附时静止省电。",
                    color = colors.subText,
                    fontSize = 11.sp
                )
            }
            Switch(
                checked = appearance.motion && appearance.dockedMotion,
                enabled = appearance.motion,
                modifier = Modifier.semantics { contentDescription = "四周吸附后播放动画" },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = colors.green,
                    uncheckedThumbColor = Color.White,
                    uncheckedTrackColor = colors.subText.copy(alpha = 0.34f),
                    uncheckedBorderColor = colors.separator
                ),
                onCheckedChange = { checked ->
                    onPreview(appearance.copy(dockedMotion = checked))
                    onSave()
                }
            )
        }
        if (appearance.motion) {
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("物理弹性与幅度", color = colors.subText, fontSize = 12.sp)
                Text(
                    "${(appearance.physicsIntensity * 100f).roundToInt()}%",
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Slider(
                value = appearance.physicsIntensity,
                onValueChange = { onPreview(appearance.copy(physicsIntensity = it)) },
                onValueChangeFinished = onSave,
                valueRange = OverlayAppearance.MIN_PHYSICS_INTENSITY..OverlayAppearance.MAX_PHYSICS_INTENSITY,
                steps = 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "物理弹性与幅度" },
                colors = SliderDefaults.colors(
                    thumbColor = colors.blue,
                    activeTrackColor = colors.blue,
                    inactiveTrackColor = colors.barTrack
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("响应灵敏度", color = colors.subText, fontSize = 12.sp)
                Text(
                    "${(appearance.physicsResponsiveness * 100f).roundToInt()}%",
                    color = colors.text,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Slider(
                value = appearance.physicsResponsiveness,
                onValueChange = { onPreview(appearance.copy(physicsResponsiveness = it)) },
                onValueChangeFinished = onSave,
                valueRange = OverlayAppearance.MIN_PHYSICS_RESPONSIVENESS..OverlayAppearance.MAX_PHYSICS_RESPONSIVENESS,
                steps = 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "响应灵敏度" },
                colors = SliderDefaults.colors(
                    thumbColor = colors.blue,
                    activeTrackColor = colors.blue,
                    inactiveTrackColor = colors.barTrack
                )
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = {
                onPreview(OverlayAppearance())
                onSave()
            }
        ) {
            Text("恢复默认外观", color = colors.blue)
        }
    }
}
