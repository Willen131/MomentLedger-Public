package com.momentledger.app.domain.engine

import com.momentledger.app.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class FinanceReportTest {
    @Test
    fun payrollCycleClampsToShortMonthThenReturnsToOriginalStartDay() {
        val cycle = budgetCycle(LocalDate.parse("2026-03-01"), 31)
        assertEquals(LocalDate.parse("2026-02-28"), cycle.start)
        assertEquals(LocalDate.parse("2026-03-31"), cycle.endExclusive)
    }

    @Test
    fun reportNavigationMovesOneBudgetCycleAcrossShortMonthsAndLeapYears() {
        val settings = BudgetSettings(startDay = 31, yearStartMonth = 2, yearStartDay = 29)
        val march = LocalDate.parse("2026-03-30")
        assertEquals(budgetCycle(march, BudgetPeriod.MONTH, settings),
            budgetCycleForReport(march, march, BudgetPeriod.MONTH, settings))
        assertEquals(BudgetCycle(LocalDate.parse("2026-03-31"), LocalDate.parse("2026-04-30")),
            budgetCycleForReport(march.plusMonths(1), march, BudgetPeriod.MONTH, settings))

        val leapDayEve = LocalDate.parse("2024-02-28")
        assertEquals(BudgetCycle(LocalDate.parse("2024-02-29"), LocalDate.parse("2025-02-28")),
            budgetCycleForReport(leapDayEve.plusYears(1), leapDayEve, BudgetPeriod.YEAR, settings))
    }

    @Test
    fun cashFlowSelectsNaturalDayMonthAndYearAndTreatsRefundsAsIncome() {
        val rows = listOf(
            row("2026-10-31", TransactionType.INCOME, 3000),
            row("2026-11-01", TransactionType.EXPENSE, 1200),
            row("2026-11-01", TransactionType.REFUND, 200),
            row("2026-11-02", TransactionType.EXPENSE, 800),
            row("2026-11-02", TransactionType.TRANSFER, 10000)
        )
        val selected = LocalDate.parse("2026-11-01")
        assertEquals(CashFlow(1200, 200), calculateCashFlow(rows, calendarPeriod(selected, BudgetPeriod.DAY)))
        assertEquals(CashFlow(2000, 200), calculateCashFlow(rows, calendarPeriod(selected, BudgetPeriod.MONTH)))
        assertEquals(CashFlow(2000, 3200), calculateCashFlow(rows, calendarPeriod(selected, BudgetPeriod.YEAR)))
    }

    @Test
    fun calculateBudgetSubtractsRefundsAndExcludesTransfersAndExcludedRows() {
        val today = LocalDate.parse("2026-11-21")
        val cycle = budgetCycle(today, 20)
        val rows = listOf(
            row("2026-11-19", TransactionType.EXPENSE, 4000),
            row("2026-11-20", TransactionType.EXPENSE, 9000),
            row("2026-11-21", TransactionType.REFUND, 500),
            row("2026-11-21", TransactionType.EXPENSE, 1000, "travel"),
            row("2026-11-21", TransactionType.INCOME, 7000),
            row("2026-11-21", TransactionType.TRANSFER, 4000),
            row("2026-11-21", TransactionType.EXPENSE, 4000).copy(excludedFromStats = true),
            row("2026-11-22", TransactionType.EXPENSE, 1000)
        )

        val near = calculateBudget(rows, cycle, 10000, today)!!
        assertEquals(9500L, near.spentCents)
        assertEquals(9500L, near.spentWithinLimitCents)
        assertEquals(500L, near.remainingCents)
        assertEquals(0L, near.overCents)
        assertEquals(BudgetAlert.NEAR, near.alert)

        val over = calculateBudget(rows, cycle, 9000, today)!!
        assertEquals(9500L, over.spentCents)
        assertEquals(9000L, over.spentWithinLimitCents)
        assertEquals(0L, over.remainingCents)
        assertEquals(500L, over.overCents)
        assertEquals(BudgetAlert.OVER, over.alert)

        val normal = calculateBudget(rows, cycle, 20000, today)!!
        assertEquals(9500L, normal.spentCents)
        assertEquals(10500L, normal.remainingCents)
        assertEquals(0L, normal.overCents)
        assertEquals(BudgetAlert.NORMAL, normal.alert)

        assertNull(calculateBudget(rows, cycle, null, today))
    }

    @Test
    fun budgetExpenseRecordsFiltersToTheReportCycleAndKeepsFlaggedExpenses() {
        val today = LocalDate.parse("2026-12-20")
        val cycle = budgetCycle(LocalDate.parse("2026-11-10"), 10)
        val rows = listOf(
            row("2026-11-09", TransactionType.EXPENSE, 100),
            row("2026-11-10", TransactionType.EXPENSE, 200),
            row("2026-11-15", TransactionType.INCOME, 300),
            row("2026-11-16", TransactionType.REFUND, 400),
            row("2026-11-17", TransactionType.TRANSFER, 500),
            row("2026-11-18", TransactionType.EXPENSE, 600).copy(excludedFromStats = true),
            row("2026-11-30", TransactionType.EXPENSE, 700),
            row("2026-12-09", TransactionType.EXPENSE, 900),
            row("2026-12-10", TransactionType.EXPENSE, 1000),
            row("2026-12-11", TransactionType.EXPENSE, 1100)
        )

        assertEquals(
            listOf(900L, 700L, 600L, 200L),
            budgetExpenseRecords(rows, cycle, today).map { it.amountCents }
        )
    }

    @Test
    fun budgetExpenseRecordsIgnoresFutureBillsAndOrdersNewestDateAndTimeFirst() {
        val today = LocalDate.parse("2026-11-21")
        val cycle = budgetCycle(today, 10)
        val rows = listOf(
            row("2026-11-20", TransactionType.EXPENSE, 100, hour = 9),
            row("2026-11-20", TransactionType.EXPENSE, 200, hour = 18),
            row("2026-11-21", TransactionType.EXPENSE, 300, hour = 8),
            row("2026-11-22", TransactionType.EXPENSE, 400, hour = 12),
            row("2026-11-10", TransactionType.EXPENSE, 500, hour = 7)
        )

        assertEquals(
            listOf(300L, 200L, 100L, 500L),
            budgetExpenseRecords(rows, cycle, today).map { it.amountCents }
        )
    }

    @Test
    fun dailyBudgetsOnlyAffectExplicitlySelectedDates() {
        val jan31 = LocalDate.parse("2026-01-31")
        val feb01 = LocalDate.parse("2026-02-01")
        val mar01 = LocalDate.parse("2026-03-01")
        val mar02 = LocalDate.parse("2026-03-02")

        assertNull(BudgetSettings().amountFor(BudgetPeriod.DAY, jan31))

        val settings = BudgetSettings().withDailyBudget(setOf(jan31, mar01), 5000)
        assertEquals(5000L, settings.amountFor(BudgetPeriod.DAY, jan31))
        assertEquals(5000L, settings.amountFor(BudgetPeriod.DAY, mar01))
        assertNull(settings.amountFor(BudgetPeriod.DAY, feb01))
        assertNull(settings.amountFor(BudgetPeriod.DAY, mar02))
        assertNull(settings.amountFor(BudgetPeriod.MONTH, jan31))
        assertNull(settings.amountFor(BudgetPeriod.YEAR, jan31))

        val changed = settings.withDailyBudget(setOf(jan31), 7000)
        assertEquals(7000L, changed.amountFor(BudgetPeriod.DAY, jan31))
        assertEquals(5000L, changed.amountFor(BudgetPeriod.DAY, mar01))

        val cleared = changed.withDailyBudget(setOf(mar01), null)
        assertEquals(7000L, cleared.amountFor(BudgetPeriod.DAY, jan31))
        assertNull(cleared.amountFor(BudgetPeriod.DAY, mar01))
        assertNull(cleared.amountFor(BudgetPeriod.DAY, feb01))
        assertNull(cleared.amountFor(BudgetPeriod.DAY, mar02))
    }

    @Test
    fun futureDailyBudgetDoesNotCountFutureTransactionsBeforeTheirDate() {
        val today = LocalDate.parse("2026-11-21")
        val tomorrow = LocalDate.parse("2026-11-22")
        val settings = BudgetSettings().withDailyBudget(setOf(today, tomorrow), 3000)
        val rows = listOf(
            row("2026-11-21", TransactionType.EXPENSE, 1200),
            row("2026-11-22", TransactionType.EXPENSE, 2500)
        )

        val todayProgress = calculateBudget(
            rows,
            budgetCycle(today, BudgetPeriod.DAY, settings),
            settings.amountFor(BudgetPeriod.DAY, today),
            today
        )!!
        assertEquals(1200L, todayProgress.spentCents)
        assertEquals(1800L, todayProgress.remainingCents)

        assertEquals(3000L, settings.amountFor(BudgetPeriod.DAY, tomorrow))
        val tomorrowProgress = calculateBudget(
            rows,
            budgetCycle(tomorrow, BudgetPeriod.DAY, settings),
            settings.amountFor(BudgetPeriod.DAY, tomorrow),
            today
        )!!
        assertEquals(0L, tomorrowProgress.spentCents)
    }

    @Test
    fun billingStartDateKeepsOriginalRecurrenceWhenCalendarClamps() {
        val monthly = BudgetSettings(startDay = 31)
        val februaryReference = LocalDate.parse("2026-02-10")
        val monthlyStart = monthly.billingStartDate(BudgetPeriod.MONTH, februaryReference)
        assertEquals(31, monthlyStart.dayOfMonth)

        val yearly = BudgetSettings(yearStartMonth = 2, yearStartDay = 29)
        val nonLeapReference = LocalDate.parse("2026-06-01")
        val yearlyStart = yearly.billingStartDate(BudgetPeriod.YEAR, nonLeapReference)
        assertEquals(2, yearlyStart.monthValue)
        assertEquals(29, yearlyStart.dayOfMonth)
        assertTrue(YearMonth.from(yearlyStart).isLeapYear)
    }

    @Test
    fun typedBillingStartDateAcceptsCalendarFormatsAndRejectsImpossibleDates() {
        assertEquals(LocalDate.of(2028, 2, 29), parseBudgetStartDate("2028-02-29"))
        assertEquals(LocalDate.of(2027, 4, 5), parseBudgetStartDate("2027/04/05"))
        assertNull(parseBudgetStartDate("2027-02-29"))
        assertNull(parseBudgetStartDate("2027-04-31"))
        assertNull(parseBudgetStartDate("0000-01-01"))
        assertNull(parseBudgetStartDate("2027-04/05"))
    }

    @Test
    fun recurringMonthUsesOnlyDayAndRecurringYearUsesOnlyMonthAndDay() {
        val leapDay = LocalDate.parse("2024-02-29")

        val monthly = BudgetSettings().withRecurringBudget(BudgetPeriod.MONTH, leapDay, 100000)
        assertEquals(29, monthly.startDay)
        assertEquals(100000L, monthly.totalCents)
        assertEquals(100000L, monthly.amountFor(BudgetPeriod.MONTH, LocalDate.parse("2026-02-15")))
        assertEquals(100000L, monthly.amountFor(BudgetPeriod.MONTH, LocalDate.parse("2027-02-28")))
        assertNull(monthly.amountFor(BudgetPeriod.DAY, leapDay))
        assertNull(monthly.amountFor(BudgetPeriod.YEAR, leapDay))

        val yearly = BudgetSettings().withRecurringBudget(BudgetPeriod.YEAR, leapDay, 1200000)
        assertEquals(2, yearly.yearStartMonth)
        assertEquals(29, yearly.yearStartDay)
        assertEquals(1200000L, yearly.yearlyCents)
        assertEquals(1200000L, yearly.amountFor(BudgetPeriod.YEAR, LocalDate.parse("2026-06-01")))
        assertEquals(1200000L, yearly.amountFor(BudgetPeriod.YEAR, LocalDate.parse("2027-01-01")))

        val clearedMonthly = monthly.withRecurringBudget(BudgetPeriod.MONTH, leapDay, null)
        assertNull(clearedMonthly.amountFor(BudgetPeriod.MONTH, leapDay))
        val clearedYearly = yearly.withRecurringBudget(BudgetPeriod.YEAR, leapDay, null)
        assertNull(clearedYearly.amountFor(BudgetPeriod.YEAR, leapDay))
    }

    @Test
    fun onlyConfiguredPeriodsProduceBudgetProgress() {
        val date = LocalDate.parse("2026-03-01")
        val settings = BudgetSettings(startDay = 20, totalCents = 1000, yearStartMonth = 4, yearStartDay = 15)
        val rows = listOf(
            row("2026-02-01", TransactionType.EXPENSE, 900),
            row("2026-02-28", TransactionType.EXPENSE, 300),
            row("2026-03-01", TransactionType.EXPENSE, 650)
        )

        val monthly = calculateBudget(
            rows,
            budgetCycle(date, BudgetPeriod.MONTH, settings),
            settings.amountFor(BudgetPeriod.MONTH, date),
            date
        )!!
        assertEquals(950L, monthly.spentCents)
        assertEquals(50L, monthly.remainingCents)
        assertEquals(0L, monthly.overCents)
        assertEquals(BudgetAlert.NEAR, monthly.alert)

        assertNull(settings.amountFor(BudgetPeriod.DAY, date))
        assertNull(settings.amountFor(BudgetPeriod.YEAR, date))
        assertNull(calculateBudget(
            rows,
            budgetCycle(date, BudgetPeriod.YEAR, settings),
            settings.amountFor(BudgetPeriod.YEAR, date),
            date
        ))
        assertEquals(LocalDate.parse("2025-04-15"), budgetCycle(date, BudgetPeriod.YEAR, settings).start)
    }

    private fun row(
        date: String,
        type: TransactionType,
        amount: Long,
        category: String = "food",
        hour: Int = 12
    ): TransactionRecord {
        val timestamp = LocalDate.parse(date).atTime(hour, 0)
            .toInstant(ZoneOffset.UTC).toEpochMilli()
        return TransactionRecord(
            0, timestamp, date, hour, amount, type, PaymentChannel.MANUAL, "", "", category, "", false,
            CaptureSource.MANUAL, null, 0, 0
        )
    }
}
