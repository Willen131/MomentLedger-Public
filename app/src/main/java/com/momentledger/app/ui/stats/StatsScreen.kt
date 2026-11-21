package com.momentledger.app.ui.stats

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryStat
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.PeriodBucket
import com.momentledger.app.domain.model.StatsMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.domain.parser.AmountParser
import com.momentledger.app.ui.components.AppleCard
import com.momentledger.app.ui.components.AppleLargeTopBar
import com.momentledger.app.ui.components.AppleSegmentedControl
import com.momentledger.app.ui.components.CategoryIconBadge
import com.momentledger.app.ui.components.HourlyBarChart
import com.momentledger.app.ui.components.MetricModePill
import com.momentledger.app.ui.components.TransactionEditorSheet
import com.momentledger.app.ui.components.TransactionTimelineRow
import com.momentledger.app.ui.theme.LocalAppleColors
import com.momentledger.app.ui.theme.TnumTextStyle
import com.momentledger.app.ui.theme.parseCategoryColor
import java.time.ZoneId

@Composable
fun StatsScreen(
    viewModel: StatsViewModel,
    modifier: Modifier = Modifier,
    onSaveCategory: (suspend (Category) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = LocalAppleColors.current

    var editingRecord by remember { mutableStateOf<TransactionRecord?>(null) }
    var periodEditorOpen by remember { mutableStateOf(false) }
    if (periodEditorOpen) {
        TimePeriodEditor(uiState.timePeriods, onSave = {
            viewModel.saveTimePeriods(it)
            periodEditorOpen = false
        }, onDismiss = { periodEditorOpen = false })
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        AppleLargeTopBar(
            title = "时刻统计",
            trailing = {
                MetricModePill(
                    mode = uiState.metricMode,
                    onToggle = viewModel::setMetricMode
                )
            }
        )

        AppleSegmentedControl(
            items = listOf(
                StatsRange.DAY to "日",
                StatsRange.WEEK to "周",
                StatsRange.MONTH to "月"
            ),
            selected = uiState.range,
            onSelect = viewModel::setRange
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Period navigator bar
        PeriodNavigatorCard(
            periodLabel = uiState.periodLabel,
            periodTotalCents = uiState.periodTotalCents,
            totalTransactionCount = uiState.totalTransactionCount,
            metricMode = uiState.metricMode,
            onPreviousPeriod = viewModel::previousPeriod,
            onNextPeriod = viewModel::nextPeriod
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Apple Health 24h Spending Rhythm Card
        HourlyRhythmCard(
            uiState = uiState,
            onSelectHour = { hour ->
                if (hour != null) {
                    viewModel.selectHour(hour)
                }
            },
            onOpenHourDrillDown = {
                viewModel.selectHourForDrillDown(uiState.selectedHour)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        TimePeriodsCard(periods = uiState.periodBuckets, onCustomize = { periodEditorOpen = true })

        Spacer(modifier = Modifier.height(12.dp))

        // Category breakdown card
        CategoryBreakdownCard(
            categories = uiState.categoryBreakdown,
            onClickCategory = { stat ->
                viewModel.selectCategoryForDrillDown(stat.categoryId)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 3-column Payment Channel distribution card
        ChannelSummaryCard(summary = uiState.channelSummary, channels = uiState.channelBreakdown)
    }

    if (editingRecord != null) {
        TransactionEditorSheet(
            initial = editingRecord,
            categories = uiState.categories,
            onSaveCategory = onSaveCategory,
            onSave = { updated ->
                viewModel.updateTransaction(updated, learnMerchantCategory = true)
                editingRecord = null
            },
            onDelete = { id ->
                viewModel.deleteTransaction(id)
                editingRecord = null
            },
            onDismiss = { editingRecord = null }
        )
    } else if (uiState.drillDown != null) {
        StatsDrillDownSheet(
            drillDown = uiState.drillDown!!,
            transactions = uiState.drillDownTransactions,
            categoriesById = uiState.categoriesById,
            metricMode = uiState.metricMode,
            showDate = uiState.range != StatsRange.DAY,
            zoneId = viewModel.zoneId,
            onClickTransaction = { record -> editingRecord = record },
            onDismiss = viewModel::dismissDrillDown
        )
    }
}

@Composable
private fun PeriodNavigatorCard(
    periodLabel: String,
    periodTotalCents: Long,
    totalTransactionCount: Int,
    metricMode: StatsMetricMode,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit
) {
    val colors = LocalAppleColors.current
    val totalColor = if (metricMode == StatsMetricMode.INCOME) colors.green else colors.text

    AppleCard(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = onPreviousPeriod,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronLeft,
                    contentDescription = "上一周期",
                    tint = colors.blue
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = periodLabel,
                    style = TnumTextStyle(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.text
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = AmountParser.formatYuan(periodTotalCents),
                        style = TnumTextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = totalColor
                        ),
                        maxLines = 1
                    )
                    Text(
                        text = "· $totalTransactionCount 笔",
                        style = TnumTextStyle(
                            fontSize = 11.sp,
                            color = colors.subText
                        )
                    )
                }
            }

            IconButton(
                onClick = onNextPeriod,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = "下一周期",
                    tint = colors.blue
                )
            }
        }
    }
}

@Composable
private fun HourlyRhythmCard(
    uiState: StatsUiState,
    onSelectHour: (Int?) -> Unit,
    onOpenHourDrillDown: () -> Unit
) {
    val colors = LocalAppleColors.current
    val selectedHour = uiState.selectedHour
    val bucket = uiState.selectedHourBucket
    val overline = if (uiState.metricMode == StatsMetricMode.INCOME) {
        "24小时收入节律"
    } else {
        "24小时消费节律"
    }

    AppleCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = overline,
                color = colors.subText,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (bucket.isPeak) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(colors.orange.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "高峰时段",
                        color = colors.orange,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AmountParser.formatYuan(bucket.displayAmountCents),
                    style = TnumTextStyle(
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.text
                    ),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${"%02d".format(selectedHour)}:00–${"%02d".format(selectedHour)}:59 · ${bucket.transactionCount} 笔",
                    style = TnumTextStyle(
                        fontSize = 12.sp,
                        color = colors.subText
                    )
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.blue.copy(alpha = 0.12f))
                    .clickable(onClick = onOpenHourDrillDown)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "查看该时段明细",
                    color = colors.blue,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = colors.blue,
                    modifier = Modifier.size(15.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        HourlyBarChart(
            buckets = uiState.hourlyBuckets,
            selectedHour = uiState.selectedHour,
            onSelectHour = onSelectHour,
            height = 132.dp,
            allowDragScrub = true
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (uiState.unknownHourCount > 0) {
                "点击或左右滑动柱状图查看任意小时分布 · 另有 ${uiState.unknownHourCount} 笔无具体时刻的导入账单未计入分时分布"
            } else {
                "点击或左右滑动柱状图，查看任意小时分布"
            },
            color = colors.subText,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TimePeriodsCard(periods: List<PeriodBucket>, onCustomize: () -> Unit) {
    val colors = LocalAppleColors.current

    AppleCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("全天六时段", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onCustomize) { Text("自定义", color = colors.blue) }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            periods.forEach { period ->
                val barColor = parseCategoryColor(period.colorHex, colors.blue)
                val animatedRatio by animateFloatAsState(
                    targetValue = (period.sharePercent / 100f).coerceIn(0f, 1f),
                    animationSpec = tween(durationMillis = 320),
                    label = "periodBar_${period.id}"
                )

                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = period.label,
                                color = colors.text,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = period.hourRangeLabel,
                                style = TnumTextStyle(
                                    fontSize = 11.sp,
                                    color = colors.subText
                                )
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = AmountParser.formatRoundedYuan(period.amountCents),
                                style = TnumTextStyle(
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.text
                                )
                            )
                            Text(
                                text = "${period.sharePercent}%",
                                style = TnumTextStyle(
                                    fontSize = 11.sp,
                                    color = colors.subText
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(CircleShape)
                            .background(colors.barTrack)
                    ) {
                        if (animatedRatio > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(animatedRatio)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(barColor)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryBreakdownCard(
    categories: List<CategoryStat>,
    onClickCategory: (CategoryStat) -> Unit
) {
    val colors = LocalAppleColors.current

    AppleCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "分类占比",
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "点击查看明细",
                color = colors.subText,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (categories.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "当前周期暂无分类数据",
                    color = colors.subText,
                    fontSize = 12.sp
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                categories.forEachIndexed { index, stat ->
                    if (index > 0) {
                        HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                    }
                    CategoryStatRow(
                        stat = stat,
                        onClick = { onClickCategory(stat) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryStatRow(
    stat: CategoryStat,
    onClick: () -> Unit
) {
    val colors = LocalAppleColors.current
    val catColor = parseCategoryColor(stat.colorHex, colors.blue)
    val animatedRatio by animateFloatAsState(
        targetValue = (stat.sharePercent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 320),
        label = "catBar_${stat.categoryId}"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryIconBadge(
            iconKey = stat.iconKey,
            colorHex = stat.colorHex,
            contentDescription = stat.categoryName,
            size = 34.dp
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stat.categoryName,
                        color = colors.text,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${stat.transactionCount}笔",
                        style = TnumTextStyle(
                            fontSize = 11.sp,
                            color = colors.subText
                        )
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = AmountParser.formatYuan(stat.amountCents),
                        style = TnumTextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.text
                        )
                    )
                    Text(
                        text = "${stat.sharePercent}%",
                        style = TnumTextStyle(
                            fontSize = 11.sp,
                            color = colors.subText
                        )
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = colors.subText,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(colors.barTrack)
            ) {
                if (animatedRatio > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedRatio)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(catColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelSummaryCard(summary: ChannelGroupSummary, channels: List<com.momentledger.app.domain.model.ChannelStat>) {
    val colors = LocalAppleColors.current

    AppleCard(contentPadding = PaddingValues(16.dp)) {
        Text(
            text = "支付渠道分布",
            color = colors.text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChannelTile(
                title = "微信支付",
                percent = summary.wechatSharePercent,
                amountCents = summary.wechatAmountCents,
                accent = colors.green,
                modifier = Modifier.weight(1f)
            )
            ChannelTile(
                title = "支付宝",
                percent = summary.alipaySharePercent,
                amountCents = summary.alipayAmountCents,
                accent = colors.blue,
                modifier = Modifier.weight(1f)
            )
            ChannelTile(
                title = "云闪付及银行",
                percent = summary.unionPayAndBankSharePercent,
                amountCents = summary.unionPayAndBankAmountCents,
                accent = colors.indigo,
                modifier = Modifier.weight(1f)
            )
        }

        channels.filter { it.channel == PaymentChannel.MANUAL || it.channel == PaymentChannel.OTHER }.forEach { stat ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${stat.label} ${stat.sharePercent}%（${AmountParser.formatYuan(stat.amountCents)}）",
                style = TnumTextStyle(fontSize = 11.sp, color = colors.subText)
            )
        }
    }
}

@Composable
private fun ChannelTile(
    title: String,
    percent: Int,
    amountCents: Long,
    accent: Color,
    modifier: Modifier = Modifier
) {
    val colors = LocalAppleColors.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bg)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            color = colors.subText,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = "$percent%",
            style = TnumTextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (percent > 0) accent else colors.text
            ),
            maxLines = 1
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = AmountParser.formatRoundedYuan(amountCents),
            style = TnumTextStyle(
                fontSize = 11.sp,
                color = colors.subText
            ),
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StatsDrillDownSheet(
    drillDown: StatsDrillDown,
    transactions: List<TransactionRecord>,
    categoriesById: Map<String, Category>,
    metricMode: StatsMetricMode,
    showDate: Boolean,
    zoneId: ZoneId,
    onClickTransaction: (TransactionRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalAppleColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val subtotalCents = remember(transactions, metricMode) {
        transactions.sumOf { tx ->
            when (metricMode) {
                StatsMetricMode.NET_EXPENSE -> when (tx.type) {
                    TransactionType.EXPENSE -> tx.amountCents
                    TransactionType.REFUND -> -tx.amountCents
                    else -> 0L
                }
                StatsMetricMode.INCOME ->
                    if (tx.type == TransactionType.INCOME) tx.amountCents else 0L
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bg,
        contentColor = colors.text
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = drillDown.title,
                        color = colors.text,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${transactions.size} 笔 · 合计 ${AmountParser.formatYuan(subtotalCents)}",
                        style = TnumTextStyle(
                            fontSize = 12.sp,
                            color = colors.subText
                        )
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        text = "完成",
                        color = colors.blue,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (transactions.isEmpty()) {
                AppleCard {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "该时段暂无符合条件的账单",
                            color = colors.subText,
                            fontSize = 13.sp
                        )
                    }
                }
            } else {
                AppleCard(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        itemsIndexed(
                            items = transactions,
                            key = { _, item -> item.id }
                        ) { index, record ->
                            if (index > 0) {
                                HorizontalDivider(color = colors.separator, thickness = 0.5.dp)
                            }
                            TransactionTimelineRow(
                                record = record,
                                category = categoriesById[record.categoryId],
                                zoneId = zoneId,
                                showDate = showDate,
                                onClick = { onClickTransaction(record) }
                            )
                        }
                    }
                }
            }
        }
    }
}
