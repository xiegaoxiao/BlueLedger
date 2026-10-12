package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerApp
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import com.blueledger.app.di.AppContainer
import com.blueledger.app.feature.backup.JsonBackupCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w354dp-h792dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssetAccountsUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repository = InMemoryLedgerRepository(clock)
    private val container = object : AppContainer {
        override val clock = this@AssetAccountsUiTest.clock
        override val ledgerRepository = repository
        override val backupCodec = JsonBackupCodec(clock)
    }
    @Before fun setup() {
        ReferencePreferences(ui.activity).storage.edit().clear().commit()
        runBlocking { repository.initializeIfNeeded() }
    }
    private fun await(tag: String) { ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() }
    private fun open() { ui.setContent { BlueLedgerApp(container) }; await("overview_screen"); ui.onNodeWithTag("tab_mine").performClick(); await("mine_settings"); ui.onNodeWithText("资产管家").performClick(); await("assets_screen") }
    private fun snapshot(name: String) {
        val file = File("build/asset-accounts-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun rows() = runBlocking { repository.observeAccounts(false).first().filter { it.account.id != Defaults.DEFAULT_ACCOUNT_ID } }
    private fun scroll(tag: String) { ui.onNodeWithTag("asset_list").performScrollToNode(hasTestTag(tag)) }
    private fun addPocket() { ui.onNodeWithTag("asset_add").performScrollTo().performClick(); ui.onNodeWithTag("asset_type_virtual").performClick(); ui.onNodeWithTag("asset_type_ALIPAY_POCKET").performClick(); await("asset_editor") }
    @Test fun singleBankAccountLayout() {
        runBlocking {
            repository.upsertAccount(AccountCommand(name = "虚构工资卡 · 1234", kind = AccountKind.BANK_CARD, openingBalanceCent = 12300, location = AccountLocation.BANK_CARD))
        }
        open(); snapshot("single-bank")
    }
    @Test fun chartsShowCompositionRankingAndSeparateDebtFromNetAssets() {
        runBlocking {
            listOf(
                AccountCommand(name = "虚构工资卡", kind = AccountKind.BANK_CARD, openingBalanceCent = 1200000, location = AccountLocation.BANK_CARD),
                AccountCommand(name = "虚构微信", kind = AccountKind.E_WALLET, openingBalanceCent = 80000, location = AccountLocation.WECHAT_BALANCE),
                AccountCommand(name = "虚构旅行荷包", kind = AccountKind.E_WALLET, openingBalanceCent = 300000, location = AccountLocation.ALIPAY_POCKET),
                AccountCommand(name = "虚构信用卡", kind = AccountKind.CREDIT_CARD, openingBalanceCent = -50000, location = AccountLocation.BANK_CARD)
            ).forEach { repository.upsertAccount(it) }
        }
        open(); ui.onNodeWithTag("asset_tab_charts").performClick(); snapshot("charts-populated")
        ui.waitUntil(10_000) {
            ui.onNodeWithTag("asset_trend_chart").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].any { it.contains("1 个时间点") }
        }
        ui.onNodeWithTag("asset_trend_chart").performTouchInput { click(androidx.compose.ui.geometry.Offset(width * (clock.today().monthValue - 1) / 11f, height / 2f)) }
        ui.onNodeWithText("${clock.today().monthValue}月  15,800.00").assertIsDisplayed()
        fun chartScroll(tag: String) { ui.onNodeWithTag("asset_charts_list").performScrollToNode(hasTestTag(tag)) }
        chartScroll("asset_chart_current_total")
        ui.onNodeWithTag("asset_chart_current_total").assertTextContains("15,800.00")
        snapshot("charts-composition")
        chartScroll("asset_rank_${rows().maxBy { it.balanceCent }.account.id}"); snapshot("charts-ranking")
        val debtId = rows().first { it.account.name == "虚构信用卡" }.account.id
        ui.onNodeWithTag("asset_chart_type_1").assertIsDisplayed().performClick().assertIsSelected()
        chartScroll("asset_chart_current_total"); ui.onNodeWithTag("asset_chart_current_total").assertTextContains("500.00")
        chartScroll("asset_rank_$debtId"); ui.onNodeWithTag("asset_rank_balance_$debtId").assertTextContains("500.00")
        snapshot("charts-debt")
        ui.onNodeWithTag("asset_chart_type_2").assertIsDisplayed().performClick().assertIsSelected()
        chartScroll("asset_chart_current_total"); ui.onNodeWithTag("asset_chart_current_total").assertTextContains("15,300.00")
        snapshot("charts-net")
        chartScroll("asset_rank_$debtId"); ui.onNodeWithTag("asset_rank_balance_$debtId").assertTextContains("-500.00")
    }
    @Test fun fourPocketsSaveContinuouslyCancelAndReconcileWithoutChangingBills() {
        open(); snapshot("empty")
        ui.onNodeWithTag("asset_tab_charts").performClick(); snapshot("charts-empty")
        ui.onNodeWithTag("asset_tab_accounts").performClick()
        addPocket(); ui.onNodeWithTag("asset_save").assertIsNotEnabled()
        listOf("生活费" to "6000", "旅行" to "3000", "备用金" to "1500", "零花钱" to "500").forEachIndexed { i, (name, balance) ->
            if (i > 0) addPocket()
            ui.onNodeWithTag("asset_name").performScrollTo().performTextReplacement(name)
            ui.onNodeWithTag("asset_balance").performScrollTo().performTextReplacement(balance)
            ui.onNodeWithTag("asset_save").performScrollTo().performClick()
            await("assets_screen"); ui.waitUntil(10_000) { rows().size == i + 1 }
        }
        await("assets_screen"); ui.waitUntil(10_000) { rows().size == 4 }
        Assert.assertEquals(1100000L, rows().sumOf { it.balanceCent })
        ui.onNodeWithTag("asset_total").assertTextContains("11000.00")
        snapshot("four-pockets")
        val id = rows().first { it.account.name == "生活费" }.account.id
        scroll("asset_account_$id"); ui.onNodeWithTag("asset_account_$id").performClick(); await("asset_detail"); ui.onNodeWithTag("asset_adjust_balance").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_balance").performScrollTo().performTextReplacement("999")
        ui.onNodeWithTag("asset_cancel").performClick(); await("asset_detail"); ui.onNodeWithTag("btn_back").performClick(); await("assets_screen")
        Assert.assertEquals(600000L, rows().first { it.account.id == id }.balanceCent)
        scroll("asset_account_$id"); ui.onNodeWithTag("asset_account_$id").performClick(); await("asset_detail"); ui.onNodeWithTag("asset_adjust_balance").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_balance").performScrollTo().performTextReplacement("5800")
        snapshot("balance-editor")
        ui.onNodeWithTag("asset_save").performScrollTo().performClick(); await("asset_detail"); ui.onNodeWithTag("btn_back").performClick(); await("assets_screen")
        ui.waitUntil(10_000) { rows().first { it.account.id == id }.balanceCent == 580000L }
        Assert.assertEquals(0, runBlocking { repository.observeTransactions(TransactionFilter()).first().totalCount })
        scroll("asset_group_E_WALLET"); ui.onNodeWithTag("asset_group_E_WALLET").assertIsDisplayed()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun doubleFontKeepsAmountsAndSaveVisibleAndShowsSaveFailure() {
        val configuration = android.content.res.Configuration(ui.activity.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION") ui.activity.resources.updateConfiguration(configuration, ui.activity.resources.displayMetrics)
        runBlocking {
            listOf(AccountLocation.ALIPAY_BALANCE, AccountLocation.WECHAT_BALANCE, AccountLocation.BANK_CARD).forEachIndexed { i, location ->
                repository.upsertAccount(AccountCommand(name = "虚构账户$i", kind = AccountKind.E_WALLET, openingBalanceCent = 999999999, location = location))
            }
        }
        open(); snapshot("groups-320dp-2x")
        ui.onNodeWithTag("asset_total").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>(); action(layouts)
            Assert.assertTrue(layouts.isNotEmpty()); Assert.assertFalse(layouts.first().hasVisualOverflow)
        }
        scroll("asset_account_${rows().first().account.id}"); snapshot("account-320dp-2x")
        ui.onNodeWithTag("asset_add").performScrollTo().performClick(); ui.onNodeWithTag("asset_type_BANK_CARD").performScrollTo().performClick(); ui.onNodeWithTag("asset_bank_0").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_save").assertIsDisplayed().assertIsNotEnabled()
        ui.onNodeWithTag("asset_name").performScrollTo().performTextReplacement("虚构工资卡")
        ui.onNodeWithTag("asset_balance").performScrollTo().performTextReplacement("12345.67")
        snapshot("editor-320dp-2x")
        repository.failNextWrite = LedgerError.Storage("虚构保存失败")
        ui.onNodeWithTag("asset_save").performClick()
        ui.onNodeWithText("虚构保存失败").performScrollTo().assertIsDisplayed()
        Assert.assertEquals(3, rows().size)
        ui.onNodeWithTag("asset_save").assertIsDisplayed().assertIsEnabled().performClick(); await("assets_screen")
        ui.waitUntil(10_000) { rows().size == 4 }
        ui.onNodeWithTag("asset_tab_charts").performClick(); snapshot("charts-320dp-2x")
        ui.onNodeWithTag("asset_tab_charts").assertIsSelected()
        ui.onNodeWithTag("asset_charts_list").performScrollToNode(hasTestTag("asset_chart_current_total")); snapshot("charts-composition-320dp-2x")
        ui.onNodeWithTag("asset_chart_current_total").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>(); action(layouts)
            Assert.assertFalse(layouts.first().hasVisualOverflow)
        }
        ui.onNodeWithTag("asset_charts_list").performScrollToNode(hasTestTag("asset_rank_${rows().maxBy { it.balanceCent }.account.id}")); snapshot("charts-ranking-320dp-2x")
    }
}
