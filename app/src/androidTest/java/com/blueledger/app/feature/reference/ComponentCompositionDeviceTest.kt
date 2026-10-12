package com.blueledger.app.feature.reference

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blueledger.app.BlueLedgerApplication
import com.blueledger.app.MainActivity
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** Actual navigation and Room writes, restricted to the isolated fictional ledger APK. */
@RunWith(AndroidJUnit4::class)
class ComponentCompositionDeviceTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val container get() = (ui.activity.application as BlueLedgerApplication).container
    private val repository get() = container.ledgerRepository

    @Before fun requireIsolatedPackage() {
        assertEquals("Write tests require the isolated APK", "com.blueledger.app.adbcheck", context.packageName)
        await("overview_screen")
    }

    private fun await(tag: String) {
        ui.waitUntil(20_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }

    private fun click(tag: String) {
        ui.onNodeWithTag(tag).assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick)
        ui.waitForIdle()
    }

    private fun snapshot(name: String) {
        ui.waitForIdle()
        SystemClock.sleep(600) // Native dialog and IME animations finish after Compose becomes idle.
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "component-ui").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    @Test fun chartTypeDropdownCoversNavigationAndPreservesPeriod() {
        click("tab_statistics"); await("statistics_screen")
        click("stats_tab_year"); click("stats_type_selector"); await("stats_type_income")
        ui.onNodeWithTag("stats_type_expense").assertIsSelected()
        click("stats_type_income"); await("stats_empty")
        ui.onNodeWithTag("stats_tab_year").assertIsSelected()
        (1..12).forEach { ui.onNodeWithText("${it}月").assertIsDisplayed() }
        click("stats_type_selector"); await("stats_type_income")
        ui.onNodeWithTag("stats_type_income").assertIsSelected()
        val row = ui.onNodeWithTag("stats_type_expense").fetchSemanticsNode().boundsInRoot
        val scale = ui.activity.resources.configuration.screenWidthDp / 361f
        assertEquals(81f * scale, row.top / ui.activity.resources.displayMetrics.density, 2f)
        snapshot("chart-type-panel")
        InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        ui.waitUntil(5_000) { ui.onAllNodesWithTag("stats_type_income").fetchSemanticsNodes().isEmpty() }
        ui.onNodeWithTag("stats_tab_year").assertIsSelected()
        ui.onNodeWithTag("stats_summary_card").assertTextContains("总收入：0.00", substring = true)
    }

    @Test fun datePickerCancelPreservesDraftAndConfirmSavesExactlyOnce() {
        val before = runBlocking { repository.exportConsistentSnapshot() }
        click("global_record_button"); await("category_cat_expense_food")
        click("category_cat_expense_food"); await("key_date")
        listOf("key_1", "key_2", "key_dot", "key_5", "key_0").forEach(::click)
        ui.onNodeWithTag("amount_display").assertTextEquals("12.50")
        click("key_date"); await("date_picker")
        ui.onNodeWithText("选择日期").assertIsDisplayed()
        ui.onNodeWithTag("date_confirm").assertIsDisplayed()
        ui.onNodeWithTag("date_dismiss").assertIsDisplayed()
        snapshot("date-picker")
        val today = container.clock.today()
        val selectedYear = today.year - 1
        ui.onNodeWithTag("date_year").performSemanticsAction(SemanticsActions.SetProgress) { it(selectedYear.toFloat()) }
        click("date_dismiss"); await("key_date")
        ui.onNodeWithTag("amount_display").assertTextEquals("12.50")
        assertEquals(before.transactions, runBlocking { repository.exportConsistentSnapshot() }.transactions)
        click("key_date"); await("date_picker")
        ui.onNodeWithTag("date_year").assertContentDescriptionEquals("年份，${today.year}")
        ui.onNodeWithTag("date_year").performSemanticsAction(SemanticsActions.SetProgress) { it(selectedYear.toFloat()) }
        click("date_confirm"); await("key_date")
        click("btn_save"); await("overview_screen")
        val after = runBlocking { repository.exportConsistentSnapshot() }
        val saved = after.transactions.single { row -> before.transactions.none { it.id == row.id } }
        assertEquals(before.transactions.size + 1, after.transactions.size)
        assertEquals(1250L, saved.amountCent)
        assertEquals(LocalDate.of(selectedYear, today.monthValue, today.dayOfMonth.coerceAtMost(java.time.YearMonth.of(selectedYear, today.monthValue).lengthOfMonth())), saved.occurredOn)
        val reopened = LedgerDatabase.create(context)
        try {
            val persisted = runBlocking { LedgerRepositoryImpl(reopened, container.clock).observeTransaction(saved.id).first() }
            assertEquals(saved, persisted)
        } finally { reopened.close() }
    }

    @Test fun settingsChoiceAndSwitchPersistAndKeepDisabledRows() {
        val before = runBlocking { repository.exportConsistentSnapshot() }.transactions
        click("tab_mine"); await("mine_settings")
        ui.onNodeWithTag("mine_settings").performScrollTo(); click("mine_settings")
        await("mine_categories")
        ui.onNodeWithText("图表页设置").performClick()
        ui.onNodeWithText("默认收支周期").performClick(); await("settings_choice_page")
        ui.onNodeWithTag("settings_choice_0").assertIsSelected()
        snapshot("settings-choice")
        click("settings_choice_1")
        assertEquals(ChartPeriod.MONTH, ReferencePreferences(context).chartPeriod)
        ui.onNodeWithText("默认收支周期").performClick(); await("settings_choice_page")
        ui.onNodeWithTag("settings_choice_1").assertIsSelected()
        click("btn_back"); click("btn_back")
        ui.onNodeWithText("收支账户").performClick(); await("account_association_switch")
        if (!ReferencePreferences(context).accountAssociation) click("account_association_switch")
        click("account_association_switch")
        assertFalse(ReferencePreferences(context).accountAssociation)
        ui.onNodeWithText("默认支出账户").assertIsNotEnabled()
        snapshot("settings-disabled")
        click("account_association_switch")
        assertTrue(ReferencePreferences(context).accountAssociation)
        ui.onNodeWithText("默认支出账户").assertIsEnabled()
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() }.transactions)
    }

    @Test fun assetFormValidatesSavesWithImeAndCancelKeepsBalance() {
        val before = runBlocking { repository.exportConsistentSnapshot() }
        click("home_资产管家"); await("asset_add"); click("asset_add")
        ui.onNodeWithTag("asset_type_BANK_CARD").performScrollTo(); click("asset_type_BANK_CARD")
        await("asset_editor")
        ui.onNodeWithTag("asset_save").assertIsNotEnabled()
        ui.onNodeWithTag("asset_name").performClick().performTextReplacement("虚构组件账户")
        ui.onNodeWithTag("asset_balance").performScrollTo().performClick().performTextReplacement("125.50")
        ui.waitUntil(15_000) {
            val insets = ViewCompat.getRootWindowInsets(ui.activity.window.decorView)
            insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0
        }
        SystemClock.sleep(600)
        ui.onNodeWithTag("asset_save").assertIsEnabled().assertIsDisplayed()
        ui.onNodeWithTag("asset_cancel").assertIsDisplayed()
        val decor = ui.activity.window.decorView
        val ime = ViewCompat.getRootWindowInsets(decor)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
        snapshot("asset-editor-ime")
        assertTrue("Save stays above the native IME", ui.onNodeWithTag("asset_save").fetchSemanticsNode().boundsInWindow.bottom <= decor.height - ime + 1)
        assertTrue("Cancel stays above the native IME", ui.onNodeWithTag("asset_cancel").fetchSemanticsNode().boundsInWindow.bottom <= decor.height - ime + 1)
        androidx.test.espresso.Espresso.pressBack()
        ui.waitForIdle(); SystemClock.sleep(600)
        click("asset_save"); await("asset_add")
        val created = runBlocking { repository.observeAccounts(false).first() }.single { it.account.name == "虚构组件账户" }
        assertEquals(12550L, created.balanceCent)
        ui.onNodeWithTag("asset_update_${created.account.id}").performScrollTo(); click("asset_update_${created.account.id}")
        await("asset_editor")
        ui.onNodeWithTag("asset_balance").performTextReplacement("999.00")
        click("asset_cancel"); await("asset_add")
        assertEquals(12550L, runBlocking { repository.observeAccounts(false).first() }.single { it.account.id == created.account.id }.balanceCent)
        assertEquals(before.transactions, runBlocking { repository.exportConsistentSnapshot() }.transactions)
        snapshot("assets-saved")
    }
}
