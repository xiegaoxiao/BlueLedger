package com.blueledger.app.feature.management

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.designsystem.LedgerProgressBar
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S08 月度预算真实 Compose 渲染测试（Robolectric，手动推帧）。
 *
 * 验收点：未设置不显示假进度；125% 显示真实比例 125.0% 与「已超支 ¥500.00」；
 * 80% 显示接近预算；100% 显示预算已用完；移除预算必须确认；
 * 进度条满格不溢出（比例由 `BudgetState.progressFraction` 封顶 1.0，
 * A2 组件对 >100% 的入参同样钳制，见最后一个用例）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class BudgetRouteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
        // 见 ManagementTestSupport.pump 的说明：Robolectric 下不能用自动时钟等待空闲。
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `未设置显示为这个月设一个预算且没有进度条`() {
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_UNSET_HINT)

        composeRule.onNodeWithTag(ManagementTags.BUDGET_MONTH_LABEL).assertTextContains("2026 年 10 月")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_UNSET_HINT).assertTextContains("为这个月设一个预算")
        // 未设置不绘制进度条（不是「填 0% 的假进度条」）。
        composeRule.onAllNodesWithTag(ManagementTags.BUDGET_PROGRESS_TRACK).assertCountEquals(0)
        composeRule.onAllNodesWithTag(ManagementTags.BUDGET_RATIO_TEXT).assertCountEquals(0)
    }

    @Test
    fun `超支一百二十五显示真实比例与超支金额`() {
        harness.seedExpense(amountCent = 250_000L, month = ManagementHarness.OCT_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_PROGRESS_TRACK)

        // 文字保留真实使用率 125.0%（进度条宽度才封顶）。
        composeRule.onNodeWithTag(ManagementTags.BUDGET_RATIO_TEXT).assertTextContains("已用 125.0%")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_STATUS_TEXT).assertTextContains("已超支 ¥500.00")
        composeRule.assertTextVisible("500.00")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_USED_TEXT).assertTextContains("已支出 ¥2,500.00")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_AMOUNT_TEXT).assertTextContains("月预算 ¥2,000.00")

        // 进度条完整落在窗口内（不越界、不被裁切）。
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        val track = composeRule.onNodeWithTag(ManagementTags.BUDGET_PROGRESS_TRACK).getUnclippedBoundsInRoot()
        val fill = composeRule.onNodeWithTag(ManagementTags.BUDGET_PROGRESS_FILL).getUnclippedBoundsInRoot()
        composeRule.onNodeWithTag(ManagementTags.BUDGET_PROGRESS_TRACK).assertIsDisplayed()
        val trackWidth = (track.right - track.left).value
        val fillWidth = (fill.right - fill.left).value
        assertTrue("进度条必须有宽度", trackWidth > 0f)
        assertTrue(
            "进度条不得超出窗口：track=$track root=$root",
            track.left.value >= -0.5f && track.right.value <= root.right.value + 0.5f,
        )
        // 「满而不溢出」：125% 时填充条宽度必须**恰好等于**轨道宽度（满格），且完全落在轨道内。
        assertEquals("125% 时填充条必须满格", trackWidth, fillWidth, 0.5f)
        assertTrue(
            "填充条不得溢出轨道：fill=$fill track=$track",
            fill.left.value >= track.left.value - 0.5f && fill.right.value <= track.right.value + 0.5f,
        )
        // 状态层：真实比例 125%，但传给进度条的比例必须是封顶后的 1.0。
        val budget = harness.budget(ManagementHarness.OCT_2026)
        assertEquals(12_500L, budget.ratioBasisPoint)
        assertEquals(1.0f, budget.progressFraction!!, 0.000001f)
        assertTrue("封顶值不得超过 1：${budget.progressFraction}", budget.progressFraction!! <= 1.0f)
    }

    @Test
    fun `八成与一百的状态文字不同`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.seedExpense(amountCent = 160_000L, month = ManagementHarness.OCT_2026)
        harness.settle()
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_STATUS_TEXT)

        composeRule.onNodeWithTag(ManagementTags.BUDGET_STATUS_TEXT).assertTextContains("接近预算")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_RATIO_TEXT).assertTextContains("已用 80.0%")

        // 同一页面里继续记一笔支出到恰好 100%。
        harness.seedExpense(amountCent = 40_000L, month = ManagementHarness.OCT_2026)
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.BUDGET_STATUS_TEXT).assertTextContains("预算已用完")
    }

    @Test
    fun `移除预算必须确认取消不改数据`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_REMOVE)

        composeRule.onNodeWithTag(ManagementTags.BUDGET_REMOVE).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.BUDGET_REMOVE_DIALOG).assertIsDisplayed()
        assertNotNull("确认前预算必须仍在", harness.budget(ManagementHarness.OCT_2026).budgetCent)

        composeRule.onNodeWithTag("btn_budget_remove_cancel").performClick()
        composeRule.settleUi()
        assertNotNull("取消后预算必须仍在", harness.budget(ManagementHarness.OCT_2026).budgetCent)
        // 取消后页面仍是「已设置」状态（本用例没有支出，因此没有状态提醒文字）。
        composeRule.onNodeWithTag(ManagementTags.BUDGET_REMOVE).assertIsDisplayed()
        composeRule.assertTextVisible("月预算 ¥2,000.00")
    }

    @Test
    fun `确认移除后回到未设置`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_REMOVE)

        composeRule.onNodeWithTag(ManagementTags.BUDGET_REMOVE).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag("btn_budget_remove_confirm").performClick()
        composeRule.settleUi()

        composeRule.awaitTag(ManagementTags.BUDGET_UNSET_HINT)
        composeRule.onNodeWithTag(ManagementTags.BUDGET_UNSET_HINT).assertTextContains("为这个月设一个预算")
        composeRule.onAllNodesWithTag(ManagementTags.BUDGET_PROGRESS_TRACK).assertCountEquals(0)
        assertFalse(harness.budget(ManagementHarness.OCT_2026).isSet)
    }

    @Test
    fun `九月预算不受十月影响`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.SEP_2026, 300_000L) }
        harness.settle()
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_UNSET_HINT)

        // 切到九月：显示九月自己的预算。
        composeRule.onNodeWithTag(ManagementTags.BUDGET_PREV_MONTH).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.BUDGET_MONTH_LABEL).assertTextContains("2026 年 9 月")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_AMOUNT_TEXT).assertTextContains("月预算 ¥3,000.00")
        assertEquals(300_000L, harness.budget(ManagementHarness.SEP_2026).budgetCent)
    }

    @Test
    fun `保存预算后进度与文案立即更新`() {
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_UNSET_HINT)

        composeRule.onNodeWithTag(ManagementTags.BUDGET_AMOUNT_FIELD).performTextReplacement("2000")
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.BUDGET_SAVE).performClick()
        composeRule.settleUi()

        assertEquals(200_000L, harness.budget(ManagementHarness.OCT_2026).budgetCent)
        composeRule.onNodeWithTag(ManagementTags.BUDGET_AMOUNT_TEXT).assertTextContains("月预算 ¥2,000.00")
        composeRule.onNodeWithTag(ManagementTags.BUDGET_RATIO_TEXT).assertTextContains("已用 0.0%")
    }

    @Test
    fun `零预算被拒绝并给出原因`() {
        composeRule.setContent { BudgetRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.BUDGET_UNSET_HINT)

        composeRule.onNodeWithTag(ManagementTags.BUDGET_AMOUNT_FIELD).performTextReplacement("0")
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.BUDGET_SAVE).performClick()
        composeRule.settleUi()

        composeRule.assertTextVisible("预算必须大于 0 元")
        assertNull(harness.budget(ManagementHarness.OCT_2026).budgetCent)
    }

    @Test
    fun `金额隐藏时预算与已支出显示为掩码`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.seedExpense(amountCent = 250_000L, month = ManagementHarness.OCT_2026)
        harness.settle()
        composeRule.setContent { BudgetRouteHost(harness, hideAmounts = true) }
        composeRule.awaitTag(ManagementTags.BUDGET_PROGRESS_TRACK)

        composeRule.assertTextVisible("••••")
        // 显示值与无障碍语义两条通道都不能泄露真实金额（B10）。
        composeRule.assertAmountFullyHidden("2,000.00", "2,500.00", "500.00")
    }

    /**
     * A2 的进度条对 >100% 的入参必须钳制在容器内（S08 依赖这一点做到「满而不溢出」）。
     */
    @Test
    fun `A2 进度条对超过百分之百的比例不溢出容器`() {
        composeRule.setContent {
            BlueLedgerTheme {
                Box(modifier = Modifier.width(200.dp).height(40.dp).testTag("progress_parent")) {
                    LedgerProgressBar(
                        fraction = 1.25f,
                        testTag = "progress_track_tag",
                        fillTestTag = "progress_fill",
                    )
                }
            }
        }
        composeRule.settleUi()
        composeRule.onNodeWithTag("progress_parent").assertIsDisplayed()
        composeRule.onNodeWithTag("progress_track_tag").assertIsDisplayed()
        composeRule.onNodeWithTag("progress_fill").assertIsDisplayed()
        val parent = composeRule.onNodeWithTag("progress_parent").getUnclippedBoundsInRoot()
        val track = composeRule.onNodeWithTag("progress_track_tag").getUnclippedBoundsInRoot()
        val fill = composeRule.onNodeWithTag("progress_fill").getUnclippedBoundsInRoot()
        assertEquals(parent.left.value, track.left.value, 0.5f)
        assertEquals(parent.right.value, track.right.value, 0.5f)
        // 入参 1.25 > 1：填充条被钳制到与轨道同宽，绝不超出容器。
        assertEquals(
            (track.right - track.left).value,
            (fill.right - fill.left).value,
            0.5f,
        )
        assertTrue(
            "填充条不得溢出父容器：fill=$fill parent=$parent",
            fill.right.value <= parent.right.value + 0.5f,
        )
    }
}
