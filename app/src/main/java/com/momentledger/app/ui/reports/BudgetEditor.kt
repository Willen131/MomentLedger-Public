@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.momentledger.app.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.engine.BudgetPeriod
import com.momentledger.app.domain.engine.BudgetSettings
import com.momentledger.app.domain.engine.parseBudgetStartDate
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.theme.LocalAppleColors
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private val WeekdayHeaders = listOf("日", "一", "二", "三", "四", "五", "六")
private val AmountPattern = Regex("[0-9]{1,14}(?:\\.[0-9]{1,2})?")

/**
 * 预算编辑弹窗。日预算按日期多选；月/年预算只维护唯一总预算和账期起始日期。
 */
@Composable
internal fun BudgetEditor(
    initial: BudgetSettings,
    period: BudgetPeriod,
    anchor: LocalDate,
    onSave: (BudgetSettings) -> Unit,
    onDismiss: () -> Unit
) {
    when (period) {
        BudgetPeriod.DAY -> DailyBudgetDialog(initial, anchor, onSave, onDismiss)
        else -> RecurringBudgetDialog(initial, period, anchor, onSave, onDismiss)
    }
}

@Composable
private fun DailyBudgetDialog(
    initial: BudgetSettings,
    anchor: LocalDate,
    onSave: (BudgetSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    var visibleMonthIso by rememberSaveable(anchor) {
        mutableStateOf(YearMonth.from(anchor).toString())
    }
    val visibleMonth = remember(visibleMonthIso) { YearMonth.parse(visibleMonthIso) }
    var selectedDates by rememberSaveable(
        anchor,
        stateSaver = Saver<Set<LocalDate>, String>(
            save = { dates -> dates.joinToString(",") },
            restore = { if (it.isEmpty()) emptySet() else it.split(",").map(LocalDate::parse).toSet() }
        )
    ) {
        mutableStateOf(setOf(anchor))
    }
    var draftAmount by rememberSaveable(anchor) { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    var pendingOverwriteCents by rememberSaveable(anchor) { mutableStateOf<Long?>(null) }

    val selectedAmounts = selectedDates.map { initial.amountFor(BudgetPeriod.DAY, it) }
    val amountText = draftAmount ?: selectedAmounts.distinct().singleOrNull()?.amountInput().orEmpty()
    val selectedBudgetedCount = selectedAmounts.count { it != null }

    val toggleDate: (LocalDate) -> Unit = { date ->
        error = null
        selectedDates = if (date in selectedDates) {
            selectedDates - date
        } else {
            selectedDates + date
        }
    }
    val selectAllVisibleMonth = {
        error = null
        selectedDates = selectedDates + (1..visibleMonth.lengthOfMonth()).map {
            visibleMonth.atDay(it)
        }
    }
    val clearSelection = {
        error = null
        selectedDates = emptySet()
    }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("设置${BudgetPeriod.DAY.budgetLabel}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "选中的每个日期都会使用相同的预算金额。",
                    color = colors.subText,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        draftAmount = it
                        error = null
                    },
                    label = { Text("总预算（元）") },
                    placeholder = { Text("例如 150.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Text(it, color = colors.red, fontSize = 12.sp)
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 ${selectedDates.size} 天",
                        color = colors.text,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (selectedBudgetedCount > 0) {
                        Text(
                            "$selectedBudgetedCount 天已有预算",
                            color = colors.subText,
                            fontSize = 12.sp
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = selectAllVisibleMonth) { Text("全选本月") }
                    TextButton(onClick = clearSelection) { Text("清空选中") }
                }

                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        enabled = visibleMonth > YearMonth.of(1, 1),
                        onClick = {
                            visibleMonthIso = visibleMonth.minusMonths(1).toString()
                        }
                    ) { Text("上月") }
                    Text(
                        text = "${visibleMonth.year} 年 ${visibleMonth.monthValue} 月",
                        modifier = Modifier.weight(1f),
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                    TextButton(
                        enabled = visibleMonth < YearMonth.of(9999, 12),
                        onClick = {
                            visibleMonthIso = visibleMonth.plusMonths(1).toString()
                        }
                    ) { Text("下月") }
                }

                Row(Modifier.fillMaxWidth()) {
                    WeekdayHeaders.forEach { label ->
                        Text(
                            text = label,
                            modifier = Modifier.weight(1f),
                            color = colors.subText,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                val leadingBlanks = visibleMonth.atDay(1).dayOfWeek.value % 7
                val dayCount = visibleMonth.lengthOfMonth()
                val rows = (leadingBlanks + dayCount + 6) / 7
                repeat(rows) { row ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { column ->
                            val dayOfMonth = row * 7 + column - leadingBlanks + 1
                            if (dayOfMonth in 1..dayCount) {
                                val date = visibleMonth.atDay(dayOfMonth)
                                BudgetDayCell(
                                    date = date,
                                    isSelected = date in selectedDates,
                                    hasBudget = initial.amountFor(BudgetPeriod.DAY, date) != null,
                                    onSelect = { toggleDate(date) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            } else {
                                Spacer(
                                    Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                )
                            }
                        }
                    }
                }

                Text(
                    "蓝底为已选日期，橙点表示该日期已有预算。",
                    color = colors.subText,
                    fontSize = 11.sp
                )

                if (selectedDates.isEmpty()) {
                    Text(
                        "请选择至少一天。",
                        color = colors.orange,
                        fontSize = 12.sp
                    )
                }

                if (selectedBudgetedCount > 0) {
                    TextButton(
                        onClick = { showClearConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("清除选中预算", color = colors.red)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedDates.isNotEmpty(),
                onClick = {
                    val cents = parseBudgetCents(amountText)
                    if (cents == null) {
                        error = "请输入大于 0 的预算金额，最多保留两位小数。"
                    } else if (selectedBudgetedCount > 0) {
                        pendingOverwriteCents = cents
                    } else {
                        onSave(initial.withDailyBudget(selectedDates, cents))
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    pendingOverwriteCents?.let { cents ->
        AlertDialog(
            onDismissRequest = { pendingOverwriteCents = null },
            title = { Text("覆盖已有日预算？") },
            text = {
                Text(
                    "已选 ${selectedDates.size} 天，其中 $selectedBudgetedCount 天已有预算。" +
                        "确认后，每个所选日期的预算都将设为 ${AmountParser.formatYuan(cents)}。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwriteCents = null
                    onSave(initial.withDailyBudget(selectedDates, cents))
                }) { Text("确认覆盖") }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwriteCents = null }) { Text("取消") }
            }
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清除选中预算？") },
            text = {
                Text(
                    "将清除已选 ${selectedDates.size} 天中 $selectedBudgetedCount 天的日预算，" +
                        "未设置预算的日期不受影响。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        onSave(initial.withDailyBudget(selectedDates, null))
                    }
                ) { Text("确认清除", color = colors.red) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun BudgetDayCell(
    date: LocalDate,
    isSelected: Boolean,
    hasBudget: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val actionLabel = if (isSelected) "取消选择该日期" else "选择该日期"
    val accessibilityLabel = buildString {
        append(date.year).append("年")
        append(date.monthValue).append("月")
        append(date.dayOfMonth).append("日，")
        append(if (isSelected) "已选中" else "未选中")
        append(if (hasBudget) "，已有预算" else "，无预算")
    }

    Box(
        modifier = modifier
            .padding(2.dp)
            .clip(CircleShape)
            .background(if (isSelected) colors.blue else Color.Transparent)
            .clickable(onClickLabel = actionLabel, onClick = onSelect)
            .clearAndSetSemantics {
                contentDescription = accessibilityLabel
                selected = isSelected
                role = Role.Button
                onClick(label = actionLabel) {
                    onSelect()
                    true
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                color = if (isSelected) Color.White else colors.text,
                fontSize = 13.sp,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
            if (hasBudget) {
                Spacer(Modifier.size(1.dp))
                Box(
                    Modifier
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(colors.orange)
                )
            }
        }
    }
}

@Composable
private fun RecurringBudgetDialog(
    initial: BudgetSettings,
    period: BudgetPeriod,
    anchor: LocalDate,
    onSave: (BudgetSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    val originalStart = remember(initial, period, anchor) {
        initial.billingStartDate(period, anchor)
    }
    var amountText by rememberSaveable(period, anchor) {
        mutableStateOf(
            initial.amountFor(period, anchor)?.amountInput().orEmpty()
        )
    }
    var startDateText by rememberSaveable(period, anchor) {
        mutableStateOf(originalStart.toString())
    }
    var error by remember { mutableStateOf<String?>(null) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("设置${period.budgetLabel}") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "总预算留空并保存，可撤销当期总预算。",
                    color = colors.subText,
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = amountText,
                    onValueChange = {
                        amountText = it
                        error = null
                    },
                    label = { Text("总预算（元）") },
                    placeholder = { Text("留空则撤销当期预算") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = startDateText,
                    onValueChange = {
                        startDateText = it
                        error = null
                    },
                    label = { Text("账期起始日期") },
                    placeholder = { Text("YYYY-MM-DD 或 YYYY/MM/DD") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = { showDatePicker = true }) {
                        Text("打开日历")
                    }
                }
                Text(
                    text = if (period == BudgetPeriod.MONTH) {
                        "起始日期会使用其中的“日”；遇到短月时自动使用月末。"
                    } else {
                        "起始日期会使用其中的“月、日”；遇到短月时自动使用月末。"
                    },
                    color = colors.subText,
                    fontSize = 12.sp
                )
                error?.let {
                    Text(it, color = colors.red, fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val start = parseBudgetStartDate(startDateText)
                    val cents = if (amountText.isBlank()) {
                        null
                    } else {
                        parseBudgetCents(amountText)
                    }
                    when {
                        start == null -> {
                            error = "账期起始日期无效，请输入真实日期，" +
                                "例如 2027-04-05 或 2027/04/05。"
                        }
                        amountText.isNotBlank() && cents == null -> {
                            error = "预算金额需大于 0 且最多两位小数；" +
                                "如需撤销当期预算，请留空。"
                        }
                        else -> {
                            onSave(
                                initial.withRecurringBudget(period, start, cents)
                            )
                        }
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )

    if (showDatePicker) {
        val pickerDate = parseBudgetStartDate(startDateText) ?: originalStart
        val initialSelectedDateMillis = remember(pickerDate) {
            pickerDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        }
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialSelectedDateMillis,
            yearRange = minOf(1900, pickerDate.year)..maxOf(2100, pickerDate.year)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    enabled = datePickerState.selectedDateMillis != null,
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            startDateText = Instant.ofEpochMilli(millis)
                                .atZone(ZoneOffset.UTC)
                                .toLocalDate()
                                .toString()
                            error = null
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("确定", color = colors.blue)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("取消", color = colors.subText)
                }
            }
        ) {
            DatePicker(state = datePickerState, showModeToggle = false)
        }
    }
}

private fun parseBudgetCents(value: String): Long? = runCatching {
    value.trim()
        .takeIf { it.matches(AmountPattern) }
        ?.toBigDecimal()
        ?.movePointRight(2)
        ?.longValueExact()
        ?.takeIf { it > 0 }
}.getOrNull()

private fun Long.amountInput(): String = BigDecimal.valueOf(this, 2).toPlainString()
