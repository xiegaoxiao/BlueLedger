package com.blueledger.app.feature.statistics

import com.blueledger.app.acceptance.Expect
import com.blueledger.app.acceptance.assertCent
import com.blueledger.app.core.model.CategorySlice
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.YearAnalysis
import com.blueledger.app.core.model.YearMonthAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A4 纯 JVM 单测：格式化、坐标轴、占比与「前 5 类 + 其余分类」合并规则、12 项年度列语义。
 *
 * 不依赖 Robolectric，也不触碰任何 Compose 组件，只验证可精确断言的坐标/展示转换。
 */
class StatisticsTextTest {

    // ───────────────────────── 坐标轴 ─────────────────────────

    @Test
    fun `三千元坐标轴用元并取整数刻度`() {
        val scale = StatisticsText.axisScale(Expect.OCT_HOUSING_CENT)
        assertEquals(StatisticsText.AxisUnit.YUAN, scale.unit)
        assertEquals(listOf(0L, 100_000L, 200_000L, 300_000L), scale.ticks)
        assertEquals(
            listOf("0", "1,000", "2,000", "3,000"),
            scale.ticks.map { scale.label(it, hidden = false) },
        )
        assertEquals("元", scale.unitLabel)
    }

    @Test
    fun `一万元坐标轴缩写为万元且摘要仍保留准确金额`() {
        // 十月收入 10,800.00 元 → 轴上限取整到 1.5 万（与原型 S06 的 0/0.5/1.0/1.5 万一致）
        val scale = StatisticsText.axisScale(Expect.OCT_INCOME_CENT)
        assertEquals(StatisticsText.AxisUnit.WAN, scale.unit)
        assertEquals(1_500_000L, scale.topCent)
        assertEquals(
            listOf("0", "0.5万", "1.0万", "1.5万"),
            scale.ticks.map { scale.label(it, hidden = false) },
        )
        // 坐标轴缩写不影响摘要/明细口径：准确金额仍是 10,800.00
        assertEquals("10,800.00", StatisticsText.money(Expect.OCT_INCOME_CENT))
    }

    @Test
    fun `零数据坐标轴只有零基线不出现 NaN`() {
        val scale = StatisticsText.axisScale(0L)
        assertEquals(0L, scale.topCent)
        assertEquals(listOf(0L), scale.ticks)
        assertEquals("0", scale.label(0L, hidden = false))
        assertFalse(scale.ticks.any { it < 0L })
    }

    @Test
    fun `小额坐标轴保留两位小数不把三角钱写成零`() {
        val scale = StatisticsText.axisScale(30L) // 0.30 元
        assertEquals(StatisticsText.AxisUnit.YUAN, scale.unit)
        assertEquals(listOf(0L, 10L, 20L, 30L), scale.ticks)
        assertEquals(listOf("0", "0.10", "0.20", "0.30"), scale.ticks.map { scale.label(it, hidden = false) })
    }

    @Test
    fun `金额隐藏时坐标轴标签全部为掩码但刻度结构保留`() {
        val scale = StatisticsText.axisScale(Expect.OCT_EXPENSE_CENT)
        assertTrue(scale.ticks.size >= 2)
        assertTrue(scale.ticks.all { scale.label(it, hidden = true) == StatisticsText.MASK })
        assertFalse(scale.ticks.map { scale.label(it, hidden = true) }.any { MONEY_LEAK_PATTERN.containsMatchIn(it) })
    }

    // ───────────────────────── 金额与占比 ─────────────────────────

    @Test
    fun `金额与千分比格式化`() {
        assertEquals("3,078.80", StatisticsText.money(Expect.OCT_EXPENSE_CENT))
        assertEquals("0.30", StatisticsText.money(Expect.A15_TOTAL_CENT))
        assertEquals("97.4%", StatisticsText.ratio(974))
        assertEquals("0.9%", StatisticsText.ratio(9))
        assertEquals("100.0%", StatisticsText.ratio(1000))
    }

    @Test
    fun `分类占比按整数分四舍五入且零总额不除零`() {
        assertEquals(974, permilleOf(300_000L, 307_880L)) // 居住
        assertEquals(16, permilleOf(5_000L, 307_880L)) // 交通
        assertEquals(9, permilleOf(2_880L, 307_880L)) // 餐饮
        assertEquals(1000, permilleOf(307_880L, 307_880L)) // 单类 100%，不超 1000
        assertEquals(0, permilleOf(0L, 0L))
        assertEquals(0, permilleOf(1_000L, 0L))
        // 三项四舍五入之和允许是 999（PRD §6.2 明确允许，不篡改金额凑整）
        val sum = permilleOf(300_000L, 307_880L) + permilleOf(5_000L, 307_880L) + permilleOf(2_880L, 307_880L)
        assertEquals(999, sum)
    }

    // ───────────────────────── 环图合并规则 ─────────────────────────

    private fun slice(id: String, name: String, amountCent: Long, permille: Int) =
        CategorySlice(categoryId = id, name = name, iconKey = "ic_food", amountCent = amountCent, ratioPermille = permille)

    @Test
    fun `分类不超过五类时不合并`() {
        val slices = listOf(
            slice("housing", "居住", 300_000L, 974),
            slice("transport", "交通", 5_000L, 16),
            slice("food", "餐饮", 2_880L, 9),
        )
        val ring = buildRingSlices(slices, 307_880L)
        assertEquals(3, ring.size)
        assertEquals(listOf("居住", "交通", "餐饮"), ring.map { it.name })
        assertTrue(ring.all { it.drillable && !it.merged })
        assertEquals(listOf(974, 16, 9), ring.map { it.ratioPermille })
    }

    @Test
    fun `分类超过五类时图表合并尾部为其余分类`() {
        val slices = listOf(
            slice("a", "住房", 300_000L, 962),
            slice("b", "交通", 5_000L, 16),
            slice("c", "餐饮", 2_880L, 9),
            slice("d", "购物", 1_000L, 3),
            slice("e", "日用", 1_000L, 3),
            slice("f", "娱乐", 1_000L, 3),
            slice("g", "医疗", 1_000L, 3),
        )
        val total = slices.sumOf { it.amountCent }
        val ring = buildRingSlices(slices, total)

        assertEquals(6, ring.size)
        assertEquals(listOf("住房", "交通", "餐饮", "购物", "日用"), ring.take(5).map { it.name })
        val merged = ring.last()
        assertTrue("第 6 项必须是合并项", merged.merged)
        assertEquals(StatisticsText.MERGED_SLICE_NAME, merged.name)
        assertNull("合并项没有单一分类，不能钻取", merged.categoryId)
        assertFalse(merged.drillable)
        assertCent(2_000L, merged.amountCent, "合并项金额 = 尾部各项之和")
        assertEquals(permilleOf(2_000L, total), merged.ratioPermille)
        assertTrue("前 5 项仍是真实分类，可钻取", ring.take(5).all { it.drillable })

        // 完整排行不经过合并：仍然保留全部 7 个真实分类
        assertEquals(7, slices.size)
        assertTrue(slices.none { it.name == StatisticsText.MERGED_SLICE_NAME })
    }

    @Test
    fun `真实分类其他与合并的其余分类不混用`() {
        // 「其他」是第 4 大分类（在前 5 类之内，保持真实分类身份），尾部才合并出「其余分类」
        val amounts = listOf(5_000L, 4_000L, 3_000L, 2_000L, 1_000L, 900L, 800L)
        val total = amounts.sumOf { it }
        val slices = amounts.mapIndexed { index, amount ->
            slice("cat_$index", if (index == 3) "其他" else "分类$index", amount, permilleOf(amount, total))
        }
        val ring = buildRingSlices(slices, total)

        assertEquals(6, ring.size)
        assertEquals("其他", ring[3].name)
        assertEquals(StatisticsText.MERGED_SLICE_NAME, ring[5].name)
        assertNotEquals(ring[3].name, ring[5].name)
        assertTrue("真实「其他」是可钻取分类", ring[3].drillable)
        assertEquals("cat_3", ring[3].categoryId)
        assertFalse("合并项不可钻取", ring[5].drillable)
        assertCent(1_700L, ring[5].amountCent, "尾部 900 + 800 合并为其余分类")
        assertEquals(permilleOf(1_700L, total), ring[5].ratioPermille)
    }

    // ───────────────────────── 环图角度 ─────────────────────────

    @Test
    fun `环图角度按金额比例合计三百六十度`() {
        val slices = listOf(
            slice("housing", "居住", 300_000L, 974),
            slice("transport", "交通", 5_000L, 16),
            slice("food", "餐饮", 2_880L, 9),
        )
        val sweeps = ringSweeps(buildRingSlices(slices, 307_880L))
        assertEquals(360f, sweeps.sum(), 0.01f)
        assertTrue("最大分类占比最高", sweeps[0] > sweeps[1])
        assertTrue(sweeps.all { it >= 0f && it <= 360f })
    }

    @Test
    fun `环图零数据角度全为零不出现 NaN`() {
        val zeroSlices = listOf(slice("a", "餐饮", 0L, 0), slice("b", "交通", 0L, 0))
        val sweeps = ringSweeps(buildRingSlices(zeroSlices, 0L))
        assertEquals(2, sweeps.size)
        assertTrue(sweeps.all { it == 0f && !it.isNaN() })
        assertTrue(ringSweeps(emptyList()).isEmpty())
    }

    // ───────────────────────── 年度列与截止文案 ─────────────────────────

    @Test
    fun `年度列恒为十二项且缺失月份按未到处理`() {
        val analysis = YearAnalysis(
            year = 2026,
            months = listOf(
                YearMonthAmount(month = 1, incomeCent = 100L, expenseCent = 50L, reached = true, hasRecords = true),
            ),
            cutoff = LocalDate.of(2026, 1, 31),
            isCurrentYear = true,
            reachedMonthCount = 1,
        )
        val columns = buildYearColumns(analysis)
        assertEquals(12, columns.size)
        assertEquals((1..12).toList(), columns.map { it.month })
        assertTrue(columns[0].reached)
        assertTrue(columns[0].hasRecords)
        assertCent(100L, columns[0].incomeCent, "1 月收入")
        assertCent(50L, columns[0].balanceCent, "1 月结余")
        assertFalse("缺失月份不得被伪装成已到的零记录月", columns[1].reached)
        assertFalse(columns[1].hasRecords)
        assertFalse(columns[11].drillable)
    }

    @Test
    fun `年度截止文案当年显示截至日期历史年显示全年`() {
        val current = YearAnalysis(
            year = 2026,
            cutoff = LocalDate.of(2026, 10, 7),
            isCurrentYear = true,
            reachedMonthCount = 10,
        )
        assertEquals("截至 2026 年 10 月 7 日", StatisticsText.cutoffText(current))
        val history = YearAnalysis(year = 2025, cutoff = null, isCurrentYear = false, reachedMonthCount = 12)
        assertEquals("全年", StatisticsText.cutoffText(history))
    }

    @Test
    fun `日趋势读数含完整日期与准确金额`() {
        val point = DailyAmount(LocalDate.of(2026, 10, 3), 5_000L)
        assertEquals("2026 年 10 月 3 日", StatisticsText.fullDate(point.date))
        assertEquals("50.00", StatisticsText.money(point.amountCent))
        assertEquals("10 月 3 日", StatisticsText.day(point.date))
        assertEquals(StatisticsText.MASK, StatisticsText.amountText(point.amountCent, hidden = true))
    }
}
