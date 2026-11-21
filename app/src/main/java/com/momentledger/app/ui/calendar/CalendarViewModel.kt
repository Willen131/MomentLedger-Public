package com.momentledger.app.ui.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.engine.AnalyticsCalculator
import com.momentledger.app.domain.model.CalendarDaySummary
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.HourlyBucket
import com.momentledger.app.domain.model.MonthlyCalendarStats
import com.momentledger.app.domain.model.CalendarMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class CalendarUiState(
    val today: LocalDate,
    val currentMonth: YearMonth,
    val selectedDate: LocalDate,
    val selectedHour: Int? = null,
    val metricMode: CalendarMetricMode = CalendarMetricMode.EXPENSE,
    val monthlyStats: MonthlyCalendarStats = MonthlyCalendarStats(
        yearMonth = currentMonth.toString(),
        totalNetAmountCents = 0L,
        dailyAverageCents = 0L,
        highSpendThresholdCents = 0L,
        daysByDate = emptyMap()
    ),
    val selectedDayHourlyBuckets: List<HourlyBucket> = AnalyticsCalculator.computeHourlyBuckets(
        emptyList(),
        metricMode
    ),
    val selectedDayAllTimeline: List<TransactionRecord> = emptyList(),
    val visibleTimeline: List<TransactionRecord> = emptyList(),
    val categories: List<Category> = emptyList(),
    val categoriesById: Map<String, Category> = emptyMap()
) {
    val visibleTimelineTotalCents: Long
        get() = visibleTimeline.sumOf { metricMode.amountFor(it) ?: 0L }

    val monthTotalCents: Long
        get() = monthlyStats.totalNetAmountCents

    val dailyAverageCents: Long
        get() = monthlyStats.dailyAverageCents

    val selectedDayTimeline: List<TransactionRecord>
        get() = selectedDayAllTimeline

    val selectedDaySummary: CalendarDaySummary?
        get() = monthlyStats.daysByDate[selectedDate.toString()]

    val selectedDayNetCents: Long
        get() = selectedDaySummary?.netAmountCents ?: 0L

    val selectedDayIncludedCount: Int
        get() = selectedDaySummary?.transactionCount ?: 0
}

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModel(
    private val repository: LedgerRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
    val zoneId: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val initialToday: LocalDate = todayProvider()
    private val currentMonthFlow = MutableStateFlow(YearMonth.from(initialToday))
    private val selectedDateFlow = MutableStateFlow(initialToday)
    private val selectedHourFlow = MutableStateFlow<Int?>(null)
    private val metricModeFlow = MutableStateFlow(CalendarMetricMode.EXPENSE)

    private data class SelectionParams(
        val currentMonth: YearMonth,
        val selectedDate: LocalDate,
        val selectedHour: Int?,
        val metricMode: CalendarMetricMode
    )

    private val selectionParamsFlow = combine(
        currentMonthFlow,
        selectedDateFlow,
        selectedHourFlow,
        metricModeFlow
    ) { month, date, hour, mode ->
        SelectionParams(
            currentMonth = month,
            selectedDate = date,
            selectedHour = hour,
            metricMode = mode
        )
    }

    private val monthTransactionsFlow = currentMonthFlow.flatMapLatest { month ->
        repository.observeTransactionsInDateRange(
            startDate = month.atDay(1).toString(),
            endDate = month.atEndOfMonth().toString()
        )
    }

    val uiState: StateFlow<CalendarUiState> = combine(
        selectionParamsFlow,
        monthTransactionsFlow,
        repository.observeCategories()
    ) { params, monthTransactions, categories ->
        val today = todayProvider()
        val monthlyStats = AnalyticsCalculator.computeMonthlyCalendar(
            transactions = monthTransactions,
            yearMonth = params.currentMonth,
            mode = params.metricMode
        )

        val selectedDateKey = params.selectedDate.toString()
        val dayTimeline = monthTransactions
            .filter { it.localDate == selectedDateKey && params.metricMode.amountFor(it) != null }
            .sortedWith(compareBy<TransactionRecord> { it.timestampMillis }.thenBy { it.id })

        val hourlyBuckets = AnalyticsCalculator.computeHourlyBuckets(
            transactions = dayTimeline,
            mode = params.metricMode
        )

        val filteredTimeline = if (params.selectedHour == null) {
            dayTimeline
        } else {
            dayTimeline.filter { it.hourOfDay == params.selectedHour }
        }

        val sortedCategories = categories.sortedWith(
            compareBy<Category> { it.categoryType.ordinal }
                .thenBy { it.sortOrder }
                .thenBy { it.id }
        )

        CalendarUiState(
            today = today,
            currentMonth = params.currentMonth,
            selectedDate = params.selectedDate,
            selectedHour = params.selectedHour,
            metricMode = params.metricMode,
            monthlyStats = monthlyStats,
            selectedDayHourlyBuckets = hourlyBuckets,
            selectedDayAllTimeline = dayTimeline,
            visibleTimeline = filteredTimeline,
            categories = sortedCategories,
            categoriesById = sortedCategories.associateBy { it.id }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = CalendarUiState(
            today = initialToday,
            currentMonth = YearMonth.from(initialToday),
            selectedDate = initialToday
        )
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
        }
    }

    fun previousMonth() {
        setMonth(currentMonthFlow.value.minusMonths(1))
    }

    fun nextMonth() {
        setMonth(currentMonthFlow.value.plusMonths(1))
    }

    fun setMonth(month: YearMonth) {
        currentMonthFlow.value = month
        val today = todayProvider()
        selectedDateFlow.value = if (month == YearMonth.from(today)) {
            today
        } else {
            month.atDay(1)
        }
        selectedHourFlow.value = null
    }

    fun selectDate(date: LocalDate) {
        val targetMonth = YearMonth.from(date)
        if (currentMonthFlow.value != targetMonth) {
            currentMonthFlow.value = targetMonth
        }
        selectedDateFlow.value = date
        selectedHourFlow.value = null
    }

    fun selectHour(hour: Int?) {
        if (hour == null || hour in 0..23) selectedHourFlow.value = hour
    }

    fun setMetricMode(mode: CalendarMetricMode) {
        if (metricModeFlow.value != mode) {
            metricModeFlow.value = mode
            selectedHourFlow.value = null
        }
    }

    fun saveManualTransaction(record: TransactionRecord) {
        viewModelScope.launch {
            repository.saveManualTransaction(record)
            val savedDate = runCatching { LocalDate.parse(record.localDate) }.getOrElse {
                java.time.Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId).toLocalDate()
            }
            if (savedDate != selectedDateFlow.value) {
                selectDate(savedDate)
            }
        }
    }

    fun updateTransaction(record: TransactionRecord, learnMerchantCategory: Boolean = true) {
        viewModelScope.launch {
            repository.updateTransaction(record, learnMerchantCategory)
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            repository.deleteTransaction(transactionId)
        }
    }

    companion object {
        fun factory(
            repository: LedgerRepository,
            todayProvider: () -> LocalDate = { LocalDate.now() },
            zoneId: ZoneId = ZoneId.systemDefault()
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass.isAssignableFrom(CalendarViewModel::class.java)) {
                    return CalendarViewModel(
                        repository = repository,
                        todayProvider = todayProvider,
                        zoneId = zoneId
                    ) as T
                }
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }
        }
    }
}
