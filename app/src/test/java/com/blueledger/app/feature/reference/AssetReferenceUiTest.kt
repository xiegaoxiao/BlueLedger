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
@Config(sdk = [34], qualifiers = "w361dp-h616dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssetReferenceUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repo = InMemoryLedgerRepository(clock)
    @Before fun prepare() { ReferencePreferences(ui.activity).storage.edit().clear().commit(); runBlocking { repo.initializeIfNeeded() } }
    private fun await(tag: String) { ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() }
    private fun open() {
        val container = object : AppContainer { override val clock = this@AssetReferenceUiTest.clock; override val ledgerRepository = repo; override val backupCodec = JsonBackupCodec(clock) }
        ui.setContent { BlueLedgerApp(container) }; await("overview_screen")
        ui.onNodeWithTag("tab_mine").performClick(); ui.onNodeWithText("资产管家").performClick(); await("assets_screen")
    }
    private fun rows() = runBlocking { repo.observeAccounts(false).first().filter { it.account.id != Defaults.DEFAULT_ACCOUNT_ID } }
    private fun add() { ui.onNodeWithTag("asset_add").performScrollTo().performClick(); await("asset_type_CASH") }
    private fun screenshot(name: String) {
        val file = File("build/asset-reference-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun cashCreatesDetailAdjustsAndSettingsCancelPreservesBills() {
        open(); add(); screenshot("types")
        ui.onNodeWithText("信用卡").assertDoesNotExist(); ui.onNodeWithText("投资账户").assertDoesNotExist()
        ui.onNodeWithTag("asset_type_CASH").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_save").assertIsNotEnabled()
        ui.onNodeWithTag("asset_balance").performTextReplacement("1234213.40"); screenshot("cash-editor")
        ui.onNodeWithTag("asset_save").performScrollTo().performClick(); await("assets_screen")
        val id = rows().single().account.id
        ui.onNodeWithTag("asset_total").assertTextContains("1234213.40")
        val density = ui.activity.resources.displayMetrics.density
        val card = ui.onNodeWithTag("asset_overview_card").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(69f, card.top / density, 2f); Assert.assertEquals(124f, card.height / density, 1f)
        screenshot("home-cash")
        ui.onNodeWithTag("asset_account_$id").performClick(); await("asset_detail")
        ui.onNodeWithText("余额调整").assertIsDisplayed()
        val detail = ui.onNodeWithTag("asset_detail_card").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(59f, detail.top / density, 2f); Assert.assertEquals(127f, detail.height / density, 1f)
        screenshot("cash-detail")
        ui.onNodeWithTag("asset_edit").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_name").performTextReplacement("虚构新名称"); screenshot("cash-settings")
        ui.onNodeWithTag("asset_cancel").performClick(); await("asset_detail")
        Assert.assertEquals("现金", rows().single().account.name)
        ui.onNodeWithTag("asset_adjust_balance").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_balance").performTextReplacement("123.45")
        ui.onNodeWithTag("asset_save").performScrollTo().performClick(); await("asset_detail")
        ui.waitUntil(5_000) { rows().single().balanceCent == 12345L }
        Assert.assertEquals(0, runBlocking { repo.observeTransactions(TransactionFilter()).first().totalCount })
        ui.onNodeWithTag("asset_remove").performClick(); ui.onNodeWithTag("asset_remove_cancel").performClick()
        Assert.assertEquals(1, rows().size)
        ui.onNodeWithTag("asset_remove").performClick(); ui.onNodeWithTag("asset_remove_confirm").performClick(); await("assets_screen")
        ui.waitUntil(5_000) { rows().isEmpty() }
        Assert.assertTrue(runBlocking { repo.observeAccounts(true).first() }.first { it.account.id == id }.account.isArchived)
    }
    @Test fun bankAndThreeVirtualAccountsSaveExactlyAndRetryRetainsDraft() {
        open(); add(); ui.onNodeWithTag("asset_type_BANK_CARD").performClick(); screenshot("banks")
        ui.onNodeWithTag("asset_type_list").performScrollToNode(hasTestTag("asset_bank_10")); ui.onNodeWithTag("asset_bank_10").assertIsDisplayed(); screenshot("banks-bottom")
        ui.onNodeWithTag("asset_type_list").performScrollToNode(hasTestTag("asset_bank_0")); ui.onNodeWithTag("asset_bank_0").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_card_last_four").performTextReplacement("123")
        ui.onNodeWithTag("asset_balance").performTextReplacement("1000.10"); ui.onNodeWithTag("asset_save").assertIsNotEnabled()
        ui.onNodeWithTag("asset_card_last_four").performTextReplacement("1234"); screenshot("bank-editor")
        repo.failNextWrite = LedgerError.Storage("虚构存储失败")
        ui.onNodeWithTag("asset_save").performScrollTo().performClick(); ui.onNodeWithText("虚构存储失败").assertIsDisplayed()
        Assert.assertEquals(0, rows().size)
        ui.onNodeWithTag("asset_card_last_four").assertTextContains("1234")
        ui.onNodeWithTag("asset_save").performScrollTo().performClick(); await("assets_screen")
        Assert.assertEquals("工商银行 · 1234", rows().single().account.name)
        Assert.assertEquals(100010L, rows().single().balanceCent)
        listOf(AccountLocation.ALIPAY_BALANCE, AccountLocation.WECHAT_BALANCE, AccountLocation.ALIPAY_POCKET).forEachIndexed { index, location ->
            add(); ui.onNodeWithTag("asset_type_virtual").performClick(); if (index == 0) screenshot("virtual-types")
            ui.onNodeWithText("其他虚拟账户").assertDoesNotExist()
            ui.onNodeWithTag("asset_type_${location.name}").performClick(); await("asset_editor")
            ui.onNodeWithTag("asset_balance").performTextReplacement("20.25")
            ui.onNodeWithTag("asset_save").performScrollTo().performClick(); await("assets_screen")
        }
        Assert.assertEquals(4, rows().size); Assert.assertEquals(3, rows().count { it.account.kind == AccountKind.E_WALLET })
        screenshot("home-mixed")
    }
    @Test fun transferCancelAndRetryChangeBothBalancesWithoutCreatingBills() {
        runBlocking {
            repo.upsertAccount(AccountCommand(name = "虚构现金", kind = AccountKind.CASH, openingBalanceCent = 10000))
            repo.upsertAccount(AccountCommand(name = "虚构微信", kind = AccountKind.E_WALLET, location = AccountLocation.WECHAT_BALANCE, openingBalanceCent = 20000))
        }
        open()
        val source = rows().first { it.account.name == "虚构现金" }.account.id
        val target = rows().first { it.account.name == "虚构微信" }.account.id
        ui.onNodeWithTag("asset_account_$source").performClick(); await("asset_detail")
        fun draft() {
            ui.onNodeWithTag("asset_transfer").performClick(); await("asset_transfer_dialog")
            ui.onNodeWithTag("asset_transfer_to_${Defaults.DEFAULT_ACCOUNT_ID}").assertDoesNotExist()
            ui.onNodeWithText("到账账户").assertIsDisplayed()
            ui.onNodeWithTag("asset_transfer_to_$target").performClick()
            ui.onNodeWithTag("asset_transfer_amount").performTextReplacement("25")
            screenshot("transfer-ready")
        }
        draft(); ui.onNodeWithTag("asset_transfer_cancel").performClick()
        Assert.assertEquals(10000L, rows().first { it.account.id == source }.balanceCent)
        draft(); repo.failNextWrite = LedgerError.Storage("虚构转账失败")
        ui.onNodeWithTag("asset_transfer_confirm").performClick()
        ui.onNodeWithText("虚构转账失败").assertIsDisplayed()
        Assert.assertEquals(30000L, rows().sumOf { it.balanceCent })
        ui.onNodeWithTag("asset_transfer_confirm").performClick()
        ui.waitUntil(5_000) { rows().first { it.account.id == source }.balanceCent == 7500L }
        Assert.assertEquals(22500L, rows().first { it.account.id == target }.balanceCent)
        Assert.assertEquals(0, runBlocking { repo.observeTransactions(TransactionFilter()).first().totalCount })
    }
    @Test @Config(qualifiers = "w320dp-h548dp-xhdpi")
    fun singleRealAccountDoesNotOfferSystemDefaultAndCanAddDestination() {
        runBlocking { repo.upsertAccount(AccountCommand(name = "虚构银行卡 · 4443", kind = AccountKind.BANK_CARD, location = AccountLocation.BANK_CARD, openingBalanceCent = 10000)) }
        open(); ui.onNodeWithTag("asset_account_${rows().single().account.id}").performClick(); await("asset_detail")
        ui.onNodeWithTag("asset_transfer").performClick(); await("asset_transfer_dialog")
        ui.onNodeWithText(Defaults.DEFAULT_ACCOUNT_NAME).assertDoesNotExist()
        ui.onNodeWithText("暂无可转入的账户").assertIsDisplayed()
        ui.onNodeWithTag("asset_transfer_amount").assertDoesNotExist()
        ui.onNodeWithTag("asset_transfer_confirm").assertDoesNotExist()
        screenshot("transfer-no-destination-small")
        ui.onNodeWithTag("asset_transfer_add_account").performClick(); await("asset_type_CASH")
        ui.onNodeWithTag("btn_back").performClick(); await("asset_detail")
        Assert.assertEquals(10000L, rows().single().balanceCent)
    }
    @Test @Config(qualifiers = "w320dp-h548dp-xhdpi")
    fun smallScreenBankEditorKeepsFieldsAndSaveReachable() {
        open(); add(); screenshot("types-small")
        ui.onNodeWithTag("asset_type_BANK_CARD").performClick(); ui.onNodeWithTag("asset_bank_0").performClick(); await("asset_editor")
        ui.onNodeWithTag("asset_balance").performTextReplacement("9999999.99")
        ui.onNodeWithTag("asset_save").performScrollTo().assertIsDisplayed().assertIsEnabled(); screenshot("bank-editor-small")
        ui.onNodeWithTag("asset_cancel").performClick(); await("assets_screen"); Assert.assertEquals(0, rows().size)
    }
}
