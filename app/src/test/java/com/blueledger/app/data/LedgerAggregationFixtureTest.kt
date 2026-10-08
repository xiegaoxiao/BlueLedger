package com.blueledger.app.data

import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/**
 * §12 固定验收夹具的精确期望：真实 Room + 真实 Repository，不使用任何 Fake。
 * 时钟固定 2026-10-07 Asia/Shanghai。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerAggregationFixtureTest : LedgerRoomTestBase() {

    @Before
    fun seed() = runDb { seedAcceptanceFixture() }

    // ───────────────────── 月度摘要 ─────────────────────

    @Test
    fun `september_summary_matches_fixture`() = runDb {
        val summary = repository.observeMonthSummary(september).first()
        assertEquals(500_000L, summary.incomeCent)
        assertEquals(10_000L, summary.expenseCent)
        assertEquals(490_000L, summary.balanceCent)
        assertEquals(2, summary.count)
    }

    @Test
    fun `october_summary_matches_fixture`() = runDb {
        val summary = repository.observeMonthSummary(october).first()
        assertEquals(1_080_000L, summary.incomeCent)
        assertEquals(307_880L, summary.expenseCent)
        assertEquals(772_120L, summary.balanceCent)
        assertEquals(7, summary.count)
    }

    @Test
    fun `year_summary_and_reached_months_match_fixture`() = runDb {
        val year = repository.observeYearAnalysis(2026).first()
        assertEquals(1_580_000L, year.incomeCent)
        assertEquals(317_880L, year.expenseCent)
        assertEquals(1_262_120L, year.balanceCent)
        assertEquals(9, year.count)
        assertEquals(12, year.months.size)
        assertEquals(LocalDate.of(2026, 10, 7), year.cutoff)
        assertTrue(year.isCurrentYear)
        // 月均分母 = 已经进入的月份数（10 个月），未到的 11/12 月不计入。
        assertEquals(10, year.reachedMonthCount)
        assertEquals(31_788L, year.averageMonthlyExpenseCent)

        val january = year.months[0]
        assertTrue("1 月已到但没有记录", january.reached)
        assertTrue(!january.hasRecords)
        assertEquals(0L, january.incomeCent)

        val septemberRow = year.months[8]
        assertTrue(septemberRow.reached)
        assertTrue(septemberRow.hasRecords)
        assertEquals(500_000L, septemberRow.incomeCent)
        assertEquals(10_000L, septemberRow.expenseCent)

        val octoberRow = year.months[9]
        assertEquals(1_080_000L, octoberRow.incomeCent)
        assertEquals(307_880L, octoberRow.expenseCent)

        // 11/12 月是未到月份：不是“已到的零记录月”。
        val november = year.months[10]
        assertTrue(!november.reached)
        assertTrue(!november.hasRecords)
        assertEquals(0L, november.incomeCent)
        assertTrue(!year.months[11].reached)
    }

    @Test
    fun `future_year_is_all_unreached`() = runDb {
        val year = repository.observeYearAnalysis(2027).first()
        assertEquals(0L, year.incomeCent)
        assertEquals(0L, year.expenseCent)
        assertEquals(0, year.reachedMonthCount)
        assertNull(year.cutoff)
        assertTrue(year.months.all { !it.reached })
    }

    @Test
    fun `historical_year_reaches_all_twelve_months`() = runDb {
        val year = repository.observeYearAnalysis(2025).first()
        assertTrue(year.months.all { it.reached })
        assertEquals(12, year.reachedMonthCount)
        assertEquals(LocalDate.of(2025, 12, 31), year.cutoff)
        assertTrue(!year.isCurrentYear)
    }

    // ───────────────────── 账户余额 ─────────────────────

    @Test
    fun `account_balances_are_derived_from_full_history`() = runDb {
        val accounts = repository.observeAccounts(includeArchived = false).first()
        val byName = accounts.associateBy { it.account.name }
        assertEquals(3, accounts.size)

        val accountA = byName.getValue("A银行卡")
        assertEquals(100_000L, accountA.openingBalanceCent)
        assertEquals(1_290_000L, accountA.balanceCent)
        assertEquals(1_190_000L, accountA.netChangeCent)
        assertEquals(4, accountA.transactionCount)

        val accountB = byName.getValue("B钱包")
        assertEquals(92_120L, accountB.balanceCent)
        assertEquals(5, accountB.transactionCount)

        // 全部账户余额 = 期初总额 1,200.00 + 全年结余 12,621.20
        assertEquals(1_382_120L, accounts.sumOf { it.balanceCent })
        assertEquals(120_000L, accounts.sumOf { it.openingBalanceCent })
        assertEquals(1_262_120L, repository.observeYearAnalysis(2026).first().balanceCent)
    }

    // ───────────────────── 月度分析：日趋势 / 分类切片 ─────────────────────

    @Test
    fun `october_expense_daily_series_is_zero_filled_to_today`() = runDb {
        val analysis = repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first()
        assertEquals(YearMonth.of(2026, 10), analysis.month)
        assertEquals(LocalDate.of(2026, 10, 7), analysis.periodEnd)
        assertEquals(7, analysis.daily.size)
        assertEquals(
            listOf(300_000L, 30L, 5_000L, 0L, 0L, 0L, 2_850L),
            analysis.daily.map { it.amountCent },
        )
        assertEquals(
            (1..7).map { LocalDate.of(2026, 10, it) },
            analysis.daily.map { it.date },
        )

        val incomeDaily = repository.observeMonthAnalysis(october, TransactionType.INCOME).first()
        assertEquals(
            listOf(1_000_000L, 0L, 0L, 0L, 0L, 80_000L, 0L),
            incomeDaily.daily.map { it.amountCent },
        )
    }

    @Test
    fun `october_slices_match_fixture_totals_and_order`() = runDb {
        val expense = repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first()
        assertEquals(307_880L, expense.typeTotalCent)
        assertEquals(1_080_000L, expense.incomeCent)
        assertEquals(307_880L, expense.expenseCent)
        assertEquals(7, expense.count)

        val expenseSlices = expense.slices
        assertEquals(3, expenseSlices.size)
        assertEquals(listOf("住房", "交通", "餐饮"), expenseSlices.map { it.name })
        assertEquals(listOf(300_000L, 5_000L, 2_880L), expenseSlices.map { it.amountCent })
        // 占比 = 百分比 × 10，一位小数四舍五入
        assertEquals(listOf(974, 16, 9), expenseSlices.map { it.ratioPermille })

        val income = repository.observeMonthAnalysis(october, TransactionType.INCOME).first()
        assertEquals(1_080_000L, income.typeTotalCent)
        assertEquals(listOf("工资", "兼职"), income.slices.map { it.name })
        assertEquals(listOf(1_000_000L, 80_000L), income.slices.map { it.amountCent })
        assertEquals(listOf(926, 74), income.slices.map { it.ratioPermille })
    }

    @Test
    fun `historical_month_daily_series_covers_whole_month`() = runDb {
        val analysis = repository.observeMonthAnalysis(september, TransactionType.EXPENSE).first()
        assertEquals(LocalDate.of(2026, 9, 30), analysis.periodEnd)
        assertEquals(30, analysis.daily.size)
        assertEquals(10_000L, analysis.daily.last().amountCent)
        assertEquals(0L, analysis.daily.dropLast(1).sumOf { it.amountCent })
        assertEquals(listOf("餐饮"), analysis.slices.map { it.name })
    }

    @Test
    fun `future_month_analysis_is_empty_without_fake_data`() = runDb {
        val analysis = repository.observeMonthAnalysis(YearMonth.of(2026, 11), TransactionType.EXPENSE).first()
        assertTrue(analysis.daily.isEmpty())
        assertTrue(analysis.slices.isEmpty())
        assertNull(analysis.periodEnd)
        assertEquals(0L, analysis.typeTotalCent)
        assertEquals(0L, repository.observeMonthSummary(YearMonth.of(2026, 11)).first().incomeCent)
    }

    @Test
    fun `zero_total_month_produces_no_ratio_and_no_fake_ring`() = runDb {
        val analysis = repository.observeMonthAnalysis(YearMonth.of(2026, 8), TransactionType.EXPENSE).first()
        assertEquals(0L, analysis.typeTotalCent)
        assertTrue(analysis.slices.isEmpty())
        assertEquals(31, analysis.daily.size)
        assertTrue(analysis.daily.all { it.amountCent == 0L })
    }

    @Test
    fun `leap_year_february_daily_series_is_complete`() = runDb {
        val analysis = repository.observeMonthAnalysis(YearMonth.of(2024, 2), TransactionType.EXPENSE).first()
        assertEquals(29, analysis.daily.size)
        assertEquals(LocalDate.of(2024, 2, 29), analysis.daily.last().date)

        val feb2025 = repository.observeMonthAnalysis(YearMonth.of(2025, 2), TransactionType.EXPENSE).first()
        assertEquals(28, feb2025.daily.size)
    }

    // ───────────────────── 预算 ─────────────────────

    @Test
    fun `october_budget_state_matches_fixture`() = runDb {
        val budget = repository.observeBudget(october).first()
        assertEquals(400_000L, budget.budgetCent)
        assertEquals(307_880L, budget.usedCent)
        assertEquals(92_120L, budget.remainingCent)
        assertNull(budget.exceededCent)
        assertEquals(7_697L, budget.ratioBasisPoint)
        assertEquals("77.0%", com.blueledger.app.core.money.Money.formatBasisPoint(budget.ratioBasisPoint!!))
        assertEquals(BudgetStatus.NORMAL, budget.status)
        assertEquals(0.7697f, budget.progressFraction!!, 0.0001f)
    }

    @Test
    fun `september_budget_is_unset`() = runDb {
        val budget = repository.observeBudget(september).first()
        assertNull(budget.budgetCent)
        assertNull(budget.remainingCent)
        assertNull(budget.ratioBasisPoint)
        assertEquals(BudgetStatus.UNSET, budget.status)
        assertTrue(!budget.isSet)
        // 未设置预算 ≠ 0 预算：已用支出仍按该月全部有效支出计算，只是没有使用率。
        assertEquals(10_000L, budget.usedCent)
    }

    // ───────────────────── 列表 / 筛选 / 搜索 / 分页 ─────────────────────

    @Test
    fun `october_list_is_sorted_by_date_then_created_at_then_id`() = runDb {
        val page = repository.observeTransactions(TransactionFilter(yearMonth = october)).first()
        assertEquals(7, page.totalCount)
        assertTrue(!page.hasMore)
        assertEquals(listOf("T9", "T8", "T7", "T6", "T5", "T4", "T3"), page.items.map { it.id })
        // 列表项自带分类/账户名称，且随重命名更新（在写入测试中验证）
        val t9 = page.items.first { it.id == "T9" }
        assertEquals("餐饮", t9.categoryName)
        assertEquals("B钱包", t9.accountName)
        assertEquals(2_850L, t9.amountCent)
        assertEquals(LocalDate.of(2026, 10, 7), t9.occurredOn)
    }

    @Test
    fun `filter_summary_uses_same_predicate_and_ignores_paging`() = runDb {
        val filter = TransactionFilter(
            yearMonth = october,
            type = TransactionType.EXPENSE,
            categoryId = FOOD,
            accountId = fixtureIds.accountB,
        )
        val page = repository.observeTransactions(filter).first()
        assertEquals(listOf("T5", "T6", "T9"), page.items.map { it.id }.sorted())
        assertEquals(3, page.totalCount)

        val summary = repository.observeFilteredSummary(filter).first()
        assertEquals(2_880L, summary.expenseCent)
        assertEquals(0L, summary.incomeCent)
        assertEquals(3, summary.count)

        // 分页参数不影响汇总
        val paged = repository.observeFilteredSummary(filter.copy(limit = 1, offset = 1)).first()
        assertEquals(summary, paged)
    }

    @Test
    fun `paging_reports_total_count_and_has_more`() = runDb {
        val firstPage = repository.observeTransactions(
            TransactionFilter(yearMonth = october, limit = 3, offset = 0),
        ).first()
        assertEquals(3, firstPage.items.size)
        assertEquals(7, firstPage.totalCount)
        assertTrue(firstPage.hasMore)

        val lastPage = repository.observeTransactions(
            TransactionFilter(yearMonth = october, limit = 3, offset = 6),
        ).first()
        assertEquals(1, lastPage.items.size)
        assertEquals(7, lastPage.totalCount)
        assertTrue(!lastPage.hasMore)
    }

    @Test
    fun `search_covers_note_category_and_account_names`() = runDb {
        val byNote = repository.observeTransactions(TransactionFilter(query = "早餐")).first()
        assertEquals(listOf("T5"), byNote.items.map { it.id })

        val byCategory = repository.observeTransactions(TransactionFilter(query = "餐饮")).first()
        // 排序仍为 occurredOn DESC → createdAt DESC → id ASC
        assertEquals(listOf("T9", "T6", "T5", "T2"), byCategory.items.map { it.id })
        assertEquals(12_880L, repository.observeFilteredSummary(TransactionFilter(query = "餐饮")).first().expenseCent)

        val byAccount = repository.observeTransactions(TransactionFilter(query = "钱包")).first()
        assertEquals(listOf("T9", "T8", "T7", "T6", "T5"), byAccount.items.map { it.id })

        // `%` 和 `_` 是普通字符，不是通配符
        assertTrue(repository.observeTransactions(TransactionFilter(query = "%")).first().items.isEmpty())
        assertTrue(repository.observeTransactions(TransactionFilter(query = "_")).first().items.isEmpty())
    }

    @Test
    fun `combined_filters_use_and_semantics`() = runDb {
        val filter = TransactionFilter(
            yearMonth = october,
            type = TransactionType.EXPENSE,
            accountId = fixtureIds.accountB,
        )
        val page = repository.observeTransactions(filter).first()
        assertEquals(listOf("T9", "T7", "T6", "T5"), page.items.map { it.id })
        assertEquals(7_880L, repository.observeFilteredSummary(filter).first().expenseCent)

        val intersection = TransactionFilter(
            yearMonth = october,
            from = LocalDate.of(2026, 10, 2),
            to = LocalDate.of(2026, 10, 3),
        )
        val ranged = repository.observeTransactions(intersection).first()
        assertEquals(listOf("T7", "T6", "T5"), ranged.items.map { it.id })
    }

    @Test
    fun `single_transaction_observer_excludes_soft_deleted`() = runDb {
        assertEquals("T9", repository.observeTransaction("T9").first()?.id)
        assertNull(repository.observeTransaction("missing").first())
    }

    @Test
    fun `cent_level_amounts_sum_exactly`() = runDb {
        // 0.10 + 0.20 = 0.30，不允许浮点误差
        val summary = repository.observeFilteredSummary(
            TransactionFilter(yearMonth = october, categoryId = FOOD, accountId = fixtureIds.accountB),
        ).first()
        assertEquals(2_880L, summary.expenseCent)
        assertEquals("28.80", com.blueledger.app.core.money.Money.format(summary.expenseCent))
    }
}
