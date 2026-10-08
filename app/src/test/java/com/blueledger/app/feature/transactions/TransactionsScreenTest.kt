package com.blueledger.app.feature.transactions

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.TransactionFilterSeed
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * S03 账单列表的真实 Compose 渲染与交互测试。
 *
 * 视口 390×1600dp（行为验证）；小屏/大字号适配由 A7 的独立矩阵负责。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp-xhdpi")
class TransactionsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: TransactionsTestHarness

    @Before
    fun setUp() {
        harness = TransactionsTestHarness()
    }

    private fun open(seed: TransactionFilterSeed = TransactionFilterSeed()) {
        composeRule.setTransactionsRouteContent(harness, seed)
        composeRule.awaitTag(TAG_TX_SUMMARY)
    }

    private fun type(query: String) {
        composeRule.onNodeWithTag(TAG_TX_SEARCH).performTextInput(query)
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()
    }

    private fun click(tag: String) {
        composeRule.onNodeWithTag(tag).performClick()
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()
    }

    @Test
    fun `按日期降序分组展示账单并显示筛选汇总`() {
        harness.seedAcceptance()
        open()

        composeRule.onNodeWithText("筛选结果").assertIsDisplayed()
        composeRule.onNodeWithText("共 7 笔").assertIsDisplayed()

        listOf(
            LocalDate.of(2026, 10, 7),
            LocalDate.of(2026, 10, 6),
            LocalDate.of(2026, 10, 3),
            LocalDate.of(2026, 10, 2),
            LocalDate.of(2026, 10, 1),
        ).forEach { date ->
            composeRule.onNodeWithTag(txDayGroupTag(date)).assertExists()
        }

        // 组头小计：10 月 2 日支出 0.30（0.10 + 0.20，精确到分）。
        composeRule
            .onNode(
                hasTestTag(txDayGroupTag(LocalDate.of(2026, 10, 2))) and
                    hasAnyDescendant(hasText("0.30", substring = true)),
            )
            .assertExists()
        // 10 月 6 日收入 800.00。
        composeRule
            .onNode(
                hasTestTag(txDayGroupTag(LocalDate.of(2026, 10, 6))) and
                    hasAnyDescendant(hasText("800.00", substring = true)),
            )
            .assertExists()
    }

    @Test
    fun `点击账单行进入详情`() {
        harness.seedAcceptance()
        open()

        val id = harness.transactionId("T9")
        click(txRowTag(id))
        assertEquals(listOf(id), harness.openedDetails)
    }

    @Test
    fun `搜索命中备注后只显示匹配行并可清除`() {
        harness.seedAcceptance()
        open()

        type("晚餐")
        composeRule.onNodeWithTag(txRowTag(harness.transactionId("T9"))).assertExists()
        assertTrue(
            composeRule.onAllNodesWithTag(txRowTag(harness.transactionId("T8")))
                .fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithText("共 1 笔").assertIsDisplayed()

        click(TAG_TX_SEARCH_CLEAR)
        composeRule.onNodeWithText("共 7 笔").assertIsDisplayed()
    }

    @Test
    fun `搜索命中分类名称与账户名称`() {
        harness.seedAcceptance()
        open()

        type("餐饮")
        composeRule.onNodeWithText("共 3 笔").assertIsDisplayed()

        click(TAG_TX_SEARCH_CLEAR)
        type("钱包")
        composeRule.onNodeWithText("共 5 笔").assertIsDisplayed()
    }

    @Test
    fun `收支类型筛选与重置保留月份`() {
        harness.seedAcceptance()
        open()

        click(TAG_TX_PREV_MONTH)
        composeRule.onNodeWithTag(TAG_TX_MONTH_LABEL).assertTextContains("2026 年 9 月")

        click(TAG_TX_FILTER_EXPENSE)
        composeRule.onNodeWithText("共 1 笔").assertIsDisplayed()

        click(TAG_TX_FILTER_RESET)
        // 重置只清附加条件，月份保持不变。
        composeRule.onNodeWithTag(TAG_TX_MONTH_LABEL).assertTextContains("2026 年 9 月")
        composeRule.onNodeWithText("共 2 笔").assertIsDisplayed()
    }

    @Test
    fun `分类下拉可以筛选历史账单`() {
        harness.seedAcceptance()
        open()

        click(TAG_TX_FILTER_CATEGORY)
        click(txCategoryOptionTag("cat_expense_food"))
        composeRule.onNodeWithText("共 3 笔").assertIsDisplayed()
    }

    @Test
    fun `账户下拉可以筛选历史账单`() {
        harness.seedAcceptance()
        open()

        click(TAG_TX_FILTER_ACCOUNT)
        click(txAccountOptionTag(harness.acceptanceAccountId(com.blueledger.app.acceptance.Fx.ACCOUNT_B_REF)))
        composeRule.onNodeWithText("共 5 笔").assertIsDisplayed()
    }

    @Test
    fun `筛选无结果显示清除条件并能恢复`() {
        harness.seedAcceptance()
        open()

        type("没有这样的账单")
        composeRule.onNodeWithTag(TAG_TX_EMPTY_FILTER).assertExists()
        composeRule.onNodeWithText("没有找到符合条件的账单").assertIsDisplayed()

        click(TAG_TX_EMPTY_FILTER + "_action")
        composeRule.onNodeWithText("共 7 笔").assertIsDisplayed()
    }

    @Test
    fun `首次空账本引导使用底部入口且不重复显示按钮`() {
        harness.initialize()
        composeRule.setTransactionsRouteContent(harness)
        composeRule.awaitTag(TAG_TX_EMPTY_LEDGER)

        composeRule.onNodeWithText("账本还是空的").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(TAG_TX_EMPTY_LEDGER + "_action").fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `当月无账单显示当月空态而不是筛选空`() {
        harness.seedAcceptance()
        open()

        click(TAG_TX_PREV_MONTH)
        click(TAG_TX_PREV_MONTH)
        composeRule.onNodeWithTag(TAG_TX_EMPTY_MONTH).assertExists()
        composeRule.onNodeWithText("这个月还没有账单").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(TAG_TX_EMPTY_FILTER).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `金额隐藏时列表摘要与分组小计都不泄露真实金额`() {
        harness.seedAcceptance()
        runBlocking { harness.repository.setHideAmounts(true) }
        open()

        assertTrue(
            composeRule.onAllNodesWithText("••••", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("3,078.80", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("28.50", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            composeRule.onAllNodesWithText("0.30", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `钻取种子进入时带上年月与类型筛选`() {
        harness.seedAcceptance()
        composeRule.setTransactionsRouteContent(
            harness,
            TransactionFilterSeed(
                yearMonth = com.blueledger.app.acceptance.Fx.SEP_2026,
                type = com.blueledger.app.core.model.TransactionType.EXPENSE,
            ),
        )
        composeRule.awaitTag(TAG_TX_SUMMARY)

        composeRule.onNodeWithTag(TAG_TX_MONTH_LABEL).assertTextContains("2026 年 9 月")
        composeRule.onNodeWithText("共 1 笔").assertIsDisplayed()
    }
}
