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
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.AccountKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S10 我的与设置真实 Compose 渲染测试（Robolectric）。
 *
 * 验收点：四个管理入口、默认账户、**全局**金额隐藏开关、主题说明、真实版本号、
 * 没有登录/会员/云头像入口、备份状态反映真实情况。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h1600dp-xhdpi")
class MineRouteUiTest {

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
    fun `四个管理入口都可点击并回调`() {
        composeRule.setContent { MineRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.MINE_SCREEN)

        composeRule.onNodeWithTag(ManagementTags.MINE_ENTRY_CATEGORIES).performClick()
        composeRule.onNodeWithTag(ManagementTags.MINE_ENTRY_BUDGET).performClick()
        composeRule.onNodeWithTag(ManagementTags.MINE_ENTRY_ACCOUNTS).performClick()
        composeRule.onNodeWithTag(ManagementTags.MINE_ENTRY_DATA).performClick()
        composeRule.settleUi()

        assertEquals(1, harness.openCategoriesCount)
        assertEquals(1, harness.openBudgetCount)
        assertEquals(1, harness.openAccountsCount)
        assertEquals(1, harness.openDataCount)
    }

    @Test
    fun `身份卡说明本地保存且没有登录会员入口`() {
        composeRule.setContent { MineRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.MINE_SCREEN)

        composeRule.onNodeWithTag(ManagementTags.MINE_IDENTITY_CARD).assertIsDisplayed()
        composeRule.assertTextVisible("我的账本")
        composeRule.assertTextVisible("本地账本 · 无需登录")
        // 没有登录按钮 / 会员营销 / 云同步入口。
        composeRule.onAllNodesWithText("登录").assertCountEquals(0)
        composeRule.onAllNodesWithText("注册").assertCountEquals(0)
        composeRule.onAllNodesWithText("会员", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("云同步", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("头像", substring = true).assertCountEquals(0)
    }

    @Test
    fun `主题说明与真实版本号`() {
        composeRule.setContent { MineRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.MINE_SCREEN)
        composeRule.settleUi()
        composeRule.onNodeWithText("蓝色浅色主题（本版不跟随系统深色）").assertIsDisplayed()

        val expected = AppVersionResolver.resolve(ApplicationProvider.getApplicationContext())
        assertFalse("版本号不能是空值", expected.isBlank())
        composeRule.settleUi()
        composeRule.onNodeWithText(expected).assertIsDisplayed()
        // 版本号不是写死的占位文案。
        composeRule.onAllNodesWithText("版本号", substring = true).assertCountEquals(0)
    }

    @Test
    fun `金额隐藏开关全局生效`() {
        composeRule.setContent { MineWithAmountProbeHost(harness) }
        composeRule.awaitTag("probe_amount")

        // 初始：探针显示真实金额。
        composeRule.assertTextVisible("10,800.00")
        runBlocking { harness.repository.setHideAmounts(false) }
        harness.settle()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.MINE_HIDE_AMOUNTS_SWITCH + "_toggle").performClick()
        composeRule.settleUi()

        // 写入的是仓库设置（首页/账单/统计/账户共用同一开关）。
        assertTrue(harness.settings().hideAmounts)
        // 同一个主题下的敏感金额节点立即变成掩码，且界面与无障碍语义都不再出现真实数字。
        composeRule.assertTextVisible("••••")
        composeRule.assertAmountFullyHidden("10,800.00")

        // 明确可见的恢复控件：再点一次关闭。
        composeRule.onNodeWithTag(ManagementTags.MINE_HIDE_AMOUNTS_SWITCH + "_toggle").performClick()
        composeRule.settleUi()
        assertFalse(harness.settings().hideAmounts)
        composeRule.assertTextVisible("10,800.00")
    }

    @Test
    fun `默认账户可以切换`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.settle()
        composeRule.setContent { MineRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.MINE_DEFAULT_ACCOUNT_ROW)

        composeRule.onNodeWithTag(ManagementTags.MINE_DEFAULT_ACCOUNT_ROW).performClick()
        composeRule.settleUi()
        composeRule.onNodeWithTag(ManagementTags.MINE_ACCOUNT_PICKER_DIALOG).assertIsDisplayed()

        composeRule.onNodeWithTag("mine_account_option_$walletId").performClick()
        composeRule.settleUi()

        assertEquals(walletId, harness.settings().defaultAccountId)
        composeRule.onNodeWithTag(ManagementTags.MINE_DEFAULT_ACCOUNT_ROW).assertTextContains("钱包")
    }

    @Test
    fun `未备份与已备份状态不同`() {
        composeRule.setContent { MineRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.MINE_SCREEN)
        composeRule.settleUi()
        composeRule.onNodeWithText("尚未备份").assertIsDisplayed()

        runBlocking {
            harness.repository.recordBackupSuccess(
                at = ManagementHarness.CLOCK_INSTANT,
                fileName = "blueledger-2026-10-07.blueledger.json",
            )
        }
        composeRule.settleUi()

        composeRule.onAllNodesWithText("尚未备份").assertCountEquals(0)
        composeRule.onAllNodesWithText("blueledger-2026-10-07.blueledger.json", substring = true)
            .assertCountEquals(1)
    }

    @Test
    fun `月度预算入口显示当前月真实预算`() {
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 800_000L) }
        harness.settle()
        composeRule.setContent { MineRouteHost(harness) }
        composeRule.awaitTag(ManagementTags.MINE_ENTRY_BUDGET)

        composeRule.onNodeWithTag(ManagementTags.MINE_ENTRY_BUDGET).assertTextContains("¥8,000.00")
        composeRule.onAllNodesWithTag(ManagementTags.MINE_ENTRY_BUDGET).assertCountEquals(1)
    }
}
