package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w361dp-h616dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BillReferenceUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repo = InMemoryLedgerRepository(clock)
    private var drilled: YearMonth? = null
    @Before fun prepare() { runBlocking { repo.initializeIfNeeded() } }
    private fun open(hidden: Boolean = false) {
        ui.setContent { BlueLedgerTheme(hideAmounts = hidden) { ReferenceBillsRoute(repo, clock, onMonth = { drilled = it }) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("bills_balance").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun snapshot(name: String) {
        val file = File("build/bill-reference-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun emptyMonthAndYearMatchReferenceAndSelectionCancels() {
        open()
        val density = ui.activity.resources.displayMetrics.density
        val card = ui.onNodeWithTag("bills_summary_card").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(69f, card.top / density, 1f); Assert.assertEquals(124f, card.height / density, 1f)
        val period = ui.onNodeWithTag("bills_period_switch").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(140f, period.width / density, 1f); Assert.assertEquals(29f, period.height / density, 1f)
        ui.onNodeWithTag("bills_row_9月").assertDoesNotExist(); snapshot("month-empty")
        ui.onNodeWithTag("bills_row_10月").performClick(); Assert.assertEquals(YearMonth.of(2026, 10), drilled)
        ui.onNodeWithTag("bills_year_selector").performClick(); ui.onNodeWithText("取消").performClick()
        ui.onNodeWithTag("bills_row_10月").assertIsDisplayed()
        ui.onNodeWithTag("bills_tab_year").performClick().assertIsSelected()
        ui.onNodeWithTag("bills_year_selector").assertDoesNotExist(); ui.onNodeWithText("总结余").assertIsDisplayed()
        ui.onNodeWithText("年账单为自然年（1.1–12.31）").assertDoesNotExist(); snapshot("year-empty")
        ui.onNodeWithTag("bills_row_2026年").performClick(); ui.onNodeWithTag("bills_tab_month").assertIsSelected()
    }
    @Test fun annualTotalsUseCalendarYearEvenWithCustomMonthStart() {
        runBlocking {
            repo.updateAdvancedSettings { it.copy(monthStartDay = 15) }
            repo.createTransaction(TransactionDraft(TransactionType.INCOME, 30000, "cat_income_salary", Defaults.DEFAULT_ACCOUNT_ID, LocalDate.of(2026, 1, 1)), "fictional-income")
            repo.createTransaction(TransactionDraft(TransactionType.EXPENSE, 10000, "cat_expense_food", Defaults.DEFAULT_ACCOUNT_ID, LocalDate.of(2025, 12, 31)), "fictional-expense")
        }
        open(); ui.onNodeWithTag("bills_tab_year").performClick()
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("bills_row_2025年").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("bills_row_2026年").assertTextContains("300.00")
        ui.onNodeWithTag("bills_row_2025年").assertTextContains("-100.00", substring = true)
        ui.onNodeWithTag("bills_balance").assertTextContains("200.00"); snapshot("year-populated")
        Assert.assertEquals(2, runBlocking { repo.observeTransactions(TransactionFilter()).first().totalCount })
    }
    @Test @Config(qualifiers = "w320dp-h548dp-xhdpi")
    fun smallScreenKeepsControlsAndHiddenAmounts() {
        open(hidden = true); ui.onNodeWithTag("bills_balance").assertTextContains("••••")
        ui.onNodeWithTag("bills_tab_month").assertIsDisplayed(); ui.onNodeWithTag("bills_row_10月").assertIsDisplayed(); snapshot("month-small-hidden")
        ui.onNodeWithTag("bills_tab_year").performClick(); ui.onNodeWithTag("bills_row_2026年").assertIsDisplayed(); snapshot("year-small-hidden")
    }
}
