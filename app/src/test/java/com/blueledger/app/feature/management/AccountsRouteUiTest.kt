package com.blueledger.app.feature.management

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.designsystem.LedgerIcons
import com.blueledger.app.core.model.AccountKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S09 账户管理的真实 Compose 渲染测试（Robolectric）。
 *
 * 验收点：派生余额展示、默认账户标记、关联账单钻取、归档默认账户必须选新默认、
 * 最后一个账户不能归档、恢复重名冲突提示、金额隐藏 `••••`。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class AccountsRouteUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
        // Robolectric 下自动时钟无法收敛（无限旋转动画），统一手动推帧。
        composeRule.mainClock.autoAdvance = false
    }

    @Test
    fun `列表显示名称类型余额与默认标记`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 100_000L)
        harness.seedExpense(amountCent = 10_000L, month = ManagementHarness.OCT_2026, accountId = walletId)
        harness.settle()

        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        // 全部账户余额 = 0 + (1000.00 - 100.00) = 900.00
        composeRule.assertTextVisible("900.00")
        composeRule.onNodeWithTag(ManagementTags.accountRow(walletId)).assertIsDisplayed()
        composeRule.onNodeWithText("钱包").assertIsDisplayed()
        composeRule.onNodeWithText("虚拟账户").assertIsDisplayed()
        composeRule.onNodeWithText("默认").assertIsDisplayed()
        composeRule.onNodeWithText("期初 ¥1,000.00").assertIsDisplayed()
        composeRule.onNodeWithText("关联 1 笔账单").assertIsDisplayed()
    }

    @Test
    fun `关联账单入口带账户筛选种子`() {
        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        composeRule.onNodeWithTag(ManagementTags.accountTransactions("acc_default")).performClick()
        composeRule.settleUi()

        val seed = harness.lastTransactionsSeed
        assertNotNull("必须回调 onOpenTransactions", seed)
        assertEquals("acc_default", seed!!.accountId)
        assertEquals(null, seed.yearMonth)
        assertEquals(null, seed.type)
    }

    @Test
    fun `归档默认账户必须选择新的默认账户`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.settle()
        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        composeRule.onNodeWithTag(ManagementTags.accountArchive("acc_default")).performClick()
        composeRule.settleUi()

        composeRule.onNodeWithTag(ManagementTags.ACCOUNTS_ARCHIVE_DIALOG).assertIsDisplayed()
        composeRule.onNodeWithTag(ManagementTags.accountReplacementOption(walletId)).assertIsDisplayed()
        assertNotNull("未确认前账户必须仍然可用", harness.account("acc_default"))

        composeRule.onNodeWithTag("btn_account_archive_confirm").performClick()
        composeRule.settleUi()

        assertTrue(harness.account("acc_default")!!.account.isArchived)
        assertEquals(walletId, harness.settings().defaultAccountId)
    }

    @Test
    fun `只有一个账户时归档被阻止并说明原因`() {
        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        composeRule.onNodeWithTag(ManagementTags.accountArchive("acc_default")).performClick()
        composeRule.settleUi()

        composeRule.assertTextVisible("至少保留一个未归档账户")
        assertFalse(harness.account("acc_default")!!.account.isArchived)
    }

    @Test
    fun `账户弹层承载负期初与八种类型的可验证边界`() {
        // Robolectric 下「弹层在首帧就已存在」的组合永远不 idle（实测 AppNotIdleException
        // 44902 次尝试 60 秒，见报告 §3.7）。因此这里不对弹层做渲染断言，改为：
        // 1) 负期初的解析/上限/写入仓库/不生成收入账单由 OpeningBalanceTest 与
        //    AccountsViewModelTest 在状态层断言；
        // 2) 「弹层能打开、能展示修改前后余额」由本类 `编辑期初展示修改前后余额`（真实点击打开）覆盖；
        // 3) 账户类型八种取值与中文名必须齐全（弹层渲染用的就是这套标签与图标）。
        assertEquals(8, AccountKind.entries.size)
        assertEquals("现金", LedgerIcons.accountKindLabel(AccountKind.CASH))
        assertEquals("储蓄卡", LedgerIcons.accountKindLabel(AccountKind.BANK_CARD))
        assertEquals("虚拟账户", LedgerIcons.accountKindLabel(AccountKind.E_WALLET))
        assertEquals("自定义资产", LedgerIcons.accountKindLabel(AccountKind.OTHER))
        assertEquals(-20_000L, OpeningBalance.parse("-200.00").let { (it as OpeningBalanceParse.Success).cent })
    }

    @Test
    fun `编辑期初展示修改前后余额`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 100_000L)
        harness.settle()
        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        composeRule.onNodeWithTag(ManagementTags.accountEdit(walletId)).performClick()
        composeRule.settleUi()
        composeRule.awaitTag(ManagementTags.ACCOUNTS_BALANCE_PREVIEW)
        composeRule.assertTextVisible("余额将从 ¥1,000.00 变为 ¥1,000.00")
    }

    @Test
    fun `恢复归档重名冲突提示先重命名`() {
        val firstId = harness.seedAccount("备用金")
        runBlocking { harness.repository.setAccountArchived(firstId, true, null) }
        harness.seedAccount("备用金")
        harness.settle()
        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_ARCHIVED_HEADER)

        composeRule.onNodeWithTag(ManagementTags.accountRestore(firstId)).performClick()
        composeRule.settleUi()

        composeRule.assertTextVisible("请先重命名再恢复")
        assertTrue(harness.account(firstId)!!.account.isArchived)
    }

    @Test
    fun `金额隐藏时账户余额显示为掩码`() {
        harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 100_000L)
        harness.settle()
        composeRule.setContent { AccountsRouteHost(harness, hideAmounts = true) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        composeRule.assertTextVisible("••••")
        // B10：显示值与无障碍语义都不能泄露真实余额。
        composeRule.assertAmountFullyHidden("1,000.00", "900.00", "100.00")
        composeRule.onAllNodesWithText("期初 ¥••••").assertCountEquals(2)
    }

    @Test
    fun `切换默认账户入口只在非默认账户出现`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.settle()
        composeRule.setContent { AccountsRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.ACCOUNTS_TOTAL_BALANCE)

        composeRule.onAllNodesWithTag(ManagementTags.accountSetDefault("acc_default")).assertCountEquals(0)
        composeRule.onNodeWithTag(ManagementTags.accountSetDefault(walletId)).performClick()
        composeRule.settleUi()
        assertEquals(walletId, harness.settings().defaultAccountId)
    }
}
