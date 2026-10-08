package com.blueledger.app.acceptance

import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.CategorySlice
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MonthAnalysis
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.YearAnalysis
import com.blueledger.app.core.model.YearMonthAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToLong

/**
 * 冻结契约的投影语义验收 —— **纯 JVM**，只依赖 core/model 的公共数据类。
 *
 * 这一层是 A7 可以独立于 A1/A2 交付进度立即给出结论的部分：
 * 「同一个事实源、同一套口径」里的**口径**本身必须先站得住。
 * 用 §12 的固定数字驱动 [MoneySummary] / [BudgetState] / [MonthAnalysis] / [YearAnalysis]，
 * 验算公式实现与文档一致，并暴露 UI 直接消费契约字段时的取值陷阱。
 */
class ContractProjectionAcceptanceTest {

    // ───────────────────────── MoneySummary ─────────────────────────

    @Test
    fun `月摘要按金额分精确聚合且结余可为负`() {
        val oct = MoneySummary(
            incomeCent = Expect.OCT_INCOME_CENT,
            expenseCent = Expect.OCT_EXPENSE_CENT,
            count = Expect.OCT_COUNT,
        )
        assertCent(Expect.OCT_BALANCE_CENT, oct.balanceCent, "十月结余")
        assertEquals("十月笔数", Expect.OCT_COUNT, oct.count)

        val sep = MoneySummary(Expect.SEP_INCOME_CENT, Expect.SEP_EXPENSE_CENT, 2)
        assertCent(Expect.SEP_BALANCE_CENT, sep.balanceCent, "九月结余")

        val year = MoneySummary(
            incomeCent = Expect.YEAR_INCOME_CENT,
            expenseCent = Expect.YEAR_EXPENSE_CENT,
            count = 9,
        )
        assertCent(Expect.YEAR_BALANCE_CENT, year.balanceCent, "年度结余")

        assertCent("空摘要", 0L, MoneySummary.EMPTY.balanceCent)
        assertEquals("空摘要笔数", 0, MoneySummary.EMPTY.count)

        val negative = MoneySummary(incomeCent = 0L, expenseCent = Expect.OCT_EXPENSE_CENT, count = 1)
        assertCent("负结余必须如实为负", -Expect.OCT_EXPENSE_CENT, negative.balanceCent)
        assertTrue("负结余不能被夹到 0", negative.balanceCent < 0)
    }

    @Test
    fun `A15 零点一元与零点二元精确合计零点三元`() {
        val summary = MoneySummary(
            incomeCent = 0L,
            expenseCent = Expect.A15_FIRST_CENT + Expect.A15_SECOND_CENT,
            count = 2,
        )
        assertCent(Expect.A15_TOTAL_CENT, summary.expenseCent, "0.10 + 0.20")
        assertEquals("0.30 元", "0.30", Cent.toYuanText(summary.expenseCent))
        assertCent("不产生浮点误差", 30L, summary.expenseCent)
    }

    @Test
    fun `大额累加仍用 64 位整数不溢出`() {
        val max = Limits.MAX_TRANSACTION_CENT
        val count = 100_000L
        val total = max * count
        assertTrue("10 万笔上限金额仍在 Long 范围内", total > 0L)
        assertEquals("精确乘法", 99_999_999_900_000L, total)
        assertCent(
            "期初 + 全年结余不会因加减法溢出",
            Expect.ALL_ACCOUNTS_BALANCE_CENT,
            Fx.TOTAL_OPENING_CENT + Expect.YEAR_BALANCE_CENT,
        )
    }

    // ───────────────────────── BudgetState ─────────────────────────

    @Test
    fun `十月预算状态与 §12 精确值一致`() {
        val state = BudgetState(
            yearMonth = Fx.OCT_2026,
            budgetCent = Fx.OCT_BUDGET_CENT,
            usedCent = Expect.OCT_EXPENSE_CENT,
        )
        assertTrue("预算已设置", state.isSet)
        assertCent("预算剩余", Expect.OCT_BUDGET_REMAINING_CENT, state.remainingCent!!)
        assertNull("未超支不得给出超支金额", state.exceededCent)
        assertEquals("使用率万分比 = 76.97%", Expect.OCT_BUDGET_RATIO_BASIS_POINT, state.ratioBasisPoint)
        assertEquals("状态：正常（<80%）", BudgetStatus.NORMAL, state.status)
        assertNotNull("进度条比例存在", state.progressFraction)
        // 精确值以整数万分比为准（7697）；progressFraction 是 Float 派生展示量，
        // 其精度上限约 1e-7，因此按 Float 容差比较，不放宽业务口径。
        assertEquals("使用率整数万分比是唯一权威值", Expect.OCT_BUDGET_PROGRESS_FRACTION_MILLI.toLong(), state.ratioBasisPoint)
        assertEquals(
            "进度条比例 = 7697/10000",
            0.7697,
            state.progressFraction!!.toDouble(),
            1e-6,
        )
        assertTrue("进度条不得超过 100%", state.progressFraction!! <= 1.0f)
    }

    @Test
    fun `预算一位小数展示必须四舍五入为 77 点 0 而不是 76 点 9`() {
        val state = BudgetState(Fx.OCT_2026, Fx.OCT_BUDGET_CENT, Expect.OCT_EXPENSE_CENT)
        val basis = requireNotNull(state.ratioBasisPoint) { "已设置预算必须有使用率" }

        // 需求口径（docs/AI开发提示词.md §12）：76.97%，一位小数展示为 77.0%。
        val oneDecimalPermille = (basis + 5L) / 10L // 万分比 → 一位小数（千分比），四舍五入
        assertEquals("76.97% 的一位小数展示", Expect.OCT_BUDGET_RATIO_PERMILLE_DISPLAY.toLong(), oneDecimalPermille)

        assertEquals(
            "BudgetState.ratioPermille 是「万分比截断」而不是「一位小数四舍五入」：" +
                "本月为 $basis 万分比（76.97%），截断得到 ${state.ratioPermille}（76.9%），" +
                "而 §12 要求展示 77.0%。" +
                "处理方式二选一（不许悄悄放宽本断言）：" +
                "(a) 数据层/契约把 ratioPermille 改成对一位小数四舍五入（本断言转绿）；" +
                "(b) UI 一律从 ratioBasisPoint 自行格式化为一位小数，" +
                "同时 A7 在阶段二改为断言界面上实际显示的比例文本，并删除本行对 ratioPermille 的直接依赖。",
            Expect.OCT_BUDGET_RATIO_PERMILLE_DISPLAY,
            state.ratioPermille,
        )
    }

    @Test
    fun `预算 80 100 125 百分比与进度条上限`() {
        val near = BudgetState(Fx.OCT_2026, Expect.C7_BUDGET_CENT, Expect.C7_NEAR_LIMIT_EXPENSE_CENT)
        assertEquals("1600/2000 = 80% 判为接近预算", BudgetStatus.NEAR_LIMIT, near.status)
        assertCent("80% 剩余 400.00", 40_000L, near.remainingCent!!)
        assertNull("80% 未超支", near.exceededCent)
        assertEquals("80.0%", 800, near.ratioPermille)
        assertEquals("进度条 0.8（Float 容差，权威值看整数万分比 8000）", 0.8, near.progressFraction!!.toDouble(), 1e-6)
        assertEquals("80% 的整数万分比", 8_000L, near.ratioBasisPoint)

        val exhausted = BudgetState(Fx.OCT_2026, Expect.C7_BUDGET_CENT, Expect.C7_EXHAUSTED_EXPENSE_CENT)
        assertEquals("2000/2000 判为已用完", BudgetStatus.EXHAUSTED, exhausted.status)
        assertCent("用完时剩余 0", 0L, exhausted.remainingCent!!)
        assertNull("刚好用完不算超支", exhausted.exceededCent)
        assertEquals("100.0%", 1_000, exhausted.ratioPermille)
        assertEquals("进度条 1.0", 1.0, exhausted.progressFraction!!.toDouble(), 1e-9)

        val exceeded = BudgetState(Fx.OCT_2026, Expect.C6_BUDGET_CENT, Expect.C6_EXPENSE_CENT)
        assertEquals("2500/2000 判为超支", BudgetStatus.EXCEEDED, exceeded.status)
        assertCent("超支 500.00", Expect.C6_EXCEEDED_CENT, exceeded.exceededCent!!)
        assertCent("剩余为负", -Expect.C6_EXCEEDED_CENT, exceeded.remainingCent!!)
        assertEquals("真实使用率 125.00%", Expect.C6_RATIO_BASIS_POINT, exceeded.ratioBasisPoint)
        assertEquals("文字保留真实 125.0%", Expect.C6_RATIO_PERMILLE_DISPLAY, exceeded.ratioPermille)
        assertEquals("进度条最多填满 1.0，不溢出", 1.0, exceeded.progressFraction!!.toDouble(), 1e-9)
        assertTrue("进度条不得大于 1", exceeded.progressFraction!! <= 1.0f)
    }

    @Test
    fun `未设置预算与零预算都不允许参与除法`() {
        val unset = BudgetState(Fx.SEP_2026, budgetCent = null, usedCent = Expect.SEP_EXPENSE_CENT)
        assertFalse("九月未设置预算", unset.isSet)
        assertEquals("未设置状态", BudgetStatus.UNSET, unset.status)
        assertNull("未设置时没有剩余", unset.remainingCent)
        assertNull("未设置时没有使用率，不能是 NaN", unset.ratioBasisPoint)
        assertNull("未设置时没有进度条", unset.progressFraction)
        assertNull("未设置时没有超支额", unset.exceededCent)

        val zero = BudgetState(Fx.SEP_2026, budgetCent = 0L, usedCent = Expect.SEP_EXPENSE_CENT)
        assertFalse("零预算不是有效预算", zero.isSet)
        assertEquals("零预算视为未设置", BudgetStatus.UNSET, zero.status)
        assertNull("零预算不做除法", zero.ratioBasisPoint)
        assertNull("零预算没有进度条", zero.progressFraction)
    }

    @Test
    fun `预算按月独立：修改十月不影响九月`() {
        val sep = BudgetState(Fx.SEP_2026, budgetCent = null, usedCent = Expect.SEP_EXPENSE_CENT)
        val oct = BudgetState(Fx.OCT_2026, budgetCent = Fx.OCT_BUDGET_CENT, usedCent = Expect.OCT_EXPENSE_CENT)
        assertEquals("九月仍未设置", BudgetStatus.UNSET, sep.status)
        assertEquals("十月已设置", BudgetStatus.NORMAL, oct.status)
        assertEquals("预算月份键", YearMonth.of(2026, 10), oct.yearMonth)
    }

    // ───────────────────────── MonthAnalysis / 分类占比 ─────────────────────────

    /** §12 十月支出分类占比（percent × 10，四舍五入）的规范值，供 A1/A4 对齐。 */
    @Test
    fun `十月支出分类占比的规范值可自行验算`() {
        val total = Expect.OCT_EXPENSE_CENT
        fun permille(part: Long): Int = ((part * 1_000.0) / total).roundToLong().toInt()

        assertEquals("餐饮 28.80/3078.80", 9, permille(Expect.OCT_FOOD_CENT))
        assertEquals("交通 50.00/3078.80", 16, permille(Expect.OCT_TRANSPORT_CENT))
        assertEquals("住房 3000.00/3078.80", 974, permille(Expect.OCT_HOUSING_CENT))
        assertEquals(
            "三项之和 999‰：单项四舍五入后展示合计可能显示 99.9%，" +
                "PRD §6.2 允许（不篡改账单凑整）；若要强制 100% 必须全项目统一最大余数法",
            999,
            permille(Expect.OCT_FOOD_CENT) + permille(Expect.OCT_TRANSPORT_CENT) + permille(Expect.OCT_HOUSING_CENT),
        )

        val totalIncome = Expect.OCT_INCOME_CENT
        fun incomePermille(part: Long): Int = ((part * 1_000.0) / totalIncome).roundToLong().toInt()
        assertEquals("工资 10000.00/10800.00", 926, incomePermille(1_000_000L))
        assertEquals("兼职 800.00/10800.00", 74, incomePermille(80_000L))
        assertEquals("十月收入占比合计 1000‰", 1_000, incomePermille(1_000_000L) + incomePermille(80_000L))
    }

    @Test
    fun `月度分析投影的汇总与日趋势口径一致`() {
        val octSlices = listOf(
            CategorySlice(Fx.CAT_HOUSING, "居住", "housing", Expect.OCT_HOUSING_CENT, 974),
            CategorySlice(Fx.CAT_TRANSPORT, "交通", "transport", Expect.OCT_TRANSPORT_CENT, 16),
            CategorySlice(Fx.CAT_FOOD, "餐饮", "restaurant", Expect.OCT_FOOD_CENT, 9),
        )
        val analysis = MonthAnalysis(
            month = Fx.OCT_2026,
            type = TransactionType.EXPENSE,
            incomeCent = Expect.OCT_INCOME_CENT,
            expenseCent = Expect.OCT_EXPENSE_CENT,
            count = Expect.OCT_COUNT,
            typeTotalCent = Expect.OCT_EXPENSE_CENT,
            daily = Expect.OCT_DAILY_EXPENSE_CENT.mapIndexed { index, cent ->
                DailyAmount(LocalDate.of(2026, 10, index + 1), cent)
            },
            slices = octSlices,
            periodEnd = Fx.TODAY,
        )

        assertCent("月结余", Expect.OCT_BALANCE_CENT, analysis.balanceCent)
        assertCent("支出模式总额 = 支出合计", Expect.OCT_EXPENSE_CENT, analysis.typeTotalCent)
        assertEquals("日趋势天数 = 1 日至今日", 7, analysis.daily.size)
        assertCent("日趋势之和 = 月支出", Expect.OCT_EXPENSE_CENT, analysis.daily.sumOf { it.amountCent })
        assertCent("分类切片之和 = 月支出", Expect.OCT_EXPENSE_CENT, analysis.slices.sumOf { it.amountCent })
        assertEquals("切片按金额降序（居住 > 交通 > 餐饮）", listOf("居住", "交通", "餐饮"), analysis.slices.map { it.name })
        assertEquals("切片绘图比例不越界", 0.974, analysis.slices.first().ratioFraction.toDouble(), 1e-6)
        assertFalse("切片比例不是 NaN", analysis.slices.first().ratioFraction.isNaN())

        val empty = MonthAnalysis(month = Fx.NOV_2026, type = TransactionType.EXPENSE)
        assertEquals("未来月日趋势为空", 0, empty.daily.size)
        assertNull("未来月没有统计截止日", empty.periodEnd)
        assertCent("零总额仍是 0 而不是 NaN", 0L, empty.typeTotalCent)
    }

    // ───────────────────────── YearAnalysis ─────────────────────────

    @Test
    fun `年度分析区分已到零记录月与未到月份`() {
        val months = (1..12).map { month ->
            when (month) {
                9 -> YearMonthAmount(month, incomeCent = Expect.SEP_INCOME_CENT, expenseCent = Expect.SEP_EXPENSE_CENT, reached = true, hasRecords = true)
                10 -> YearMonthAmount(month, incomeCent = Expect.OCT_INCOME_CENT, expenseCent = Expect.OCT_EXPENSE_CENT, reached = true, hasRecords = true)
                in 1..10 -> YearMonthAmount(month, reached = true, hasRecords = false)
                else -> YearMonthAmount(month, reached = false, hasRecords = false)
            }
        }
        val year = YearAnalysis(
            year = Fx.YEAR_2026,
            incomeCent = Expect.YEAR_INCOME_CENT,
            expenseCent = Expect.YEAR_EXPENSE_CENT,
            count = 9,
            months = months,
            cutoff = Fx.TODAY,
            isCurrentYear = true,
            reachedMonthCount = Expect.YEAR_2026_REACHED_MONTHS,
        )

        assertEquals("恒有 12 个月位置", 12, year.months.size)
        assertCent("年度结余", Expect.YEAR_BALANCE_CENT, year.balanceCent)
        assertEquals("当年截止日", LocalDate.of(2026, 10, 7), year.cutoff)
        assertTrue("当年截止日期早于 12-31（不是把整个自然年算满）", year.cutoff!!.isBefore(LocalDate.of(2026, 12, 31)))
        assertEquals("分母 = 已进入月份数 10（含无记录的已到月份）", 10, year.reachedMonthCount)
        assertCent("截至目前月均支出 = 3178.80/10", Expect.YEAR_2026_AVG_MONTHLY_EXPENSE_FLOOR_CENT, year.averageMonthlyExpenseCent!!)

        val nov = year.months.first { it.month == 11 }
        assertFalse("11 月未到", nov.reached)
        assertCent("未到月份金额为 0", 0L, nov.expenseCent)
        assertFalse("11 月不得被记为已到零记录月", nov.hasRecords)
        val mar = year.months.first { it.month == 3 }
        assertTrue("3 月已到", mar.reached)
        assertFalse("3 月已到但无记录（与未到月份不是同一状态）", mar.hasRecords)
        assertTrue("已到月份与未到月份的 reached 标志必须不同", mar.reached != nov.reached)

        assertEquals(
            "年度合计 = 12 个月之和（本月为 0 的月份不产生虚假金额）",
            Expect.YEAR_EXPENSE_CENT,
            year.months.sumOf { it.expenseCent },
        )
    }

    @Test
    fun `历史年分母为 12 且月均可计算`() {
        val months = (1..12).map { YearMonthAmount(it, reached = true, hasRecords = it == 1, expenseCent = if (it == 1) 120_000L else 0L) }
        val historical = YearAnalysis(
            year = 2025,
            incomeCent = 0L,
            expenseCent = 120_000L,
            count = 1,
            months = months,
            cutoff = LocalDate.of(2025, 12, 31),
            isCurrentYear = false,
            reachedMonthCount = 12,
        )
        assertEquals("历史年分母 12", 12, historical.reachedMonthCount)
        assertCent("月均支出 1200.00/12", 10_000L, historical.averageMonthlyExpenseCent!!)
        assertFalse("历史年不是当年", historical.isCurrentYear)

        val noMonth = historical.copy(reachedMonthCount = 0)
        assertNull("分母为 0 时不得除零，返回 null", noMonth.averageMonthlyExpenseCent)
    }

    // ───────────────────────── §1 冲突裁决：撤销窗口与上限常量 ─────────────────────────

    @Test
    fun `关键业务常量与冲突裁决一致`() {
        assertEquals("单笔金额下限 1 分", 1L, Limits.MIN_TRANSACTION_CENT)
        assertEquals("单笔金额上限 999999999 分", 999_999_999L, Limits.MAX_TRANSACTION_CENT)
        assertEquals("期初余额绝对值上限 999999999 分", 999_999_999L, Limits.MAX_OPENING_BALANCE_CENT)
        assertEquals("金额最多两位小数", 2, Limits.MAX_AMOUNT_DECIMALS)
        assertEquals("备注最多 200 字符", 200, Limits.MAX_NOTE_LENGTH)
        assertEquals("分类名 1—12 字符", 1, Limits.MIN_CATEGORY_NAME_LENGTH)
        assertEquals("分类名上限 12", 12, Limits.MAX_CATEGORY_NAME_LENGTH)
        assertEquals("账户名 1—20 字符", 20, Limits.MAX_ACCOUNT_NAME_LENGTH)
        assertEquals(
            "撤销窗口 5 秒（§1 裁决：原型的 12 秒只是演示，不作正式规则）",
            5_000L,
            Limits.UNDO_WINDOW_MILLIS,
        )
    }
}
