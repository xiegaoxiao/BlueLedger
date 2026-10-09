package com.blueledger.app.feature.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

enum class ChartPeriod { WEEK, MONTH, YEAR }

data class ChartRange(val from: LocalDate, val to: LocalDate, val period: ChartPeriod, val monthStartDay: Int = 1) {
    fun previous(): ChartRange = shifted(-1)
    fun next(): ChartRange = shifted(1)
    private fun shifted(delta: Long): ChartRange = when (period) {
        ChartPeriod.WEEK -> copy(from = from.plusWeeks(delta), to = to.plusWeeks(delta))
        ChartPeriod.MONTH -> month(YearMonth.from(from).plusMonths(delta), monthStartDay)
        ChartPeriod.YEAR -> year(from.year + delta.toInt(), monthStartDay)
    }
    companion object {
        fun week(day: LocalDate): ChartRange {
            val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            return ChartRange(monday, monday.plusDays(6), ChartPeriod.WEEK)
        }
        fun month(month: YearMonth, day: Int = 1) = LedgerPeriods.range(month, day).let { ChartRange(it.start, it.endInclusive, ChartPeriod.MONTH, day) }
        fun year(year: Int, day: Int = 1) = ChartRange(LedgerPeriods.start(YearMonth.of(year, 1), day), LedgerPeriods.start(YearMonth.of(year + 1, 1), day).minusDays(1), ChartPeriod.YEAR, day)
        fun forPeriod(period: ChartPeriod, today: LocalDate, day: Int = 1) = when (period) {
            ChartPeriod.WEEK -> week(today)
            ChartPeriod.MONTH -> month(LedgerPeriods.monthOf(today, day), day)
            ChartPeriod.YEAR -> year(LedgerPeriods.monthOf(today, day).year, day)
        }
    }
}

data class ReferenceDay(val date: LocalDate, val rows: List<TransactionWithRefs>, val income: Long, val expense: Long)
data class ReferenceCategory(val id: String, val name: String, val icon: String, val cent: Long, val permille: Int)
data class ReferenceMonth(val month: YearMonth, val income: Long, val expense: Long) {
    val balance: Long get() = income - expense
}
data class ReferenceLedgerState(
    val filter: TransactionFilter,
    val loaded: Boolean = false,
    val error: String? = null,
    val rows: List<TransactionWithRefs> = emptyList(),
    val days: List<ReferenceDay> = emptyList(),
    val categories: List<ReferenceCategory> = emptyList(),
    val expenseCategories: List<ReferenceCategory> = emptyList(),
    val expenseRanking: List<TransactionWithRefs> = emptyList(),
    val months: List<ReferenceMonth> = emptyList(),
    val summary: MoneySummary = MoneySummary.EMPTY,
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
    val earliestDate: LocalDate? = null,
)

/** 聚合放在后台，页面离开 STARTED 后取消查询，保留上次显示的数据。 */
class ReferenceLedgerViewModel(repository: LedgerRepository, initialFilter: TransactionFilter) : ViewModel() {
    private val filter = MutableStateFlow(initialFilter)
    val state = filter.flatMapLatest { query ->
        combine(repository.observeTransactions(query), repository.observeFilteredSummary(query), repository.observeAdvancedSettings()) { page, summary, settings ->
            buildReferenceState(query, page, summary, settings.monthStartDay)
        }.flowOn(Dispatchers.Default)
            .catch { emit(ReferenceLedgerState(query, loaded = true, error = "读取失败，请重试")) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0), ReferenceLedgerState(initialFilter))
    fun select(query: TransactionFilter) { filter.value = query }
    fun loadMore() { filter.update { it.copy(limit = it.limit + 50) } }
    fun retry() { filter.update { it.copy(limit = it.limit + 1) } }
}

internal fun buildReferenceState(filter: TransactionFilter, page: TransactionPageState, summary: MoneySummary, monthStartDay: Int = 1): ReferenceLedgerState {
    val rows = page.items
    val categoryTotal = rows.sumOf { it.amountCent }
    return ReferenceLedgerState(
        filter = filter, loaded = true, rows = rows, summary = summary,
        totalCount = page.totalCount, hasMore = page.hasMore,
        earliestDate = rows.minOfOrNull { it.occurredOn },
        days = rows.groupBy { it.occurredOn }.map { (date, group) ->
            ReferenceDay(date, group, group.filter { it.type.isIncome }.sumOf { it.amountCent }, group.filter { it.type.isExpense }.sumOf { it.amountCent })
        },
        categories = rows.groupBy { it.transaction.categoryId }.map { (id, group) ->
            val amount = group.sumOf { it.amountCent }
            ReferenceCategory(id, group.first().categoryName, group.first().categoryIconKey, amount,
                if (categoryTotal == 0L) 0 else ((amount * 1000 + categoryTotal / 2) / categoryTotal).toInt())
        }.sortedWith(compareByDescending<ReferenceCategory> { it.cent }.thenBy { it.id }),
        expenseCategories = referenceCategories(rows.filter { it.type.isExpense }),
        expenseRanking = rows.filter { it.type.isExpense }.sortedByDescending { it.amountCent }.take(10),
        months = rows.groupBy { LedgerPeriods.monthOf(it.occurredOn, monthStartDay) }.map { (month, group) ->
            ReferenceMonth(month, group.filter { it.type.isIncome }.sumOf { it.amountCent }, group.filter { it.type.isExpense }.sumOf { it.amountCent })
        }.sortedByDescending { it.month },
    )
}

private fun referenceCategories(rows: List<TransactionWithRefs>): List<ReferenceCategory> {
    val total = rows.sumOf { it.amountCent }
    return rows.groupBy { it.transaction.categoryId }.map { (id, group) ->
        val amount = group.sumOf { it.amountCent }
        ReferenceCategory(id, group.first().categoryName, group.first().categoryIconKey, amount,
            if (total == 0L) 0 else ((amount * 1000 + total / 2) / total).toInt())
    }.sortedWith(compareByDescending<ReferenceCategory> { it.cent }.thenBy { it.id })
}

internal fun chartValues(state: ReferenceLedgerState, range: ChartRange): List<Long> =
    if (range.period == ChartPeriod.YEAR) (1..12).map { month ->
        state.months.firstOrNull { it.month.monthValue == month }?.let { it.income + it.expense } ?: 0
    } else generateSequence(range.from) { it.plusDays(1).takeIf { date -> !date.isAfter(range.to) } }.map { date ->
        state.days.firstOrNull { it.date == date }?.let { it.income + it.expense } ?: 0
    }.toList()

internal fun averageDaily(total: Long, range: ChartRange, today: LocalDate): Long? {
    val end = minOf(today, range.to)
    val days = if (end < range.from) 0 else java.time.temporal.ChronoUnit.DAYS.between(range.from, end) + 1
    return if (days == 0L) null else total / days
}

internal fun assetBalanceAt(account: LedgerAccount, rows: List<TransactionWithRefs>, date: LocalDate): Long =
    AssetHistory.openingAt(account, date) + rows.asSequence()
        .filter { it.transaction.accountId == account.id && !it.occurredOn.isAfter(date) }
        .sumOf { if (it.type.isIncome) it.amountCent else -it.amountCent }

/** 一次遍历各账户账单和调整历史，避免 12 月 × 账户数 × 全部流水的重复扫描。 */
internal fun assetMonthlyTotals(accounts: List<LedgerAccount>, rows: List<TransactionWithRefs>, year: Int, today: LocalDate): List<Pair<Long, Long>> {
    val byAccount = rows.groupBy { it.transaction.accountId }
    val balances = List(12) { mutableListOf<Long>() }
    for (account in accounts) {
        val events = byAccount[account.id].orEmpty().sortedBy { it.occurredOn }
        val history = AssetHistory.parse(account.openingHistory)
        var eventIndex = 0; var historyIndex = 0; var flow = 0L
        var opening = if (history.isEmpty()) account.openingBalanceCent else 0L
        for (month in 1..12) {
            val yearMonth = YearMonth.of(year, month)
            if (yearMonth > YearMonth.from(today)) { balances[month - 1].add(0); continue }
            val end = minOf(yearMonth.atEndOfMonth(), today)
            while (eventIndex < events.size && events[eventIndex].occurredOn <= end) {
                val event = events[eventIndex++]
                flow += if (event.type.isIncome) event.amountCent else -event.amountCent
            }
            while (historyIndex < history.size && history[historyIndex].first <= end) opening = history[historyIndex++].second
            balances[month - 1].add(opening + flow)
        }
    }
    return balances.map(::assetTotals)
}
