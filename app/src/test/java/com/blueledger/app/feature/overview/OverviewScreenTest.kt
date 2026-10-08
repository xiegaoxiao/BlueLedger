package com.blueledger.app.feature.overview

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Fx
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * S01 首页的真实 Compose 渲染与点击测试（Robolectric 驱动真实 Compose 布局）。
 *
 * 视口用 390×1600dp：本测试关注**行为与数据**（金额、导航参数、空态、金额隐藏），
 * 小屏/大字号/安全区的适配矩阵由 A7 的独立验收负责（见 docs/AI开发提示词.md §13）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp-xhdpi")
class OverviewScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: OverviewTestHarness

    @Before
    fun setUp() {
        harness = OverviewTestHarness()
    }

    private fun open() {
        composeRule.setOverviewRouteContent(harness)
        composeRule.awaitTag(TAG_OVERVIEW_SUMMARY_CARD)
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()
    }

    @Test
    fun `摘要卡显示十月验收金额与本月结余标签`() {
        harness.seedAcceptance()
        open()

        composeRule.onNodeWithTag(TAG_OVERVIEW_SUMMARY_CARD).assertIsDisplayed()
        composeRule.onNodeWithText("本月结余").assertIsDisplayed()
        composeRule.onNodeWithText("7,721.20").assertIsDisplayed()
        composeRule.onNodeWithText("10,800.00").assertIsDisplayed()
        // 预算卡也会展示同一支出金额；明确检查摘要卡，避免全页同文案歧义。
        composeRule.onNodeWithTag(TAG_OVERVIEW_SUMMARY_CARD).assertTextContains("3,078.80")
        // 页面上不得出现把结余写成「余额」的标签。
        assertTrue(composeRule.onAllNodesWithText("本月余额").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `顶部显示设备今日与当前月份`() {
        harness.seedAcceptance()
        open()

        composeRule.onNodeWithTag(TAG_OVERVIEW_TODAY).assertTextContains("10 月 7 日", substring = true)
        composeRule.onNodeWithTag(TAG_OVERVIEW_MONTH_LABEL).assertTextContains("2026 年 10 月")
        // 当前月不显示「回到本月」。
        assertTrue(composeRule.onAllNodesWithTag(TAG_OVERVIEW_BACK_TO_CURRENT).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `快捷分类点击后进入记账并预选该分类`() {
        harness.seedAcceptance()
        open()

        click(overviewQuickTag("cat_expense_food"))

        assertEquals(1, harness.entryLaunches.size)
        assertEquals(
            EntryLaunch(type = TransactionType.EXPENSE, categoryId = "cat_expense_food"),
            harness.entryLaunches.first(),
        )
    }

    @Test
    fun `浏览历史月份后点常用分类仍然不携带任何日期信息`() {
        harness.seedAcceptance()
        open()

        click(TAG_OVERVIEW_PREV_MONTH)
        composeRule.onNodeWithTag(TAG_OVERVIEW_MONTH_LABEL).assertTextContains("2026 年 9 月")

        click(overviewQuickTag("cat_expense_food"))

        // EntryLaunch 没有日期字段：发生日期永远由 S02 取设备今日，不是历史月份的任意一天。
        assertEquals(
            listOf(EntryLaunch(type = TransactionType.EXPENSE, categoryId = "cat_expense_food")),
            harness.entryLaunches,
        )
    }

    @Test
    fun `查看全部与统计入口携带所选月份`() {
        harness.seedAcceptance()
        open()

        click(TAG_OVERVIEW_PREV_MONTH)
        click(TAG_OVERVIEW_STATS_ENTRY)
        click(TAG_OVERVIEW_VIEW_ALL)

        assertEquals(listOf(YearMonth.of(2026, 9)), harness.openedStatistics)
        assertEquals(listOf(YearMonth.of(2026, 9)), harness.openedTransactions)
    }

    @Test
    fun `摘要卡点击进入所选月份统计`() {
        harness.seedAcceptance()
        open()

        click(TAG_OVERVIEW_SUMMARY_CARD)
        assertEquals(listOf(YearMonth.of(2026, 10)), harness.openedStatistics)
    }

    @Test
    fun `最近账单最多 5 条且点击进入详情`() {
        harness.seedAcceptance()
        open()

        val recentIds = runBlocking {
            harness.repository.observeTransactions(
                TransactionFilter(yearMonth = Fx.OCT_2026, limit = 100),
            ).first().items.map { it.id }
        }
        assertEquals(7, recentIds.size)

        val rendered = recentIds.take(5).count { id ->
            composeRule.onAllNodesWithTag(overviewRowTag(id)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("首页只渲染最近 5 条", 5, rendered)
        assertTrue(
            "第 6 条不应出现在首页",
            composeRule.onAllNodesWithTag(overviewRowTag(recentIds[5])).fetchSemanticsNodes().isEmpty(),
        )

        click(overviewRowTag(recentIds.first()))
        assertEquals(listOf(recentIds.first()), harness.openedDetails)
    }

    @Test
    fun `未设置预算时给出设置入口且没有进度条`() {
        harness.seedAcceptance()
        open()

        // 十月有预算：显示进度条与 77.0% 使用率。
        // 预算卡整体可点击（Modifier.clickable 会合并子节点语义），
        // 因此卡内的进度条/使用率/按钮必须用 useUnmergedTree 定位。
        val progressBounds = composeRule
            .onNodeWithTag(TAG_OVERVIEW_BUDGET_PROGRESS, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "预算进度条必须有可见高度，实际高度=" + progressBounds.height,
            progressBounds.height > 0f,
        )
        assertTrue("预算进度条必须有可见宽度", progressBounds.width > 0f)
        composeRule
            .onNodeWithTag(TAG_OVERVIEW_BUDGET_RATIO, useUnmergedTree = true)
            .assertTextContains("77.0%")

        // 切到没有预算的九月。
        click(TAG_OVERVIEW_PREV_MONTH)

        composeRule
            .onNodeWithTag(TAG_OVERVIEW_BUDGET_SET, useUnmergedTree = true)
            .assertIsDisplayed()
        assertTrue(
            "未设置预算不能显示假进度条",
            composeRule
                .onAllNodesWithTag(TAG_OVERVIEW_BUDGET_PROGRESS, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        composeRule.onNodeWithTag(TAG_OVERVIEW_BUDGET_SET, useUnmergedTree = true).performClick()
        composeRule.waitForIdle()
        settleMainLooper()
        assertEquals(listOf(YearMonth.of(2026, 9)), harness.openedBudgets)
    }

    @Test
    fun `首次空账本显示引导且不重复放记账按钮`() {
        harness.initialize()
        composeRule.setOverviewRouteContent(harness)
        composeRule.awaitTag(TAG_OVERVIEW_EMPTY_FIRST)

        assertTrue(
            composeRule.onAllNodesWithTag(TAG_OVERVIEW_EMPTY_FIRST + "_action").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `当月无账单但账本非空时显示当月空态并能回到本月`() {
        harness.seedAcceptance()
        open()

        click(TAG_OVERVIEW_PREV_MONTH)
        click(TAG_OVERVIEW_PREV_MONTH)

        composeRule.onNodeWithText("这个月还没有账单").assertIsDisplayed()
        click(TAG_OVERVIEW_BACK_TO_CURRENT + "_in_empty")
        composeRule.onNodeWithTag(TAG_OVERVIEW_MONTH_LABEL).assertTextContains("2026 年 10 月")
    }

    @Test
    fun `金额隐藏时摘要与最近账单都不泄露真实金额`() {
        harness.seedAcceptance()
        runBlocking { harness.repository.setHideAmounts(true) }
        open()

        composeRule.onNodeWithTag(TAG_OVERVIEW_SUMMARY_CARD).assertTextContains("••••")
        // 用未合并树检查：任何被合并进卡片的子文本都不得残留真实金额。
        assertTrue(
            "隐藏后不得再出现真实金额文本",
            composeRule.onAllNodesWithText("7,721.20", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("10,800.00", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "预算使用率也不能泄露",
            composeRule.onAllNodesWithText("77.0%", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `顶部的金额显示开关写回仓库设置`() {
        harness.seedAcceptance()
        open()

        click(TAG_OVERVIEW_HIDE_TOGGLE)

        assertTrue(runBlocking { harness.repository.observeSettings().first().hideAmounts })
        composeRule.awaitTag(TAG_OVERVIEW_SUMMARY_CARD)
        composeRule.onNodeWithTag(TAG_OVERVIEW_SUMMARY_CARD).assertTextContains("••••")
    }
}
