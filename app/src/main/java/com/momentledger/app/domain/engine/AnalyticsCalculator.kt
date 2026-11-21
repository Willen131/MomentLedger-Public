package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.CalendarDaySummary
import com.momentledger.app.domain.model.CalendarMetricMode
import com.momentledger.app.domain.model.TimePeriod
import com.momentledger.app.domain.model.formatTimePeriodMinute
import com.momentledger.app.domain.model.Category
import com.momentledger.app.domain.model.CategoryStat
import com.momentledger.app.domain.model.ChannelStat
import com.momentledger.app.domain.model.DefaultCategories
import com.momentledger.app.domain.model.HourlyBucket
import com.momentledger.app.domain.model.MonthlyCalendarStats
import com.momentledger.app.domain.model.PaymentChannel
import com.momentledger.app.domain.model.PeriodBucket
import com.momentledger.app.domain.model.StatsMetricMode
import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import kotlin.math.roundToLong

object AnalyticsCalculator {

    private data class PeriodSpec(
        val id: String,
        val label: String,
        val hourRangeLabel: String,
        val hours: Set<Int>,
        val colorHex: String
    )

    private val fourPeriodSpecs = listOf(
        PeriodSpec(
            id = "morning",
            label = "清晨",
            hourRangeLabel = "05:00–10:59",
            hours = setOf(5, 6, 7, 8, 9, 10),
            colorHex = "#34C759"
        ),
        PeriodSpec(
            id = "noon",
            label = "午间",
            hourRangeLabel = "11:00–14:59",
            hours = setOf(11, 12, 13, 14),
            colorHex = "#007AFF"
        ),
        PeriodSpec(
            id = "evening",
            label = "下午/晚间",
            hourRangeLabel = "15:00–21:59",
            hours = setOf(15, 16, 17, 18, 19, 20, 21),
            colorHex = "#5856D6"
        ),
        PeriodSpec(
            id = "late_night",
            label = "深夜",
            hourRangeLabel = "22:00–04:59",
            hours = setOf(22, 23, 0, 1, 2, 3, 4),
            colorHex = "#FF9500"
        )
    )

    fun computeMonthlyCalendar(
        transactions: List<TransactionRecord>,
        yearMonth: YearMonth,
        mode: StatsMetricMode = StatsMetricMode.NET_EXPENSE
    ): MonthlyCalendarStats = monthlyCalendar(transactions, yearMonth) { includedSignedAmount(it, mode) }

    fun computeMonthlyCalendar(
        transactions: List<TransactionRecord>,
        yearMonth: YearMonth,
        mode: CalendarMetricMode
    ): MonthlyCalendarStats = monthlyCalendar(transactions, yearMonth) {
        if (it.excludedFromStats) null else mode.amountFor(it)
    }

    private fun monthlyCalendar(
        transactions: List<TransactionRecord>,
        yearMonth: YearMonth,
        amountFor: (TransactionRecord) -> Long?
    ): MonthlyCalendarStats {
        val monthRecordsByDate = linkedMapOf<String, MutableList<Pair<TransactionRecord, Long>>>()

        for (record in transactions) {
            val signed = amountFor(record) ?: continue
            val parsedDate = runCatching { LocalDate.parse(record.localDate) }.getOrNull() ?: continue
            if (YearMonth.from(parsedDate) != yearMonth) continue
            monthRecordsByDate.getOrPut(record.localDate) { mutableListOf() }.add(record to signed)
        }

        if (monthRecordsByDate.isEmpty()) {
            return MonthlyCalendarStats(
                yearMonth = yearMonth.toString(),
                totalNetAmountCents = 0L,
                dailyAverageCents = 0L,
                highSpendThresholdCents = 0L,
                daysByDate = emptyMap()
            )
        }

        val rawDayEntries = monthRecordsByDate.entries
            .sortedBy { it.key }
            .map { (dateStr, entries) ->
                val dayOfMonth = LocalDate.parse(dateStr).dayOfMonth
                val netAmountCents = entries.sumOf { it.second }
                val hasRefund = entries.any { it.first.type == TransactionType.REFUND }
                CalendarDaySummary(
                    localDate = dateStr,
                    dayOfMonth = dayOfMonth,
                    netAmountCents = netAmountCents,
                    transactionCount = entries.size,
                    isHighSpend = false,
                    hasRefund = hasRefund
                )
            }

        val totalNetAmountCents = rawDayEntries.sumOf { it.netAmountCents }
        val positiveDayTotals = rawDayEntries
            .map { it.netAmountCents }
            .filter { it > 0L }
            .sorted()

        // Average across active positive days keeps threshold/average meaningful when refund-heavy days exist.
        val dailyAverageCents = if (positiveDayTotals.isEmpty()) {
            0L
        } else {
            positiveDayTotals.sum() / positiveDayTotals.size
        }

        val highSpendThresholdCents = if (positiveDayTotals.isEmpty()) {
            0L
        } else {
            val oneAndHalfAvg = (dailyAverageCents * 1.5).roundToLong()
            val q3Index = ((positiveDayTotals.size - 1) * 3) / 4
            val topQuartile = positiveDayTotals[q3Index]
            maxOf(oneAndHalfAvg, topQuartile)
        }

        val daysByDate = rawDayEntries.associate { day ->
            val isHighSpend = highSpendThresholdCents > 0L &&
                day.netAmountCents > 0L &&
                day.netAmountCents >= highSpendThresholdCents
            day.localDate to day.copy(isHighSpend = isHighSpend)
        }

        return MonthlyCalendarStats(
            yearMonth = yearMonth.toString(),
            totalNetAmountCents = totalNetAmountCents,
            dailyAverageCents = dailyAverageCents,
            highSpendThresholdCents = highSpendThresholdCents,
            daysByDate = daysByDate
        )
    }

    fun computeHourlyBuckets(
        transactions: List<TransactionRecord>,
        mode: StatsMetricMode = StatsMetricMode.NET_EXPENSE
    ): List<HourlyBucket> = hourlyBuckets(transactions, showNegativeMagnitude = false) { includedSignedAmount(it, mode) }

    fun computeHourlyBuckets(
        transactions: List<TransactionRecord>,
        mode: CalendarMetricMode
    ): List<HourlyBucket> = hourlyBuckets(transactions, showNegativeMagnitude = true) {
        if (it.excludedFromStats) null else mode.amountFor(it)
    }

    private fun hourlyBuckets(
        transactions: List<TransactionRecord>,
        showNegativeMagnitude: Boolean,
        amountFor: (TransactionRecord) -> Long?
    ): List<HourlyBucket> {
        val grouped = Array(24) { mutableListOf<Pair<TransactionRecord, Long>>() }

        for (record in transactions) {
            if (record.hourOfDay !in 0..23) continue
            val signed = amountFor(record) ?: continue
            grouped[record.hourOfDay].add(record to signed)
        }

        val rawBuckets = (0..23).map { hour ->
            val entries = grouped[hour]
            val netAmountCents = entries.sumOf { it.second }
            val displayAmountCents = if (showNegativeMagnitude) kotlin.math.abs(netAmountCents) else maxOf(0L, netAmountCents)
            val hasRefund = entries.any { it.first.type == TransactionType.REFUND }
            HourlyBucket(
                hour = hour,
                netAmountCents = netAmountCents,
                displayAmountCents = displayAmountCents,
                transactionCount = entries.size,
                hasRefund = hasRefund,
                isPeak = false
            )
        }

        val maxDisplay = rawBuckets.maxOfOrNull { it.displayAmountCents } ?: 0L
        if (maxDisplay <= 0L) {
            return rawBuckets
        }
        return rawBuckets.map { bucket ->
            bucket.copy(isPeak = bucket.displayAmountCents == maxDisplay)
        }
    }

    fun computeFourPeriods(hourlyBuckets: List<HourlyBucket>): List<PeriodBucket> {
        val amounts = fourPeriodSpecs.map { spec ->
            hourlyBuckets
                .filter { it.hour in spec.hours }
                .sumOf { it.displayAmountCents }
        }
        val shares = computeSharePercents(amounts)

        return fourPeriodSpecs.mapIndexed { index, spec ->
            PeriodBucket(
                id = spec.id,
                label = spec.label,
                hourRangeLabel = spec.hourRangeLabel,
                amountCents = amounts[index],
                sharePercent = shares[index],
                colorHex = spec.colorHex
            )
        }
    }

    fun computeTimePeriods(
        transactions: List<TransactionRecord>,
        periods: List<TimePeriod>,
        mode: StatsMetricMode,
        zoneId: ZoneId
    ): List<PeriodBucket> {
        val totals = LongArray(periods.size)
        for (record in transactions) {
            if (record.hourOfDay !in 0..23) continue
            val amount = includedSignedAmount(record, mode) ?: continue
            val time = Instant.ofEpochMilli(record.timestampMillis).atZone(zoneId)
            val minute = record.hourOfDay * 60 + time.minute
            val index = periods.indexOfFirst { minute >= it.startMinute && minute < it.endMinute }
            if (index >= 0) totals[index] += amount
        }
        val shares = computeSharePercents(totals.map { it.coerceAtLeast(0L) })
        return periods.mapIndexed { index, period ->
            PeriodBucket(
                id = index.toString(),
                label = period.label,
                hourRangeLabel = "${formatTimePeriodMinute(period.startMinute)}–${formatTimePeriodMinute(period.endMinute)}",
                amountCents = totals[index],
                sharePercent = shares[index],
                colorHex = "#007AFF"
            )
        }
    }

    fun computeCategoryBreakdown(
        transactions: List<TransactionRecord>,
        categories: List<Category>,
        mode: StatsMetricMode = StatsMetricMode.NET_EXPENSE
    ): List<CategoryStat> {
        val grouped = linkedMapOf<String, MutableList<Long>>()
        for (record in transactions) {
            val signed = includedSignedAmount(record, mode) ?: continue
            grouped.getOrPut(record.categoryId) { mutableListOf() }.add(signed)
        }

        val positiveGroups = grouped.entries
            .map { (categoryId, amounts) -> Triple(categoryId, amounts.sum(), amounts.size) }
            .filter { it.second > 0L }
            .sortedWith(compareByDescending<Triple<String, Long, Int>> { it.second }.thenBy { it.first })

        if (positiveGroups.isEmpty()) {
            return emptyList()
        }

        val categoryById = categories.associateBy { it.id }
        val defaultCategoryById = DefaultCategories.all.associateBy { it.id }
        val shares = computeSharePercents(positiveGroups.map { it.second })

        return positiveGroups.mapIndexed { index, (categoryId, amountCents, count) ->
            val category = categoryById[categoryId] ?: defaultCategoryById[categoryId]
            CategoryStat(
                categoryId = categoryId,
                categoryName = category?.name ?: categoryId,
                iconKey = category?.iconKey ?: "more_horiz",
                colorHex = category?.colorHex ?: "#8E8E93",
                amountCents = amountCents,
                transactionCount = count,
                sharePercent = shares[index]
            )
        }
    }

    fun computeChannelBreakdown(
        transactions: List<TransactionRecord>,
        mode: StatsMetricMode = StatsMetricMode.NET_EXPENSE
    ): List<ChannelStat> {
        val grouped = linkedMapOf<Pair<PaymentChannel, String>, MutableList<Long>>()
        for (record in transactions) {
            val signed = includedSignedAmount(record, mode) ?: continue
            grouped.getOrPut(record.channel to channelLabel(record)) { mutableListOf() }.add(signed)
        }

        val positiveGroups = grouped.entries
            .map { (channel, amounts) -> Triple(channel, amounts.sum(), amounts.size) }
            .filter { it.second > 0L }
            .sortedWith(compareByDescending<Triple<Pair<PaymentChannel, String>, Long, Int>> { it.second }
                .thenBy { it.first.first.ordinal }.thenBy { it.first.second })

        if (positiveGroups.isEmpty()) {
            return emptyList()
        }

        val shares = computeSharePercents(positiveGroups.map { it.second })
        return positiveGroups.mapIndexed { index, (channel, amountCents, count) ->
            ChannelStat(
                channel = channel.first,
                label = channel.second,
                amountCents = amountCents,
                transactionCount = count,
                sharePercent = shares[index]
            )
        }
    }

    fun channelLabel(record: TransactionRecord): String =
        record.customChannel?.takeIf { it.isNotBlank() } ?: channelLabel(record.channel)

    fun channelLabel(channel: PaymentChannel): String = when (channel) {
        PaymentChannel.WECHAT -> "微信支付"
        PaymentChannel.ALIPAY -> "支付宝"
        PaymentChannel.UNIONPAY -> "云闪付"
        PaymentChannel.BANK -> "银行卡"
        PaymentChannel.MANUAL -> "手动记账"
        PaymentChannel.OTHER -> "其他"
    }

    private fun includedSignedAmount(record: TransactionRecord, mode: StatsMetricMode): Long? {
        if (record.excludedFromStats) return null
        return when (mode) {
            StatsMetricMode.NET_EXPENSE -> when (record.type) {
                TransactionType.EXPENSE -> record.amountCents
                TransactionType.REFUND -> -record.amountCents
                TransactionType.INCOME, TransactionType.TRANSFER -> null
            }
            StatsMetricMode.INCOME -> when (record.type) {
                TransactionType.INCOME -> record.amountCents
                TransactionType.EXPENSE, TransactionType.REFUND, TransactionType.TRANSFER -> null
            }
        }
    }

    private fun computeSharePercents(amounts: List<Long>): List<Int> {
        val total = amounts.sum()
        if (amounts.isEmpty() || total <= 0L) {
            return List(amounts.size) { 0 }
        }

        val bases = IntArray(amounts.size)
        val remainders = LongArray(amounts.size)
        for (i in amounts.indices) {
            val scaled = amounts[i] * 100L
            bases[i] = (scaled / total).toInt()
            remainders[i] = scaled % total
        }

        var leftover = 100 - bases.sum()
        if (leftover > 0) {
            val rankedIndices = amounts.indices
                .filter { amounts[it] > 0L }
                .sortedWith(
                    compareByDescending<Int> { remainders[it] }
                        .thenByDescending { amounts[it] }
                        .thenBy { it }
                )
            for (index in rankedIndices) {
                if (leftover == 0) break
                bases[index] += 1
                leftover -= 1
            }
        }
        return bases.toList()
    }
}
