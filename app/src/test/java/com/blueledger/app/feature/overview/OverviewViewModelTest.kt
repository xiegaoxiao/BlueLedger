package com.blueledger.app.feature.overview

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Expect
import com.blueledger.app.acceptance.Fx
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * S01 首页 ViewModel 的行为与金额口径测试（不渲染 UI）。
 *
 * 期望值全部引用 A7 的 §12 验收常量（`Expect`），首页不允许有自己的聚合口径。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OverviewViewModelTest {

    private lateinit var harness: OverviewTestHarness

    @Before
    fun setUp() {
        harness = OverviewTestHarness()
    }

    @Test
    fun `默认显示设备当前月并给出十月验收口径的摘要与预算`() {
        harness.seedAcceptance()
        val state = harness.viewModel().state.value

        assertEquals(YearMonth.of(2026, 10), state.month)
        assertEquals(YearMonth.of(2026, 10), state.currentMonth)
        assertEquals(Expect.OCT_INCOME_CENT, state.summary.incomeCent)
        assertEquals(Expect.OCT_EXPENSE_CENT, state.summary.expenseCent)
        assertEquals(Expect.OCT_BALANCE_CENT, state.summary.balanceCent)
        assertEquals(Expect.OCT_COUNT, state.summary.count)

        val budget = requireNotNull(state.budget)
        assertTrue(budget.isSet)
        assertEquals(Fx.OCT_BUDGET_CENT, budget.budgetCent)
        assertEquals(Expect.OCT_BUDGET_REMAINING_CENT, budget.remainingCent)
        assertEquals(Expect.OCT_BUDGET_RATIO_BASIS_POINT, budget.ratioBasisPoint)
        // 76.97% 一位小数展示为 77.0%（四舍五入，不是 76.9%）。
        assertEquals(Expect.OCT_BUDGET_RATIO_PERMILLE_DISPLAY, budget.ratioPermille)
        assertFalse(state.isLedgerEmpty)
        assertFalse(state.isSelectedMonthEmpty)
    }

    @Test
    fun `结余标签不写成余额`() {
        harness.seedAcceptance()
        val state = harness.viewModel().state.value
        assertEquals("本月结余", state.balanceLabel)
        assertFalse(state.balanceLabel.contains("余额"))
    }

    @Test
    fun `最近账单最多 5 条且排序与账单列表完全一致`() {
        harness.seedAcceptance()
        val state = harness.viewModel().state.value

        val listOrder = runBlocking {
            harness.repository.observeTransactions(
                TransactionFilter(yearMonth = Fx.OCT_2026, limit = 100),
            ).first().items
        }
        assertEquals(Expect.OCT_COUNT, listOrder.size)
        assertEquals(5, state.recentTransactions.size)
        assertEquals(
            listOrder.take(5).map { it.id },
            state.recentTransactions.map { it.id },
        )
        // 最近一条是 10 月 7 日的 T9。
        assertEquals(harness.transactionId("T9"), state.recentTransactions.first().id)
    }

    @Test
    fun `切到九月后摘要与未设置预算的口径随之改变`() {
        harness.seedAcceptance()
        val model = harness.viewModel()

        model.onPreviousMonth()
        settleMainLooper()
        val state = model.state.value

        assertEquals(Fx.SEP_2026, state.month)
        assertEquals(Expect.SEP_INCOME_CENT, state.summary.incomeCent)
        assertEquals(Expect.SEP_EXPENSE_CENT, state.summary.expenseCent)
        assertEquals(Expect.SEP_BALANCE_CENT, state.summary.balanceCent)
        // 九月没有预算：不能显示假进度条，也不能把 0 当预算分母。
        assertFalse(state.hasBudget)
        assertEquals(BudgetStatus.UNSET, state.budget?.status)
        assertNull(state.budget?.ratioPermille)
        // 九月的两笔账单仍在「最近账单」里。
        assertEquals(2, state.recentTransactions.size)
    }

    @Test
    fun `不允许翻到未来月份且可以回到本月`() {
        harness.seedAcceptance()
        val model = harness.viewModel()

        model.onNextMonth()
        settleMainLooper()
        assertEquals(Fx.OCT_2026, model.state.value.month)
        assertFalse(model.state.value.canGoToNextMonth)

        model.onPreviousMonth()
        settleMainLooper()
        model.onPreviousMonth()
        settleMainLooper()
        assertEquals(YearMonth.of(2026, 8), model.state.value.month)
        assertTrue(model.state.value.canGoToNextMonth)

        model.onCurrentMonth()
        settleMainLooper()
        assertEquals(Fx.OCT_2026, model.state.value.month)
    }

    @Test
    fun `快捷分类只包含未归档分类并按默认顺序`() {
        harness.seedAcceptance()
        val model = harness.viewModel()

        assertEquals(
            listOf("cat_expense_food", "cat_expense_transport", "cat_expense_shopping", "cat_expense_daily", "cat_expense_housing"),
            model.state.value.quickCategories.map { it.id },
        )

        runBlocking {
            harness.repository.setCategoryArchived("cat_expense_food", archived = true)
        }
        settleMainLooper()

        val ids = model.state.value.quickCategories.map { it.id }
        assertFalse("归档分类不能出现在快捷记账入口", ids.contains("cat_expense_food"))
        assertEquals(listOf("cat_expense_transport", "cat_expense_shopping", "cat_expense_daily", "cat_expense_housing"), ids)
    }

    @Test
    fun `首次空账本为零金额且没有预算`() {
        harness.initialize()
        val state = harness.viewModel().state.value

        assertTrue(state.isLedgerEmpty)
        assertTrue(state.isSelectedMonthEmpty)
        assertEquals(0L, state.summary.incomeCent)
        assertEquals(0L, state.summary.expenseCent)
        assertEquals(0L, state.summary.balanceCent)
        assertTrue(state.recentTransactions.isEmpty())
        assertFalse(state.hasBudget)
        // 空账本仍然要有默认快捷分类入口。
        assertEquals(5, state.quickCategories.size)
    }

    @Test
    fun `历史月份为空时不是首次空账本`() {
        harness.seedAcceptance()
        val model = harness.viewModel()

        model.onPreviousMonth()
        settleMainLooper()
        model.onPreviousMonth()
        settleMainLooper()
        val state = model.state.value

        assertEquals(YearMonth.of(2026, 8), state.month)
        assertTrue(state.isSelectedMonthEmpty)
        assertFalse(state.isLedgerEmpty)
        assertEquals(Expect.OCT_COUNT + 2, state.ledgerTransactionCount)
    }

    @Test
    fun `金额隐藏读取仓库设置而不是本地开关`() {
        harness.seedAcceptance()
        val model = harness.viewModel()
        assertFalse(model.state.value.hideAmounts)

        runBlocking { harness.repository.setHideAmounts(true) }
        settleMainLooper()
        assertTrue(model.state.value.hideAmounts)

        model.onToggleHideAmounts()
        settleMainLooper()
        assertFalse(model.state.value.hideAmounts)
        assertFalse(runBlocking { harness.repository.observeSettings().first().hideAmounts })
    }

    @Test
    fun `读取失败显示错误而不是零账单空账本`() {
        harness.seedAcceptance()
        val failing = FailingMonthSummaryRepository(harness.repository)
        val model = OverviewViewModel(repository = failing, clock = harness.clock)
        settleMainLooper()

        val state = model.state.value
        assertEquals(OverviewViewModel.READ_ERROR_MESSAGE, state.readErrorMessage)
        assertFalse(state.loading)

        failing.failMonthSummary = false
        model.onRetry()
        settleMainLooper()

        val recovered = model.state.value
        assertNull(recovered.readErrorMessage)
        assertEquals(Expect.OCT_INCOME_CENT, recovered.summary.incomeCent)
    }

    @Test
    fun `编辑或删除后首页摘要从同一事实源刷新`() {
        val seeded = harness.seedAcceptance()
        val model = harness.viewModel()
        assertEquals(Expect.OCT_EXPENSE_CENT, model.state.value.summary.expenseCent)

        // §12-1：把 T4 从 3,000.00 改成 3,200.00。
        runBlocking { seeded.update("T4", amountCent = Expect.C1_T4_AMOUNT_CENT) }
        settleMainLooper()

        assertEquals(Expect.C1_OCT_EXPENSE_CENT, model.state.value.summary.expenseCent)
        assertEquals(Expect.C1_OCT_BALANCE_CENT, model.state.value.summary.balanceCent)
        assertEquals(Expect.C1_OCT_BUDGET_REMAINING_CENT, model.state.value.budget?.remainingCent)

        // §12-3：软删除 T9，首页立刻排除；撤销后回到原值。
        val receipt = runBlocking {
            (harness.repository.softDeleteTransaction(seeded.transactionId("T9"))
                as com.blueledger.app.core.model.Outcome.Success).value
        }
        settleMainLooper()
        // 本用例里 T4 已被改成 3,200.00，因此期望是「C1 的十月支出 − T9 的 28.50」。
        assertEquals(Expect.C1_OCT_EXPENSE_CENT - 2_850L, model.state.value.summary.expenseCent)

        runBlocking { harness.repository.undoDelete(receipt) }
        settleMainLooper()
        assertEquals(Expect.C1_OCT_EXPENSE_CENT, model.state.value.summary.expenseCent)
    }

    @Test
    fun `归档账户的历史账单仍参与首页统计`() {
        val seeded = harness.seedAcceptance()
        val model = harness.viewModel()
        val before = model.state.value.summary

        runBlocking {
            harness.repository.setAccountArchived(
                seeded.accountId(Fx.ACCOUNT_B_REF),
                archived = true,
                replacementDefaultId = null,
            )
        }
        settleMainLooper()

        assertEquals(before, model.state.value.summary)
    }
}

/**
 * 只让月度摘要读取失败的装饰器（接口委托，不重写其它方法），
 * 用于验证「读取失败不能伪装成空账本」。
 */
private class FailingMonthSummaryRepository(
    private val delegate: LedgerRepository,
) : LedgerRepository by delegate {

    var failMonthSummary: Boolean = true

    override fun observeMonthSummary(month: YearMonth): Flow<MoneySummary> =
        if (failMonthSummary) {
            flow { throw IllegalStateException("模拟本地读取失败") }
        } else {
            delegate.observeMonthSummary(month)
        }
}
