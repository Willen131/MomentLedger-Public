package com.momentledger.app.ui.reports

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.momentledger.app.domain.engine.BudgetAlert
import com.momentledger.app.domain.engine.BudgetCycle
import com.momentledger.app.domain.engine.BudgetPeriod
import com.momentledger.app.domain.engine.BudgetSettings
import com.momentledger.app.domain.engine.budgetCycleForReport
import com.momentledger.app.domain.engine.budgetExpenseRecords
import com.momentledger.app.domain.engine.calculateBudget
import com.momentledger.app.domain.engine.calculateCashFlow
import com.momentledger.app.domain.engine.calendarPeriod
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.AppleLargeTopBar
import com.momentledger.app.ui.components.AppleSegmentedControl
import com.momentledger.app.ui.theme.LocalAppleColors
import java.time.LocalDate

@Composable
fun ReportsScreen(
    records: List<TransactionRecord>,
    categories: List<Category>,
    today: LocalDate,
    budget: BudgetSettings,
    onBudgetChange: (BudgetSettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    var period by rememberSaveable { mutableStateOf(BudgetPeriod.MONTH) }
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    var editBudget by rememberSaveable { mutableStateOf(false) }
    val anchor = LocalDate.parse(dateText)
    val calendar = remember(anchor, period) { calendarPeriod(anchor, period) }
    val cycle = remember(anchor, today, period, budget) { budgetCycleForReport(anchor, today, period, budget) }
    val limitCents = budget.amountFor(period, anchor)
    val progress = remember(records, cycle, limitCents, today) {
        calculateBudget(records, cycle, limitCents, today)
    }
    val cashFlow = remember(records, calendar) { calculateCashFlow(records, calendar) }

    Column(
        modifier.fillMaxSize().background(colors.bg).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AppleLargeTopBar(title = "收支预算")
        AppleSegmentedControl(BudgetPeriod.entries.map { it to it.label }, period, { period = it })
        val (previous, next) = when (period) {
            BudgetPeriod.DAY -> "前一天" to "后一天"
            BudgetPeriod.MONTH -> "上个月" to "下个月"
            BudgetPeriod.YEAR -> "上一年" to "下一年"
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { dateText = moveDate(anchor, period, -1).toString() }) { Text(previous) }
            Text(
                when (period) {
                    BudgetPeriod.DAY -> anchor.toString()
                    BudgetPeriod.MONTH -> "${anchor.year} 年 ${anchor.monthValue} 月"
                    BudgetPeriod.YEAR -> "${anchor.year} 年"
                },
                color = colors.text,
                fontWeight = FontWeight.SemiBold
            )
            TextButton(onClick = { dateText = moveDate(anchor, period, 1).toString() }) { Text(next) }
        }

        AppleCard(contentPadding = PaddingValues(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionTitle("收支对比")
                PieWithLegend(
                    listOf(
                        PieSlice("收入（含退款）", cashFlow.incomeCents, colors.green),
                        PieSlice("支出", cashFlow.expenseCents, colors.blue)
                    ),
                    description = "收入与支出",
                    emptyText = "本期暂无收支"
                )
            }
        }

        AppleCard(contentPadding = PaddingValues(16.dp)) {
            Column {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    SectionTitle(period.budgetLabel)
                    TextButton(onClick = { editBudget = true }) { Text("设置${period.budgetLabel}") }
                }
                Text(
                    if (period == BudgetPeriod.DAY) cycle.start.toString() else cycle.dateLabel(),
                    color = colors.subText, fontSize = 12.sp
                )
                if (progress == null) {
                    Text("尚未设置${period.budgetLabel}", color = colors.subText, fontSize = 13.sp)
                }
            }
        }

        progress?.let { item ->
            AppleCard(contentPadding = PaddingValues(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle("总预算与支出")
                    Text(
                        "预算 ${AmountParser.formatYuan(item.limitCents)} · 净支出 ${AmountParser.formatYuan(item.spentCents)}",
                        color = colors.subText, fontSize = 13.sp
                    )
                    val slices = listOf(
                        PieSlice("预算内支出", item.spentWithinLimitCents, colors.blue),
                        PieSlice("剩余额度", item.remainingCents, colors.green),
                        PieSlice("超出预算", item.overCents, colors.red)
                    )
                    PieWithLegend(
                        slices.filter { it.cents > 0 },
                        description = "预算与支出",
                        emptyText = ""
                    )
                    if (item.overCents > 0) {
                        Text(
                            "超支通知 · 已超出 ${AmountParser.formatYuan(item.overCents)}",
                            color = colors.red,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    } else if (item.alert == BudgetAlert.NEAR) {
                        Text(
                            if (item.remainingCents == 0L) "预算额度已用完" else "接近预算上限",
                            color = colors.orange, fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (progress != null && progress.overCents > 0) {
            val expenses = remember(records, cycle, today) { budgetExpenseRecords(records, cycle, today) }
            val categoriesById = remember(categories) { categories.associateBy { it.id } }
            OverspentExpensesCard(expenses, categoriesById, period, cycle)
        }
    }

    if (editBudget) BudgetEditor(
        initial = budget,
        period = period,
        anchor = anchor,
        onSave = { onBudgetChange(it); editBudget = false },
        onDismiss = { editBudget = false }
    )
}

private fun moveDate(date: LocalDate, period: BudgetPeriod, amount: Long): LocalDate = when (period) {
    BudgetPeriod.DAY -> date.plusDays(amount)
    BudgetPeriod.MONTH -> date.plusMonths(amount)
    BudgetPeriod.YEAR -> date.plusYears(amount)
}

private fun BudgetCycle.dateLabel(): String = "$start 至 ${endExclusive.minusDays(1)}"

@Composable
private fun SectionTitle(label: String) {
    Text(label, color = LocalAppleColors.current.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

private data class PieSlice(val label: String, val cents: Long, val color: Color)

@Composable
private fun PieWithLegend(slices: List<PieSlice>, description: String, emptyText: String) {
    val colors = LocalAppleColors.current
    val total = slices.sumOf { it.cents.coerceAtLeast(0L).toDouble() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(Modifier.size(108.dp).semantics { contentDescription = description }) {
            if (total == 0.0) {
                drawCircle(colors.barTrack)
            } else {
                var start = -90f
                for (slice in slices) {
                    val sweep = (slice.cents.coerceAtLeast(0L) / total * 360).toFloat()
                    if (sweep > 0) drawArc(slice.color, start, sweep, useCenter = true)
                    start += sweep
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (total == 0.0 && emptyText.isNotBlank()) Text(emptyText, color = colors.subText, fontSize = 12.sp)
            slices.forEach { slice ->
                Text("● ${slice.label}", color = slice.color, fontSize = 12.sp)
                Text(AmountParser.formatYuan(slice.cents), color = colors.text, fontSize = 14.sp)
            }
        }
    }
}
