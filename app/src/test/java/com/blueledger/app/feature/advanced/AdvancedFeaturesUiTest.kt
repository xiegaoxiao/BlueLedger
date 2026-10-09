package com.blueledger.app.feature.advanced

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w354dp-h792dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdvancedFeaturesUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private lateinit var db: LedgerDatabase
    private lateinit var repository: LedgerRepositoryImpl
    private val clock = FixedClock()
    private var detail: String? = null
    @Before fun prepare() = runBlocking {
        ui.mainClock.autoAdvance = false
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LedgerDatabase::class.java).allowMainThreadQueries().build()
        repository = LedgerRepositoryImpl(db, clock)
        repository.initializeIfNeeded()
        Unit
    }
    @After fun close() { db.close() }
    private fun open() { ui.setContent { BlueLedgerTheme { AdvancedFeaturesRoute(repository, clock, {}, { detail = it }) } }; settle() }
    private fun settle() { repeat(8) { ui.mainClock.advanceTimeByFrame(); org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle() } }
    private fun click(tag: String) {
        await { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().any { !it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
        ui.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }
        settle()
    }
    private fun await(condition: () -> Boolean) { ui.waitUntil(10_000) { settle(); condition() }; settle() }
    // IME 焦点等待在 Robolectric 手动时钟下无法收敛；通过真实 SetText
    // 语义操作触发输入回调，再验证按钮状态、保存事务及显示结果。
    private fun fill(tag: String, value: String) {
        ui.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(value)) }
        settle()
    }
    private fun scrollListTo(tag: String) {
        repeat(20) {
            settle()
            if (ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return
            ui.onNodeWithTag("advanced_list").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.ScrollBy) { it(0f, 300f) }
        }
        throw AssertionError("未找到 $tag")
    }
    private fun state() = runBlocking { repository.observeAdvancedSettings().first() }
    private fun seed(): String = runBlocking { (repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 1250, "cat_expense_food", "acc_default", clock.today(), "午餐"), "ui-fixture") as SaveResult.Success).transactionId }
    private fun shot(name: String) {
        val file = File("build/advanced-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun categoryBudgetCanBeSavedAndShowsRealUsage() {
        seed(); open()
        click("advanced_分类预算")
        click("category_budget_cat_expense_food")
        fill("advanced_amount", "0")
        ui.onNodeWithTag("advanced_save").assertIsNotEnabled()
        fill("advanced_amount", "12.345")
        ui.onNodeWithTag("advanced_save").assertIsNotEnabled()
        fill("advanced_amount", "50")
        click("advanced_save")
        await { state().categoryBudgets.any { it.amountCent == 5000L } }
        await { ui.onAllNodesWithText("已用 12.50 / 50.00").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithText("已用 12.50 / 50.00").assertIsDisplayed()
        shot("category-budgets")
    }

    @Test fun tagCanBeCreatedAssociatedAndFilteredWithoutChangingTransaction() {
        val id = seed(); open()
        click("advanced_记账标签")
        click("tag_add")
        fill("advanced_name", "旅行")
        click("advanced_save")
        await { state().tags.size == 1 }
        click("tag_${state().tags.single().id}")
        click("tag_assign")
        click("tag_transaction_$id")
        await { state().transactionTags[id]?.size == 1 }
        await { ui.onAllNodesWithTag("tag_transaction_$id").fetchSemanticsNodes().any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.ToggleableState) { androidx.compose.ui.state.ToggleableState.Off } == androidx.compose.ui.state.ToggleableState.On } }
        ui.onNodeWithTag("tag_transaction_$id").assertIsOn()
        click("tag_assign")
        ui.onNodeWithText("餐饮").assertIsDisplayed()
        assertEquals(1250L, runBlocking { repository.observeTransaction(id).first()!!.amountCent })
        shot("tag-transactions")
    }

    @Test fun calendarShowsDailyTotalsAndOpensRealDetail() {
        val id = seed(); open()
        click("advanced_记账日历")
        click("calendar_2026-10-07")
        ui.onNodeWithText("收入 0.00 · 支出 12.50").assertIsDisplayed()
        shot("calendar")
        ui.onNodeWithText("餐饮").performClick()
        assertEquals(id, detail)
    }

    @Test fun recycleBinRestoresOriginalRecord() {
        val id = seed(); runBlocking { repository.softDeleteTransaction(id) }; open()
        click("advanced_回收站")
        shot("recycle-bin")
        click("trash_restore_$id")
        await { runBlocking { repository.observeRecycleBin().first().isEmpty() } }
        assertEquals(id, runBlocking { repository.observeTransaction(id).first()!!.id })
        ui.onNodeWithText("回收站为空").assertIsDisplayed()
    }

    @Test fun monthStartChangesPersistAndRecurringFormRejectsEmptyInput() {
        open()
        click("advanced_月起始日")
        scrollListTo("month_start_15")
        click("month_start_15")
        await { state().monthStartDay == 15 }
        shot("month-start")
        click("btn_back")
        click("advanced_自动记账")
        click("recurring_add")
        ui.onNodeWithTag("advanced_save").assertIsNotEnabled()
        fill("recurring_name", "房租")
        fill("recurring_amount", "1500")
        fill("recurring_date", "2026-10-08")
        settle()
        ui.onNodeWithTag("advanced_save").assertIsEnabled()
        click("advanced_save")
        await { state().recurringRules.size == 1 }
        assertEquals(150000L, state().recurringRules.single().amountCent)
        assertEquals(0, runBlocking { repository.observeTransactions(TransactionFilter()).first().totalCount })
        shot("recurring-rules")
    }
}
