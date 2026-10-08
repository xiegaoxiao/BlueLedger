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
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.designsystem.LedgerMoney
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S04 详情的真实 Compose 渲染测试：字段、无备注、缺失状态、删除确认弹窗与导航事件。
 *
 * 视口 390×1600dp（行为验证）；小屏/大字号适配由 A7 的独立矩阵负责。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp-xhdpi")
class DetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: TransactionsTestHarness

    @Before
    fun setUp() {
        harness = TransactionsTestHarness()
    }

    /** 断言「标签 / 值」字段行内部确实包含期望值（字段与取值必须对应）。 */
    private fun fieldRowContains(rowTag: String, expected: String) {
        composeRule
            .onNode(hasTestTag(rowTag) and hasAnyDescendant(hasText(expected, substring = true)))
            .assertExists()
    }

    private fun open(transactionId: String) {
        composeRule.setDetailRouteContent(harness, transactionId)
        composeRule.awaitTag(TAG_DETAIL_AMOUNT)
    }

    @Test
    fun `详情展示完整字段并区分发生日期与创建时间`() {
        val seeded = harness.seedAcceptance()
        open(seeded.transactionId("T9"))

        composeRule.onNodeWithTag(TAG_DETAIL_CATEGORY_NAME).assertTextContains("餐饮")
        fieldRowContains(TAG_DETAIL_TYPE_CHIP, "支出账单")

        // 金额：两位小数 + 支出符号（原始 amountCent 仍是正整数分）。
        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertTextContains("28.50", substring = true)
        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT)
            .assertTextContains(LedgerMoney.formatSigned(isIncome = false, cents = 2_850L))

        fieldRowContains(TAG_DETAIL_CATEGORY, "餐饮")
        fieldRowContains(TAG_DETAIL_DATE, "2026 / 10 / 07")
        // 创建时间完整可见，且与发生日期是两行不同字段（创建时间不能替代发生日期）。
        fieldRowContains(TAG_DETAIL_CREATED_AT, "2026 / 10 / 07 00:00")
        fieldRowContains(TAG_DETAIL_ACCOUNT, "钱包")
        fieldRowContains(TAG_DETAIL_NOTE, "晚餐")
    }

    @Test
    fun `收入账单显示加号与收入类型`() {
        val seeded = harness.seedAcceptance()
        open(seeded.transactionId("T8"))

        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertTextContains("+800.00", substring = true)
        fieldRowContains(TAG_DETAIL_TYPE_CHIP, "收入账单")
        fieldRowContains(TAG_DETAIL_CATEGORY, "兼职")
    }

    @Test
    fun `无备注显示未填写备注而不是 null`() {
        harness.initialize()
        val id = harness.addTransaction(note = "", requestId = "detail-screen-no-note")
        open(id)

        fieldRowContains(TAG_DETAIL_NOTE, "未填写备注")
        assertTrue(
            "不能把 null 直接显示给用户",
            composeRule.onAllNodesWithText("null", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `记录不存在时显示专门状态并且不再显示旧内容`() {
        harness.seedAcceptance()
        composeRule.setDetailRouteContent(harness, "missing-transaction-id")
        composeRule.awaitTag(TAG_DETAIL_MISSING)

        composeRule.onNodeWithText("账单不存在").assertIsDisplayed()
        assertTrue(
            "缺失状态不得继续显示金额等旧内容",
            composeRule.onAllNodesWithTag(TAG_DETAIL_AMOUNT).fetchSemanticsNodes().isEmpty(),
        )

        composeRule.onNodeWithTag(TAG_DETAIL_MISSING + "_action").performClick()
        composeRule.waitForIdle()
        assertEquals(1, harness.backRequests)
    }

    @Test
    fun `编辑按钮只发出导航事件不复制编辑表单`() {
        val seeded = harness.seedAcceptance()
        open(seeded.transactionId("T9"))

        composeRule.onNodeWithTag(TAG_DETAIL_EDIT).performClick()
        composeRule.waitForIdle()

        assertEquals(1, harness.editRequests)
        // 详情页不出现 S02 的保存控件。
        assertTrue(composeRule.onAllNodesWithText("保存账单").fetchSemanticsNodes().isEmpty())
        assertTrue(composeRule.onAllNodesWithText("保存并再记").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `删除确认弹窗包含金额分类与日期并回传撤销凭据`() {
        val seeded = harness.seedAcceptance()
        val id = seeded.transactionId("T9")
        open(id)

        composeRule.onNodeWithTag(TAG_DETAIL_DELETE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_DETAIL_DELETE_DIALOG).assertExists()

        // 弹窗必须含金额、分类、日期三项摘要，不能只说「确定删除吗」。
        fieldRowContains(TAG_DETAIL_DELETE_DIALOG, "28.50")
        fieldRowContains(TAG_DETAIL_DELETE_DIALOG, "餐饮")
        fieldRowContains(TAG_DETAIL_DELETE_DIALOG, "2026 / 10 / 07")

        composeRule.onNodeWithTag("dialog_confirm").performClick()
        composeRule.waitForIdle()
        settleMainLooper()
        composeRule.waitForIdle()

        assertEquals(1, harness.deletedReceipts.size)
        assertEquals(id, harness.deletedReceipts.first().transactionId)
        assertEquals(2_850L, harness.deletedReceipts.first().original.amountCent)
        // 本页不自己 popBackStack、不自己弹撤销提示（由 NavHost 统一处理）。
        assertEquals(0, harness.backRequests)
    }

    @Test
    fun `取消删除不改变任何数据`() {
        val seeded = harness.seedAcceptance()
        open(seeded.transactionId("T9"))

        composeRule.onNodeWithTag(TAG_DETAIL_DELETE).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("dialog_dismiss").performClick()
        composeRule.waitForIdle()
        settleMainLooper()

        assertTrue(harness.deletedReceipts.isEmpty())
        assertTrue(
            composeRule.onAllNodesWithTag(TAG_DETAIL_DELETE_DIALOG).fetchSemanticsNodes().isEmpty(),
        )
        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertTextContains("28.50", substring = true)
    }

    @Test
    fun `详情页在金额隐藏时仍显示用户主动打开的明细金额`() {
        val seeded = harness.seedAcceptance()
        runBlocking { harness.repository.setHideAmounts(true) }
        open(seeded.transactionId("T9"))

        // PRD S10：用户主动打开的明细可显示必要金额（隐藏仍覆盖列表与统计）。
        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertTextContains("28.50", substring = true)
        fieldRowContains(TAG_DETAIL_CATEGORY, "餐饮")
    }

    @Test
    fun `同日多笔账单各自进入自己的详情`() {
        val seeded = harness.seedAcceptance()
        open(seeded.transactionId("T5"))

        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertTextContains("0.10", substring = true)
        fieldRowContains(TAG_DETAIL_CREATED_AT, "2026 / 10 / 07")
        assertEquals(0, harness.editRequests)
        assertEquals(0, harness.backRequests)
    }

    @Test
    fun `发生日期按账单自己的日期显示`() {
        val seeded = harness.seedAcceptance()
        open(seeded.transactionId("T4"))

        composeRule.onNodeWithTag(TAG_DETAIL_AMOUNT).assertTextContains("3,000.00", substring = true)
        fieldRowContains(TAG_DETAIL_CATEGORY, "住房")
        fieldRowContains(TAG_DETAIL_DATE, "2026 / 10 / 01")
        fieldRowContains(TAG_DETAIL_ACCOUNT, "银行卡")
    }
}
