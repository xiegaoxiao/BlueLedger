package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
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

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w361dp-h616dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MonthPickerReferenceUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repo = InMemoryLedgerRepository(clock)
    private var septemberId = ""
    @Before fun prepare() = runBlocking {
        repo.initializeIfNeeded()
        septemberId = (repo.createTransaction(TransactionDraft(TransactionType.EXPENSE, 1234,
            "cat_expense_food", Defaults.DEFAULT_ACCOUNT_ID, LocalDate.of(2026, 9, 5)), "fictional-picker") as SaveResult.Success).transactionId
    }
    private fun await(tag: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }
    private fun open() {
        ui.setContent { BlueLedgerTheme { ReferenceHomeRoute(repo, clock, {}, {}, {}, {}, {}) } }
        await("overview_month_label")
        ui.onNodeWithTag("overview_month_label").performClick(); await("month_picker")
    }
    private fun selectMonth(value: Float) {
        ui.onNodeWithTag("month_number").performSemanticsAction(SemanticsActions.SetProgress) { it(value) }
    }
    private fun snapshot(name: String) {
        val file = File("build/month-picker-ui/$name.png"); file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun referenceGeometryAndCancelKeepTheSelectedLedgerMonth() {
        open()
        val density = ui.activity.resources.displayMetrics.density
        val sheet = ui.onNodeWithTag("month_picker").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(244f, sheet.height / density, 1f)
        val year = ui.onNodeWithTag("month_year").fetchSemanticsNode().boundsInRoot
        val month = ui.onNodeWithTag("month_number").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(64f, year.width / density, 1f)
        Assert.assertEquals(180f, year.height / density, 1f)
        Assert.assertEquals(24f, (month.left - year.right) / density, 1f)
        snapshot("standard")
        selectMonth(9f)
        ui.onNodeWithTag("month_dismiss").performClick(); await("overview_month_label")
        ui.onNodeWithTag("overview_month_label").assertTextContains("10")
        ui.onNodeWithTag("overview_row_$septemberId").assertDoesNotExist()
        Assert.assertEquals(1, runBlocking { repo.observeTransactions(TransactionFilter()).first().items.size })
    }
    @Test fun confirmFiltersRealRecordsAndFutureSelectionRemainsBounded() {
        open()
        ui.onNodeWithTag("month_number").performTouchInput { swipeUp() }
        ui.waitForIdle()
        ui.onNodeWithTag("month_number").assertContentDescriptionEquals("月份，10")
        selectMonth(11f)
        ui.onNodeWithTag("month_number").assertContentDescriptionEquals("月份，10")
        selectMonth(9f)
        ui.onNodeWithTag("month_confirm").performClick(); await("overview_row_$septemberId")
        ui.onNodeWithTag("overview_month_label").assertTextContains("09")
        ui.onNodeWithTag("overview_month_label").performClick(); await("month_picker")
        ui.onNodeWithTag("month_number").assertContentDescriptionEquals("月份，9")
        ui.onNodeWithTag("month_dismiss").performClick(); await("overview_row_$septemberId")
    }
    @Test @Config(qualifiers = "w320dp-h548dp-xhdpi")
    fun smallScreenKeepsBothActionsAndWheelsVisible() {
        open()
        listOf("month_dismiss", "month_confirm", "month_year", "month_number").forEach { ui.onNodeWithTag(it).assertIsDisplayed() }
        snapshot("small")
        selectMonth(9f); ui.onNodeWithTag("month_confirm").performClick(); await("overview_row_$septemberId")
    }
}
