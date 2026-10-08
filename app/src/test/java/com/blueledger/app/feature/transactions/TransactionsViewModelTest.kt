package com.blueledger.app.feature.transactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Expect
import com.blueledger.app.acceptance.Fx
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * S03 账单列表 ViewModel 的筛选、汇总、分组与分页口径测试。
 *
 * 期望值引用 A7 的 §12 常量；筛选汇总必须覆盖全部匹配记录，不受分页影响。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TransactionsViewModelTest {

    private lateinit var harness: TransactionsTestHarness

    @Before
    fun setUp() {
        harness = TransactionsTestHarness()
    }

    @Test
    fun `默认显示当前月并用验收口径汇总`() {
        harness.seedAcceptance()
        val state = harness.listViewModel().state.value

        assertEquals(Fx.OCT_2026, state.month)
        assertEquals(Expect.OCT_COUNT, state.page.totalCount)
        assertEquals(Expect.OCT_INCOME_CENT, state.summary.incomeCent)
        assertEquals(Expect.OCT_EXPENSE_CENT, state.summary.expenseCent)
        assertEquals(Expect.OCT_BALANCE_CENT, state.summary.balanceCent)
        assertEquals(Expect.OCT_COUNT, state.groups.sumOf { it.items.size })
        assertTrue(state.countMatchesShownRows)
    }

    @Test
    fun `筛选汇总基于全部匹配记录不受分页影响`() {
        harness.seedAcceptance()
        val model = harness.listViewModel(initialPageSize = 2)

        var state = model.state.value
        assertEquals("第一页只加载 2 条", 2, state.shownCount)
        assertEquals(Expect.OCT_COUNT, state.page.totalCount)
        assertTrue(state.hasMore)
        assertFalse(state.countMatchesShownRows)
        // 汇总仍是整月 7 笔，而不是这 2 笔。
        assertEquals(Expect.OCT_COUNT, state.summary.count)
        assertEquals(Expect.OCT_INCOME_CENT, state.summary.incomeCent)
        assertEquals(Expect.OCT_EXPENSE_CENT, state.summary.expenseCent)

        model.loadMore()
        settleMainLooper()
        model.loadMore()
        settleMainLooper()
        state = model.state.value
        assertEquals(Expect.OCT_COUNT, state.shownCount)
        assertFalse(state.hasMore)
        assertTrue(state.countMatchesShownRows)
        assertEquals(Expect.OCT_EXPENSE_CENT, state.groups.sumOf { it.expenseCent })
    }

    @Test
    fun `搜索命中备注`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onQueryChanged("晚餐")
        settleMainLooper()
        val state = model.state.value

        assertEquals(1, state.page.totalCount)
        assertEquals(harness.transactionId("T9"), state.page.items.first().id)
        assertEquals(2_850L, state.summary.expenseCent) // T9 晚餐 28.50，不含备注含「晚餐」的九月账单
    }

    @Test
    fun `搜索命中分类名称`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onQueryChanged("餐饮")
        settleMainLooper()
        val state = model.state.value

        assertEquals(Expect.OCT_FOOD_COUNT, state.page.totalCount)
        assertEquals(Expect.OCT_FOOD_CENT, state.summary.expenseCent)
        assertEquals(0L, state.summary.incomeCent)
    }

    @Test
    fun `搜索命中账户名称`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onQueryChanged("钱包")
        settleMainLooper()
        val state = model.state.value

        assertEquals(5, state.page.totalCount)
        assertEquals(80_000L, state.summary.incomeCent)
        assertEquals(7_880L, state.summary.expenseCent)
    }

    @Test
    fun `搜索大小写不敏感且百分号下划线是普通字符`() {
        harness.initialize()
        harness.addTransaction(amountCent = 100L, note = "折扣50%off", requestId = "pct")
        harness.addTransaction(amountCent = 200L, note = "a_b", requestId = "under")
        harness.addTransaction(amountCent = 300L, note = "ABC", requestId = "abc")
        val model = harness.listViewModel()

        model.onQueryChanged("%")
        settleMainLooper()
        assertEquals(1, model.state.value.page.totalCount)
        assertEquals(100L, model.state.value.summary.expenseCent)

        model.onQueryChanged("_")
        settleMainLooper()
        assertEquals(1, model.state.value.page.totalCount)
        assertEquals(200L, model.state.value.summary.expenseCent)

        model.onQueryChanged("abc")
        settleMainLooper()
        assertEquals(1, model.state.value.page.totalCount)
        assertEquals(300L, model.state.value.summary.expenseCent)
    }

    @Test
    fun `收支类型分类账户筛选按 AND 组合`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onTypeSelected(TransactionType.EXPENSE)
        settleMainLooper()
        model.onCategorySelected(Fx.CAT_FOOD)
        settleMainLooper()
        model.onAccountSelected(harness.acceptanceAccountId(Fx.ACCOUNT_B_REF))
        settleMainLooper()

        val state = model.state.value
        assertEquals(Expect.FILTER_EXPENSE_FOOD_B_LABELS.size, state.page.totalCount)
        assertEquals(Expect.FILTER_EXPENSE_FOOD_B_TOTAL_CENT, state.summary.expenseCent)
        assertEquals(0L, state.summary.incomeCent)
    }

    @Test
    fun `重置清空附加条件但保留当前月份`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onPreviousMonth()
        settleMainLooper()
        model.onQueryChanged("晚餐")
        model.onTypeSelected(TransactionType.EXPENSE)
        model.onCategorySelected(Fx.CAT_FOOD)
        settleMainLooper()
        assertEquals(Fx.SEP_2026, model.state.value.month)
        assertTrue(model.state.value.hasExtraFilters)

        model.onResetFilters()
        settleMainLooper()
        val state = model.state.value

        assertEquals("重置必须保留月份", Fx.SEP_2026, state.month)
        assertEquals("", state.query)
        assertNull(state.type)
        assertNull(state.categoryId)
        assertNull(state.accountId)
        assertFalse(state.hasExtraFilters)
        // 九月全部两笔可见。
        assertEquals(2, state.page.totalCount)
    }

    @Test
    fun `按日期降序分组且组内顺序与仓库一致`() {
        harness.seedAcceptance()
        val state = harness.listViewModel().state.value

        assertEquals(
            listOf(
                java.time.LocalDate.of(2026, 10, 7),
                java.time.LocalDate.of(2026, 10, 6),
                java.time.LocalDate.of(2026, 10, 3),
                java.time.LocalDate.of(2026, 10, 2),
                java.time.LocalDate.of(2026, 10, 1),
            ),
            state.groups.map { it.date },
        )

        val repoOrder = runBlocking {
            harness.repository.observeTransactions(
                TransactionFilter(yearMonth = Fx.OCT_2026, limit = 100),
            ).first().items
        }
        state.groups.forEach { group ->
            assertEquals(
                repoOrder.filter { it.occurredOn == group.date }.map { it.id },
                group.items.map { it.id },
            )
        }

        val oct2 = state.groups.first { it.date == java.time.LocalDate.of(2026, 10, 2) }
        assertEquals(30L, oct2.expenseCent) // T5 0.10 + T6 0.20
        val oct6 = state.groups.first { it.date == java.time.LocalDate.of(2026, 10, 6) }
        assertEquals(80_000L, oct6.incomeCent)
        val oct1 = state.groups.first { it.date == java.time.LocalDate.of(2026, 10, 1) }
        assertEquals(1_000_000L, oct1.incomeCent)
        assertEquals(300_000L, oct1.expenseCent)
    }

    @Test
    fun `同日多笔账单按 createdAt 降序再按 id 升序稳定排列`() {
        harness.initialize()
        // 同一天三笔：仓库排序键必须是 occurredOn DESC → createdAt DESC → id ASC。
        val ids = harness.seedManyOnOneDay(count = 3)
        val state = harness.listViewModel().state.value

        val repoOrder = runBlocking {
            harness.repository.observeTransactions(
                TransactionFilter(yearMonth = Fx.OCT_2026, limit = 100),
            ).first().items.map { it.id }
        }
        assertEquals(repoOrder, state.page.items.map { it.id })
        assertEquals(3, ids.size)
        assertEquals(1, state.groups.size)
    }

    @Test
    fun `钻取种子应用年月类型分类与账户筛选`() {
        harness.seedAcceptance()
        val model = harness.listViewModel(
            seed = TransactionFilterSeed(
                yearMonth = Fx.OCT_2026,
                type = TransactionType.EXPENSE,
                categoryId = Fx.CAT_FOOD,
                accountId = harness.acceptanceAccountId(Fx.ACCOUNT_B_REF),
            ),
        )
        val state = model.state.value

        assertEquals(Fx.OCT_2026, state.month)
        assertEquals(TransactionType.EXPENSE, state.type)
        assertEquals(Fx.CAT_FOOD, state.categoryId)
        assertEquals(Expect.FILTER_EXPENSE_FOOD_B_LABELS.size, state.page.totalCount)
        assertEquals(Expect.FILTER_EXPENSE_FOOD_B_TOTAL_CENT, state.summary.expenseCent)
    }

    @Test
    fun `筛选无结果与当月空是两种状态`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onQueryChanged("没有这样的备注")
        settleMainLooper()
        var state = model.state.value
        assertTrue(state.isFilteredEmpty)
        assertFalse(state.isMonthEmpty)
        assertFalse(state.isLedgerEmpty)

        model.onResetFilters()
        settleMainLooper()
        model.onPreviousMonth()
        settleMainLooper()
        model.onPreviousMonth()
        settleMainLooper()
        state = model.state.value
        assertEquals(YearMonth.of(2026, 8), state.month)
        assertTrue(state.isMonthEmpty)
        assertFalse(state.isFilteredEmpty)
        assertFalse(state.isLedgerEmpty)
    }

    @Test
    fun `首次空账本是账本为空而不是筛选为空`() {
        harness.initialize()
        val state = harness.listViewModel().state.value

        assertTrue(state.isLedgerEmpty)
        assertFalse(state.isFilteredEmpty)
        assertFalse(state.isMonthEmpty)
        assertEquals(0, state.page.totalCount)
    }

    @Test
    fun `类型切换会清掉不兼容的分类筛选`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()

        model.onTypeSelected(TransactionType.EXPENSE)
        settleMainLooper()
        model.onCategorySelected(Fx.CAT_FOOD)
        settleMainLooper()
        assertEquals(Fx.CAT_FOOD, model.state.value.categoryId)

        model.onTypeSelected(TransactionType.INCOME)
        settleMainLooper()
        assertNull("支出分类不能留在收入筛选里", model.state.value.categoryId)

        // 切回「全部」时分类仍是空的，不会自己冒出一个不兼容的筛选。
        model.onTypeSelected(null)
        settleMainLooper()
        assertNull(model.state.value.categoryId)
        assertEquals(TransactionType.EXPENSE, model.state.value.categories.first().type)
    }

    @Test
    fun `归档分类与账户仍能筛选历史账单`() {
        harness.seedAcceptance()
        runBlocking {
            harness.repository.setCategoryArchived(Fx.CAT_FOOD, archived = true)
            harness.repository.setAccountArchived(
                harness.acceptanceAccountId(Fx.ACCOUNT_B_REF),
                archived = true,
                replacementDefaultId = null,
            )
        }
        val model = harness.listViewModel()
        settleMainLooper()

        val food = model.state.value.categories.firstOrNull { it.id == Fx.CAT_FOOD }
        assertNotNull("归档分类仍要能用于查历史账单", food)
        assertTrue(food!!.isArchived)
        assertTrue(model.state.value.accounts.any { it.account.id == harness.acceptanceAccountId(Fx.ACCOUNT_B_REF) })

        model.onCategorySelected(Fx.CAT_FOOD)
        settleMainLooper()
        assertEquals(Expect.OCT_FOOD_COUNT, model.state.value.page.totalCount)
    }

    @Test
    fun `金额隐藏读取仓库设置`() {
        harness.seedAcceptance()
        val model = harness.listViewModel()
        assertFalse(model.state.value.hideAmounts)

        runBlocking { harness.repository.setHideAmounts(true) }
        settleMainLooper()
        assertTrue(model.state.value.hideAmounts)
    }

    @Test
    fun `编辑账单后列表与汇总自动刷新`() {
        val seeded = harness.seedAcceptance()
        val model = harness.listViewModel()
        assertEquals(Expect.OCT_EXPENSE_CENT, model.state.value.summary.expenseCent)

        runBlocking { seeded.update("T4", amountCent = Expect.C1_T4_AMOUNT_CENT) }
        settleMainLooper()

        assertEquals(Expect.C1_OCT_EXPENSE_CENT, model.state.value.summary.expenseCent)
        val row = model.state.value.page.items.first { it.id == seeded.transactionId("T4") }
        assertEquals(Expect.C1_T4_AMOUNT_CENT, row.amountCent)
    }

    @Test
    fun `删除账单后列表排除撤销后恢复原 id`() {
        val seeded = harness.seedAcceptance()
        val model = harness.listViewModel()

        val receipt = runBlocking {
            (harness.repository.softDeleteTransaction(seeded.transactionId("T9"))
                as com.blueledger.app.core.model.Outcome.Success).value
        }
        settleMainLooper()
        assertEquals(Expect.OCT_COUNT - 1, model.state.value.page.totalCount)
        assertEquals(Expect.C3_OCT_EXPENSE_CENT, model.state.value.summary.expenseCent)

        runBlocking { harness.repository.undoDelete(receipt) }
        settleMainLooper()
        assertEquals(Expect.OCT_COUNT, model.state.value.page.totalCount)
        assertTrue(model.state.value.page.items.any { it.id == seeded.transactionId("T9") })
    }
}
