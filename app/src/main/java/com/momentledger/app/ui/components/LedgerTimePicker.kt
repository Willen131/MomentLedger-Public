package com.momentledger.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import java.time.LocalTime
import kotlinx.coroutines.launch

private val WheelItemHeight = 40.dp
private val WheelViewportHeight = 120.dp
private val TimePreviewFormat = "%02d:%02d"

/**
 * 解析手动输入的 24 小时制时间。
 *
 * 小时只允许 0..23，分钟只允许 0..59；空值、非数字、超长或越界输入均返回 null。
 */
internal fun parseLedgerTime(hour: String, minute: String): LocalTime? {
    if (hour.length !in 1..2 || minute.length !in 1..2) return null
    if (!hour.all { it in '0'..'9' } || !minute.all { it in '0'..'9' }) return null
    val parsedHour = hour.toIntOrNull() ?: return null
    val parsedMinute = minute.toIntOrNull() ?: return null
    if (parsedHour !in 0..23 || parsedMinute !in 0..59) return null
    return LocalTime.of(parsedHour, parsedMinute)
}

/**
 * 24 小时制时间选择弹窗：单列小时轮 + 单列分钟轮，或手动输入 HH:mm。
 */
@Composable
internal fun LedgerTimePicker(
    initial: LocalTime,
    onConfirm: (LocalTime) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    var selectedHour by remember(initial) { mutableIntStateOf(initial.hour) }
    var selectedMinute by remember(initial) { mutableIntStateOf(initial.minute) }
    var manualMode by remember { mutableStateOf(false) }
    var hourInput by remember(initial) {
        mutableStateOf(initial.hour.toString().padStart(2, '0'))
    }
    var minuteInput by remember(initial) {
        mutableStateOf(initial.minute.toString().padStart(2, '0'))
    }
    var inputError by remember { mutableStateOf<String?>(null) }

    val manualTime = parseLedgerTime(hourInput, minuteInput)
    val previewTime = if (manualMode) {
        manualTime
    } else {
        LocalTime.of(selectedHour, selectedMinute)
    }
    val previewText = previewTime?.let {
        TimePreviewFormat.format(it.hour, it.minute)
    } ?: "--:--"

    fun switchMode(toManual: Boolean) {
        if (toManual == manualMode) return
        if (toManual) {
            hourInput = selectedHour.toString().padStart(2, '0')
            minuteInput = selectedMinute.toString().padStart(2, '0')
            inputError = null
        } else {
            parseLedgerTime(hourInput, minuteInput)?.let { parsed ->
                selectedHour = parsed.hour
                selectedMinute = parsed.minute
            }
            inputError = null
        }
        manualMode = toManual
    }

    fun confirm() {
        val result = if (manualMode) {
            parseLedgerTime(hourInput, minuteInput)
        } else {
            LocalTime.of(selectedHour, selectedMinute)
        }
        if (result == null) {
            inputError = manualInputError(hourInput, minuteInput)
            return
        }
        onConfirm(result)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = {
            Text(
                text = "选择时间",
                color = colors.text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = previewText,
                    style = TnumTextStyle(
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.blue
                    ),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                TimeModeSelector(
                    manualMode = manualMode,
                    onModeChange = ::switchMode
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (manualMode) {
                    ManualTimeInput(
                        hour = hourInput,
                        minute = minuteInput,
                        isError = inputError != null,
                        onHourChange = {
                            hourInput = it
                            inputError = null
                        },
                        onMinuteChange = {
                            minuteInput = it
                            inputError = null
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = inputError ?: "请输入 0–23 时、0–59 分",
                        color = if (inputError == null) colors.subText else colors.red,
                        fontSize = 12.sp,
                        fontWeight = if (inputError == null) FontWeight.Normal else FontWeight.Medium
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TimeWheel(
                            values = (0..23).toList(),
                            selectedValue = selectedHour,
                            onSelected = { selectedHour = it },
                            label = "小时",
                            formatValue = { it.toString() },
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = ":",
                            style = TnumTextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.text
                            ),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.width(18.dp)
                        )
                        TimeWheel(
                            values = (0..59).toList(),
                            selectedValue = selectedMinute,
                            onSelected = { selectedMinute = it },
                            label = "分钟",
                            formatValue = { it.toString().padStart(2, '0') },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "上下滚动或点击数值，选择 0–23 时和 0–59 分",
                        color = colors.subText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = ::confirm) {
                Text(
                    text = "确定",
                    color = colors.blue,
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", color = colors.subText)
            }
        }
    )
}

@Composable
private fun TimeModeSelector(
    manualMode: Boolean,
    onModeChange: (Boolean) -> Unit
) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.segBg)
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        TimeModeOption(
            label = "滚轮选择",
            selected = !manualMode,
            onClick = { onModeChange(false) },
            modifier = Modifier.weight(1f)
        )
        TimeModeOption(
            label = "手动输入",
            selected = manualMode,
            onClick = { onModeChange(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TimeModeOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.blue.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) colors.blue else colors.subText,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

@Composable
private fun TimeWheel(
    values: List<Int>,
    selectedValue: Int,
    onSelected: (Int) -> Unit,
    label: String,
    formatValue: (Int) -> String,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val middle = (Int.MAX_VALUE / 2 / values.size) * values.size
    val initialIndex = middle + values.indexOf(selectedValue).coerceAtLeast(0)
    val wheel = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val selectedIndex by remember(wheel) {
        derivedStateOf {
            val info = wheel.layoutInfo
            val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { item ->
                kotlin.math.abs(item.offset + item.size / 2 - center)
            }?.index ?: initialIndex
        }
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(selectedIndex) { onSelected(values[selectedIndex % values.size]) }

    Column(modifier = modifier) {
        Text(
            text = label,
            color = colors.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(WheelViewportHeight)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .height(WheelItemHeight)
                    .padding(horizontal = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.blue.copy(alpha = 0.12f))
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = wheel,
                contentPadding = PaddingValues(vertical = WheelItemHeight),
                flingBehavior = rememberSnapFlingBehavior(wheel)
            ) {
                items(Int.MAX_VALUE) { index ->
                    val value = values[index % values.size]
                    val isSelected = index == selectedIndex
                    Box(
                        modifier = Modifier.fillMaxWidth().height(WheelItemHeight)
                            .clickable {
                                onSelected(value)
                                scope.launch { wheel.scrollToItem(index) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = formatValue(value),
                            style = TnumTextStyle(
                                fontSize = if (isSelected) 22.sp else 17.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) colors.blue else colors.text.copy(alpha = 0.55f)
                            ),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManualTimeInput(
    hour: String,
    minute: String,
    isError: Boolean,
    onHourChange: (String) -> Unit,
    onMinuteChange: (String) -> Unit
) {
    val colors = LocalAppleColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ManualTimeField(
            label = "小时",
            value = hour,
            isError = isError,
            onValueChange = onHourChange,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = ":",
            style = TnumTextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(24.dp)
        )
        ManualTimeField(
            label = "分钟",
            value = minute,
            isError = isError,
            onValueChange = onMinuteChange,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ManualTimeField(
    label: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val fieldShape = RoundedCornerShape(10.dp)
    Column(modifier = modifier) {
        Text(
            text = label,
            color = if (isError) colors.red else colors.subText,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            textStyle = TnumTextStyle(
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = colors.text
            ).copy(textAlign = TextAlign.Center),
            cursorBrush = SolidColor(colors.blue),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(fieldShape)
                        .background(colors.segBg)
                        .border(
                            width = if (isError) 1.dp else 0.dp,
                            color = if (isError) colors.red else Color.Transparent,
                            shape = fieldShape
                        )
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = "00",
                            style = TnumTextStyle(
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.subText.copy(alpha = 0.45f)
                            )
                        )
                    }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        innerTextField()
                    }
                }
            }
        )
    }
}

private fun manualInputError(hour: String, minute: String): String = when {
    hour.isEmpty() || minute.isEmpty() -> "请填写完整的小时和分钟"
    hour.length > 2 || minute.length > 2 -> "小时和分钟最多输入两位数字"
    !hour.all { it in '0'..'9' } || !minute.all { it in '0'..'9' } -> "小时和分钟只能输入数字"
    hour.toInt() !in 0..23 -> "小时需在 0–23 之间"
    minute.toInt() !in 0..59 -> "分钟需在 0–59 之间"
    else -> "请输入有效的时间"
}
