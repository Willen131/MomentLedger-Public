package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class CalendarAndPeriodsTest {
    @Test
    fun calendarSeparatesFlowsAndUsesSignedNetAmounts() {
        val transactions = listOf(
            record(TransactionType.EXPENSE, 1200, "11:00"),
            record(TransactionType.INCOME, 300, "11:00"),
            record(TransactionType.REFUND, 200, "11:00"),
            record(TransactionType.TRANSFER, 4000, "11:00"),
            record(TransactionType.EXPENSE, 8000, "11:00").copy(excludedFromStats = true)
        )
        val expectations = listOf(
            Triple(CalendarMetricMode.EXPENSE, 1200L, 1),
            Triple(CalendarMetricMode.INCOME, 500L, 2),
            Triple(CalendarMetricMode.NET_EXPENSE, 700L, 3),
            Triple(CalendarMetricMode.NET_INCOME, -700L, 3)
        )
        for ((mode, total, count) in expectations) {
            val month = AnalyticsCalculator.computeMonthlyCalendar(transactions, YearMonth.of(2026, 11), mode)
            val hour = AnalyticsCalculator.computeHourlyBuckets(transactions, mode)[11]
            assertEquals(total, month.totalNetAmountCents)
            assertEquals(total, hour.netAmountCents)
            assertEquals(kotlin.math.abs(total), hour.displayAmountCents)
            assertEquals(count, hour.transactionCount)
            assertEquals(mode != CalendarMetricMode.EXPENSE, hour.hasRefund)
        }
    }

    @Test
    fun boundariesUseMinutesAndEveryKnownTransactionFallsInExactlyOnePeriod() {
        val periods = defaultTimePeriods
        assertTrue(areValidTimePeriods(periods))
        val adjusted = periods.mapIndexed { index, period ->
            when (index) {
                0 -> period.copy(endMinute = 11 * 60 + 15)
                1 -> period.copy(startMinute = 11 * 60 + 15)
                else -> period
            }
        }
        assertTrue(areValidTimePeriods(adjusted))
        assertFalse(areValidTimePeriods(adjusted.mapIndexed { i, row ->
            if (i == 1) row.copy(startMinute = 11 * 60 + 16) else row
        }))
        val rows = listOf(
            record(TransactionType.EXPENSE, 1200, "11:14"),
            record(TransactionType.EXPENSE, 300, "11:15"),
            record(TransactionType.REFUND, 100, "11:20"),
            record(TransactionType.EXPENSE, 999, "11:15").copy(hourOfDay = HOUR_OF_DAY_UNKNOWN)
        )
        val result = AnalyticsCalculator.computeTimePeriods(rows, adjusted, StatsMetricMode.NET_EXPENSE, ZoneOffset.UTC)
        assertEquals(listOf(1200L, 200L, 0L, 0L, 0L, 0L), result.map { it.amountCents })
        assertEquals(100, result.sumOf { it.sharePercent })
    }

    @Test
    fun customPeriodsAcceptAnyCountThatPartitionsTheDay() {
        val morning = TimePeriod("上午", 0, 720)
        val afternoon = TimePeriod("下午", 720, 1440)
        assertTrue(areValidTimePeriods(listOf(TimePeriod("全天", 0, 1440))))
        assertTrue(areValidTimePeriods(listOf(afternoon, morning)))
        assertFalse(areValidTimePeriods(emptyList()))
        assertFalse(areValidTimePeriods(listOf(morning.copy(label = " "), afternoon)))
        assertFalse(areValidTimePeriods(listOf(morning.copy(endMinute = 721), afternoon)))
        assertFalse(areValidTimePeriods(listOf(morning.copy(endMinute = 719), afternoon)))
        assertFalse(areValidTimePeriods(listOf(morning.copy(startMinute = -1), afternoon)))
        assertFalse(areValidTimePeriods(listOf(morning, afternoon.copy(endMinute = 1441))))
        assertFalse(areValidTimePeriods(listOf(morning, afternoon.copy(endMinute = 720))))
    }

    @Test
    fun dateBucketsUseLedgerDatesAndKeepEmptyDays() {
        val start = LocalDate.of(2026, 11, 1)
        val transactions = listOf(
            record(TransactionType.EXPENSE, 1200, "11:00", "2026-11-01").copy(hourOfDay = HOUR_OF_DAY_UNKNOWN),
            record(TransactionType.REFUND, 400, "11:00", "2026-11-03"),
            record(TransactionType.INCOME, 200, "11:00", "2026-11-03"),
            record(TransactionType.TRANSFER, 900, "11:00", "2026-11-01"),
            record(TransactionType.EXPENSE, 600, "11:00", "2026-11-02").copy(excludedFromStats = true),
            record(TransactionType.EXPENSE, 900, "11:00", "2026-10-31")
        )
        val net = AnalyticsCalculator.computeDateBuckets(transactions, start, start.plusDays(2), StatsMetricMode.NET_EXPENSE)
        assertEquals(listOf(1200L, 0L, -400L), net.map { it.amountCents })
        assertEquals(listOf(1, 0, 1), net.map { it.transactionCount })
        val income = AnalyticsCalculator.computeDateBuckets(transactions, start, start.plusDays(2), StatsMetricMode.INCOME)
        assertEquals(listOf(0L, 0L, 200L), income.map { it.amountCents })
    }

    @Test
    fun dateBucketsClipMondayWeeksAtMonthEdges() {
        val start = LocalDate.of(2026, 11, 1)
        val end = LocalDate.of(2026, 11, 9)
        val transactions = listOf(
            record(TransactionType.EXPENSE, 100, "11:00", "2026-11-01"),
            record(TransactionType.EXPENSE, 200, "11:00", "2026-11-02"),
            record(TransactionType.REFUND, 50, "11:00", "2026-11-08"),
            record(TransactionType.EXPENSE, 400, "11:00", "2026-11-09"),
            record(TransactionType.EXPENSE, 900, "11:00", "2026-11-10")
        )
        val weeks = AnalyticsCalculator.computeDateBuckets(transactions, start, end, StatsMetricMode.NET_EXPENSE, byWeek = true)
        assertEquals(listOf("2026-11-01", "2026-11-02", "2026-11-09"), weeks.map { it.startDate.toString() })
        assertEquals(listOf("2026-11-01", "2026-11-08", "2026-11-09"), weeks.map { it.endDate.toString() })
        assertEquals(listOf(100L, 150L, 400L), weeks.map { it.amountCents })
        assertEquals(listOf(1, 2, 1), weeks.map { it.transactionCount })
    }

    /**
     * Regression for the planned record-level period filter:
     * start-inclusive / end-exclusive minutes, unknown time-of-day excluded, and the same mode
     * inclusion rules as the aggregate period buckets (expense mode keeps refunds, income mode
     * keeps only income). Both calendar days are returned because callers own date-range filtering.
     */
    @Test
    fun transactionsInTimePeriodUsesHalfOpenMinutesAndModeFilters() {
        val period = TimePeriod("午后片段", 11 * 60 + 15, 11 * 60 + 45)
        val transactions = listOf(
            record(TransactionType.EXPENSE, 1250, "11:14").copy(merchant = "临界前一分钟"),
            record(TransactionType.EXPENSE, 1300, "11:15").copy(merchant = "临界开始"),
            record(TransactionType.REFUND, 300, "11:30").copy(merchant = "退款计入"),
            record(TransactionType.EXPENSE, 1400, "11:44").copy(merchant = "临界结束前一分钟"),
            record(TransactionType.EXPENSE, 1500, "11:45").copy(merchant = "临界结束"),
            record(TransactionType.INCOME, 900, "11:20").copy(merchant = "收入记录"),
            record(TransactionType.EXPENSE, 999, "11:20")
                .copy(merchant = "未知时刻", hourOfDay = HOUR_OF_DAY_UNKNOWN),
            record(TransactionType.EXPENSE, 800, "11:20")
                .copy(merchant = "已排除", excludedFromStats = true),
            record(TransactionType.EXPENSE, 1100, "11:20", "2026-11-22").copy(merchant = "次日同时段")
        )

        val expenseRecords = AnalyticsCalculator.transactionsInTimePeriod(
            transactions,
            period,
            StatsMetricMode.NET_EXPENSE,
            ZoneOffset.UTC
        )

        assertEquals(
            listOf("临界开始", "退款计入", "临界结束前一分钟", "次日同时段").sorted(),
            expenseRecords.map { it.merchant }.sorted()
        )
        assertEquals(
            listOf(300L, 1100L, 1300L, 1400L),
            expenseRecords.map { it.amountCents }.sorted()
        )
        assertTrue(expenseRecords.any { it.type == TransactionType.REFUND })
        assertFalse(expenseRecords.any { it.merchant == "临界前一分钟" })
        assertFalse(expenseRecords.any { it.merchant == "临界结束" })
        assertFalse(expenseRecords.any { it.merchant == "未知时刻" })
        assertFalse(expenseRecords.any { it.merchant == "已排除" })

        val incomeRecords = AnalyticsCalculator.transactionsInTimePeriod(
            transactions,
            period,
            StatsMetricMode.INCOME,
            ZoneOffset.UTC
        )

        assertEquals(listOf("收入记录"), incomeRecords.map { it.merchant })
        assertEquals(listOf(900L), incomeRecords.map { it.amountCents })
    }

    private fun record(type: TransactionType, cents: Long, time: String, date: String = "2026-11-21") = TransactionRecord(
        id = 0, timestampMillis = Instant.parse("${date}T$time:00Z").toEpochMilli(),
        localDate = date, hourOfDay = time.take(2).toInt(), amountCents = cents,
        type = type, channel = PaymentChannel.MANUAL, merchant = "测试", fundingMethod = "",
        categoryId = DefaultCategories.EXPENSE_OTHER_ID, note = "", excludedFromStats = false,
        captureSource = CaptureSource.MANUAL, externalOrderId = null, createdAtMillis = 0,
        updatedAtMillis = 0
    )
}
