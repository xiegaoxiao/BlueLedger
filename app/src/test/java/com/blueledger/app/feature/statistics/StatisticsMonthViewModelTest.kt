package com.blueledger.app.feature.statistics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Expect
import com.blueledger.app.acceptance.Fx
import com.blueledger.app.acceptance.assertCent
import com.blueledger.app.acceptance.assertCentList
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * S05 月度统计 ViewModel 单测（§12 十月夹具逐点验算）。
 *
 * 断言全部来自 A7 的 `Expect`：日支出 1—7 日 = 3000.00/0.30/50.00/0/0/0/28.50、
 * 日收入 = 10000/0/0/0/0/800/0；总额为 0 的空月份不出现假环图/NaN。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatisticsMonthViewModelTest {

    private lateinit var harness: StatisticsHarness

    @Before
    fun setUp() {
        harness = StatisticsHarness()
    }

    @Test
    fun `十月摘要与日趋势逐点正确`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.OCT)
        val panel = requireNotNull(viewModel.state.value.monthPanel)

        assertEquals(StatisticsHarness.OCT, panel.month)
        assertEquals(TransactionType.EXPENSE, panel.type)
        assertCent(Expect.OCT_INCOME_CENT, panel.incomeCent, "十月收入")
        assertCent(Expect.OCT_EXPENSE_CENT, panel.expenseCent, "十月支出")
        assertCent(Expect.OCT_BALANCE_CENT, panel.balanceCent, "十月结余")
        assertEquals(Expect.OCT_COUNT, panel.count)

        // 日趋势：当月 1 日 → 今日（2026-10-07），逐点补零
        assertEquals(7, panel.daily.size)
        assertEquals((1..7).map { LocalDate.of(2026, 10, it) }, panel.daily.map { it.date })
        assertCentList(Expect.OCT_DAILY_EXPENSE_CENT, panel.daily.map { it.amountCent }, "十月日支出 1—7 日")
        assertEquals(LocalDate.of(2026, 10, 7), panel.periodEnd)
        assertCent(300_000L, panel.maxDailyCent, "日趋势最大值")

        // 摘要与图表同源：类型总额 = 日趋势之和 = 排行之和
        assertCent(Expect.OCT_EXPENSE_CENT, panel.typeTotalCent, "支出分析总额")
        assertCent(panel.typeTotalCent, panel.daily.sumOf { it.amountCent }, "日趋势合计")
        assertCent(panel.typeTotalCent, panel.slices.sumOf { it.amountCent }, "排行合计")
    }

    @Test
    fun `十月分类切片按金额降序且占比用仓库千分比`() {
        harness.seedAcceptance()
        val panel = requireNotNull(harness.viewModel(initialYearMonth = StatisticsHarness.OCT).state.value.monthPanel)

        assertEquals(
            listOf(Fx.CAT_HOUSING, Fx.CAT_TRANSPORT, Fx.CAT_FOOD),
            panel.slices.map { it.categoryId },
        )
        assertCent(Expect.OCT_HOUSING_CENT, panel.slices[0].amountCent, "居住")
        assertCent(Expect.OCT_TRANSPORT_CENT, panel.slices[1].amountCent, "交通")
        assertCent(Expect.OCT_FOOD_CENT, panel.slices[2].amountCent, "餐饮")
        assertEquals(974, panel.slices[0].ratioPermille)
        assertEquals(16, panel.slices[1].ratioPermille)
        assertEquals(9, panel.slices[2].ratioPermille)
        assertEquals(3, panel.ringSlices.size)
        assertTrue(panel.ringSlices.all { it.drillable })
    }

    @Test
    fun `类型切换同时改变环图日趋势与排行`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.OCT)

        viewModel.onTypeSelected(TransactionType.INCOME)
        harness.settle()
        val panel = requireNotNull(viewModel.state.value.monthPanel)

        assertEquals(TransactionType.INCOME, panel.type)
        assertCent(Expect.OCT_INCOME_CENT, panel.typeTotalCent, "收入分析总额")
        assertCentList(Expect.OCT_DAILY_INCOME_CENT, panel.daily.map { it.amountCent }, "十月日收入 1—7 日")
        assertEquals(
            listOf(Fx.CAT_SALARY, Fx.CAT_PART_TIME),
            panel.slices.map { it.categoryId },
        )
        assertCent(1_000_000L, panel.slices[0].amountCent, "工资")
        assertCent(80_000L, panel.slices[1].amountCent, "兼职")

        // 环图与排行都必须来自同一份分析结果
        assertCent(panel.typeTotalCent, panel.ringSlices.sumOf { it.amountCent }, "环图合计")
        assertCent(panel.typeTotalCent, panel.daily.sumOf { it.amountCent }, "日趋势合计")
        assertCent(panel.typeTotalCent, panel.slices.sumOf { it.amountCent }, "排行合计")

        // 切回支出后三项同时回到支出口径
        viewModel.onTypeSelected(TransactionType.EXPENSE)
        harness.settle()
        val back = requireNotNull(viewModel.state.value.monthPanel)
        assertCentList(Expect.OCT_DAILY_EXPENSE_CENT, back.daily.map { it.amountCent }, "切回支出后的日趋势")
        assertCent(Expect.OCT_EXPENSE_CENT, back.ringSlices.sumOf { it.amountCent }, "切回支出后的环图")
    }

    @Test
    fun `历史月补齐整月且九月数据正确`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.OCT)
        viewModel.onPickMonth(StatisticsHarness.SEP)
        harness.settle()
        val panel = requireNotNull(viewModel.state.value.monthPanel)

        assertEquals(StatisticsHarness.SEP, panel.month)
        assertCent(Expect.SEP_INCOME_CENT, panel.incomeCent, "九月收入")
        assertCent(Expect.SEP_EXPENSE_CENT, panel.expenseCent, "九月支出")
        assertCent(Expect.SEP_BALANCE_CENT, panel.balanceCent, "九月结余")
        assertEquals(2, panel.count)
        assertEquals(LocalDate.of(2026, 9, 30), panel.periodEnd)
        assertEquals("历史月必须补齐整月 30 天", 30, panel.daily.size)
        assertEquals(LocalDate.of(2026, 9, 30), panel.daily.last().date)
        assertCent(Expect.SEP_EXPENSE_CENT, panel.daily.last().amountCent, "9 月 30 日支出")
        assertEquals(0L, panel.daily.first().amountCent)
    }

    @Test
    fun `没有账单的月份显示空态而不是假环图`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.MAY)
        val panel = requireNotNull(viewModel.state.value.monthPanel)

        assertEquals(StatisticsHarness.MAY, panel.month)
        assertCent(0L, panel.typeTotalCent, "五月支出")
        assertEquals(0, panel.count)
        assertFalse(panel.hasTypeData)
        assertTrue("零数据不得生成分类切片", panel.slices.isEmpty())
        assertTrue("零数据不得生成环图扇区", panel.ringSlices.isEmpty())
        assertEquals("历史月补齐整月 31 天", 31, panel.daily.size)
        assertTrue("零记录日补 0", panel.daily.all { it.amountCent == 0L })
        assertCent(0L, panel.balanceCent, "五月结余")
        assertEquals("本月暂无支出", panel.emptyText())
        assertEquals(0L, panel.averageDailyCent)
        assertTrue(ringSweeps(panel.ringSlices).isEmpty())
    }

    @Test
    fun `未来月份不可选择也不可钻取`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.OCT)

        assertFalse("当前月不能再往后走", viewModel.state.value.canGoNextMonth)
        viewModel.onNextMonth()
        harness.settle()
        assertEquals(StatisticsHarness.OCT, viewModel.state.value.month)

        viewModel.onPickMonth(StatisticsHarness.NOV)
        harness.settle()
        assertEquals("未来月份必须被夹回当前月", StatisticsHarness.OCT, viewModel.state.value.month)

        assertFalse("未到月份不能打开月报", viewModel.onOpenMonthReport(StatisticsHarness.NOV))
        harness.settle()
        assertEquals(StatisticsHarness.OCT, viewModel.state.value.month)
        assertEquals(StatisticsTab.MONTH, viewModel.state.value.tab)

        assertTrue(viewModel.onOpenMonthReport(StatisticsHarness.SEP))
        harness.settle()
        assertEquals(StatisticsHarness.SEP, viewModel.state.value.month)
    }

    @Test
    fun `点按日趋势选中最近一天且越界索引被忽略`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.OCT)

        assertNull(viewModel.state.value.selectedDay)
        viewModel.onSelectDay(2)
        harness.settle()
        val selected = requireNotNull(viewModel.state.value.selectedDay)
        assertEquals(LocalDate.of(2026, 10, 3), selected.date)
        assertCent(5_000L, selected.amountCent, "10 月 3 日支出")

        viewModel.onSelectDay(99)
        harness.settle()
        assertEquals("越界索引不得改变选中项", 2, viewModel.state.value.selectedDayIndex)

        viewModel.onClearDaySelection()
        harness.settle()
        assertNull(viewModel.state.value.selectedDayIndex)
    }

    @Test
    fun `日均按已到天数计算且历史月按整月天数`() {
        harness.seedAcceptance()
        val october = requireNotNull(harness.viewModel(initialYearMonth = StatisticsHarness.OCT).state.value.monthPanel)
        assertEquals(7, october.daysInPeriod)
        assertEquals(307_880L / 7, october.averageDailyCent)

        val september = requireNotNull(harness.viewModel(initialYearMonth = StatisticsHarness.SEP).state.value.monthPanel)
        assertEquals(30, september.daysInPeriod)
        assertEquals(10_000L / 30, september.averageDailyCent)
    }

    @Test
    fun `月度与年度分段切换保留各自周期`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialYearMonth = StatisticsHarness.SEP, initialYear = 2026)

        assertNotNull(viewModel.state.value.monthPanel)
        viewModel.onTabSelected(StatisticsTab.YEAR)
        harness.settle()
        assertEquals(StatisticsTab.YEAR, viewModel.state.value.tab)
        assertEquals("切换到年度后月份选择保留", StatisticsHarness.SEP, viewModel.state.value.month)
        assertNotNull(viewModel.state.value.yearPanel)

        viewModel.onTabSelected(StatisticsTab.MONTH)
        harness.settle()
        assertEquals(StatisticsTab.MONTH, viewModel.state.value.tab)
        assertEquals(StatisticsHarness.SEP, viewModel.state.value.month)
    }

    @Test
    fun `初始参数缺省为设备当前月与当前年`() {
        val viewModel = harness.viewModel()
        assertEquals(StatisticsHarness.OCT, viewModel.state.value.month)
        assertEquals(StatisticsHarness.YEAR, viewModel.state.value.year)
        assertEquals(StatisticsTab.MONTH, viewModel.state.value.tab)
        assertEquals(TransactionType.EXPENSE, viewModel.state.value.type)
        assertEquals(StatisticsHarness.TODAY, viewModel.state.value.today)
    }
}
