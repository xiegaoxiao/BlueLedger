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

/** Real registered Compose pages with fictional ledger data, rendered offline. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w354dp-h792dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FreeToolsUiTest {
    @get:Rule val ui = createAndroidComposeRule<ComponentActivity>()
    private val clock = FixedClock()
    private val repository = InMemoryLedgerRepository(clock)
    private val container = object : AppContainer {
        override val clock = this@FreeToolsUiTest.clock
        override val ledgerRepository = repository
        override val backupCodec = JsonBackupCodec(clock)
    }
    private fun await(tag: String) {
        ui.waitUntil(10_000) { ui.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        ui.waitForIdle()
    }
    @Test fun populatedLedgerKeepsUsefulStatisticsWithoutCheckIn() { inspect(true, "normal") }
    @Test @Config(qualifiers = "w320dp-h640dp-xhdpi")
    fun emptyLedgerAtDoubleFontKeepsUsefulStatisticsWithoutCheckIn() {
        val configuration = android.content.res.Configuration(ui.activity.resources.configuration).apply { fontScale = 2f }
        @Suppress("DEPRECATION")
        ui.activity.resources.updateConfiguration(configuration, ui.activity.resources.displayMetrics)
        inspect(false, "320dp-2x")
    }
    private fun inspect(populated: Boolean, suffix: String) {
        val preferences = ReferencePreferences(ui.activity)
        preferences.storage.edit().clear().putString("check_in", clock.today().toString())
            .putInt("check_streak", 99).putString("profile_badge", "旧徽章").commit()
        runBlocking {
            repository.initializeIfNeeded()
            if (populated) repository.createTransaction(TransactionDraft(TransactionType.EXPENSE, 1250,
                "cat_expense_food", "acc_default", clock.today(), "虚构午餐"), "free-tool-preview")
        }
        val before = runBlocking { repository.exportConsistentSnapshot() }
        ui.setContent { BlueLedgerApp(container) }; await("overview_screen")
        ui.onNodeWithTag("tab_mine").performClick(); await("mine_settings")
        ui.onNodeWithTag("mine_check_in").assertDoesNotExist()
        listOf("打卡", "连续打卡", "积分", "补卡", "尊贵标识", "旧徽章").forEach {
            ui.onAllNodesWithText(it, substring = true).assertCountEquals(0)
        }
        ui.onNodeWithText("免费记账", substring = true).assertIsDisplayed()
        ui.onNodeWithText("无广告", substring = true).assertIsDisplayed()
        ui.onNodeWithText("记账天数").assertIsDisplayed()
        ui.onNodeWithText("记账总笔数").assertIsDisplayed()
        val file = File("build/free-tools-ui/mine-$suffix.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { ui.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        listOf("check_in", "check_streak", "profile_badge").forEach { Assert.assertFalse(preferences.storage.contains(it)) }
        ui.onNodeWithTag("mine_settings").performScrollTo().performClick(); await("mine_categories")
        Assert.assertEquals(before.transactions, runBlocking { repository.exportConsistentSnapshot() }.transactions)
    }
}
