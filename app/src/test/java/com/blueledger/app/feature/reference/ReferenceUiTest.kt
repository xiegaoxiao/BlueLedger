package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerApp
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import com.blueledger.app.di.AppContainer
import com.blueledger.app.feature.backup.JsonBackupCodec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
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
class ReferenceUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repository = InMemoryLedgerRepository(clock)
    private val container = object : AppContainer {
        override val clock = this@ReferenceUiTest.clock
        override val ledgerRepository = repository
        override val backupCodec = JsonBackupCodec(clock)
    }
    @Before fun prepare() {
        ReferencePreferences(ApplicationProvider.getApplicationContext()).storage.edit().clear().commit()
        runBlocking { repository.initializeIfNeeded() }
    }
    private fun open() { ui.setContent { BlueLedgerApp(container) }; await("overview_screen") }
    private fun await(tag: String) { ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; ui.waitForIdle() }
    private fun snapshot(name: String) {
        val file = File("build/reference-ui/$name.png"); file.parentFile.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
    private fun seed(amount: Long = 1250, id: String = "reference-demo") = runBlocking {
        repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, amount, "cat_expense_food", "acc_default", clock.today(), "午餐"), id) as SaveResult.Success
    }
    @Test fun categoryFirstEntryAndCalculatorSaveExactlyOnce() {
        open(); ui.onNodeWithText("记账").performClick(); await("category_cat_expense_food")
        ui.onNodeWithTag("entry_keyboard").assertDoesNotExist()
        ui.onNodeWithTag("amount_display").assertDoesNotExist()
        snapshot("entry-categories")
        ui.onNodeWithTag("category_cat_expense_food").performClick(); await("key_plus")
        listOf("key_dot", "key_1", "key_plus", "key_dot", "key_2").forEach { ui.onNodeWithTag(it).performClick() }
        ui.onNodeWithTag("amount_display").assertTextContains("+", substring = true)
        snapshot("entry-keypad")
        ui.onNodeWithTag("btn_save").performClick()
        ui.onNodeWithTag("amount_display").assertTextEquals("0.30")
        assertEquals(0, runBlocking { repository.observeTransactions(TransactionFilter()).first().items.size })
        ui.onNodeWithTag("btn_save").performClick(); await("overview_screen")
        val rows = runBlocking { repository.observeTransactions(TransactionFilter()).first().items }
        assertEquals(1, rows.size); assertEquals(30L, rows.single().amountCent)
    }
    @Test fun blueHomeGraphAndMonthlyReportRenderRealData() {
        val saved = seed(); open(); await("overview_row_${saved.transactionId}"); snapshot("home")
        ui.onNodeWithTag("tab_statistics").performClick(); await("stats_rank_cat_expense_food"); snapshot("chart-week")
        ui.onNodeWithTag("stats_tab_month").performClick(); await("stats_rank_cat_expense_food"); snapshot("chart-month")
        ui.onNodeWithTag("stats_rank_cat_expense_food").performClick(); await("overview_row_${saved.transactionId}")
        ui.onNodeWithTag("btn_back").performClick()
        ui.onNodeWithTag("tab_transactions").performClick(); await("transactions_screen"); snapshot("bills")
        ui.onNodeWithText("10月").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("记录了 1 天 · 1 笔账单").fetchSemanticsNodes().isNotEmpty() }
        snapshot("month-report")
    }
    @Test fun assetDebtChangesNetAssetWithoutBecomingExpense() {
        open(); ui.onNodeWithTag("home_资产管家").performClick(); await("asset_add"); snapshot("assets-empty")
        ui.onNodeWithTag("asset_add").performClick(); ui.onNodeWithText("信用卡").performClick(); await("asset_name")
        ui.onNodeWithTag("asset_name").performTextInput("花呗")
        ui.onNodeWithTag("asset_balance").performTextInput("125.50"); snapshot("asset-form")
        ui.onNodeWithTag("asset_save").performClick(); await("asset_add")
        ui.onNodeWithText("花呗").assertIsDisplayed(); snapshot("assets-debt")
        val account = runBlocking { repository.observeAccounts(false).first().single { it.account.name == "花呗" } }
        assertEquals(-12550L, account.balanceCent)
        assertEquals(0L, runBlocking { repository.observeMonthSummary(clock.currentYearMonth()).first().expenseCent })
    }
    @Test fun defaultEntryTypeAndSettingsPersist() {
        open(); ui.onNodeWithTag("tab_mine").performClick(); await("mine_settings"); snapshot("mine")
        ui.onNodeWithTag("mine_settings").performClick(); ui.onNodeWithText("默认记账类型").performClick()
        ui.onNodeWithText("收入").performClick(); ui.onNodeWithTag("btn_back").performClick()
        ui.onNodeWithTag("global_record_button").performClick(); await("category_cat_income_salary")
        ui.onNodeWithTag("category_cat_income_salary").assertIsDisplayed()
        assertEquals(TransactionType.INCOME, ReferencePreferences(ui.activity).defaultType)
    }
    @Test fun dateSheetCancelsWithoutChangingTheEntryOrSavingAnything() {
        open()
        ui.onNodeWithTag("global_record_button").performClick(); await("category_cat_expense_food")
        ui.onNodeWithTag("category_cat_expense_food").performClick(); await("key_date")
        ui.onNodeWithTag("key_1").performClick(); ui.onNodeWithTag("key_2").performClick()
        val entered = ui.onNodeWithTag("amount_display").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].single().text
        ui.onNodeWithTag("key_date").performClick(); await("date_picker")
        ui.onNodeWithTag("date_confirm").assertIsDisplayed(); ui.onNodeWithTag("date_year").assertIsDisplayed()
        snapshot("date-picker")
        ui.onNodeWithTag("date_dismiss").performClick(); await("key_date")
        ui.onNodeWithTag("amount_display").assertTextEquals(entered)
        assertEquals(0, runBlocking { repository.observeTransactions(TransactionFilter()).first().items.size })
    }
    @Test fun chartPageUsesSegmentedPeriodsAndReferenceSummaryLines() {
        val saved = seed(); open(); await("overview_row_${saved.transactionId}")
        ui.onNodeWithTag("tab_statistics").performClick(); await("stats_rank_cat_expense_food")
        listOf("stats_tab_week", "stats_tab_month", "stats_tab_year").forEach { ui.onNodeWithTag(it).assertIsDisplayed() }
        // 参考应用的写法：一行「总支出：」+ 去尾零金额，不是两位小数。
        ui.onNodeWithTag("stats_summary_card").assertTextContains("总支出：12.5", substring = true)
        ui.onNodeWithTag("stats_tab_month").performClick(); await("stats_rank_cat_expense_food")
        ui.onNodeWithTag("stats_summary_card").assertTextContains("总支出：12.5", substring = true)
        snapshot("chart-month")
    }
    @Test fun weeklyChartShowsAllSevenDaysAndKeepsRelativeLabelsWhenBrowsingHistory() {
        seed(); open()
        ui.onNodeWithTag("tab_statistics").performClick(); await("stats_rank_cat_expense_food")
        val start = ChartRange.week(clock.today()).from
        (0L..6L).forEach { day ->
            ui.onNodeWithText("${start.plusDays(day).dayOfMonth}日").assertIsDisplayed()
        }
        ui.onNodeWithTag("stats_month_label").assertTextContains("本周")
        val currentChartTop = ui.onNodeWithTag("reference_line_chart").fetchSemanticsNode().boundsInRoot.top
        ui.onNodeWithText("上周").performClick()
        ui.onNodeWithTag("stats_month_label").assertTextContains("上周")
        ui.onNodeWithText("本周").assertDoesNotExist()
        ui.waitUntil(5_000) {
            ui.onNodeWithTag("reference_line_chart").fetchSemanticsNode().boundsInRoot.top == currentChartTop
        }
        ui.onNodeWithText("›").performClick()
        ui.onNodeWithTag("stats_month_label").assertTextContains("本周")
        ui.waitUntil(5_000) {
            ui.onNodeWithTag("reference_line_chart").fetchSemanticsNode().boundsInRoot.top == currentChartTop
        }
    }
    @Test fun accountSettingsRowsFollowReferenceAndDisableWhenUnlinked() {
        open(); ui.onNodeWithTag("tab_mine").performClick(); await("mine_settings")
        ui.onNodeWithTag("mine_settings").performClick()
        ui.onNodeWithText("收支账户").performClick()
        ui.onNodeWithText("收支账户设置").assertIsDisplayed()
        ui.onNodeWithText("账户展示设置").assertIsDisplayed()
        ui.onNodeWithText("开启后，主账本记账时可选收支账户").assertIsDisplayed()
        ui.onAllNodesWithText("不关联账户").assertCountEquals(2)
        // 参考应用：关闭账户关联时下面三行置灰不可点。
        ui.onNodeWithText("默认支出账户").assertIsNotEnabled()
        ui.onNodeWithText("账户展示设置").assertIsNotEnabled()
        snapshot("settings-accounts")
        ui.onNodeWithTag("account_association_switch").performClick()
        assertTrue(ReferencePreferences(ui.activity).accountAssociation)
        ui.waitForIdle()
        ui.onNodeWithText("默认支出账户").assertIsEnabled()
        ui.onNodeWithText("账户展示设置").assertIsEnabled()
    }
    @Test fun chartDefaultPeriodUsesASeparateCheckedSelectionPage() {
        open(); ui.onNodeWithTag("tab_mine").performClick(); await("mine_settings")
        ui.onNodeWithTag("mine_settings").performClick()
        ui.onNodeWithText("图表页设置").performClick()
        ui.onNodeWithText("默认收支周期").performClick(); await("settings_choice_page")
        ui.onNodeWithTag("settings_choice_0").assertIsSelected()
        snapshot("settings-period")
        ui.onNodeWithText("月").performClick()
        assertEquals(ChartPeriod.MONTH, ReferencePreferences(ui.activity).chartPeriod)
        ui.onNodeWithText("默认收支周期").performClick(); await("settings_choice_page")
        ui.onNodeWithTag("settings_choice_1").assertIsSelected()
        ui.onNodeWithTag("btn_back").performClick()
        ui.onNodeWithText("默认收支周期").assertIsDisplayed()
    }
    @Test fun dateWheelAccessibleAdjustmentUpdatesOnlyTheDraft() {
        open()
        ui.onNodeWithTag("global_record_button").performClick(); await("category_cat_expense_food")
        ui.onNodeWithTag("category_cat_expense_food").performClick(); await("key_date")
        ui.onNodeWithTag("key_date").performClick(); await("date_picker")
        ui.onNodeWithTag("date_month").assertContentDescriptionEquals("月份，10")
        ui.onNodeWithTag("date_month").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(9f) }
        ui.onNodeWithTag("date_month").assertContentDescriptionEquals("月份，9")
        ui.onNodeWithTag("date_confirm").performClick(); await("key_date")
        ui.onNodeWithText("9/${clock.today().dayOfMonth}").assertIsDisplayed()
        assertEquals(0, runBlocking { repository.observeTransactions(TransactionFilter()).first().items.size })
    }
    @Test fun draggingCategoryHandleReordersTheActualRepository() {
        open(); ui.onNodeWithTag("tab_mine").performClick(); await("mine_settings")
        ui.onNodeWithTag("mine_settings").performClick()
        ui.onNodeWithText("类别设置").performClick(); await("category_drag_cat_expense_food")
        val initial = runBlocking { repository.observeCategories(TransactionType.EXPENSE, true).first() }.sortedBy { it.sortOrder }.map { it.id }
        val height = ui.onNodeWithTag(com.blueledger.app.feature.management.ManagementTags.categoryRow("cat_expense_food")).fetchSemanticsNode().boundsInRoot.height
        snapshot("category-settings")
        ui.onNodeWithTag("category_drag_cat_expense_food").performTouchInput {
            down(center)
            advanceEventTime(800)
            moveBy(androidx.compose.ui.geometry.Offset(0f, height), delayMillis = 200)
            up()
        }
        ui.waitForIdle()
        val changed = runBlocking { repository.observeCategories(TransactionType.EXPENSE, true).first() }.sortedBy { it.sortOrder }.map { it.id }
        assertEquals(listOf(initial[1], initial[0]), changed.take(2))
        assertEquals(initial.toSet(), changed.toSet())
    }
    @Test
    @Config(qualifiers = "w320dp-h520dp-xhdpi")
    fun formalKeypadFitsSmallScreen() { assertFormalKeypadBounds() }
    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun formalKeypadFitsLargeFont() {
        val configuration = android.content.res.Configuration(ui.activity.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        ui.activity.resources.updateConfiguration(configuration, ui.activity.resources.displayMetrics)
        open()
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        listOf("明细", "图表", "账单", "我的", "记账").forEach { label ->
            val text = ui.onAllNodesWithText(label).fetchSemanticsNodes().last().boundsInRoot
            assertTrue("大字号底栏 $label 不能超出屏幕", text.top >= root.top && text.bottom <= root.bottom)
        }
        ui.onNodeWithText("点击下方＋，开始记账").assertIsDisplayed()
        assertFormalKeypadBounds(openApp = false)
    }
    private fun assertFormalKeypadBounds(openApp: Boolean = true) {
        if (openApp) open()
        ui.onNodeWithTag("global_record_button").performClick(); await("category_cat_expense_food")
        ui.onNodeWithTag("category_cat_expense_food").performClick(); await("key_plus")
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val density = ui.activity.resources.displayMetrics.density
        listOf("key_0", "key_1", "key_2", "key_3", "key_4", "key_5", "key_6", "key_7", "key_8", "key_9", "key_dot", "key_delete", "key_plus", "key_minus", "key_date", "btn_save").forEach { tag ->
            val node = ui.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag 在屏幕内", node.left >= root.left && node.right <= root.right && node.top >= root.top && node.bottom <= root.bottom)
            assertTrue("$tag 点击尺寸至少48dp", node.width >= 48 * density && node.height >= 48 * density)
        }
    }
    @Test fun monthlyImageShareUsesReadOnlyContentUriAndHiddenAmounts() {
        seed(); val filter = TransactionFilter(yearMonth = clock.currentYearMonth(), limit = Int.MAX_VALUE)
        val state = runBlocking { buildReferenceState(filter, repository.observeTransactions(filter).first(), repository.observeFilteredSummary(filter).first()) }
        val previous = ReferenceLedgerState(filter, loaded = true)
        val image = renderMonthlyReport(clock.currentYearMonth(), state, previous, true)
        assertEquals(1080, image.width); assertTrue(image.height > 1840)
        val file = File("build/reference-ui/shared-report-hidden.png"); file.parentFile.mkdirs()
        file.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        // AndroidX FileProvider 的根目录边界固定使用 '/'，Windows JVM canonicalPath 使用 '\\'。
        // 此处替代平台 URI 工厂，PNG 生成、写文件和 Intent 权限仍执行真实代码。
        // 原生 ContentProvider 的读取由 MonthlyReportShareDeviceTest 验证（需 Android 设备）。
        var sharedFile: File? = null
        val intent = runBlocking { monthlyReportShareIntent(ui.activity, clock.currentYearMonth(), state, previous, true) { context, file ->
            sharedFile = file
            android.net.Uri.parse("content://${context.packageName}.reports/reports/${file.name}")
        } }
        assertEquals("image/png", intent.type)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertTrue(intent.flags and android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertFalse(intent.flags and android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        sharedFile!!.inputStream().use { assertNotNull(android.graphics.BitmapFactory.decodeStream(it)) }
    }

    @Test
    @Config(qualifiers = "w320dp-h520dp-xhdpi")
    fun budgetKeypadFitsSmallScreenWithoutDraggingSheet() { assertBudgetKeypadBounds() }

    @Test
    @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun budgetKeypadFitsLargeFontWithoutDraggingSheet() {
        val configuration = android.content.res.Configuration(ui.activity.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        ui.activity.resources.updateConfiguration(configuration, ui.activity.resources.displayMetrics)
        assertBudgetKeypadBounds()
    }

    private fun assertBudgetKeypadBounds() {
        open(); ui.onNodeWithTag("home_预算").performClick()
        ui.onNodeWithText("编辑").performClick()
        ui.onNodeWithText("编辑月度总预算").performClick(); await("key_0")
        val minimum = 48 * ui.activity.resources.displayMetrics.density
        (listOf("key_dot", "key_delete") + (0..9).map { "key_$it" }).forEach { tag ->
            val bounds = ui.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
            assertTrue("预算 $tag 完整点击尺寸至少48dp", bounds.width >= minimum && bounds.height >= minimum)
        }
    }
}
