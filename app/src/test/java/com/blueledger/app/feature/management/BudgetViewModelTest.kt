package com.blueledger.app.feature.management

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
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
 * S08 月度预算 ViewModel 单测。
 *
 * 覆盖 §12-6/§12-7 与 PRD B03/B04：五态（未设置/正常/80%/100%/125%）、
 * 进度条不溢出、各月独立、确认移除、沿用上月（显式触发）、失败展示。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BudgetViewModelTest {

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
    }

    private fun viewModel(
        month: YearMonth? = ManagementHarness.OCT_2026,
        repository: LedgerRepository = harness.repository,
    ): BudgetViewModel {
        val vm = BudgetViewModel(repository, harness.clock, month)
        harness.settle()
        return vm
    }

    // ───────────────────────── 五态 ─────────────────────────

    @Test
    fun `未设置月份不计算使用率也不显示假进度`() {
        val vm = viewModel()
        val budget = vm.state.value.budget!!
        assertEquals(BudgetStatus.UNSET, budget.status)
        assertNull(budget.budgetCent)
        assertNull(budget.progressFraction)
        assertNull(BudgetPresentation.ratioText(budget))
        assertNull(BudgetPresentation.statusText(budget))
        assertNull(BudgetPresentation.remainingText(budget))
        assertEquals("为这个月设一个预算", BudgetPresentation.UNSET_TITLE)
    }

    @Test
    fun `正常状态低于八成没有提醒文字`() {
        harness.seedExpense(amountCent = 95_000L, month = ManagementHarness.OCT_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        val budget = viewModel().state.value.budget!!
        assertEquals(BudgetStatus.NORMAL, budget.status)
        assertNull(BudgetPresentation.statusText(budget))
        assertEquals("47.5%", BudgetPresentation.ratioText(budget))
        assertEquals(105_000L, budget.remainingCent)
    }

    @Test
    fun `八成显示接近预算`() {
        harness.seedExpense(amountCent = 160_000L, month = ManagementHarness.OCT_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        val budget = viewModel().state.value.budget!!
        assertEquals(BudgetStatus.NEAR_LIMIT, budget.status)
        assertEquals("接近预算", BudgetPresentation.statusText(budget))
        assertEquals("80.0%", BudgetPresentation.ratioText(budget))
    }

    @Test
    fun `恰好一百显示预算已用完`() {
        harness.seedExpense(amountCent = 200_000L, month = ManagementHarness.OCT_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        val budget = viewModel().state.value.budget!!
        assertEquals(BudgetStatus.EXHAUSTED, budget.status)
        assertEquals("预算已用完", BudgetPresentation.statusText(budget))
        assertEquals("100.0%", BudgetPresentation.ratioText(budget))
        assertEquals(1.0f, budget.progressFraction!!, 0.000001f)
    }

    @Test
    fun `一百二十五显示超支且进度条最多满格`() {
        harness.seedExpense(amountCent = 250_000L, month = ManagementHarness.OCT_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        val budget = viewModel().state.value.budget!!
        assertEquals(BudgetStatus.EXCEEDED, budget.status)
        assertEquals("125.0%", BudgetPresentation.ratioText(budget))
        assertEquals("已超支 ¥500.00", BudgetPresentation.statusText(budget))
        assertEquals(50_000L, budget.exceededCent)
        assertEquals(-50_000L, budget.remainingCent)
        assertEquals("已超支 ¥500.00", BudgetPresentation.remainingText(budget))
        // 关键：真实比例 125%，但进度条宽度封顶 1.0（不溢出）。
        assertEquals(12_500L, budget.ratioBasisPoint)
        assertEquals(1.0f, budget.progressFraction!!, 0.000001f)
    }

    @Test
    fun `收入不降低已支出`() {
        harness.seedExpense(amountCent = 160_000L, month = ManagementHarness.OCT_2026)
        harness.seedIncome(amountCent = 900_000L, month = ManagementHarness.OCT_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        val budget = viewModel().state.value.budget!!
        assertEquals(160_000L, budget.usedCent)
        assertEquals(BudgetStatus.NEAR_LIMIT, budget.status)
    }

    // ───────────────────────── 月份独立 ─────────────────────────

    @Test
    fun `预算按年月独立九月不受十月影响`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.SEP_2026, 300_000L) }
        harness.settle()

        val october = viewModel(ManagementHarness.OCT_2026)
        assertEquals(BudgetStatus.UNSET, october.state.value.budget!!.status)

        val september = viewModel(ManagementHarness.SEP_2026)
        assertEquals(300_000L, september.state.value.budget!!.budgetCent)

        // 切换月份：九月 → 十月 → 八月，值各自独立。
        september.onPreviousMonth() // 八月（未设置）
        harness.settle()
        assertEquals(ManagementHarness.SEP_2026.minusMonths(1), september.state.value.month)
        assertEquals(BudgetStatus.UNSET, september.state.value.budget!!.status)
        september.onNextMonth() // 九月
        september.onNextMonth() // 十月
        harness.settle()
        assertEquals(BudgetStatus.UNSET, september.state.value.budget!!.status)
    }

    @Test
    fun `当前月默认取设备当前月份`() {
        assertEquals(ManagementHarness.OCT_2026, viewModel(month = null).state.value.month)
    }

    // ───────────────────────── 保存 / 校验 ─────────────────────────

    @Test
    fun `保存预算写入仓库并给出成功文案`() {
        val vm = viewModel()
        vm.onStartEdit()
        vm.onAmountChanged("2000")
        vm.onSaveBudget()
        harness.settle()

        assertEquals(200_000L, harness.budget(ManagementHarness.OCT_2026).budgetCent)
        assertFalse(vm.state.value.banner!!.isError)
        assertEquals("已保存 2026 年 10 月预算 ¥2,000.00", vm.state.value.banner!!.message)
        assertFalse(vm.state.value.editing)
    }

    @Test
    fun `零预算空预算与超限都被拒绝且仓库不变`() {
        val vm = viewModel()

        vm.onStartEdit()
        vm.onAmountChanged("0")
        vm.onSaveBudget()
        harness.settle()
        assertEquals("预算必须大于 0 元", vm.state.value.fieldError)
        assertNull(harness.budget(ManagementHarness.OCT_2026).budgetCent)

        vm.onAmountChanged("")
        vm.onSaveBudget()
        harness.settle()
        assertEquals("请输入预算金额", vm.state.value.fieldError)

        vm.onAmountChanged("-100")
        vm.onSaveBudget()
        harness.settle()
        assertEquals("预算必须大于 0 元", vm.state.value.fieldError)

        vm.onAmountChanged("10000000")
        vm.onSaveBudget()
        harness.settle()
        assertEquals("预算不能超过 9,999,999.99 元", vm.state.value.fieldError)
        assertNull(harness.budget(ManagementHarness.OCT_2026).budgetCent)

        vm.onAmountChanged("12.345")
        vm.onSaveBudget()
        harness.settle()
        assertEquals("金额最多两位小数", vm.state.value.fieldError)
    }

    @Test
    fun `数据层失败不得当作保存成功`() {
        harness.repository.failNextWrite = LedgerError.Storage("保存失败，内容已保留，请重试")
        val vm = viewModel()
        vm.onStartEdit()
        vm.onAmountChanged("2000")
        vm.onSaveBudget()
        harness.settle()

        assertEquals("保存失败，内容已保留，请重试", vm.state.value.fieldError)
        assertNull(harness.budget(ManagementHarness.OCT_2026).budgetCent)
    }

    // ───────────────────────── 移除 ─────────────────────────

    @Test
    fun `移除预算必须先确认`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()
        val vm = viewModel()

        vm.onRequestRemove()
        assertTrue(vm.state.value.showRemoveConfirm)
        assertNotNull("未确认前不得移除", harness.budget(ManagementHarness.OCT_2026).budgetCent)

        vm.onCancelRemove()
        harness.settle()
        assertFalse(vm.state.value.showRemoveConfirm)
        assertNotNull(harness.budget(ManagementHarness.OCT_2026).budgetCent)

        vm.onRequestRemove()
        vm.onConfirmRemove()
        harness.settle()
        assertNull(harness.budget(ManagementHarness.OCT_2026).budgetCent)
        assertEquals(BudgetStatus.UNSET, vm.state.value.budget!!.status)
    }

    @Test
    fun `移除失败时预算保留并提示原因`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.repository.failNextWrite = LedgerError.Storage("写入失败，请重试")
        harness.settle()
        val vm = viewModel()

        vm.onRequestRemove()
        vm.onConfirmRemove()
        harness.settle()

        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("写入失败，请重试", vm.state.value.banner!!.message)
        assertNotNull("移除失败时预算必须保留", harness.budget(ManagementHarness.OCT_2026).budgetCent)
    }

    // ───────────────────────── 沿用上月（显式触发） ─────────────────────────

    @Test
    fun `不自动继承上月预算`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.SEP_2026, 300_000L) }
        harness.settle()
        val vm = viewModel(ManagementHarness.OCT_2026)

        assertNull("打开页面不得自动写入十月预算", harness.budget(ManagementHarness.OCT_2026).budgetCent)
        assertFalse("未设置月份应处于可输入状态", vm.state.value.isSet)
        assertEquals(300_000L, vm.state.value.previousMonthBudgetCent)
    }

    @Test
    fun `点击沿用上月才会填入金额`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.SEP_2026, 300_000L) }
        harness.settle()
        val vm = viewModel(ManagementHarness.OCT_2026)

        vm.onUsePreviousMonth()
        harness.settle()
        assertTrue(vm.state.value.editing)
        assertEquals("3,000.00", vm.state.value.amountText)
        assertNull("沿用只是填入输入框，未确认前不落库", harness.budget(ManagementHarness.OCT_2026).budgetCent)

        vm.onSaveBudget()
        harness.settle()
        assertEquals(300_000L, harness.budget(ManagementHarness.OCT_2026).budgetCent)
    }

    @Test
    fun `上月没有预算时沿用给出原因`() {
        val vm = viewModel(ManagementHarness.OCT_2026)
        vm.onUsePreviousMonth()
        harness.settle()
        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("上月（2026 年 9 月）没有设置预算", vm.state.value.banner!!.message)
    }

    // ───────────────────────── 预算不阻止记账 ─────────────────────────

    @Test
    fun `超支不阻止保存支出`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.seedExpense(amountCent = 250_000L, month = ManagementHarness.OCT_2026)
        harness.settle()

        val result = runBlocking {
            harness.repository.createTransaction(
                TransactionDraft(
                    type = TransactionType.EXPENSE,
                    amountCent = 10_000L,
                    categoryId = "cat_expense_food",
                    accountId = "acc_default",
                    occurredOn = ManagementHarness.TODAY,
                    note = "超支后继续记账",
                ),
                "budget-does-not-block",
            )
        }
        assertTrue("预算不限制记账：$result", result is SaveResult.Success)
        assertEquals(BudgetStatus.EXCEEDED, viewModel().state.value.budget!!.status)
    }
}
