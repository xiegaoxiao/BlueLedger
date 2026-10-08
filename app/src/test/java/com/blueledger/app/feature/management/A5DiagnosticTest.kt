package com.blueledger.app.feature.management

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 临时诊断用例（A5）：定位 Robolectric 下 `waitForIdle()` 无法收敛导致的
 * `ArrayIndexOutOfBoundsException`（Robolectric NativeObjRegistry / ShadowLineBreaker）。
 *
 * 这些用例**不调用 waitForIdle**，只手动推进帧，用来区分：
 * - 组合/布局本身有问题（会在这里同样崩溃）；还是
 * - 只有「等待空闲」这一步不收敛（这里能过、awaitTag/setContent 默认等待会崩）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class A5DiagnosticTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
    }

    private fun pump(frames: Int = 40) {
        repeat(frames) { composeRule.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `d1 纯文字`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { BlueLedgerTheme { Text("你好") } }
        pump()
        composeRule.onNodeWithText("你好").assertIsDisplayed()
    }

    @Test
    fun `d2 账户路由只手动推帧`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { AccountsRouteHost(harness) }
        pump(120)
        composeRule.onNodeWithTag(ManagementTags.ACCOUNTS_SCREEN).assertIsDisplayed()
    }

    @Test
    fun `d3 分类路由只手动推帧`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { CategoriesRouteHost(harness) }
        pump(120)
        composeRule.onNodeWithTag(ManagementTags.CATEGORIES_SCREEN).assertIsDisplayed()
    }

    @Test
    fun `d4 预算路由只手动推帧`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { BudgetRouteHost(harness) }
        pump(120)
        composeRule.onNodeWithTag(ManagementTags.BUDGET_SCREEN).assertIsDisplayed()
    }

    @Test
    fun `d5 我的路由只手动推帧`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent { MineRouteHost(harness) }
        pump(120)
        composeRule.onNodeWithTag(ManagementTags.MINE_SCREEN).assertIsDisplayed()
    }
}
