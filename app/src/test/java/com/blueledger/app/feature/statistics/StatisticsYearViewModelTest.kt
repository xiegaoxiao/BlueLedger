package com.blueledger.app.feature.statistics

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Expect
import com.blueledger.app.acceptance.assertCent
import com.blueledger.app.core.model.StatisticsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * S06 年度统计 ViewModel 单测。
 *
 * 重点：`reached` 语义（11/12 月是未到月份，**不是 0**）、月均分母 = 已到月份数、
 * 未到月份不可选中/不可钻取、历史年 12 个月分母。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatisticsYearViewModelTest {

    private lateinit var harness: StatisticsHarness

    @Before
    fun setUp() {
        harness = StatisticsHarness()
    }

    @Test
    fun `二零二六年十二项到达语义与年度摘要正确`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR)
        val panel = requireNotNull(viewModel.state.value.yearPanel)

        assertEquals(12, panel.columns.size)
        assertEquals((1..12).toList(), panel.columns.map { it.month })
        assertEquals("已到月份 1—10", (1..10).toList(), panel.columns.filter { it.reached }.map { it.month })
        assertEquals("11/12 月是未到月份", listOf(11, 12), panel.columns.filter { !it.reached }.map { it.month })

        // 未到月份不是 0：金额字段为 0 但 reached=false，且没有记录标记
        panel.columns.filter { !it.reached }.forEach { column ->
            assertFalse("${column.month} 月不是已到月份", column.hasRecords)
            assertEquals(0L, column.incomeCent)
            assertEquals(0L, column.expenseCent)
            assertFalse("未到月份不可钻取", column.drillable)
        }

        assertCent(Expect.YEAR_INCOME_CENT, panel.incomeCent, "年度收入")
        assertCent(Expect.YEAR_EXPENSE_CENT, panel.expenseCent, "年度支出")
        assertCent(Expect.YEAR_BALANCE_CENT, panel.balanceCent, "年度结余")
        assertEquals(9, panel.count)
        assertEquals(LocalDate.of(2026, 10, 7), panel.cutoff)
        assertTrue(panel.isCurrentYear)
        assertEquals("截至 2026 年 10 月 7 日", StatisticsText.cutoffText(panel.cutoff))
    }

    @Test
    fun `月均分母是已到月份数十一个月不计入`() {
        harness.seedAcceptance()
        val panel = requireNotNull(
            harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR).state.value.yearPanel,
        )
        assertEquals(Expect.YEAR_2026_REACHED_MONTHS, panel.reachedMonthCount)
        assertCent(
            Expect.YEAR_2026_AVG_MONTHLY_EXPENSE_FLOOR_CENT,
            requireNotNull(panel.averageMonthlyExpenseCent),
            "截至目前月均支出（分母 10）",
        )
        assertTrue(
            "若误用 12 做分母会得到 ${Expect.YEAR_EXPENSE_CENT / 12}，必须不是它",
            panel.averageMonthlyExpenseCent != Expect.YEAR_EXPENSE_CENT / 12,
        )
    }

    @Test
    fun `已到月份明细含九月金额与已到的零记录月`() {
        harness.seedAcceptance()
        val panel = requireNotNull(
            harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR).state.value.yearPanel,
        )

        val september = panel.columns.first { it.month == 9 }
        assertTrue(september.reached)
        assertTrue(september.hasRecords)
        assertCent(Expect.SEP_INCOME_CENT, september.incomeCent, "9 月收入")
        assertCent(Expect.SEP_EXPENSE_CENT, september.expenseCent, "9 月支出")
        assertCent(Expect.SEP_BALANCE_CENT, september.balanceCent, "9 月结余")

        val august = panel.columns.first { it.month == 8 }
        assertTrue("8 月已到", august.reached)
        assertFalse("8 月没有记录但仍然是已到的 0", august.hasRecords)
        assertCent(0L, august.expenseCent, "8 月支出为 0")
        assertCent(0L, august.incomeCent, "8 月收入为 0")
    }

    @Test
    fun `未到月份不可选中也不可打开月报`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR)

        viewModel.onSelectMonth(11)
        harness.settle()
        assertNull("未到月份不得被选中", viewModel.state.value.selectedMonth)

        viewModel.onSelectMonth(9)
        harness.settle()
        assertEquals(9, viewModel.state.value.selectedMonth)
        val column = requireNotNull(viewModel.state.value.selectedYearColumn)
        assertEquals(9, column.month)

        assertFalse("未到月份不能进入月报", viewModel.onOpenMonthReport(StatisticsHarness.NOV))
        harness.settle()
        assertEquals(StatisticsTab.YEAR, viewModel.state.value.tab)

        assertTrue(viewModel.onOpenMonthReport(StatisticsHarness.SEP))
        harness.settle()
        val state = viewModel.state.value
        assertEquals("年度点月份 → 切换月度统计", StatisticsTab.MONTH, state.tab)
        assertEquals("保留该年份下的月份", StatisticsHarness.SEP, state.month)
        assertEquals(StatisticsHarness.YEAR, state.year)
    }

    @Test
    fun `历史年十二个月都是已到且分母为十二`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR)

        viewModel.onPickYear(2025)
        harness.settle()
        val panel = requireNotNull(viewModel.state.value.yearPanel)

        assertEquals(2025, panel.year)
        assertEquals(12, panel.columns.size)
        assertTrue("历史年全部为已到月份", panel.columns.all { it.reached })
        assertEquals(12, panel.reachedMonthCount)
        assertFalse(panel.isCurrentYear)
        assertNull("历史年没有截止日", panel.cutoff)
        assertEquals("全年", StatisticsText.cutoffText(panel.cutoff))
        assertCent(0L, requireNotNull(panel.averageMonthlyExpenseCent), "2025 年无记录，月均为 0")
        assertCent(0L, panel.expenseCent, "2025 年支出")
        assertTrue(panel.columns.all { it.drillable })
    }

    @Test
    fun `年份不可进入未来且历史年可回看`() {
        harness.seedAcceptance()
        val viewModel = harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR)

        assertFalse("当前年不能再看下一年", viewModel.state.value.canGoNextYear)
        viewModel.onNextYear()
        harness.settle()
        assertEquals(StatisticsHarness.YEAR, viewModel.state.value.year)

        viewModel.onPickYear(2027)
        harness.settle()
        assertEquals("未来年份必须被夹回当前年", StatisticsHarness.YEAR, viewModel.state.value.year)

        viewModel.onPreviousYear()
        harness.settle()
        assertEquals(2025, viewModel.state.value.year)
        assertEquals(2025, requireNotNull(viewModel.state.value.yearPanel).year)

        assertTrue(viewModel.state.value.canGoNextYear)
        viewModel.onNextYear()
        harness.settle()
        assertEquals(StatisticsHarness.YEAR, viewModel.state.value.year)
    }

    @Test
    fun `年图最大值用于坐标轴且未到月份不参与`() {
        harness.seedAcceptance()
        val panel = requireNotNull(
            harness.viewModel(initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR).state.value.yearPanel,
        )
        // 2026 年最大单月数值 = 10 月的收入合计 10,800.00（T3 10,000.00 + T8 800.00）
        assertCent(Expect.OCT_INCOME_CENT, panel.maxMonthlyCent, "年图最大值（十月收入）")
        val scale = StatisticsText.axisScale(panel.maxMonthlyCent)
        assertEquals(StatisticsText.AxisUnit.WAN, scale.unit)
        assertEquals("上限取整到 1.5 万，与原型 S06 的 0/0.5/1.0/1.5 万一致", 1_500_000L, scale.topCent)
        assertTrue(panel.hasAnyRecords)
    }
}
