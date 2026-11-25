package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.CaptureSource
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryType
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.StatsMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime

class AnalyticsCalculatorTest {

    private val shanghaiZone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun record(
        id: Long,
        date: String,
        hour: Int,
        minute: Int = 0,
        amountCents: Long,
        type: TransactionType,
        channel: PaymentChannel = PaymentChannel.WECHAT,
        categoryId: String = "exp_food",
        excludedFromStats: Boolean = false
    ): TransactionRecord {
        val parts = date.split("-").map { it.toInt() }
        val millis = ZonedDateTime.of(
            parts[0], parts[1], parts[2],
            hour, minute, 0, 0,
            shanghaiZone
        ).toInstant().toEpochMilli()
        return TransactionRecord(
            id = id,
            timestampMillis = millis,
            localDate = date,
            hourOfDay = hour,
            amountCents = amountCents,
            type = type,
            channel = channel,
            fundingMethod = "零钱",
            merchant = "商户$id",
            categoryId = categoryId,
            note = "",
            excludedFromStats = excludedFromStats,
            captureSource = CaptureSource.NOTIFICATION,
            externalOrderId = null,
            createdAtMillis = millis,
            updatedAtMillis = millis
        )
    }

    @Test
    fun netExpenseMode_handlesRefundsTransfersAndExcludedTransactions() {
        val transactions = listOf(
            record(1L, "2026-11-19", 12, 15, 3000L, TransactionType.EXPENSE, categoryId = "exp_food"),
            record(2L, "2026-11-19", 12, 40, 10000L, TransactionType.REFUND, categoryId = "exp_food"),
            record(3L, "2026-11-19", 13, 0, 50000L, TransactionType.TRANSFER, channel = PaymentChannel.BANK),
            record(4L, "2026-11-19", 14, 0, 2000L, TransactionType.EXPENSE, channel = PaymentChannel.ALIPAY, excludedFromStats = true),
            record(5L, "2026-11-19", 15, 0, 80000L, TransactionType.INCOME, channel = PaymentChannel.BANK, categoryId = "inc_salary")
        )

        val calendar = AnalyticsCalculator.computeMonthlyCalendar(
            transactions = transactions,
            yearMonth = YearMonth.of(2026, 11),
            mode = StatsMetricMode.NET_EXPENSE
        )
        assertEquals("2026-11", calendar.yearMonth)
        assertEquals(-7000L, calendar.totalNetAmountCents)
        assertEquals(0L, calendar.dailyAverageCents)
        assertEquals(0L, calendar.highSpendThresholdCents)
        assertEquals(1, calendar.daysByDate.size)

        val day19 = calendar.daysByDate.getValue("2026-11-19")
        assertEquals(19, day19.dayOfMonth)
        assertEquals(-7000L, day19.netAmountCents)
        assertEquals(2, day19.transactionCount)
        assertTrue(day19.hasRefund)
        assertFalse(day19.isHighSpend)

        val hourly = AnalyticsCalculator.computeHourlyBuckets(
            transactions = transactions,
            mode = StatsMetricMode.NET_EXPENSE
        )
        assertEquals(24, hourly.size)
        assertEquals((0..23).toList(), hourly.map { it.hour })

        val hour12 = hourly[12]
        assertEquals(-7000L, hour12.netAmountCents)
        assertEquals(0L, hour12.displayAmountCents)
        assertEquals(2, hour12.transactionCount)
        assertTrue(hour12.hasRefund)
        assertFalse(hour12.isPeak)

        assertEquals(0, hourly[13].transactionCount)
        assertEquals(0, hourly[14].transactionCount)
        assertEquals(0, hourly[15].transactionCount)
        assertTrue(hourly.none { it.isPeak })

        val periods = AnalyticsCalculator.computeFourPeriods(hourly)
        assertEquals(4, periods.size)
        assertEquals(listOf("清晨", "午间", "下午/晚间", "深夜"), periods.map { it.label })
        assertEquals(hourly.sumOf { it.displayAmountCents }, periods.sumOf { it.amountCents })
        assertTrue(periods.all { it.amountCents == 0L && it.sharePercent == 0 })
    }

    @Test
    fun hourlyAndFourPeriods_coverAll24HoursWithZeroGapsAndDetectPeakHours() {
        val transactions = listOf(
            // 深夜: 22..23 + 0..4 (1000 + 2000 + 1500 + 500 = 5000)
            record(1L, "2026-11-10", 0, 10, 1000L, TransactionType.EXPENSE),
            record(2L, "2026-11-10", 4, 50, 2000L, TransactionType.EXPENSE),
            record(3L, "2026-11-10", 22, 0, 1500L, TransactionType.EXPENSE),
            record(4L, "2026-11-10", 23, 59, 500L, TransactionType.EXPENSE),
            // 清晨: 5..10 (3000 + 2000 = 5000)
            record(5L, "2026-11-10", 5, 0, 3000L, TransactionType.EXPENSE),
            record(6L, "2026-11-10", 10, 59, 2000L, TransactionType.EXPENSE),
            // 午间: 11..14 (6000 + 4000 = 10000), hour 11 is tied peak
            record(7L, "2026-11-10", 11, 0, 6000L, TransactionType.EXPENSE),
            record(8L, "2026-11-10", 14, 30, 4000L, TransactionType.EXPENSE),
            // 下午/晚间: 15..21 (6000 + 4000 = 10000), hour 19 is tied peak
            record(9L, "2026-11-10", 15, 0, 4000L, TransactionType.EXPENSE),
            record(10L, "2026-11-10", 19, 15, 6000L, TransactionType.EXPENSE)
        )

        val hourly = AnalyticsCalculator.computeHourlyBuckets(transactions, StatsMetricMode.NET_EXPENSE)
        assertEquals(24, hourly.size)
        assertTrue(hourly[11].isPeak)
        assertTrue(hourly[19].isPeak)
        assertEquals(2, hourly.count { it.isPeak })

        val periods = AnalyticsCalculator.computeFourPeriods(hourly)
        assertEquals(4, periods.size)

        val morning = periods[0]
        assertEquals("清晨", morning.label)
        assertEquals("05:00–10:59", morning.hourRangeLabel)
        assertEquals("#34C759", morning.colorHex)
        assertEquals(5000L, morning.amountCents)

        val noon = periods[1]
        assertEquals("午间", noon.label)
        assertEquals("11:00–14:59", noon.hourRangeLabel)
        assertEquals("#007AFF", noon.colorHex)
        assertEquals(10000L, noon.amountCents)

        val evening = periods[2]
        assertEquals("下午/晚间", evening.label)
        assertEquals("15:00–21:59", evening.hourRangeLabel)
        assertEquals("#5856D6", evening.colorHex)
        assertEquals(10000L, evening.amountCents)

        val lateNight = periods[3]
        assertEquals("深夜", lateNight.label)
        assertEquals("22:00–04:59", lateNight.hourRangeLabel)
        assertEquals("#FF9500", lateNight.colorHex)
        assertEquals(5000L, lateNight.amountCents)

        assertEquals(hourly.sumOf { it.displayAmountCents }, periods.sumOf { it.amountCents })
        assertEquals(100, periods.sumOf { it.sharePercent })
    }

    @Test
    fun incomeMode_aggregatesOnlyIncludedIncomeTransactions() {
        val transactions = listOf(
            record(1L, "2026-11-05", 9, 0, 1500000L, TransactionType.INCOME, PaymentChannel.BANK, "inc_salary"),
            record(2L, "2026-11-05", 9, 30, 50000L, TransactionType.INCOME, PaymentChannel.WECHAT, "inc_redpacket", excludedFromStats = true),
            record(3L, "2026-11-05", 12, 0, 4500L, TransactionType.EXPENSE, PaymentChannel.WECHAT, "exp_food"),
            record(4L, "2026-11-05", 12, 30, 2000L, TransactionType.REFUND, PaymentChannel.WECHAT, "exp_food"),
            record(5L, "2026-11-05", 18, 0, 10000L, TransactionType.TRANSFER, PaymentChannel.ALIPAY, "exp_other")
        )

        val calendar = AnalyticsCalculator.computeMonthlyCalendar(transactions, YearMonth.of(2026, 11), StatsMetricMode.INCOME)
        assertEquals(1500000L, calendar.totalNetAmountCents)
        assertEquals(1, calendar.daysByDate.getValue("2026-11-05").transactionCount)
        assertFalse(calendar.daysByDate.getValue("2026-11-05").hasRefund)

        val hourly = AnalyticsCalculator.computeHourlyBuckets(transactions, StatsMetricMode.INCOME)
        assertEquals(1500000L, hourly[9].netAmountCents)
        assertEquals(1500000L, hourly[9].displayAmountCents)
        assertEquals(1, hourly[9].transactionCount)
        assertTrue(hourly[9].isPeak)
        assertFalse(hourly[12].hasRefund)
        assertEquals(0L, hourly[12].netAmountCents)

        val categories = AnalyticsCalculator.computeCategoryBreakdown(transactions, DefaultCategories.all, StatsMetricMode.INCOME)
        assertEquals(1, categories.size)
        assertEquals("inc_salary", categories.single().categoryId)
        assertEquals("工资", categories.single().categoryName)
        assertEquals(100, categories.single().sharePercent)

        val channels = AnalyticsCalculator.computeChannelBreakdown(transactions, StatsMetricMode.INCOME)
        assertEquals(1, channels.size)
        assertEquals(PaymentChannel.BANK, channels.single().channel)
        assertEquals(100, channels.single().sharePercent)
    }

    @Test
    fun monthlyCalendar_filtersByYearMonthAndMarksHighSpendDays() {
        val transactions = listOf(
            record(1L, "2026-10-31", 20, 0, 99999L, TransactionType.EXPENSE),
            record(2L, "2026-11-01", 10, 0, 1000L, TransactionType.EXPENSE),
            record(3L, "2026-11-02", 10, 0, 1200L, TransactionType.EXPENSE),
            record(4L, "2026-11-03", 10, 0, 1400L, TransactionType.EXPENSE),
            record(5L, "2026-11-04", 10, 0, 1600L, TransactionType.EXPENSE),
            record(6L, "2026-11-05", 10, 0, 8000L, TransactionType.EXPENSE)
        )

        val calendar = AnalyticsCalculator.computeMonthlyCalendar(transactions, YearMonth.of(2026, 11))
        assertEquals(13200L, calendar.totalNetAmountCents)
        assertEquals(2640L, calendar.dailyAverageCents)
        assertTrue(calendar.highSpendThresholdCents >= 3960L)
        assertEquals(5, calendar.daysByDate.size)
        assertFalse(calendar.daysByDate.containsKey("2026-10-31"))
        assertFalse(calendar.daysByDate.getValue("2026-11-01").isHighSpend)
        assertFalse(calendar.daysByDate.getValue("2026-11-04").isHighSpend)
        assertTrue(calendar.daysByDate.getValue("2026-11-05").isHighSpend)

        val emptyCalendar = AnalyticsCalculator.computeMonthlyCalendar(transactions, YearMonth.of(2026, 12))
        assertEquals(0L, emptyCalendar.totalNetAmountCents)
        assertEquals(0L, emptyCalendar.dailyAverageCents)
        assertEquals(0L, emptyCalendar.highSpendThresholdCents)
        assertTrue(emptyCalendar.daysByDate.isEmpty())
    }

    @Test
    fun categoryAndChannelBreakdowns_excludeNonPositiveNetsAndSortDescending() {
        val customCategory = Category("custom_coffee", "精品咖啡", CategoryType.EXPENSE, "local_cafe", "#A2845E", 20, false)
        val categories = DefaultCategories.all + customCategory

        val transactions = listOf(
            record(1L, "2026-11-19", 9, 0, 5000L, TransactionType.EXPENSE, PaymentChannel.WECHAT, "custom_coffee"),
            record(2L, "2026-11-19", 10, 0, 1000L, TransactionType.REFUND, PaymentChannel.WECHAT, "custom_coffee"),
            record(3L, "2026-11-19", 12, 0, 2000L, TransactionType.EXPENSE, PaymentChannel.ALIPAY, "exp_transport"),
            record(4L, "2026-11-19", 14, 0, 1000L, TransactionType.EXPENSE, PaymentChannel.UNIONPAY, "exp_shopping"),
            record(5L, "2026-11-19", 15, 0, 3000L, TransactionType.REFUND, PaymentChannel.UNIONPAY, "exp_shopping")
        )

        val categoryStats = AnalyticsCalculator.computeCategoryBreakdown(transactions, categories, StatsMetricMode.NET_EXPENSE)
        assertEquals(2, categoryStats.size)
        assertEquals("custom_coffee", categoryStats[0].categoryId)
        assertEquals("精品咖啡", categoryStats[0].categoryName)
        assertEquals("#A2845E", categoryStats[0].colorHex)
        assertEquals(4000L, categoryStats[0].amountCents)
        assertEquals(2, categoryStats[0].transactionCount)
        assertEquals(67, categoryStats[0].sharePercent)

        assertEquals("exp_transport", categoryStats[1].categoryId)
        assertEquals("交通", categoryStats[1].categoryName)
        assertEquals(2000L, categoryStats[1].amountCents)
        assertEquals(33, categoryStats[1].sharePercent)

        val channelStats = AnalyticsCalculator.computeChannelBreakdown(transactions, StatsMetricMode.NET_EXPENSE)
        assertEquals(2, channelStats.size)
        assertEquals(PaymentChannel.WECHAT, channelStats[0].channel)
        assertEquals("微信支付", channelStats[0].label)
        assertEquals(4000L, channelStats[0].amountCents)
        assertEquals(67, channelStats[0].sharePercent)

        assertEquals(PaymentChannel.ALIPAY, channelStats[1].channel)
        assertEquals("支付宝", channelStats[1].label)
        assertEquals(2000L, channelStats[1].amountCents)
        assertEquals(33, channelStats[1].sharePercent)
    }
}
