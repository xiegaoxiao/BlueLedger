package com.blueledger.app.feature.reference

import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.Instant

class ReferenceModelTest {
    private val date = LocalDate.of(2026, 10, 7)
    private fun row(id: String, amount: Long, category: String, type: TransactionType = TransactionType.EXPENSE) = TransactionWithRefs(
        LedgerTransaction(id, type, amount, category, "wallet", date, "", Instant.EPOCH, Instant.EPOCH), category, "restaurant", false, "钱包")
    @Test fun weekSpansYearAndUsesMonday() {
        val range = ChartRange.week(LocalDate.of(2027, 1, 1))
        assertEquals(LocalDate.of(2026, 12, 28), range.from)
        assertEquals(LocalDate.of(2027, 1, 3), range.to)
        assertEquals(LocalDate.of(2026, 12, 21), range.previous().from)
    }
    @Test fun monthShiftsWithDifferentLengths() { assertEquals(LocalDate.of(2024, 2, 29), ChartRange.month(YearMonth.of(2024, 3)).previous().to) }
    @Test fun accountingMonthAndYearChartsKeepCustomStartAcrossYearBoundary() {
        val current = ChartRange.forPeriod(ChartPeriod.MONTH, LocalDate.of(2027, 1, 7), 15)
        assertEquals(LocalDate.of(2026, 12, 15), current.from)
        assertEquals(LocalDate.of(2027, 1, 14), current.to)
        assertEquals(LocalDate.of(2027, 1, 15), current.next().from)
        assertEquals(LocalDate.of(2026, 1, 15), ChartRange.forPeriod(ChartPeriod.YEAR, LocalDate.of(2027, 1, 7), 15).from)
        val entry = row("custom", 1250, "food").let { it.copy(transaction = it.transaction.copy(occurredOn = LocalDate.of(2027, 1, 7))) }
        val state = buildReferenceState(TransactionFilter(), TransactionPageState(listOf(entry), 1, false), MoneySummary(expenseCent = 1250, count = 1), 15)
        assertEquals(YearMonth.of(2026, 12), state.months.single().month)
        assertEquals(1250L, chartValues(state, ChartRange.year(2026, 15)).last())
    }
    @Test fun averagesOnlyReachedDays() {
        assertEquals(100L, averageDaily(700, ChartRange.month(YearMonth.of(2026, 10)), date))
        assertNull(averageDaily(700, ChartRange.month(YearMonth.of(2026, 11)), date))
    }
    @Test fun aggregationIncludesAllCategoriesAndZeroDays() {
        val rows = listOf(row("a", 10, "food"), row("b", 20, "food"), row("c", 970, "other"))
        val filter = TransactionFilter(yearMonth = YearMonth.of(2026, 10), type = TransactionType.EXPENSE)
        val state = buildReferenceState(filter, TransactionPageState(rows, 3, false), MoneySummary(expenseCent = 1000, count = 3))
        assertEquals(listOf(970L, 30L), state.categories.map { it.cent })
        assertEquals(30, state.categories.last().permille)
        assertEquals(31, chartValues(state, ChartRange.month(YearMonth.of(2026, 10))).size)
        assertEquals(1000L, chartValues(state, ChartRange.week(date))[2])
    }
    @Test fun assetsAndDebtProduceSignedNetAssets() { assertEquals(3000L to 1500L, assetTotals(listOf(1000, 2000, -1500, 0))) }
    @Test fun historyAdjustmentDoesNotRewriteEarlierBalance() {
        val raw = AssetHistory.record(AssetHistory.record("", date.minusDays(1), 1000), date, 2000)
        val account = LedgerAccount("wallet", "现金", AccountKind.CASH, 2000, openingHistory = raw)
        assertEquals(1000L, AssetHistory.openingAt(account, date.minusDays(1)))
        assertEquals(1990L, assetBalanceAt(account, listOf(row("a", 10, "food")), date))
        assertEquals(0L, AssetHistory.openingAt(account, date.minusDays(2)))
        assertEquals(2, AssetHistory.parse(AssetHistory.record(raw, date, 3000)).size)
    }
    @Test fun reminderRollsToTomorrowAfterSameTime() {
        val clock = FixedClock.atDate(date)
        assertEquals(date.plusDays(1).atStartOfDay(clock.zoneId()).toInstant().toEpochMilli(), nextReminderMillis(clock, LocalTime.MIDNIGHT))
    }
    @Test fun optimizedAssetSeriesMatchesHistoricalBalanceIncludingAdjustmentsAndDebt() {
        val accounts = listOf(
            LedgerAccount("wallet", "现金", AccountKind.CASH, 3000, openingHistory = AssetHistory.record(AssetHistory.record("", date.minusMonths(2), 1000), date, 3000)),
            LedgerAccount("debt", "信用卡", AccountKind.CREDIT_CARD, -2000),
        )
        val rows = listOf(row("a", 10, "food"), row("b", 100, "salary", TransactionType.INCOME).let { it.copy(transaction = it.transaction.copy(occurredOn = date.minusMonths(1))) },
            row("c", 200, "food").let { it.copy(transaction = it.transaction.copy(accountId = "debt")) })
        val totals = assetMonthlyTotals(accounts, rows, date.year, date)
        for (month in 1..date.monthValue) {
            val end = minOf(YearMonth.of(date.year, month).atEndOfMonth(), date)
            assertEquals(assetTotals(accounts.map { assetBalanceAt(it, rows, end) }), totals[month - 1])
        }
        assertEquals(listOf(0L to 0L, 0L to 0L), totals.takeLast(2))
    }
    @Test fun negativeCashBalanceAndPositiveDebtInputKeepTheirSigns() {
        assertEquals(-1250L, parseAssetBalance("-12.50", AccountKind.CASH))
        assertEquals(-1250L, parseAssetBalance("12.50", AccountKind.CREDIT_CARD))
        assertNull(parseAssetBalance("-12.50", AccountKind.CREDIT_CARD))
        assertNull(parseAssetBalance("1.999", AccountKind.CASH))
    }
}
