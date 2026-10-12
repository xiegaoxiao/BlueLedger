package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerApp
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w320dp-h548dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BudgetCategoryUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private lateinit var db: LedgerDatabase
    private lateinit var repository: LedgerRepositoryImpl
    @Before fun setup() = runBlocking {
        ReferencePreferences(ui.activity).storage.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        repository = LedgerRepositoryImpl(db, clock); repository.initializeIfNeeded(); Unit
    }
    @After fun close() { db.close() }
    private fun await(tag: String) { ui.waitUntil(10000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() }
    private fun click(tag: String) { await(tag); ui.onNodeWithTag(tag).performClick(); ui.waitForIdle() }
    private fun state() = runBlocking { repository.observeAdvancedSettings().first() }
    private fun open(source: LedgerRepository = repository) {
        val container = object : AppContainer {
            override val clock = this@BudgetCategoryUiTest.clock
            override val ledgerRepository = source
            override val backupCodec = JsonBackupCodec(clock)
        }
        ui.setContent { BlueLedgerApp(container) }; click("home_预算"); await("budget_screen")
    }
    private fun shot(name: String) {
        val file = File("build/budget-composition-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun input(value: String, clear: Boolean = false) {
        await("key_0")
        if (clear) repeat(12) { click("key_delete") }
        value.forEach { click(if (it == '.') "key_dot" else "key_$it") }
    }
    private fun seed() = runBlocking {
        repository.setMonthlyBudget(1300000, clock.currentYearMonth())
        repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 99900, "cat_expense_food", "acc_default", clock.today(), "虚构餐饮"), "budget-oct")
        repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 10000, "cat_expense_food", "acc_default", LocalDate.of(clock.today().year, 1, 3), "虚构年初支出"), "budget-jan")
        repository.createTransaction(TransactionDraft(TransactionType.INCOME, 900000, "cat_income_salary", "acc_default", clock.today(), "虚构收入"), "budget-income")
        Unit
    }
    private fun addCategory(value: String) { click("budget_category_add"); click("budget_choose_cat_expense_food"); input(value); click("budget_save"); await("category_budget_cat_expense_food") }
    @Test fun categoryAddEditCancelDeleteAndRealExpenses() {
        seed(); open(); await("budget_categories_empty"); shot("category-empty-320")
        addCategory("2000")
        ui.onNodeWithTag("category_budget_cat_expense_food_remaining").assertTextEquals("1001.00")
        ui.onNodeWithTag("category_budget_cat_expense_food_used").assertTextEquals("999.00")
        val totalBounds = ui.onNodeWithTag("budget_summary").fetchSemanticsNode().boundsInRoot
        val totalRing = ui.onNodeWithTag("budget_ring").fetchSemanticsNode().boundsInRoot
        val categoryBounds = ui.onNodeWithTag("category_budget_cat_expense_food_summary").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("总预算环图保持参考图宽度比例", 87f / 361f, totalRing.width / totalBounds.width, .005f)
        Assert.assertEquals("总预算区块保持参考图高度比例", 143f / 361f, totalBounds.height / totalBounds.width, .008f)
        Assert.assertEquals("分类预算行保持参考图高度比例", 138f / 361f, categoryBounds.height / categoryBounds.width, .008f)
        ui.onNodeWithText("50%").assertIsDisplayed(); shot("category-populated-320")
        click("category_budget_cat_expense_food_edit"); click("budget_menu_edit"); input("3000", true); click("budget_cancel")
        Assert.assertEquals(200000L, state().categoryBudgets.single().amountCent)
        click("category_budget_cat_expense_food_edit"); click("budget_menu_edit"); input("3000", true); click("budget_save")
        ui.waitUntil(10000) { state().categoryBudgets.single().amountCent == 300000L }
        click("category_budget_cat_expense_food_edit"); click("budget_menu_remove"); click("budget_clear_cancel")
        Assert.assertEquals(1, state().categoryBudgets.size)
        click("category_budget_cat_expense_food_edit"); click("budget_menu_remove"); click("budget_clear_confirm")
        ui.waitUntil(10000) { state().categoryBudgets.isEmpty() }; await("budget_categories_empty")
        Assert.assertEquals(3, runBlocking { repository.observeTransactions(TransactionFilter()).first().totalCount })
    }
    @Test fun monthlyAndYearlyTotalsAndCategoriesRemainIndependent() {
        seed(); open(); addCategory("2000")
        click("budget_cycle"); ui.onNodeWithTag("budget_cycle_month").assertIsSelected(); shot("cycle-menu-320")
        click("budget_cycle_year"); await("budget_empty"); click("budget_empty_action"); input("40000"); click("budget_save"); await("budget_edit")
        ui.onNodeWithTag("budget_used").assertTextEquals("1099.00")
        addCategory("4000")
        Assert.assertEquals(4000000L, state().yearlyBudgetCent)
        Assert.assertEquals(400000L, state().yearlyCategoryBudgets.single().amountCent)
        ui.onNodeWithTag("category_budget_cat_expense_food_remaining").assertTextEquals("2901.00")
        shot("year-populated-320")
        click("budget_month"); await("budget_year")
        ui.onNodeWithTag("budget_year").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it((clock.today().year - 1).toFloat()) }
        click("year_dismiss"); ui.onNodeWithTag("budget_used").assertTextEquals("1099.00")
        click("budget_month"); await("budget_year")
        ui.onNodeWithTag("budget_year").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it((clock.today().year - 1).toFloat()) }
        click("year_confirm"); await("budget_used"); ui.waitUntil(10000) { ui.onAllNodes(hasTestTag("budget_used") and hasText("0.00")).fetchSemanticsNodes().isNotEmpty() }
        click("budget_cycle"); ui.onNodeWithTag("budget_cycle_year").assertIsSelected(); click("budget_cycle_month")
        await("budget_remaining"); ui.onNodeWithTag("budget_total").assertTextEquals("13000.00")
        ui.onNodeWithTag("category_budget_cat_expense_food_total").assertTextEquals("2000.00")
        click("budget_edit"); click("budget_menu_remove"); click("budget_clear_confirm")
        await("budget_total_add"); ui.onNodeWithTag("category_budget_cat_expense_food").assertIsDisplayed()
        Assert.assertEquals(4000000L, state().yearlyBudgetCent)
    }
    @Test fun failedCategorySaveKeepsDraftAndLocksCloseUntilFinished() {
        seed(); val gate = CompletableDeferred<Unit>(); var first = true
        val source = object : LedgerRepository by repository {
            override suspend fun updateAdvancedSettings(change: (AdvancedLedgerSettings) -> AdvancedLedgerSettings): MutationResult {
                if (first) { first = false; gate.await(); return MutationResult.Failure(LedgerError.Storage("虚构分类保存失败")) }
                return repository.updateAdvancedSettings(change)
            }
        }
        open(source); click("budget_category_add"); click("budget_choose_cat_expense_food"); input("2000"); click("budget_save")
        ui.onNodeWithTag("budget_cancel").assertIsNotEnabled(); ui.onNodeWithTag("key_0").assertIsNotEnabled()
        gate.complete(Unit); await("budget_editor_error"); ui.onNodeWithTag("budget_draft").assertTextEquals("2000")
        Assert.assertTrue(state().categoryBudgets.isEmpty()); shot("category-save-failed-320")
        click("budget_save"); await("category_budget_cat_expense_food")
        Assert.assertEquals(200000L, state().categoryBudgets.single().amountCent)
    }
    @Test fun hidesAmountsAndPercentagesInBothKindsOfBudget() {
        seed(); runBlocking {
            repository.updateAdvancedSettings { it.copy(categoryBudgets = listOf(CategoryBudgetRule("cat_expense_food", 200000)), yearlyBudgetCent = 4000000, yearlyCategoryBudgets = listOf(CategoryBudgetRule("cat_expense_food", 400000))) }
            repository.setHideAmounts(true)
        }
        open(); await("budget_remaining"); ui.onAllNodes(hasText("%", substring = true)).assertCountEquals(0)
        ui.onNodeWithTag("category_budget_cat_expense_food_remaining").assertTextEquals("••••")
        click("budget_cycle"); click("budget_cycle_year"); await("budget_remaining")
        ui.onAllNodes(hasText("%", substring = true)).assertCountEquals(0); ui.onNodeWithTag("budget_used").assertTextEquals("••••")
        shot("category-hidden-320")
    }
}
