package com.blueledger.app.feature.reference

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerApp
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.designsystem.LedgerCategoryIcon
import com.blueledger.app.core.designsystem.LedgerIcons
import com.blueledger.app.core.model.*
import com.blueledger.app.core.time.FixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import com.blueledger.app.di.AppContainer
import com.blueledger.app.feature.backup.JsonBackupCodec
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Offline previews use synthetic records only and never connect to a device. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w354dp-h792dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiUpgradePreviewTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repository = InMemoryLedgerRepository(clock)
    private var firstRecordId = ""
    private val container = object : AppContainer {
        override val clock = this@UiUpgradePreviewTest.clock
        override val ledgerRepository = repository
        override val backupCodec = JsonBackupCodec(clock)
    }

    @Before fun prepare() {
        ReferencePreferences(ui.activity).storage.edit().clear().commit()
        runBlocking {
            repository.initializeIfNeeded()
            listOf(1300L, 8500L, 2600L).forEachIndexed { index, amount ->
                val saved = repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, amount,
                    "cat_expense_food", "acc_default", clock.today().minusDays(index.toLong()),
                    listOf("午餐", "晚餐", "早餐")[index]), "preview-$index") as SaveResult.Success
                if (index == 0) firstRecordId = saved.transactionId
            }
            repository.createTransaction(TransactionDraft(TransactionType.INCOME, 160000L,
                "cat_income_salary", "acc_default", clock.today().minusDays(1), "工资"), "preview-income")
        }
    }

    private fun await(tag: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }

    private fun snapshot(name: String) {
        val file = File("build/ui-upgrade-preview/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            ui.onRoot().captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        val density = ui.activity.resources.displayMetrics.density
        val root = ui.onRoot().fetchSemanticsNode().boundsInRoot
        val tags = listOf("home_shortcut_card", "overview_month_label", "reference_line_chart",
            "stats_tab_week", "stats_month_label", "bills_summary_card", "mine_check_in",
            "mine_settings", "mine_data", "entry_top_bar", "category_cat_expense_food",
            "key_7", "key_0", "global_record_button", "tab_overview", "tab_mine")
        val bounds = tags.mapNotNull { tag ->
            ui.onAllNodesWithTag(tag).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.let { b ->
                "\"$tag\":{\"xDp\":${b.left / density},\"yDp\":${b.top / density},\"widthDp\":${b.width / density},\"heightDp\":${b.height / density}}"
            }
        }
        File(file.parentFile, "$name-layout.json").writeText(
            "{\"widthDp\":${root.width / density},\"heightDp\":${root.height / density},\"bounds\":{${bounds.joinToString(",")}}}")
    }

    private fun heightDp(tag: String): Float =
        ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.height / ui.activity.resources.displayMetrics.density

    @Test fun mainPagesAndCategorySelectionPreview() {
        ui.setContent { BlueLedgerApp(container) }
        await("overview_row_$firstRecordId"); snapshot("home")
        assertTrue("快捷卡保持参考的紧凑高度", heightDp("home_shortcut_card") in 60f..64f)
        assertEquals("底栏统一保持50dp", 50f, heightDp("tab_mine"), 1f)
        ui.onNodeWithTag("tab_statistics").performClick()
        await("stats_rank_cat_expense_food"); snapshot("chart")
        assertEquals("趋势图比例按参考图归一", 108f, heightDp("reference_line_chart"), 1f)
        ui.onNodeWithTag("stats_tab_month").performClick()
        await("stats_rank_cat_expense_food"); snapshot("chart-month")
        ui.onNodeWithTag("tab_transactions").performClick()
        await("bills_balance"); snapshot("bill")
        assertTrue("账单汇总卡高度保持参考比例", heightDp("bills_summary_card") in 120f..128f)
        ui.onNodeWithTag("tab_mine").performClick()
        await("mine_settings"); snapshot("mine")
        ui.onNodeWithTag("mine_check_in").performClick()
        ui.waitUntil(10_000) { ui.onAllNodesWithText("已打卡").fetchSemanticsNodes().isNotEmpty() }
        ui.onNodeWithTag("mine_check_in").assertIsNotEnabled(); snapshot("mine-checked")
        assertEquals(1, ReferencePreferences(ui.activity).storage.getInt("check_streak", 0))
        ui.onNodeWithTag("global_record_button").performClick()
        await("category_cat_expense_food"); snapshot("entry")
        ui.onNodeWithTag("category_cat_expense_food").performClick()
        await("key_plus"); snapshot("entry-keyboard")
        ui.onNodeWithTag("category_cat_expense_food").assertIsSelected()
    }

    @Test fun allSelectableCategoryIconsPreview() {
        ui.setContent {
            BlueLedgerTheme {
                LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxSize().background(T.Surface),
                    contentPadding = PaddingValues(T.SpaceL)) {
                    items(CategoryIcons.SELECTABLE) { key ->
                        Column(Modifier.padding(T.SpaceS), horizontalAlignment = Alignment.CenterHorizontally) {
                            LedgerCategoryIcon(key, false)
                            Text(LedgerIcons.iconLabel(key), fontSize = T.Caption, color = T.TextPrimary)
                        }
                    }
                }
            }
        }
        ui.waitForIdle(); snapshot("category-icons")
    }
}
