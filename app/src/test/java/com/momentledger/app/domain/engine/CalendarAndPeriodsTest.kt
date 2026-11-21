package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
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

    private fun record(type: TransactionType, cents: Long, time: String) = TransactionRecord(
        id = 0, timestampMillis = Instant.parse("2026-11-21T$time:00Z").toEpochMilli(),
        localDate = "2026-11-21", hourOfDay = time.take(2).toInt(), amountCents = cents,
        type = type, channel = PaymentChannel.MANUAL, merchant = "测试", fundingMethod = "",
        categoryId = DefaultCategories.EXPENSE_OTHER_ID, note = "", excludedFromStats = false,
        captureSource = CaptureSource.MANUAL, externalOrderId = null, createdAtMillis = 0,
        updatedAtMillis = 0
    )
}
