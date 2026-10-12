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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
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
class MonthlyReportReferenceUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repo = InMemoryLedgerRepository(clock)
    private val month = YearMonth.of(2026, 10)
    private var openedMonth: YearMonth? = null
    private var openedId: String? = null
    @Before fun prepare() { runBlocking { repo.initializeIfNeeded() } }
    private fun open(hidden: Boolean = false) {
        ui.setContent { BlueLedgerTheme(hideAmounts = hidden) { ReferenceMonthReportRoute(repo, clock, month, {}, { openedId = it }, { openedMonth = it }) } }
        ui.waitUntil(10_000) { ui.onAllNodesWithTag("month_report_summary").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun snapshot(name: String) {
        val file = File("build/monthly-report-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun seed() = runBlocking {
        listOf(1 to 10000L, 2 to 20000L, 4 to 30000L).forEach { (day, cents) ->
            repo.createTransaction(TransactionDraft(TransactionType.EXPENSE, cents, "cat_expense_food", Defaults.DEFAULT_ACCOUNT_ID, LocalDate.of(2026, 10, day)), "fictional-$day")
        }
        repo.createTransaction(TransactionDraft(TransactionType.INCOME, 100000, "cat_income_salary", Defaults.DEFAULT_ACCOUNT_ID, LocalDate.of(2026, 10, 2)), "fictional-income")
        repo.createTransaction(TransactionDraft(TransactionType.INCOME, 15000, "cat_income_salary", Defaults.DEFAULT_ACCOUNT_ID, LocalDate.of(2026, 9, 1)), "fictional-previous")
    }
    @Test fun emptyCardsFollowReferenceWithoutProfileOrShare() {
        open()
        val density = ui.activity.resources.displayMetrics.density
        val summary = ui.onNodeWithTag("month_report_summary").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(200f, summary.top / density, 1f); Assert.assertEquals(180f, summary.height / density, 1f)
        val metrics = ui.onNodeWithTag("month_report_metrics").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(390f, metrics.top / density, 1f); Assert.assertEquals(122f, metrics.height / density, 1f)
        ui.onNodeWithTag("month_report_balance").assertTextContains("0.00")
        ui.onNodeWithTag("month_report_count").assertTextEquals("0")
        ui.onNodeWithTag("report_share").assertDoesNotExist(); ui.onNodeWithText("分享到").assertDoesNotExist()
        snapshot("empty")
    }
    @Test fun populatedBalancesAndMonthlyAchievementsAreRealAndBillsRemainReachable() {
        seed(); open()
        ui.onNodeWithTag("month_report_balance").assertTextContains("400.00")
        ui.onNodeWithTag("month_report_previous").assertTextContains("150.00")
        ui.onNodeWithTag("month_report_expense").assertTextContains("600.00")
        ui.onNodeWithTag("month_report_income").assertTextContains("1000.00")
        ui.onNodeWithTag("month_report_streak").assertTextEquals("2")
        ui.onNodeWithTag("month_report_days").assertTextEquals("3")
        ui.onNodeWithTag("month_report_count").assertTextEquals("4"); snapshot("populated")
        val firstId = runBlocking { repo.observeTransactions(TransactionFilter(yearMonth = month)).first().items }.first().id
        ui.onNodeWithTag("month_report_list").performScrollToNode(hasTestTag("month_report_row_$firstId"))
        ui.onNodeWithTag("month_report_row_$firstId").performClick(); Assert.assertEquals(firstId, openedId)
        ui.onNodeWithTag("month_report_list").performScrollToNode(hasTestTag("month_report_all"))
        snapshot("details"); ui.onNodeWithTag("month_report_all").performClick(); Assert.assertEquals(month, openedMonth)
    }
    @Test @Config(qualifiers = "w320dp-h548dp-xhdpi")
    fun smallScreenPrivacyKeepsSummaryAndMetricsReadable() {
        seed(); open(hidden = true)
        ui.onNodeWithTag("month_report_balance").assertTextContains("••••")
        ui.onNodeWithTag("month_report_previous").assertTextContains("••••")
        ui.onNodeWithTag("month_report_income").assertTextContains("••••")
        ui.onNodeWithTag("month_report_metrics").assertIsDisplayed(); snapshot("small-hidden")
    }
}
