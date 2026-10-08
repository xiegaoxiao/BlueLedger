package com.blueledger.app.feature.statistics

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Fx
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.YearMonth

/**
 * S05/S06 的真实 Compose 渲染测试（Robolectric + 真实布局，读取真实节点 bounds）。
 *
 * 覆盖 A4 的硬性验收点：
 * - 十月夹具的日趋势逐点（含补零）在图表明细里可读；
 * - 点按图表**任意横向位置**都会命中最近一天（不是只有圆点像素可点）；
 * - 年柱每个月份列宽/高 ≥48dp，整列是触控目标；
 * - 未到月份是占位、不可点击、不显示 0；
 * - 分类 >5 时图表合并「其余分类」而完整排行保留全部真实分类；
 * - 总量为 0 时是空心占位，不出现百分比；
 * - 金额隐藏时任何文本/语义描述都不含金额与百分比。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StatisticsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: StatisticsHarness

    @Before
    fun setUp() {
        // **必须自动推进帧时钟**：
        // 1. `performScrollTo()` 走 `verticalScroll` 的 ScrollBy 语义动作，是一次**带动画**的滚动；
        //    帧时钟不推进时它永远不返回（实测挂死，见交接报告 §8.5）；
        // 2. 触摸注入（`performTouchInput { click(...) }`）的手势识别协程同样挂在 UI 调度器上。
        // 之所以现在可以安全地自动推进：统计页的「加载态无限动画」根因已从产品侧修掉
        // （首帧前数据就绪，`LedgerLoadingState` 不再出现在测试路径上），因此 `waitForIdle` 会收敛。
        composeRule.mainClock.autoAdvance = true
        harness = StatisticsHarness()
    }

    // ───────────────────────── S05 ─────────────────────────

    @Test
    fun `月度页显示十月摘要与逐日明细`() {
        harness.seedAcceptance()
        composeRule.setContent { StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.OCT) }
        composeRule.awaitTag(StatisticsTags.MONTH_LABEL)

        composeRule.onNodeWithTag(StatisticsTags.SUMMARY_CARD, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.SUMMARY_COUNT, useUnmergedTree = true)
            .assert(hasText("共 7 笔账单", substring = true))

        // 日趋势明细逐点：1 日 3000.00、2 日 0.30、3 日 50.00、4 日 0.00、7 日 28.50
        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(1), useUnmergedTree = true)
            .assert(hasContentDescription("3,000.00", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(2), useUnmergedTree = true)
            .assert(hasContentDescription("0.30", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(3), useUnmergedTree = true)
            .assert(hasContentDescription("50.00", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(4), useUnmergedTree = true)
            .assert(hasContentDescription("0.00", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(7), useUnmergedTree = true)
            .assert(hasContentDescription("28.50", substring = true))

        // 分类排行：住房（3,000.00，974‰）第一，且完整保留三类
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_HOUSING), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_TRANSPORT), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_FOOD), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_HOUSING), useUnmergedTree = true)
            .assert(hasContentDescription("占 97.4%", substring = true))
    }

    @Test
    fun `日趋势点按任意横向位置命中最近一天`() {
        harness.seedAcceptance()
        composeRule.setContent { StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.OCT) }
        composeRule.awaitTag(StatisticsTags.TREND_CHART)

        // 最左侧 → 10 月 1 日（3,000.00）
        tapTrendChart(0.01f)
        awaitText("2026 年 10 月 1 日", "点按图表最左侧应选中 1 日")
        awaitText("3,000.00", "读数必须含准确金额")

        // 最右侧 → 10 月 7 日（28.50）
        tapTrendChart(0.99f)
        awaitText("2026 年 10 月 7 日", "点按图表最右侧应选中 7 日")
        awaitText("28.50", "读数必须含准确金额")

        // 中间位置也必须有命中（证明整个绘图区可点，而不是只有数据点像素）
        tapTrendChart(0.5f)
        awaitText("2026 年 10 月 4 日", "点按中部应命中第 4 天（含补零日）")
    }

    /** 在趋势图的**自身坐标**上按横向比例点击；坐标来自节点尺寸，不写死像素。 */
    private fun tapTrendChart(fractionX: Float) {
        val chart = composeRule.onNodeWithTag(StatisticsTags.TREND_CHART, useUnmergedTree = true)
        chart.performScrollTo()
        chart.performTouchInput {
            click(Offset(width * fractionX.coerceIn(0f, 1f), height / 2f))
        }
        composeRule.waitForIdle()
    }

    /** 等待某段文字出现（`waitUntil` 自带超时，绝不做无界等待）。 */
    private fun awaitText(text: String, message: String) {
        composeRule.waitUntil(TEXT_WAIT_MILLIS) {
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "$message（应出现「$text」）",
            composeRule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `分类排行点击按同月同类型同分类钻取`() {
        harness.seedAcceptance()
        composeRule.setContent { StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.OCT) }
        composeRule.awaitTag(StatisticsTags.rankingRow(Fx.CAT_HOUSING))

        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_HOUSING), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.pump()

        assertEquals(1, harness.drillDowns.size)
        val seed = harness.drillDowns.single()
        assertEquals(StatisticsHarness.OCT, seed.yearMonth)
        assertEquals(TransactionType.EXPENSE, seed.type)
        assertEquals(Fx.CAT_HOUSING, seed.categoryId)
        assertNull(seed.accountId)
        assertTrue(harness.entryLaunches.isEmpty())
    }

    @Test
    fun `分类超过五类时图表合并其余分类而完整排行保留全部`() {
        harness.seedAcceptance()
        listOf(
            "cat_expense_shopping",
            "cat_expense_daily",
            "cat_expense_entertainment",
            "cat_expense_medical",
        ).forEachIndexed { index, categoryId ->
            harness.seedExpense(1_000L, LocalDate.of(2026, 10, 4), categoryId, note = "extra-$index")
        }
        composeRule.setContent { StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.OCT) }
        composeRule.awaitTag(StatisticsTags.RING_CHART)

        composeRule.onNodeWithTag(StatisticsTags.LEGEND_MERGED, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.LEGEND_MERGED, useUnmergedTree = true)
            .assert(hasContentDescription("其余分类", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.LEGEND_MERGED, useUnmergedTree = true).assertHasNoClickAction()

        // 完整排行必须保留 7 个真实分类（合并不影响排行）
        listOf(
            Fx.CAT_HOUSING,
            Fx.CAT_TRANSPORT,
            Fx.CAT_FOOD,
            "cat_expense_shopping",
            "cat_expense_daily",
            "cat_expense_entertainment",
            "cat_expense_medical",
        ).forEach { categoryId ->
            composeRule.onNodeWithTag(StatisticsTags.rankingRow(categoryId), useUnmergedTree = true).assertExists()
        }

        // 图例点击（真实分类）→ 同月同类型同分类钻取
        composeRule.onNodeWithTag(StatisticsTags.legendRow(Fx.CAT_TRANSPORT), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.pump()
        assertEquals(1, harness.drillDowns.size)
        assertEquals(Fx.CAT_TRANSPORT, harness.drillDowns.single().categoryId)
        assertEquals(StatisticsHarness.OCT, harness.drillDowns.single().yearMonth)
        assertEquals(TransactionType.EXPENSE, harness.drillDowns.single().type)
    }

    @Test
    fun `总量为零时显示空心占位且不出现百分比`() {
        harness.seedAcceptance()
        composeRule.setContent { StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.MAY) }
        composeRule.awaitTag(StatisticsTags.RING_CHART)

        composeRule.onNodeWithTag(StatisticsTags.RING_EMPTY, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.RING_EMPTY, useUnmergedTree = true)
            .assert(hasText("本月暂无支出", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.RING_CENTER_TOTAL, useUnmergedTree = true).assertTextEquals("0.00")
        assertTrue(
            "零数据不得生成百分比",
            composeRule.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithTag(StatisticsTags.EMPTY_STATE, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `切换收入后环图趋势排行同时变为收入口径`() {
        harness.seedAcceptance()
        composeRule.setContent { StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.OCT) }
        composeRule.awaitTag(StatisticsTags.TYPE_INCOME)

        composeRule.onNodeWithTag(StatisticsTags.TYPE_INCOME, useUnmergedTree = true).performScrollTo().performClick()
        composeRule.pump()

        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(1), useUnmergedTree = true)
            .assert(hasContentDescription("10,000.00", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.dayDetailRow(6), useUnmergedTree = true)
            .assert(hasContentDescription("800.00", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_SALARY), useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_PART_TIME), useUnmergedTree = true).assertExists()
        // 支出分类在收入模式下不出现
        assertTrue(
            "收入模式不得出现支出分类",
            composeRule.onAllNodesWithTag(StatisticsTags.rankingRow(Fx.CAT_HOUSING), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    // ───────────────────────── S06 ─────────────────────────

    @Test
    fun `年度页十二列且未到月份是占位不可钻取`() {
        harness.seedAcceptance()
        composeRule.setContent {
            StatisticsRouteHost(harness, initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR)
        }
        composeRule.awaitTag(StatisticsTags.YEAR_CHART)

        (1..12).forEach { month ->
            composeRule.onNodeWithTag(StatisticsTags.yearColumn(month), useUnmergedTree = true).assertExists()
        }
        composeRule.onNodeWithTag(StatisticsTags.YEAR_CUTOFF, useUnmergedTree = true)
            .assert(hasText("截至 2026 年 10 月 7 日", substring = true))

        // 触控目标：整个月份列 ≥48dp（宽与高）。
        // 用**未裁切** bounds：12 列放不下时容器横向滚动，被滚动出视口的列裁切后宽度会是 0，
        // 那属于「当前不在视口」而不是「触控目标太小」。
        val firstColumn = composeRule.onNodeWithTag(StatisticsTags.yearColumn(1), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("月份列宽应 ≥48dp，实际 ${firstColumn.right - firstColumn.left}", (firstColumn.right - firstColumn.left) >= 48.dp)
        assertTrue("月份列高应 ≥48dp，实际 ${firstColumn.bottom - firstColumn.top}", (firstColumn.bottom - firstColumn.top) >= 48.dp)
        val tenthColumn = composeRule.onNodeWithTag(StatisticsTags.yearColumn(10), useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("每个月份列宽都应 ≥48dp，实际 ${tenthColumn.right - tenthColumn.left}", (tenthColumn.right - tenthColumn.left) >= 48.dp)

        composeRule.onNodeWithTag(StatisticsTags.yearColumn(10), useUnmergedTree = true).assertHasClickAction()
        composeRule.onNodeWithTag(StatisticsTags.yearColumn(11), useUnmergedTree = true)
            .assert(hasContentDescription("未到月份", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.yearColumn(11), useUnmergedTree = true).assertHasNoClickAction()
        composeRule.onNodeWithTag(StatisticsTags.yearColumn(12), useUnmergedTree = true).assertHasNoClickAction()

        // 月份明细：11/12 月占位不可点击；已到月份可点击进入月报
        composeRule.onNodeWithTag(StatisticsTags.yearMonthRow(11), useUnmergedTree = true)
            .performScrollTo()
            .assert(hasContentDescription("未到月份", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.yearMonthRow(11), useUnmergedTree = true).assertHasNoClickAction()

        composeRule.onNodeWithTag(StatisticsTags.yearMonthRow(9), useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.pump()
        composeRule.onNodeWithTag(StatisticsTags.MONTH_LABEL, useUnmergedTree = true)
            .assert(hasContentDescription("2026 年 9 月", substring = true))
    }

    @Test
    fun `年图点选月份后可查看该月账单钻取`() {
        harness.seedAcceptance()
        composeRule.setContent {
            StatisticsRouteHost(harness, initialTab = StatisticsTab.YEAR, initialYear = StatisticsHarness.YEAR)
        }
        composeRule.awaitTag(StatisticsTags.YEAR_CHART)

        // 先把**页面**纵向滚到年图可见：年柱的最近 ScrollBy 祖先是横向滚动容器，
        // 直接对列 `performScrollTo()` 只会横向滚动，图表仍可能在折叠线以下（实测 bounds 为空矩形）。
        composeRule.onNodeWithTag(StatisticsTags.YEAR_CHART, useUnmergedTree = true).performScrollTo()

        // 点视口内的月份列（第 1 列，横向最左）。列宽 ≥48dp 由
        // `年度页十二列且未到月份是占位不可钻取` 用未裁切 bounds 断言；这里验证整列可点 + 钻取参数。
        val januaryColumn = composeRule.onNodeWithTag(StatisticsTags.yearColumn(1), useUnmergedTree = true)
        val januaryBounds = januaryColumn.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "第 1 列应已滚入视口（bounds=$januaryBounds），否则触摸点不进去",
            januaryBounds.width > 0f && januaryBounds.height > 0f,
        )
        januaryColumn.performTouchInput { click(center) }

        composeRule.waitUntil(TEXT_WAIT_MILLIS) {
            composeRule.onAllNodesWithTag(StatisticsTags.SELECTED_MONTH_CARD, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(StatisticsTags.SELECTED_MONTH_CARD, useUnmergedTree = true).assertExists()

        composeRule.onNodeWithTag(StatisticsTags.SELECTED_MONTH_BILLS, useUnmergedTree = true)
            .performScrollTo()
            .performClick()
        composeRule.pump()

        assertEquals(1, harness.drillDowns.size)
        assertEquals(YearMonth.of(2026, 1), harness.drillDowns.single().yearMonth)
        assertNull(harness.drillDowns.single().type)
    }

    // ───────────────────────── 金额隐藏 ─────────────────────────

    @Test
    fun `金额隐藏时月度页不泄露金额与百分比`() {
        harness.seedAcceptance()
        composeRule.setContent {
            StatisticsRouteHost(harness, hideAmounts = true, initialYearMonth = StatisticsHarness.OCT)
        }
        composeRule.awaitTag(StatisticsTags.RING_CHART)

        val texts = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().allSemanticTexts()
        assertTrue(
            "金额隐藏时不得出现任何金额/百分比数值，实际语义文本：$texts",
            texts.none { MONEY_LEAK_PATTERN.containsMatchIn(it) },
        )
        assertTrue("隐藏时必须有掩码占位", texts.any { it.contains("••••") })
        // 图表结构仍在（不隐藏图形结构）
        composeRule.onNodeWithTag(StatisticsTags.RING_CHART, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.TREND_CHART, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `金额隐藏时年度页不泄露金额与百分比`() {
        harness.seedAcceptance()
        composeRule.setContent {
            StatisticsRouteHost(
                harness,
                hideAmounts = true,
                initialTab = StatisticsTab.YEAR,
                initialYear = StatisticsHarness.YEAR,
            )
        }
        composeRule.awaitTag(StatisticsTags.YEAR_CHART)

        val texts = composeRule.onRoot(useUnmergedTree = true).fetchSemanticsNode().allSemanticTexts()
        assertTrue(
            "年度页金额隐藏时不得出现任何金额/百分比数值，实际语义文本：$texts",
            texts.none { MONEY_LEAK_PATTERN.containsMatchIn(it) },
        )
        composeRule.onNodeWithTag(StatisticsTags.yearColumn(10), useUnmergedTree = true)
            .assert(hasContentDescription("金额已隐藏", substring = true))
        composeRule.onNodeWithTag(StatisticsTags.yearColumn(11), useUnmergedTree = true)
            .assert(hasContentDescription("未到月份", substring = true))
    }

    // ───────────────────────── 适配 ─────────────────────────

    @Test
    @Config(qualifiers = "w320dp-h700dp-xhdpi")
    fun `小屏与大字体下统计图表仍完整可读`() {
        harness.seedAcceptance()
        composeRule.setContent {
            StatisticsRouteHost(harness, initialYearMonth = StatisticsHarness.OCT, fontScale = 2f)
        }
        composeRule.awaitTag(StatisticsTags.MONTH_LABEL)

        composeRule.onNodeWithTag(StatisticsTags.RING_CHART, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.TREND_CHART, useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag(StatisticsTags.rankingRow(Fx.CAT_HOUSING), useUnmergedTree = true).assertExists()

        val trend = composeRule.onNodeWithTag(StatisticsTags.TREND_CHART, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        assertTrue("趋势图触控区域宽应 ≥48dp", (trend.right - trend.left) >= 48.dp)
        assertTrue("趋势图触控区域高应 ≥48dp", (trend.bottom - trend.top) >= 48.dp)
    }

    private companion object {
        /** 等待文案出现的上限；`waitUntil` 自带超时，绝不无界等待。 */
        const val TEXT_WAIT_MILLIS: Long = 10_000L
    }
}
