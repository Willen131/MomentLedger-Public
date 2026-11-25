package com.momentledger.app.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.data.prefs.LedgerPreferences
import com.momentledger.app.domain.model.TimePeriod
import com.momentledger.app.domain.model.defaultTimePeriods
import kotlinx.coroutines.flow.flowOf
import com.momentledger.app.domain.engine.AnalyticsCalculator
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryStat
import com.momentledger.app.domain.model.ChannelStat
import com.momentledger.app.domain.model.DateBucket
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HOUR_OF_DAY_UNKNOWN
import com.momentledger.app.domain.model.HourlyBucket
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.PeriodBucket
import com.momentledger.app.domain.model.StatsMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class StatsRange {
    DAY,
    WEEK,
    MONTH
}

sealed interface StatsDrillDown {
    val title: String

    data class ByHour(
        val hour: Int,
        override val title: String
    ) : StatsDrillDown

    data class ByCategory(
        val categoryId: String,
        val categoryName: String,
        override val title: String
    ) : StatsDrillDown

    data class ByTimePeriod(
        val period: TimePeriod,
        override val title: String
    ) : StatsDrillDown
}

data class ChannelGroupSummary(
    val wechatSharePercent: Int = 0,
    val wechatAmountCents: Long = 0L,
    val alipaySharePercent: Int = 0,
    val alipayAmountCents: Long = 0L,
    val unionPayAndBankSharePercent: Int = 0,
    val unionPayAndBankAmountCents: Long = 0L,
    val otherSharePercent: Int = 0,
    val otherAmountCents: Long = 0L
)

data class StatsUiState(
    val range: StatsRange = StatsRange.MONTH,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val periodLabel: String,
    val metricMode: StatsMetricMode = StatsMetricMode.NET_EXPENSE,
    val periodTotalCents: Long = 0L,
    val totalTransactionCount: Int = 0,
    val unknownHourCount: Int = 0,
    val hourlyBuckets: List<HourlyBucket> = AnalyticsCalculator.computeHourlyBuckets(
        emptyList(),
        metricMode
    ),
    val selectedHour: Int = DEFAULT_SELECTED_HOUR,
    val selectedHourBucket: HourlyBucket = hourlyBuckets.getOrNull(DEFAULT_SELECTED_HOUR)
        ?: HourlyBucket(
            hour = DEFAULT_SELECTED_HOUR,
            netAmountCents = 0L,
            displayAmountCents = 0L,
            transactionCount = 0,
            hasRefund = false,
            isPeak = false
        ),
    val selectedHourTransactions: List<TransactionRecord> = emptyList(),
    val timePeriods: List<TimePeriod> = defaultTimePeriods,
    val periodBuckets: List<PeriodBucket> = emptyList(),
    val dailyBuckets: List<DateBucket> = emptyList(),
    val weeklyBuckets: List<DateBucket> = emptyList(),
    val categoryBreakdown: List<CategoryStat> = emptyList(),
    val channelBreakdown: List<ChannelStat> = emptyList(),
    val channelSummary: ChannelGroupSummary = ChannelGroupSummary(),
    val categories: List<Category> = emptyList(),
    val categoriesById: Map<String, Category> = emptyMap(),
    val drillDown: StatsDrillDown? = null,
    val drillDownTransactions: List<TransactionRecord> = emptyList()
) {
    companion object {
        const val DEFAULT_SELECTED_HOUR = 12
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModel(
    private val repository: LedgerRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    val zoneId: ZoneId = ZoneId.systemDefault(),
    private val preferences: LedgerPreferences? = null,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) : ViewModel() {

    private val initialToday: LocalDate = todayProvider()
    private val todayFlow = MutableStateFlow(initialToday)
    private val rangeFlow = MutableStateFlow(StatsRange.MONTH)
    private val anchorDateFlow = MutableStateFlow(initialToday)
    private val metricModeFlow = MutableStateFlow(StatsMetricMode.NET_EXPENSE)
    private val pinnedHourFlow = MutableStateFlow<Int?>(null)
    private val drillDownRequestFlow = MutableStateFlow<DrillDownRequest?>(null)

    private sealed interface DrillDownRequest {
        data class Hour(val hour: Int) : DrillDownRequest
        data class Category(val categoryId: String) : DrillDownRequest
        data class Period(val index: Int) : DrillDownRequest
    }

    private data class PeriodWindow(
        val range: StatsRange,
        val anchorDate: LocalDate,
        val startDate: LocalDate,
        val endDate: LocalDate
    )

    private data class ControlState(
        val today: LocalDate,
        val metricMode: StatsMetricMode,
        val pinnedHour: Int?,
        val drillDownRequest: DrillDownRequest?
    )

    private val windowFlow = combine(rangeFlow, anchorDateFlow) { range, anchor ->
        computePeriodWindow(range, anchor)
    }

    private val periodTransactionsFlow = windowFlow.flatMapLatest { window ->
        repository.observeTransactionsInDateRange(
            startDate = window.startDate.toString(),
            endDate = window.endDate.toString()
        ).map { window to it }
    }

    private val controlStateFlow = combine(
        todayFlow,
        metricModeFlow,
        pinnedHourFlow,
        drillDownRequestFlow
    ) { today, mode, pinnedHour, drillDown ->
        ControlState(
            today = today,
            metricMode = mode,
            pinnedHour = pinnedHour,
            drillDownRequest = drillDown
        )
    }

    val uiState: StateFlow<StatsUiState> = combine(
        controlStateFlow,
        periodTransactionsFlow,
        repository.observeCategories(),
        preferences?.timePeriods ?: flowOf(defaultTimePeriods)
    ) { controls, (window, periodTransactions), categories, timePeriods ->
        val today = controls.today
        val mode = controls.metricMode

        val sortedCategories = categories.sortedWith(
            compareBy<Category> { it.categoryType.ordinal }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
        )
        val categoriesById = sortedCategories.associateBy { it.id }

        val hourlyBuckets = AnalyticsCalculator.computeHourlyBuckets(
            transactions = periodTransactions,
            mode = mode
        )
        val resolvedHour = controls.pinnedHour?.takeIf { it in 0..23 }
            ?: hourlyBuckets.firstOrNull { it.isPeak }?.hour
            ?: StatsUiState.DEFAULT_SELECTED_HOUR
        val selectedHourBucket = hourlyBuckets.getOrElse(resolvedHour) {
            HourlyBucket(
                hour = resolvedHour,
                netAmountCents = 0L,
                displayAmountCents = 0L,
                transactionCount = 0,
                hasRefund = false,
                isPeak = false
            )
        }

        val includedTransactions = periodTransactions
            .filter { isIncludedInMode(it, mode) }
            .sortedWith(compareByDescending<TransactionRecord> { it.timestampMillis }.thenByDescending { it.id })

        val periodTotalCents = includedTransactions.sumOf { signedAmountForMode(it, mode) }
        val unknownHourCount = includedTransactions.count { it.hourOfDay == HOUR_OF_DAY_UNKNOWN }
        val selectedHourTransactions = includedTransactions.filter { it.hourOfDay == resolvedHour }

        val periodBuckets = AnalyticsCalculator.computeTimePeriods(periodTransactions, timePeriods, mode, zoneId)
        val categoryBreakdown = AnalyticsCalculator.computeCategoryBreakdown(
            transactions = periodTransactions,
            categories = sortedCategories,
            mode = mode
        )
        val channelBreakdown = AnalyticsCalculator.computeChannelBreakdown(
            transactions = periodTransactions,
            mode = mode
        )
        val channelSummary = summarizeChannels(channelBreakdown)

        val (resolvedDrillDown, drillDownTransactions) = when (val req = controls.drillDownRequest) {
            null -> null to emptyList()
            is DrillDownRequest.Hour -> {
                val label = "${"%02d".format(req.hour)}:00–${"%02d".format(req.hour)}:59 明细"
                StatsDrillDown.ByHour(hour = req.hour, title = label) to
                    includedTransactions.filter { it.hourOfDay == req.hour }
            }
            is DrillDownRequest.Category -> {
                val categoryName = categoriesById[req.categoryId]?.name
                    ?: DefaultCategories.all.firstOrNull { it.id == req.categoryId }?.name
                    ?: req.categoryId
                StatsDrillDown.ByCategory(
                    categoryId = req.categoryId,
                    categoryName = categoryName,
                    title = "$categoryName · 明细"
                ) to includedTransactions.filter { it.categoryId == req.categoryId }
            }
            is DrillDownRequest.Period -> {
                val period = timePeriods.getOrNull(req.index)
                val bucket = periodBuckets.getOrNull(req.index)
                if (period == null || bucket == null) {
                    null to emptyList()
                } else {
                    StatsDrillDown.ByTimePeriod(period, "${period.label} · ${bucket.hourRangeLabel} 明细") to
                        AnalyticsCalculator.transactionsInTimePeriod(includedTransactions, period, mode, zoneId)
                }
            }
        }

        StatsUiState(
            range = window.range,
            startDate = window.startDate,
            endDate = window.endDate,
            periodLabel = formatPeriodLabel(window, today),
            metricMode = mode,
            periodTotalCents = periodTotalCents,
            totalTransactionCount = includedTransactions.size,
            unknownHourCount = unknownHourCount,
            hourlyBuckets = hourlyBuckets,
            selectedHour = resolvedHour,
            selectedHourBucket = selectedHourBucket,
            selectedHourTransactions = selectedHourTransactions,
            timePeriods = timePeriods,
            periodBuckets = periodBuckets,
            dailyBuckets = if (window.range == StatsRange.DAY) emptyList() else
                AnalyticsCalculator.computeDateBuckets(periodTransactions, window.startDate, window.endDate, mode),
            weeklyBuckets = if (window.range != StatsRange.MONTH) emptyList() else
                AnalyticsCalculator.computeDateBuckets(periodTransactions, window.startDate, window.endDate, mode, byWeek = true),
            categoryBreakdown = categoryBreakdown,
            channelBreakdown = channelBreakdown,
            channelSummary = channelSummary,
            categories = sortedCategories,
            categoriesById = categoriesById,
            drillDown = resolvedDrillDown,
            drillDownTransactions = drillDownTransactions
        )
    }.flowOn(computeDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = run {
                val initialWindow = computePeriodWindow(StatsRange.MONTH, initialToday)
                StatsUiState(
                    range = StatsRange.MONTH,
                    startDate = initialWindow.startDate,
                    endDate = initialWindow.endDate,
                    periodLabel = formatPeriodLabel(initialWindow, initialToday)
                )
            }
        )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun setRange(range: StatsRange) {
        if (rangeFlow.value != range) {
            rangeFlow.value = range
            pinnedHourFlow.value = null
            drillDownRequestFlow.value = null
        }
    }

    fun previousPeriod() {
        val current = anchorDateFlow.value
        anchorDateFlow.value = when (rangeFlow.value) {
            StatsRange.DAY -> current.minusDays(1)
            StatsRange.WEEK -> current.minusWeeks(1)
            StatsRange.MONTH -> {
                val prevMonth = YearMonth.from(current).minusMonths(1)
                prevMonth.atDay(minOf(current.dayOfMonth, prevMonth.lengthOfMonth()))
            }
        }
        pinnedHourFlow.value = null
        drillDownRequestFlow.value = null
    }

    fun nextPeriod() {
        val current = anchorDateFlow.value
        anchorDateFlow.value = when (rangeFlow.value) {
            StatsRange.DAY -> current.plusDays(1)
            StatsRange.WEEK -> current.plusWeeks(1)
            StatsRange.MONTH -> {
                val nextMonth = YearMonth.from(current).plusMonths(1)
                nextMonth.atDay(minOf(current.dayOfMonth, nextMonth.lengthOfMonth()))
            }
        }
        pinnedHourFlow.value = null
        drillDownRequestFlow.value = null
    }

    fun setMetricMode(mode: StatsMetricMode) {
        if (metricModeFlow.value != mode) {
            metricModeFlow.value = mode
            pinnedHourFlow.value = null
            drillDownRequestFlow.value = null
        }
    }

    fun selectHour(hour: Int) {
        if (hour !in 0..23) return
        pinnedHourFlow.value = hour
    }

    fun selectCategory(categoryId: String?) {
        drillDownRequestFlow.value = categoryId?.takeIf { it.isNotBlank() }?.let {
            DrillDownRequest.Category(it)
        }
    }

    fun selectCategoryForDrillDown(categoryId: String?) {
        selectCategory(categoryId)
    }

    fun selectHourForDrillDown(hour: Int?) {
        if (hour == null || hour !in 0..23) {
            drillDownRequestFlow.value = null
        } else {
            pinnedHourFlow.value = hour
            drillDownRequestFlow.value = DrillDownRequest.Hour(hour)
        }
    }

    fun dismissDrillDown() {
        drillDownRequestFlow.value = null
    }

    fun selectTimePeriodForDrillDown(periodId: String) {
        val index = periodId.toIntOrNull() ?: return
        drillDownRequestFlow.value = DrillDownRequest.Period(index)
    }

    fun refreshTodayIfNeeded() {
        val nowToday = todayProvider()
        val previousToday = todayFlow.value
        if (nowToday == previousToday) return
        todayFlow.value = nowToday
        if (anchorDateFlow.value == previousToday) {
            anchorDateFlow.value = nowToday
        }
    }

    fun updateTransaction(record: TransactionRecord, learnMerchantCategory: Boolean = true) {
        viewModelScope.launch {
            runCatching { repository.updateTransaction(record, learnMerchantCategory) }
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            runCatching { repository.deleteTransaction(transactionId) }
        }
    }

    private fun computePeriodWindow(range: StatsRange, anchor: LocalDate): PeriodWindow =
        when (range) {
            StatsRange.DAY -> PeriodWindow(
                range = range,
                anchorDate = anchor,
                startDate = anchor,
                endDate = anchor
            )
            StatsRange.WEEK -> {
                val monday = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                PeriodWindow(
                    range = range,
                    anchorDate = anchor,
                    startDate = monday,
                    endDate = monday.plusDays(6)
                )
            }
            StatsRange.MONTH -> {
                val ym = YearMonth.from(anchor)
                PeriodWindow(
                    range = range,
                    anchorDate = anchor,
                    startDate = ym.atDay(1),
                    endDate = ym.atEndOfMonth()
                )
            }
        }

    private fun formatPeriodLabel(window: PeriodWindow, today: LocalDate): String =
        when (window.range) {
            StatsRange.DAY -> {
                val d = window.startDate
                val datePart = if (d.year == today.year) {
                    "${d.monthValue}月${d.dayOfMonth}日"
                } else {
                    "${d.year}年${d.monthValue}月${d.dayOfMonth}日"
                }
                when (d) {
                    today -> "今天 · $datePart"
                    today.minusDays(1) -> "昨天 · $datePart"
                    else -> datePart
                }
            }
            StatsRange.WEEK -> {
                val s = window.startDate
                val e = window.endDate
                val todayMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                val rangeText = "${s.monthValue}.${s.dayOfMonth}–${e.monthValue}.${e.dayOfMonth}"
                when (s) {
                    todayMonday -> "本周 · $rangeText"
                    todayMonday.minusWeeks(1) -> "上周 · $rangeText"
                    else -> if (s.year == today.year) {
                        "${s.monthValue}月${s.dayOfMonth}日–${e.monthValue}月${e.dayOfMonth}日"
                    } else {
                        "${s.year}年 $rangeText"
                    }
                }
            }
            StatsRange.MONTH -> {
                val ym = YearMonth.from(window.startDate)
                if (ym == YearMonth.from(today)) {
                    "本月 · ${ym.year}年${ym.monthValue}月"
                } else {
                    "${ym.year}年${ym.monthValue}月"
                }
            }
        }

    private fun isIncludedInMode(record: TransactionRecord, mode: StatsMetricMode): Boolean {
        if (record.excludedFromStats) return false
        return when (mode) {
            StatsMetricMode.NET_EXPENSE ->
                record.type == TransactionType.EXPENSE || record.type == TransactionType.REFUND
            StatsMetricMode.INCOME ->
                record.type == TransactionType.INCOME
        }
    }

    private fun signedAmountForMode(record: TransactionRecord, mode: StatsMetricMode): Long =
        when (mode) {
            StatsMetricMode.NET_EXPENSE -> when (record.type) {
                TransactionType.EXPENSE -> record.amountCents
                TransactionType.REFUND -> -record.amountCents
                TransactionType.INCOME, TransactionType.TRANSFER -> 0L
            }
            StatsMetricMode.INCOME ->
                if (record.type == TransactionType.INCOME) record.amountCents else 0L
        }

    private fun summarizeChannels(stats: List<ChannelStat>): ChannelGroupSummary {
        var wechatPct = 0
        var wechatAmt = 0L
        var alipayPct = 0
        var alipayAmt = 0L
        var unionBankPct = 0
        var unionBankAmt = 0L
        var otherPct = 0
        var otherAmt = 0L

        for (stat in stats) {
            when (stat.channel) {
                PaymentChannel.WECHAT -> {
                    wechatPct += stat.sharePercent
                    wechatAmt += stat.amountCents
                }
                PaymentChannel.ALIPAY -> {
                    alipayPct += stat.sharePercent
                    alipayAmt += stat.amountCents
                }
                PaymentChannel.UNIONPAY, PaymentChannel.BANK -> {
                    unionBankPct += stat.sharePercent
                    unionBankAmt += stat.amountCents
                }
                PaymentChannel.MANUAL, PaymentChannel.OTHER -> {
                    otherPct += stat.sharePercent
                    otherAmt += stat.amountCents
                }
            }
        }

        return ChannelGroupSummary(
            wechatSharePercent = wechatPct,
            wechatAmountCents = wechatAmt,
            alipaySharePercent = alipayPct,
            alipayAmountCents = alipayAmt,
            unionPayAndBankSharePercent = unionBankPct,
            unionPayAndBankAmountCents = unionBankAmt,
            otherSharePercent = otherPct,
            otherAmountCents = otherAmt
        )
    }

    fun saveTimePeriods(periods: List<TimePeriod>) {
        preferences?.setTimePeriods(periods)
    }

    companion object {
        fun factory(
            repository: LedgerRepository,
            todayProvider: () -> LocalDate = { LocalDate.now() },
            zoneId: ZoneId = ZoneId.systemDefault(),
            preferences: LedgerPreferences? = null
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(StatsViewModel::class.java)) {
                    return StatsViewModel(
                        repository = repository,
                        todayProvider = todayProvider,
                        zoneId = zoneId,
                        preferences = preferences
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
