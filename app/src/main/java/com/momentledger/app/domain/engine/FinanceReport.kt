package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.TransactionRecord
import com.momentledger.app.domain.model.TransactionType
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private val budgetStartDatePattern = Regex("""([0-9]{4})([-/])([0-9]{1,2})\2([0-9]{1,2})""")

fun parseBudgetStartDate(input: String): LocalDate? {
    val (year, _, month, day) = budgetStartDatePattern.matchEntire(input.trim())?.destructured ?: return null
    if (year == "0000") return null
    return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
}

@Serializable
data class BudgetSettings(
    val startDay: Int = 1,
    val totalCents: Long? = null,
    val dailyCents: Map<String, Long> = emptyMap(),
    val yearlyCents: Long? = null,
    val yearStartMonth: Int = 1,
    val yearStartDay: Int = 1
) {
    fun amountFor(period: BudgetPeriod, date: LocalDate): Long? = when (period) {
        BudgetPeriod.DAY -> dailyCents[date.toString()]
        BudgetPeriod.MONTH -> totalCents
        BudgetPeriod.YEAR -> yearlyCents
    }

    fun withDailyBudget(dates: Set<LocalDate>, amountCents: Long?): BudgetSettings {
        require(amountCents == null || amountCents > 0)
        require(dates.all { it.year in 1..9999 })
        val updated = dailyCents.toMutableMap()
        for (date in dates) {
            val key = date.toString()
            if (amountCents == null) updated.remove(key) else updated[key] = amountCents
        }
        return copy(dailyCents = updated)
    }

    fun billingStartDate(period: BudgetPeriod, reference: LocalDate): LocalDate = when (period) {
        BudgetPeriod.DAY -> error("日预算没有重复账期")
        BudgetPeriod.MONTH -> {
            val month = YearMonth.from(reference)
            val representative = if (startDay <= month.lengthOfMonth()) month else month.plusMonths(1)
            representative.atDay(startDay)
        }
        BudgetPeriod.YEAR -> {
            val start = MonthDay.of(yearStartMonth, yearStartDay)
            val year = (reference.year downTo (reference.year - 8).coerceAtLeast(1))
                .firstOrNull(start::isValidYear) ?: 4
            start.atYear(year)
        }
    }

    fun withRecurringBudget(period: BudgetPeriod, start: LocalDate, amountCents: Long?): BudgetSettings {
        require(amountCents == null || amountCents > 0)
        return when (period) {
            BudgetPeriod.DAY -> error("日预算没有重复账期")
            BudgetPeriod.MONTH -> copy(startDay = start.dayOfMonth, totalCents = amountCents)
            BudgetPeriod.YEAR -> copy(
                yearStartMonth = start.monthValue,
                yearStartDay = start.dayOfMonth,
                yearlyCents = amountCents
            )
        }
    }

    val isValid: Boolean get() = startDay in 1..31 && yearStartMonth in 1..12 &&
        yearStartDay in 1..Month.of(yearStartMonth).maxLength() &&
        (totalCents ?: 1) > 0 && (yearlyCents ?: 1) > 0 &&
        dailyCents.all { (key, amount) ->
            amount > 0 && runCatching {
                val date = LocalDate.parse(key)
                date.year in 1..9999 && date.toString() == key
            }.getOrDefault(false)
        }
}

enum class BudgetPeriod(val label: String, val budgetLabel: String) {
    DAY("日", "日预算"), MONTH("月", "月预算"), YEAR("年", "年预算")
}

data class BudgetCycle(val start: LocalDate, val endExclusive: LocalDate)
enum class BudgetAlert { NORMAL, NEAR, OVER }
data class BudgetProgress(val limitCents: Long, val spentCents: Long) {
    val fraction: Double get() = spentCents.toDouble() / limitCents
    val overCents: Long get() = if (spentCents > limitCents) spentCents - limitCents else 0L
    val remainingCents: Long get() = limitCents - spentCents.coerceIn(0L, limitCents)
    val spentWithinLimitCents: Long get() = spentCents.coerceIn(0L, limitCents)
    val alert: BudgetAlert get() = when {
        spentCents > limitCents -> BudgetAlert.OVER
        fraction >= 0.8 -> BudgetAlert.NEAR
        else -> BudgetAlert.NORMAL
    }
}

fun budgetCycle(date: LocalDate, period: BudgetPeriod, settings: BudgetSettings): BudgetCycle = when (period) {
    BudgetPeriod.DAY -> BudgetCycle(date, date.plusDays(1))
    BudgetPeriod.MONTH -> budgetCycle(date, settings.startDay)
    BudgetPeriod.YEAR -> {
        fun annualStart(year: Int): LocalDate {
            val month = YearMonth.of(year, settings.yearStartMonth)
            return month.atDay(settings.yearStartDay.coerceAtMost(month.lengthOfMonth()))
        }
        val year = if (date < annualStart(date.year)) date.year - 1 else date.year
        BudgetCycle(annualStart(year), annualStart(year + 1))
    }
}

fun calendarPeriod(date: LocalDate, period: BudgetPeriod): BudgetCycle = when (period) {
    BudgetPeriod.DAY -> BudgetCycle(date, date.plusDays(1))
    BudgetPeriod.MONTH -> date.withDayOfMonth(1).let { BudgetCycle(it, it.plusMonths(1)) }
    BudgetPeriod.YEAR -> date.withDayOfYear(1).let { BudgetCycle(it, it.plusYears(1)) }
}

fun budgetCycleForReport(
    anchor: LocalDate, today: LocalDate, period: BudgetPeriod, settings: BudgetSettings
): BudgetCycle {
    if (period == BudgetPeriod.DAY) return budgetCycle(anchor, period, settings)
    val current = budgetCycle(today, period, settings)
    // Move by complete cycles relative to the current one, even across clamped month ends.
    val target = when (period) {
        BudgetPeriod.DAY -> anchor
        BudgetPeriod.MONTH -> {
            val offset = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(anchor))
            YearMonth.from(current.start).plusMonths(offset).atEndOfMonth()
        }
        BudgetPeriod.YEAR -> LocalDate.of(current.start.year + anchor.year - today.year, 12, 31)
    }
    return budgetCycle(target, period, settings)
}

fun budgetCycle(today: LocalDate, startDay: Int): BudgetCycle {
    require(startDay in 1..31)
    fun start(month: YearMonth) = month.atDay(startDay.coerceAtMost(month.lengthOfMonth()))
    val current = YearMonth.from(today)
    val startMonth = if (today < start(current)) current.minusMonths(1) else current
    return BudgetCycle(start(startMonth), start(startMonth.plusMonths(1)))
}

fun calculateBudget(
    transactions: List<TransactionRecord>, cycle: BudgetCycle, limitCents: Long?, today: LocalDate
): BudgetProgress? {
    if (limitCents == null || limitCents <= 0) return null
    var total = 0L
    for (record in transactions) {
        if (record.excludedFromStats || !record.isWithinBudgetCycle(cycle, today)) continue
        total += when (record.type) {
            TransactionType.EXPENSE -> record.amountCents
            TransactionType.REFUND -> -record.amountCents
            else -> continue
        }
    }
    return BudgetProgress(limitCents, total)
}

fun budgetExpenseRecords(
    transactions: List<TransactionRecord>, cycle: BudgetCycle, today: LocalDate
): List<TransactionRecord> = transactions.asSequence()
    .filter { it.type == TransactionType.EXPENSE && it.isWithinBudgetCycle(cycle, today) }
    .sortedWith(compareByDescending<TransactionRecord> { it.localDate }
        .thenByDescending { it.timestampMillis }
        .thenByDescending { it.id })
    .toList()

private fun TransactionRecord.isWithinBudgetCycle(cycle: BudgetCycle, today: LocalDate): Boolean {
    val date = parseLedgerDate(this) ?: return false
    return date >= cycle.start && date < cycle.endExclusive && date <= today
}

data class CashFlow(val expenseCents: Long, val incomeCents: Long)

fun calculateCashFlow(transactions: List<TransactionRecord>, period: BudgetCycle): CashFlow {
    var expenses = 0L
    var income = 0L
    for (record in transactions) {
        if (record.excludedFromStats) continue
        val date = parseLedgerDate(record) ?: continue
        if (date < period.start || date >= period.endExclusive) continue
        when (record.type) {
            TransactionType.EXPENSE -> expenses += record.amountCents
            TransactionType.INCOME, TransactionType.REFUND -> income += record.amountCents
            TransactionType.TRANSFER -> Unit
        }
    }
    return CashFlow(expenses, income)
}

private fun parseLedgerDate(record: TransactionRecord): LocalDate? =
    runCatching { LocalDate.parse(record.localDate) }.getOrNull()
