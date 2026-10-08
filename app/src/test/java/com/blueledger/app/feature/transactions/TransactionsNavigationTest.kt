package com.blueledger.app.feature.transactions

import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.TransactionFilterSeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 「进入详情再返回，筛选与滚动位置必须保留」的真实导航测试。
 *
 * 这里用一个最小 NavHost 复现总控在 `BlueLedgerApp.kt` 里的接线方式：
 * 账单列表 → 详情 → 返回。ViewModel 作用域与 `rememberLazyListState` 的
 * saveable 行为都在真实的 NavBackStackEntry 下验证，而不是靠断言代码里写了什么。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class TransactionsNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: TransactionsTestHarness
    private var navControllerRef: NavHostController? = null

    @Before
    fun setUp() {
        harness = TransactionsTestHarness()
    }

    private fun openApp() {
        composeRule.setContent {
            BlueLedgerTheme {
                val navController = rememberNavController()
                navControllerRef = navController
                NavHost(navController = navController, startDestination = "transactions") {
                    composable("transactions") {
                        TransactionsRoute(
                            repository = harness.repository,
                            clock = harness.clock,
                            initialSeed = TransactionFilterSeed(),
                            onOpenDetail = { id -> navController.navigate("detail/$id") },
                            onOpenEntry = { harness.openedEntries.add(it) },
                        )
                    }
                    composable("detail/{id}") { entry ->
                        DetailRoute(
                            repository = harness.repository,
                            clock = harness.clock,
                            transactionId = entry.arguments?.getString("id").orEmpty(),
                            onEdit = {},
                            onDeleted = { navController.popBackStack() },
                            onBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
        composeRule.awaitTag(TAG_TX_SUMMARY)
    }

    private fun backFromDetail() {
        composeRule.runOnUiThread { navControllerRef?.popBackStack() }
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()
    }

    @Test
    fun `从详情返回后搜索条件与月份都保留`() {
        harness.seedAcceptance()
        openApp()

        // 切到九月 + 搜索「九月」，制造非默认的筛选状态。
        composeRule.onNodeWithTag(TAG_TX_PREV_MONTH).performClick()
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.onNodeWithTag(TAG_TX_SEARCH).performTextInput("九月")
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("共 2 笔").assertIsDisplayed()

        val rowId = harness.transactionId("T2")
        composeRule.onNodeWithTag(txRowTag(rowId)).performClick()
        composeRule.awaitTag(TAG_DETAIL_AMOUNT)
        backFromDetail()

        // 返回后：月份、搜索词与筛选结果都不能被重置成「今天 / 当前月」。
        composeRule.awaitTag(TAG_TX_SUMMARY)
        composeRule.onNodeWithTag(TAG_TX_MONTH_LABEL).assertTextContains("2026 年 9 月")
        composeRule.onNodeWithText("共 2 笔").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_TX_SEARCH).assertTextContains("九月")
    }

    @Test
    fun `从详情返回后滚动位置保留`() {
        harness.initialize()
        // 30 笔账单分 6 天，保证列表需要滚动才能看到后面几天。
        val scrollIds = (1..30).map { index ->
            harness.addTransaction(
                amountCent = index.toLong() * 100L,
                occurredOn = LocalDate.of(2026, 10, 1).plusDays((index / 5).toLong()),
                note = "滚动账单 " + index,
                requestId = "scroll-" + index,
            )
        }
        openApp()

        // 前五项是页面头部；滚到第三个日期分组，确保汇总区完整离开视口。
        composeRule.onNodeWithTag(TAG_TX_SCREEN).performScrollToIndex(7)
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()

        val candidateDates = listOf(7, 6, 5, 4, 3, 2).map { LocalDate.of(2026, 10, it) }
        fun composedGroups(): List<LocalDate> = candidateDates.filter { date ->
            composeRule.onAllNodesWithTag(txDayGroupTag(date)).fetchSemanticsNodes().isNotEmpty()
        }

        val visibleBefore = composedGroups()
        assertTrue("滚动后至少要能看到一个日期分组", visibleBefore.isNotEmpty())
        // 顶部内容已经被滚走，说明确实不在初始位置。
        composeRule.onNodeWithTag(TAG_TX_SUMMARY).assertIsNotDisplayed()

        // 打开当前可见分组里的一笔账单详情，再返回。
        val listBounds = composeRule.onNodeWithTag(TAG_TX_SCREEN).fetchSemanticsNode().boundsInRoot
        val visibleId = scrollIds.first { id ->
            composeRule.onAllNodesWithTag(txRowTag(id)).fetchSemanticsNodes().any { node ->
                val bounds = node.boundsInRoot
                bounds.height > 0 && bounds.top >= listBounds.top && bounds.bottom <= listBounds.bottom
            }
        }
        composeRule.onNodeWithTag(txRowTag(visibleId)).performClick()
        composeRule.awaitTag(TAG_DETAIL_AMOUNT)
        backFromDetail()

        // 返回后不等待顶部节点（它可能仍在屏幕外），只等列表重新渲染。
        composeRule.waitUntil(10_000L) { composedGroups().isNotEmpty() }
        settleMainLooper()
        composeRule.waitForIdle()

        assertEquals("返回后可见分组必须与离开前一致", visibleBefore, composedGroups())
        assertEquals(0, harness.openedEntries.size)
    }

    @Test
    fun `在详情删除后返回列表账单消失`() {
        val seeded = harness.seedAcceptance()
        openApp()
        val id = seeded.transactionId("T9")
        composeRule.onNodeWithText("晚餐").performClick()
        composeRule.awaitTag(TAG_DETAIL_AMOUNT)

        composeRule.onNodeWithTag(TAG_DETAIL_DELETE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_confirm").performClick()
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()

        composeRule.awaitTag(TAG_TX_SUMMARY)
        composeRule.onNodeWithText("共 6 笔").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithTag(txRowTag(id)).fetchSemanticsNodes().isEmpty(),
        )
    }
}
