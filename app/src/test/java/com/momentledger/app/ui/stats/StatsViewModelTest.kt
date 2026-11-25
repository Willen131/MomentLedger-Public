package com.momentledger.app.ui.stats

import com.momentledger.app.data.repository.LedgerRepository
import com.momentledger.app.domain.engine.DeduplicationEngine
import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.StatsMetricMode
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@OptIn(ExperimentalCoroutinesApi::class)
class StatsViewModelTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")
    // 2026-11-19 is a Thursday (week: Mon 2026-11-16 .. Sun 2026-11-22)
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

    private fun createHarness(): Pair<LedgerRepository, StatsViewModel> {
        val store = FakeLedgerStore()
        val repository = LedgerRepository(
            store = store,
            dedupEngine = DeduplicationEngine(zoneId = shanghaiZone),
            zoneId = shanghaiZone
        )
        val viewModel = StatsViewModel(
            repository = repository,
            todayProvider = { fixedToday },
            zoneId = shanghaiZone,
            computeDispatcher = testDispatcher
        )
        return repository to viewModel
    }

    @Test
    fun switchingStatsRangeAndSteppingPeriods_updatesDateBoundariesAndFilteredTotals() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        // Seed data across October, early Nov, current week, and yesterday/today
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 10, 25), hour = 12, amountCents = 10000L, merchant = "十月消费")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 10), hour = 12, amountCents = 5000L, merchant = "十一月初")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 17), hour = 18, amountCents = 4000L, merchant = "本周二")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 18), hour = 9, amountCents = 2500L, merchant = "昨天")
        )
        repository.saveManualTransaction(
            createRecord(date = LocalDate.of(2026, 11, 19), hour = 20, amountCents = 3000L, merchant = "今天")
        )
        advanceUntilIdle()

        // Default: MONTH (2026-11-01 .. 2026-11-30)
        assertEquals(StatsRange.MONTH, viewModel.uiState.value.range)
        assertEquals(LocalDate.of(2026, 11, 1), viewModel.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 11, 30), viewModel.uiState.value.endDate)
        assertEquals(14500L, viewModel.uiState.value.periodTotalCents)

        // Previous month -> October
        viewModel.previousPeriod()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 10, 1), viewModel.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 10, 31), viewModel.uiState.value.endDate)
        assertEquals(10000L, viewModel.uiState.value.periodTotalCents)

        // Next month -> back to November
        viewModel.nextPeriod()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 1), viewModel.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 11, 30), viewModel.uiState.value.endDate)

        // Switch to WEEK (Mon 2026-11-16 .. Sun 2026-11-22)
        viewModel.setRange(StatsRange.WEEK)
        advanceUntilIdle()
        assertEquals(StatsRange.WEEK, viewModel.uiState.value.range)
        assertEquals(LocalDate.of(2026, 11, 16), viewModel.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 11, 22), viewModel.uiState.value.endDate)
        assertEquals(9500L, viewModel.uiState.value.periodTotalCents)

        // Previous week (Mon 2026-11-09 .. Sun 2026-11-15)
        viewModel.previousPeriod()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 9), viewModel.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 11, 15), viewModel.uiState.value.endDate)
        assertEquals(5000L, viewModel.uiState.value.periodTotalCents)

        // Next week -> back to 11-16..11-22, then switch to DAY (2026-11-19)
        viewModel.nextPeriod()
        advanceUntilIdle()
        viewModel.setRange(StatsRange.DAY)
        advanceUntilIdle()
        assertEquals(StatsRange.DAY, viewModel.uiState.value.range)
        assertEquals(fixedToday, viewModel.uiState.value.startDate)
        assertEquals(fixedToday, viewModel.uiState.value.endDate)
        assertEquals(3000L, viewModel.uiState.value.periodTotalCents)

        // Previous day -> 2026-11-18
        viewModel.previousPeriod()
        advanceUntilIdle()
        assertEquals(LocalDate.of(2026, 11, 18), viewModel.uiState.value.startDate)
        assertEquals(LocalDate.of(2026, 11, 18), viewModel.uiState.value.endDate)
        assertEquals(2500L, viewModel.uiState.value.periodTotalCents)

        collectJob.cancel()
    }

    @Test
    fun selectedHour_defaultsToPeakOr12WhenEmpty_andExposesMatchingDrillDownTransactions() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }
        advanceUntilIdle()

        // Empty period defaults selectedHour to 12
        assertEquals(12, viewModel.uiState.value.selectedHour)
        assertTrue(viewModel.uiState.value.selectedHourTransactions.isEmpty())

        repository.saveManualTransaction(
            createRecord(date = fixedToday, hour = 8, amountCents = 2500L, merchant = "早餐店")
        )
        repository.saveManualTransaction(
            createRecord(date = fixedToday, hour = 12, amountCents = 4000L, merchant = "午餐店")
        )
        repository.saveManualTransaction(
            createRecord(date = fixedToday, hour = 19, amountCents = 12000L, merchant = "晚餐火锅")
        )
        advanceUntilIdle()

        // Automatically moves to peak hour 19 when not pinned by user
        assertEquals(19, viewModel.uiState.value.selectedHour)
        assertTrue(viewModel.uiState.value.selectedHourBucket.isPeak)
        assertEquals(listOf("晚餐火锅"), viewModel.uiState.value.selectedHourTransactions.map { it.merchant })

        // Selecting hour 8 pins hour 8 and exposes hour-8 transactions
        viewModel.selectHour(8)
        advanceUntilIdle()
        assertEquals(8, viewModel.uiState.value.selectedHour)
        assertEquals(listOf("早餐店"), viewModel.uiState.value.selectedHourTransactions.map { it.merchant })

        // Triggering hour drill-down sheet exposes hour-8 transactions
        viewModel.selectHourForDrillDown(8)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.drillDown is StatsDrillDown.ByHour)
        assertEquals(listOf("早餐店"), viewModel.uiState.value.drillDownTransactions.map { it.merchant })

        viewModel.dismissDrillDown()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.drillDown)

        collectJob.cancel()
    }

    @Test
    fun selectCategory_filtersDrillDownTransactionsForCategoryInActivePeriod() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        repository.saveManualTransaction(
            createRecord(
                date = LocalDate.of(2026, 10, 15),
                hour = 12,
                amountCents = 9900L,
                merchant = "十月餐饮",
                categoryId = "exp_food"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = LocalDate.of(2026, 11, 5),
                hour = 12,
                amountCents = 3400L,
                merchant = "老乡鸡",
                categoryId = "exp_food"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = LocalDate.of(2026, 11, 19),
                hour = 8,
                amountCents = 1800L,
                merchant = "瑞幸咖啡",
                categoryId = "exp_food"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = LocalDate.of(2026, 11, 19),
                hour = 18,
                amountCents = 2500L,
                merchant = "被排除餐饮",
                categoryId = "exp_food",
                excludedFromStats = true
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = LocalDate.of(2026, 11, 19),
                hour = 19,
                amountCents = 2400L,
                merchant = "滴滴出行",
                categoryId = "exp_transport"
            )
        )
        advanceUntilIdle()

        viewModel.selectCategory("exp_food")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.drillDown is StatsDrillDown.ByCategory)
        val byCategory = state.drillDown as StatsDrillDown.ByCategory
        assertEquals("exp_food", byCategory.categoryId)
        assertEquals(listOf("瑞幸咖啡", "老乡鸡"), state.drillDownTransactions.map { it.merchant })

        viewModel.dismissDrillDown()
        advanceUntilIdle()
        assertNull(viewModel.uiState.value.drillDown)

        collectJob.cancel()
    }

    @Test
    fun switchingMetricMode_recomputesPeakHourCategoryAndChannelBreakdown() = runTest {
        val (repository, viewModel) = createHarness()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        repository.saveManualTransaction(
            createRecord(
                date = fixedToday,
                hour = 12,
                amountCents = 6000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.WECHAT,
                merchant = "午餐",
                categoryId = "exp_food"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = fixedToday,
                hour = 19,
                amountCents = 4000L,
                type = TransactionType.EXPENSE,
                channel = PaymentChannel.BANK,
                merchant = "打车",
                categoryId = "exp_transport"
            )
        )
        repository.saveManualTransaction(
            createRecord(
                date = fixedToday,
                hour = 9,
                amountCents = 50000L,
                type = TransactionType.INCOME,
                channel = PaymentChannel.ALIPAY,
                merchant = "兼职报酬",
                categoryId = "inc_salary"
            )
        )
        advanceUntilIdle()

        // Expense mode: peak hour 12, pin hour 19 manually
        assertEquals(12, viewModel.uiState.value.selectedHour)
        assertEquals(60, viewModel.uiState.value.channelSummary.wechatSharePercent)
        assertEquals(40, viewModel.uiState.value.channelSummary.unionPayAndBankSharePercent)

        viewModel.selectHour(19)
        advanceUntilIdle()
        assertEquals(19, viewModel.uiState.value.selectedHour)

        // Switching to INCOME clears hour pin and picks peak income hour 9
        viewModel.setMetricMode(StatsMetricMode.INCOME)
        advanceUntilIdle()

        assertEquals(StatsMetricMode.INCOME, viewModel.uiState.value.metricMode)
        assertEquals(9, viewModel.uiState.value.selectedHour)
        assertEquals(50000L, viewModel.uiState.value.periodTotalCents)
        assertEquals(100, viewModel.uiState.value.channelSummary.alipaySharePercent)
        assertEquals(listOf("inc_salary"), viewModel.uiState.value.categoryBreakdown.map { it.categoryId })

        collectJob.cancel()
    }
}
