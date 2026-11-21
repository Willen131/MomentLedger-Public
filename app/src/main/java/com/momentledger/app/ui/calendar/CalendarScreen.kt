package com.momentledger.app.ui.calendar

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momentledger.app.domain.model.CalendarDaySummary
import com.momentledger.app.domain.model.CalendarMetricMode
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.AppleLargeTopBar
import com.momentledger.app.ui.components.AppleSegmentedControl
import com.momentledger.app.ui.components.ChartType
import com.momentledger.app.ui.components.ChartTypeButton
import com.momentledger.app.ui.components.SwitchableHourlyChart
import com.momentledger.app.ui.components.TransactionEditorSheet
import com.momentledger.app.ui.components.TransactionTimelineRow
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel,
    modifier: Modifier = Modifier,
    onSaveCategory: (suspend (Category) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalAppleColors.current

    var editorOpen by remember { mutableStateOf(false) }
    var editingRecord by remember { mutableStateOf<TransactionRecord?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        AppleLargeTopBar(
            title = "账单日历",
            subtitle = "${uiState.currentMonth.year}年${uiState.currentMonth.monthValue}月 · 纯本地",
            trailing = {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.blue)
                        .clickable {
                            editingRecord = null
                            editorOpen = true
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Add,
                        contentDescription = "手动记一笔",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        )

        AppleSegmentedControl(
            items = CalendarMetricMode.entries.map { mode -> mode to mode.label },
            selected = uiState.metricMode,
            onSelect = viewModel::setMetricMode
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Month navigator & monthly summary row
        MonthHeaderSummaryCard(
            currentMonth = uiState.currentMonth,
            metricMode = uiState.metricMode,
            monthTotalCents = uiState.monthTotalCents,
            dailyAverageCents = uiState.dailyAverageCents,
            onPreviousMonth = viewModel::previousMonth,
            onNextMonth = viewModel::nextMonth
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 7-column Sun–Sat calendar card
        CalendarGridCard(
            currentMonth = uiState.currentMonth,
            today = uiState.today,
            selectedDate = uiState.selectedDate,
            daysByDate = uiState.monthlyStats.daysByDate,
            onSelectDate = viewModel::selectDate
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Selected day 24h mini bar chart card
        SelectedDayHourlyCard(
            uiState = uiState,
            onSelectHour = viewModel::selectHour
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Minute-precision timeline card
        SelectedDayTimelineCard(
            uiState = uiState,
            zoneId = viewModel.zoneId,
            onClearHourFilter = { viewModel.selectHour(null) },
            onClickTransaction = { record ->
                editingRecord = record
                editorOpen = true
            }
        )
    }

    if (editorOpen) {
        TransactionEditorSheet(
            initial = editingRecord,
            categories = uiState.categories,
            onSave = { saved ->
                if (editingRecord == null) {
                    viewModel.saveManualTransaction(saved)
                } else {
                    viewModel.updateTransaction(saved, learnMerchantCategory = true)
                }
                editorOpen = false
                editingRecord = null
            },
            onDelete = if (editingRecord != null) {
                { id ->
                    viewModel.deleteTransaction(id)
                    editorOpen = false
                    editingRecord = null
                }
            } else {
                null
            },
            onDismiss = {
                editorOpen = false
                editingRecord = null
            },
            defaultDate = uiState.selectedDate,
            onSaveCategory = onSaveCategory
        )
    }
}

@Composable
private fun MonthHeaderSummaryCard(
    currentMonth: YearMonth,
    metricMode: CalendarMetricMode,
    monthTotalCents: Long,
    dailyAverageCents: Long,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit
) {
    val colors = LocalAppleColors.current
    val totalLabel = "本月${metricMode.label}"
    val totalColor = when (metricMode) {
        CalendarMetricMode.EXPENSE, CalendarMetricMode.NET_EXPENSE -> colors.text
        CalendarMetricMode.INCOME, CalendarMetricMode.NET_INCOME -> colors.green
    }

    AppleCard(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onPreviousMonth,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronLeft,
                        contentDescription = "上个月",
                        tint = colors.blue
                    )
                }
                Text(
                    text = "${currentMonth.year}年${currentMonth.monthValue}月",
                    style = TnumTextStyle(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text
                    ),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(
                    onClick = onNextMonth,
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = "下个月",
                        tint = colors.blue
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = totalLabel,
                    color = colors.subText,
                    fontSize = 11.sp
                )
                Text(
                    text = AmountParser.formatYuan(monthTotalCents),
                    style = TnumTextStyle(
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = totalColor
                    ),
                    maxLines = 1
                )
                Text(
                    text = "日均 ${AmountParser.formatYuan(dailyAverageCents)}",
                    style = TnumTextStyle(
                        fontSize = 11.sp,
                        color = colors.subText
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CalendarGridCard(
    currentMonth: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate,
    daysByDate: Map<String, CalendarDaySummary>,
    onSelectDate: (LocalDate) -> Unit
) {
    val colors = LocalAppleColors.current
    val firstDayOffset = remember(currentMonth) {
        currentMonth.atDay(1).dayOfWeek.value % 7
    }
    val totalDays = currentMonth.lengthOfMonth()
    val totalSlots = ((firstDayOffset + totalDays + 6) / 7) * 7

    AppleCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)) {
        // Weekday header: Sun..Sat
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            listOf("日", "一", "二", "三", "四", "五", "六").forEach { dayLabel ->
                Text(
                    text = dayLabel,
                    color = colors.subText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (rowStart in 0 until totalSlots step 7) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (col in 0 until 7) {
                        val slotIndex = rowStart + col
                        val dayOfMonth = slotIndex - firstDayOffset + 1
                        if (dayOfMonth in 1..totalDays) {
                            val cellDate = currentMonth.atDay(dayOfMonth)
                            val summary = daysByDate[cellDate.toString()]
                            CalendarDayCell(
                                dayOfMonth = dayOfMonth,
                                summary = summary,
                                isSelected = cellDate == selectedDate,
                                isToday = cellDate == today,
                                isFutureEmpty = cellDate.isAfter(today) && summary == null,
                                onClick = { onSelectDate(cellDate) },
                                modifier = Modifier.weight(1f)
                            )
                        } else {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDayCell(
    dayOfMonth: Int,
    summary: CalendarDaySummary?,
    isSelected: Boolean,
    isToday: Boolean,
    isFutureEmpty: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    val hasAmount = summary != null && summary.transactionCount > 0
    val amountText = if (hasAmount) {
        AmountParser.formatRoundedYuan(summary!!.netAmountCents)
    } else {
        "—"
    }
    val dayNumberColor = when {
        isSelected -> Color.White
        isToday -> colors.blue
        else -> colors.text
    }
    val amountColor = when {
        isSelected -> Color.White.copy(alpha = 0.92f)
        hasAmount -> colors.subText
        else -> colors.subText.copy(alpha = 0.55f)
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    isSelected -> colors.blue
                    isToday -> colors.blue.copy(alpha = 0.08f)
                    else -> Color.Transparent
                }
            )
            .alpha(if (isFutureEmpty && !isSelected) 0.35f else 1f)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = dayOfMonth.toString(),
                style = TnumTextStyle(
                    fontSize = 13.sp,
                    fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.SemiBold,
                    color = dayNumberColor
                ),
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = amountText,
            style = TnumTextStyle(
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Medium,
                color = amountColor
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SelectedDayHourlyCard(
    uiState: CalendarUiState,
    onSelectHour: (Int?) -> Unit
) {
    val colors = LocalAppleColors.current
    val selectedDate = uiState.selectedDate
    val selectedHour = uiState.selectedHour
    val metricMode = uiState.metricMode
    var chartType by rememberSaveable { mutableStateOf(ChartType.BAR) }
    val subtitleText = if (selectedHour != null) {
        "已筛选 ${hourRangeLabel(selectedHour)}"
    } else if (chartType == ChartType.PIE) {
        "点按扇区或图例，查看分布"
    } else {
        "点按或左右拖动，查看${metricMode.label}分布"
    }
    val amountColor = when (metricMode) {
        CalendarMetricMode.EXPENSE, CalendarMetricMode.NET_EXPENSE -> colors.blue
        CalendarMetricMode.INCOME, CalendarMetricMode.NET_INCOME -> colors.green
    }

    AppleCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${selectedDate.monthValue}月${selectedDate.dayOfMonth}日 · 按时刻分布",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitleText,
                    color = if (selectedHour != null) colors.blue else colors.subText,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = AmountParser.formatYuan(uiState.selectedDayNetCents),
                    style = TnumTextStyle(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = amountColor
                    ),
                    maxLines = 1
                )
                Text(
                    text = "${uiState.selectedDayIncludedCount} 笔",
                    style = TnumTextStyle(
                        fontSize = 11.sp,
                        color = colors.subText
                    )
                )
            }
            ChartTypeButton(chartType) { chartType = it }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SwitchableHourlyChart(
            buckets = uiState.selectedDayHourlyBuckets,
            type = chartType,
            selectedHour = uiState.selectedHour,
            onSelectHour = onSelectHour,
            height = 84.dp,
            calendarStyle = true
        )
    }
}

@Composable
private fun SelectedDayTimelineCard(
    uiState: CalendarUiState,
    zoneId: java.time.ZoneId,
    onClearHourFilter: () -> Unit,
    onClickTransaction: (TransactionRecord) -> Unit
) {
    val colors = LocalAppleColors.current
    val visible = uiState.visibleTimeline
    val rangeLabel = uiState.selectedHour?.let(::hourRangeLabel) ?: "全天"

    AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "时间轴明细（${visible.size} 笔）",
                    color = colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = rangeLabel,
                    style = TnumTextStyle(
                        fontSize = 11.sp,
                        color = colors.subText
                    ),
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (uiState.selectedHour != null) {
                Text(
                    text = "显示全天",
                    color = colors.blue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClearHourFilter)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            } else {
                Text(
                    text = "已显示全天",
                    color = colors.subText,
                    fontSize = 11.sp
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "合计",
                color = colors.subText,
                fontSize = 11.sp
            )
            Text(
                text = AmountParser.formatYuan(uiState.visibleTimelineTotalCents),
                style = TnumTextStyle(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.text
                ),
                maxLines = 1
            )
        }

        if (uiState.metricMode == CalendarMetricMode.NET_EXPENSE ||
            uiState.metricMode == CalendarMetricMode.NET_INCOME
        ) {
            val expense = visible.sumOf { CalendarMetricMode.EXPENSE.amountFor(it) ?: 0L }
            val income = visible.sumOf { CalendarMetricMode.INCOME.amountFor(it) ?: 0L }
            Text(
                text = "支出 ${AmountParser.formatYuan(expense)} · 收入 ${AmountParser.formatYuan(income)}",
                color = colors.subText,
                fontSize = 11.sp
            )
        }

        if (visible.isEmpty()) {
            val metricNoun = uiState.metricMode.label
            val emptyMessage = if (uiState.selectedHour != null && uiState.selectedDayAllTimeline.isNotEmpty()) {
                "该时刻无${metricNoun}记录（点击上方柱子或“显示全天”取消筛选）"
            } else {
                "当日暂无账单记录，点击右上角 + 可手动记一笔"
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 22.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = emptyMessage,
                    color = colors.subText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                visible.forEachIndexed { index, record ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                    }
                    TransactionTimelineRow(
                        record = record,
                        category = uiState.categoriesById[record.categoryId],
                        zoneId = zoneId,
                        onClick = { onClickTransaction(record) }
                    )
                }
            }
        }
    }
}

private fun hourRangeLabel(hour: Int): String = "%02d:00–%02d:00".format(hour, hour + 1)
