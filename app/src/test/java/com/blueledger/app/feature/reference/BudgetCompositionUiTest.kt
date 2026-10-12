package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerApp
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import com.blueledger.app.di.AppContainer
import com.blueledger.app.feature.backup.JsonBackupCodec
import kotlinx.coroutines.CompletableDeferred
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
class BudgetCompositionUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repository = InMemoryLedgerRepository(clock)
    @Before fun setup() {
        ReferencePreferences(ui.activity).storage.edit().clear().commit()
        runBlocking { repository.initializeIfNeeded() }
    }
    private fun await(tag: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }
    private fun open(source: LedgerRepository = repository) {
        val container = object : AppContainer {
            override val clock = this@BudgetCompositionUiTest.clock
            override val ledgerRepository = source
            override val backupCodec = JsonBackupCodec(clock)
        }
        ui.setContent { BlueLedgerApp(container) }; await("overview_screen")
        ui.onNodeWithTag("home_预算").performClick(); ui.waitForIdle()
    }
    private fun snapshot(name: String) {
        val file = File("build/budget-composition-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun budget() = runBlocking { repository.observeBudget(clock.currentYearMonth()).first() }
    private fun edit() {
        if (ui.onAllNodesWithTag("budget_empty_action").fetchSemanticsNodes().isNotEmpty()) {
            ui.onNodeWithTag("budget_empty_action").performClick(); await("key_0"); return
        }
        ui.onNodeWithText("编辑").performClick()
        ui.onNodeWithText("编辑月度总预算").performClick(); await("key_0")
    }
    @Test fun emptyPageRendersActualRoute() {
        open(); await("budget_empty"); ui.onNodeWithText("暂无预算").assertIsDisplayed()
        ui.onNodeWithTag("budget_empty_action").assertIsDisplayed().assertIsEnabled()
        ui.onNodeWithTag("budget_remaining").assertDoesNotExist()
        snapshot("empty-normal")
    }
    @Test fun hiddenAmountsAlsoHideBudgetPercentage() {
        runBlocking {
            repository.setMonthlyBudget(100000, clock.currentYearMonth())
            repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 25000, "cat_expense_food", "acc_default", clock.today(), "虚构支出"), "budget-demo")
            repository.setHideAmounts(true)
        }
        open(); snapshot("hidden")
        ui.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        ui.onAllNodesWithText("1,000.00").assertCountEquals(0)
    }
    @Test fun savesExactAmountAndCancellationKeepsBudgetAndBills() {
        runBlocking {
            repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 1250, "cat_expense_food", "acc_default", clock.today(), "虚构午餐"), "budget-cancel-demo")
        }
        val originalBills = runBlocking { repository.observeTransactions(TransactionFilter()).first().items }
        open(); edit(); ui.onNodeWithTag("budget_save").assertIsNotEnabled()
        snapshot("editor-empty-normal")
        "1250".forEach { ui.onNodeWithTag("key_$it").performClick() }
        ui.onNodeWithTag("budget_save").performClick(); await("budget_edit")
        Assert.assertEquals(125000L, budget().budgetCent)
        ui.onNodeWithTag("budget_remaining").assertTextEquals("1237.50")
        snapshot("populated-normal")
        edit(); repeat(7) { ui.onNodeWithTag("key_delete").performClick() }
        ui.onNodeWithTag("key_9").performClick()
        ui.onNodeWithTag("budget_draft").assertTextEquals("9")
        ui.onNodeWithTag("budget_cancel").performClick(); await("budget_edit")
        Assert.assertEquals(125000L, budget().budgetCent)
        ui.onNodeWithTag("budget_edit").performClick(); ui.onNodeWithText("清除月度总预算").performClick()
        await("budget_clear_dialog"); snapshot("clear-confirm")
        ui.onNodeWithTag("budget_clear_cancel").performClick()
        Assert.assertEquals(125000L, budget().budgetCent)
        ui.onNodeWithTag("budget_edit").performClick(); ui.onNodeWithText("清除月度总预算").performClick()
        ui.onNodeWithTag("budget_clear_confirm").performClick()
        ui.waitUntil(10_000) { budget().budgetCent == null }
        await("budget_empty"); ui.onNodeWithTag("budget_empty_action").assertIsDisplayed()
        Assert.assertEquals(originalBills, runBlocking { repository.observeTransactions(TransactionFilter()).first().items })
    }
    @Test fun savingDisablesActionsAndFailureKeepsDraftForRetry() {
        val gate = CompletableDeferred<Unit>()
        var firstWrite = true
        val source = object : LedgerRepository by repository {
            override suspend fun setMonthlyBudget(amountCent: Long?, selectedMonth: java.time.YearMonth): MutationResult {
                if (firstWrite) {
                    firstWrite = false; gate.await()
                    return MutationResult.Failure(LedgerError.Validation(ValidationCode.BUDGET_OUT_OF_RANGE, "虚构保存失败"))
                }
                return repository.setMonthlyBudget(amountCent, selectedMonth)
            }
        }
        open(source); edit(); ui.onNodeWithTag("key_5").performClick()
        ui.onNodeWithTag("budget_save").performClick(); ui.waitForIdle()
        ui.onNodeWithTag("budget_save").assertIsNotEnabled()
        ui.onNodeWithTag("budget_cancel").assertIsNotEnabled()
        snapshot("saving")
        gate.complete(Unit); await("budget_editor_error")
        ui.onNodeWithTag("budget_draft").assertTextEquals("5")
        snapshot("save-failed")
        ui.onNodeWithTag("budget_save").performClick(); await("budget_edit")
        Assert.assertEquals(500L, budget().budgetCent)
    }
}
