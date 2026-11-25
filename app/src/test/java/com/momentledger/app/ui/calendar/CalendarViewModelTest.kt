package com.momentledger.app.ui.calendar

import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.CalendarMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import com.momentledger.app.testutil.FakeLedgerStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val fixedToday: LocalDate = LocalDate.of(2026, 11, 19)
    private lateinit var testDispatcher: TestDispatcher

    @Before
    fun setUp() {
        testDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createRecord(
        id: Long = 0L,
        date: LocalDate,
        hour: Int,
        minute: Int = 0,
        amountCents: Long,
        type: TransactionType = TransactionType.EXPENSE,
        channel: PaymentChannel = PaymentChannel.WECHAT,
        merchant: String = "商户$id",
        categoryId: String = "exp_food",
        excludedFromStats: Boolean = false
    ): TransactionRecord {
        val millis = ZonedDateTime.of(
            date.year,
            date.monthValue,
            date.dayOfMonth,
            hour,
            minute,
            0,
            0,
            shanghaiZone
        ).toInstant().toEpochMilli()
        return TransactionRecord(
            id = id,
            timestampMillis = millis,
            localDate = date.toString(),
            hourOfDay = hour,
            amountCents = amountCents,
            type = type,
            channel = channel,
            fundingMethod = "零钱",
            merchant = merchant,
            categoryId = categoryId,
            note = "",
            excludedFromStats = excludedFromStats,
            captureSource = CaptureSource.MANUAL,
            externalOrderId = null,
            createdAtMillis = millis,
            updatedAtMillis = millis
        )
    }

    private fun createHarness(): Pair<LedgerRepository, CalendarViewModel> {
        val store = FakeLedgerStore()
        val repository = LedgerRepository(
            store = store,
            dedupEngine = DeduplicationEngine(zoneId = shanghaiZone),
            zoneId = shanghaiZone
        )
        val viewModel = CalendarViewModel(
            repository = repository,
            todayProvider = { fixedToday },
            zoneId = shanghaiZone,
            computeDispatcher = testDispatcher
        )
        return repository to viewModel
    }

    @Test
    fun switchingMonth_updatesObservedRangeAndRecomputesTotalsAndDailyAverage() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        // October 2026: 50.00 + 150.00 = 200.00 across 2 active days (daily avg 100.00)
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 10, 10), hour = 10, amountCents = 5000L, merchant = "十月早餐")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 10, 20), hour = 19, amountCents = 15000L, merchant = "十月晚餐")
        )

        // November 2026: 30.00 + 90.00 + 60.00 = 180.00 across 3 active days (daily avg 60.00)
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 1), hour = 9, amountCents = 3000L, merchant = "十一月一号")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 2), hour = 12, amountCents = 9000L, merchant = "十一月二号")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 19), hour = 18, amountCents = 6000L, merchant = "十一月十九")
        )
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 11), viewModel.uiState.value.currentMonth)
        assertEquals(18000L, viewModel.uiState.value.monthTotalCents)
        assertEquals(6000L, viewModel.uiState.value.dailyAverageCents)
        assertEquals(3, viewModel.uiState.value.monthlyStats.daysByDate.size)

        viewModel.previousMonth()
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 10), viewModel.uiState.value.currentMonth)
        assertEquals(20000L, viewModel.uiState.value.monthTotalCents)
        assertEquals(10000L, viewModel.uiState.value.dailyAverageCents)
        assertEquals(2, viewModel.uiState.value.monthlyStats.daysByDate.size)
        assertFalse(viewModel.uiState.value.monthlyStats.daysByDate.containsKey("2026-11-19"))

        viewModel.nextMonth()
        advanceUntilIdle()

        assertEquals(YearMonth.of(2026, 11), viewModel.uiState.value.currentMonth)
        assertEquals(18000L, viewModel.uiState.value.monthTotalCents)
        assertEquals(6000L, viewModel.uiState.value.dailyAverageCents)

        collectJob.cancel()
    }

    @Test
    fun selectingDayAndTogglingHour_filtersAndClearsVisibleTimeline() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val nov18 = LocalDate.of(2026, 11, 18)
        val nov19 = LocalDate.of(2026, 11, 19)

        repository.saveManualTransaction(
            createRecord(date = nov18, hour = 9, minute = 15, amountCents = 2000L, merchant = "星巴克")
        )
        repository.saveManualTransaction(
            createRecord(date = nov19, hour = 8, minute = 36, amountCents = 1690L, merchant = "瑞幸咖啡")
        )
        repository.saveManualTransaction(
            createRecord(date = nov19, hour = 12, minute = 14, amountCents = 3450L, merchant = "老乡鸡餐厅")
        )
        repository.saveManualTransaction(
            createRecord(date = nov19, hour = 12, minute = 42, amountCents = 1550L, merchant = "喜茶")
        )
        repository.saveManualTransaction(
            createRecord(date = nov19, hour = 20, minute = 10, amountCents = 4860L, merchant = "夜宵烧烤")
        )
        advanceUntilIdle()

        // Default selectedDate is fixedToday (2026-11-19)
        val initialState = viewModel.uiState.value
        assertEquals(nov19, initialState.selectedDate)
        assertNull(initialState.selectedHour)
        assertEquals(4, initialState.selectedDayTimeline.size)
        assertEquals(4, initialState.visibleTimeline.size)
        assertEquals(24, initialState.selectedDayHourlyBuckets.size)
        assertEquals(5000L, initialState.selectedDayHourlyBuckets[12].displayAmountCents)
        assertTrue(initialState.selectedDayHourlyBuckets[12].isPeak)

        // Select Nov 18
        viewModel.selectDate(nov18)
        advanceUntilIdle()
        assertEquals(nov18, viewModel.uiState.value.selectedDate)
        assertEquals(1, viewModel.uiState.value.selectedDayTimeline.size)
        assertEquals("星巴克", viewModel.uiState.value.visibleTimeline.single().merchant)
        assertEquals(2000L, viewModel.uiState.value.selectedDayHourlyBuckets[9].displayAmountCents)

        // Back to Nov 19 and toggle hour 12
        viewModel.selectDate(nov19)
        advanceUntilIdle()
        viewModel.selectHour(12)
        advanceUntilIdle()

        assertEquals(12, viewModel.uiState.value.selectedHour)
        assertEquals(4, viewModel.uiState.value.selectedDayTimeline.size)
        assertEquals(2, viewModel.uiState.value.visibleTimeline.size)
        assertEquals(listOf("老乡鸡餐厅", "喜茶"), viewModel.uiState.value.visibleTimeline.map { it.merchant })

        assertEquals(5000L, viewModel.uiState.value.visibleTimelineTotalCents)
        viewModel.selectHour(12)
        advanceUntilIdle()
        assertEquals(12, viewModel.uiState.value.selectedHour)

        viewModel.selectHour(null)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.selectedHour)
        assertEquals(4, viewModel.uiState.value.visibleTimeline.size)

        collectJob.cancel()
    }

    @Test
    fun switchingMetricMode_updatesCalendarCellAmountsAndHeaderTotals() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val nov19 = LocalDate.of(2026, 11, 19)
        repository.saveManualTransaction(
            createRecord(
                date = nov19,
                hour = 12,
                amountCents = 10000L,
                type = TransactionType.EXPENSE,
                merchant = "盒马鲜生"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = nov19,
                hour = 13,
                amountCents = 2500L,
                type = TransactionType.REFUND,
                merchant = "盒马退款"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = nov19,
                hour = 15,
                amountCents = 80000L,
                type = TransactionType.INCOME,
                channel = PaymentChannel.BANK,
                merchant = "工资入账",
                categoryId = "inc_salary"
            )
        )
        advanceUntilIdle()

        assertEquals(CalendarMetricMode.EXPENSE, viewModel.uiState.value.metricMode)
        assertEquals(10000L, viewModel.uiState.value.monthTotalCents)
        assertEquals(listOf(TransactionType.EXPENSE), viewModel.uiState.value.visibleTimeline.map { it.type })
        assertFalse(viewModel.uiState.value.selectedDaySummary!!.hasRefund)

        viewModel.setMetricMode(CalendarMetricMode.INCOME)
        advanceUntilIdle()
        assertEquals(82500L, viewModel.uiState.value.monthTotalCents)
        assertEquals(listOf(TransactionType.REFUND, TransactionType.INCOME), viewModel.uiState.value.visibleTimeline.map { it.type })
        assertTrue(viewModel.uiState.value.selectedDaySummary!!.hasRefund)
        assertEquals(80000L, viewModel.uiState.value.selectedDayHourlyBuckets[15].displayAmountCents)

        viewModel.selectHour(15)
        advanceUntilIdle()
        assertEquals(80000L, viewModel.uiState.value.visibleTimelineTotalCents)
        viewModel.setMetricMode(CalendarMetricMode.NET_EXPENSE)
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.selectedHour)
        assertEquals(-72500L, viewModel.uiState.value.visibleTimelineTotalCents)
        assertEquals(3, viewModel.uiState.value.visibleTimeline.size)

        viewModel.setMetricMode(CalendarMetricMode.NET_INCOME)
        advanceUntilIdle()
        assertEquals(72500L, viewModel.uiState.value.visibleTimelineTotalCents)
        assertEquals(3, viewModel.uiState.value.visibleTimeline.size)

        collectJob.cancel()
    }

    @Test
    fun saveUpdateAndDeleteTransaction_reflectsReactivelyInCalendarState() = runTest {
        val (_, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        val nov19 = LocalDate.of(2026, 11, 19)
        viewModel.saveManualTransaction(
            createRecord(
                date = nov19,
                hour = 10,
                minute = 15,
                amountCents = 4200L,
                merchant = "测试书店",
                categoryId = "exp_study"
            )
        )
        advanceUntilIdle()

        assertEquals(4200L, viewModel.uiState.value.monthTotalCents)
        val saved = viewModel.uiState.value.visibleTimeline.single()
        assertEquals("测试书店", saved.merchant)

        viewModel.updateTransaction(
            saved.copy(amountCents = 5800L, excludedFromStats = true),
            learnMerchantCategory = false
        )
        advanceUntilIdle()

        assertEquals(0L, viewModel.uiState.value.monthTotalCents)
        assertEquals(1, viewModel.uiState.value.visibleTimeline.size)
        assertTrue(viewModel.uiState.value.visibleTimeline.single().excludedFromStats)

        viewModel.deleteTransaction(saved.id)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.visibleTimeline.isEmpty())
        assertEquals(0L, viewModel.uiState.value.monthTotalCents)

        collectJob.cancel()
    }
}
