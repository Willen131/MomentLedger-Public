package com.momentledger.app.domain.model

import java.time.LocalDate

enum class StatsMetricMode {
    NET_EXPENSE,
    INCOME
}

enum class CalendarMetricMode(val label: String) {
    EXPENSE("支出"),
    INCOME("收入"),
    NET_EXPENSE("净支出"),
    NET_INCOME("净收入");

    fun amountFor(record: TransactionRecord): Long? = when (record.type) {
        TransactionType.EXPENSE -> when (this) {
            EXPENSE, NET_EXPENSE -> record.amountCents
            NET_INCOME -> -record.amountCents
            INCOME -> null
        }
        TransactionType.INCOME, TransactionType.REFUND -> when (this) {
            INCOME, NET_INCOME -> record.amountCents
            NET_EXPENSE -> -record.amountCents
            EXPENSE -> null
        }
        TransactionType.TRANSFER -> null
    }
}

@kotlinx.serialization.Serializable
data class TimePeriod(val label: String, val startMinute: Int, val endMinute: Int)

val defaultTimePeriods = listOf(
    TimePeriod("早晨 / 上午", 360, 660),
    TimePeriod("午间", 660, 840),
    TimePeriod("下午", 840, 1020),
    TimePeriod("傍晚 / 晚间", 1020, 1260),
    TimePeriod("夜间 / 睡前", 1260, 1440),
    TimePeriod("凌晨 / 深夜", 0, 360)
)

fun areValidTimePeriods(periods: List<TimePeriod>): Boolean {
    if (periods.isEmpty() || periods.any {
            it.label.isBlank() || it.startMinute !in 0..1439 || it.endMinute !in 1..1440 ||
                it.startMinute >= it.endMinute
        }) return false
    val ordered = periods.sortedBy { it.startMinute }
    return ordered.first().startMinute == 0 && ordered.last().endMinute == 1440 &&
        ordered.zipWithNext().all { (before, after) -> before.endMinute == after.startMinute }
}

fun formatTimePeriodMinute(minute: Int): String =
    String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)

data class CalendarDaySummary(
    val localDate: String,
    val dayOfMonth: Int,
    val netAmountCents: Long,
    val transactionCount: Int,
    val isHighSpend: Boolean,
    val hasRefund: Boolean
)

data class MonthlyCalendarStats(
    val yearMonth: String,
    val totalNetAmountCents: Long,
    val dailyAverageCents: Long,
    val highSpendThresholdCents: Long,
    val daysByDate: Map<String, CalendarDaySummary>
)

data class HourlyBucket(
    val hour: Int,
    val netAmountCents: Long,
    val displayAmountCents: Long,
    val transactionCount: Int,
    val hasRefund: Boolean,
    val isPeak: Boolean
)

data class DateBucket(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val amountCents: Long,
    val transactionCount: Int
)

data class PeriodBucket(
    val id: String,
    val label: String,
    val hourRangeLabel: String,
    val amountCents: Long,
    val sharePercent: Int,
    val colorHex: String
)

data class CategoryStat(
    val categoryId: String,
    val categoryName: String,
    val iconKey: String,
    val colorHex: String,
    val amountCents: Long,
    val transactionCount: Int,
    val sharePercent: Int
)

data class ChannelStat(
    val channel: PaymentChannel,
    val label: String,
    val amountCents: Long,
    val transactionCount: Int,
    val sharePercent: Int
)
