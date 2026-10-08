package com.blueledger.app.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.seedAcceptanceFixture
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.*
import com.blueledger.app.demo.BootstrapFixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import com.blueledger.app.di.AppContainer
import com.blueledger.app.feature.backup.JsonBackupCodec
import com.blueledger.app.feature.management.ManagementTags
import com.blueledger.app.feature.overview.TAG_OVERVIEW_MONTH_LABEL
import com.blueledger.app.feature.overview.TAG_OVERVIEW_PREV_MONTH
import com.blueledger.app.feature.overview.TAG_OVERVIEW_SCREEN
import com.blueledger.app.feature.statistics.StatisticsTags
import com.blueledger.app.feature.transactions.TAG_TX_SCREEN
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/** 用真实导航图验证反复切页的订阅数量和页面状态；不把 JVM 耗时当作真机帧率。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class RepeatedTabNavigationTest {
    @get:Rule val composeRule = createComposeRule()

    private val clock = BootstrapFixedClock()
    private val repository = TrackingRepository(InMemoryLedgerRepository(clock))
    private val container = object : AppContainer {
        override val clock = this@RepeatedTabNavigationTest.clock
        override val ledgerRepository = repository
        override val backupCodec = JsonBackupCodec(clock)
    }

    @Test
    fun repeatedTabsStopInactiveReadersAndKeepSelectedMonth() {
        runBlocking { repository.seedAcceptanceFixture() }
        openApp()
        val actions = composeRule.onNodeWithTag(TAG_OVERVIEW_MONTH_LABEL).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        composeRule.runOnIdle { actions.first { it.label == "上个月" }.action() }
        composeRule.waitForIdle()
        val baseline = mutableMapOf<String, Map<String, Int>>()
        val screens = listOf(
            "transactions" to TAG_TX_SCREEN,
            "statistics" to StatisticsTags.SCREEN,
            "mine" to ManagementTags.MINE_SCREEN,
            "overview" to TAG_OVERVIEW_SCREEN,
        )
        repeat(10) { round ->
            screens.forEach { (tab, screen) ->
                composeRule.onNodeWithTag("tab_$tab").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithTag(screen).assertIsDisplayed()
                val active = repository.active.filterValues { it > 0 }.toMap()
                if (round == 0) baseline[tab] = active
                else assertEquals("第 ${round + 1} 轮 $tab 订阅不能增长", baseline[tab], active)
                assertEquals("每个前台页面只有一组流水查询", 1, repository.count("transactions"))
                assertEquals("每个前台页面只有一组汇总查询", 1, repository.count("filteredSummary"))
                assertEquals("设置查询保持唯一", 1, repository.count("settings"))
            }
            composeRule.onNodeWithTag(TAG_OVERVIEW_MONTH_LABEL).assertTextContains("09月", substring = true)
        }
    }

    @Test
    fun selectedTabAndSettingsRecompositionDoNotRestartSettingsReaders() {
        openApp()
        val factories = repository.settingsFactories
        val starts = repository.settingsStarts
        repeat(12) { composeRule.onNodeWithTag("tab_overview").performClick() }
        composeRule.waitForIdle()
        assertEquals(factories, repository.settingsFactories)
        assertEquals(starts, repository.settingsStarts)

        composeRule.runOnIdle { runBlocking { repository.setHideAmounts(true) } }
        composeRule.waitForIdle()
        assertEquals("主题重组不应创建设置查询", factories, repository.settingsFactories)
        assertEquals("主题重组不应重新订阅设置", starts, repository.settingsStarts)
    }

    @Test
    fun statisticsTabSelectionSurvivesLeavingAndReturning() {
        openApp()
        composeRule.onNodeWithTag("tab_statistics").performClick()
        composeRule.onNodeWithTag(StatisticsTags.TAB_YEAR).performClick()
        composeRule.onNodeWithTag(StatisticsTags.YEAR_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag("tab_overview").performClick()
        composeRule.onNodeWithTag("tab_statistics").performClick()
        composeRule.onNodeWithTag(StatisticsTags.YEAR_LABEL).assertIsDisplayed()
    }

    @Test
    fun statisticsRefreshesAfterWriteWhileInactive() {
        openApp()
        composeRule.onNodeWithTag("tab_statistics").performClick()
        composeRule.onNodeWithTag("tab_overview").performClick()
        assertEquals(0, repository.count("monthAnalysis"))
        composeRule.runOnIdle {
            runBlocking {
                val saved = repository.createTransaction(
                    TransactionDraft(
                        type = TransactionType.EXPENSE,
                        amountCent = 1250L,
                        categoryId = "cat_expense_food",
                        accountId = "acc_default",
                        occurredOn = clock.today(),
                        note = "后台页面刷新验证",
                    ),
                    requestId = "inactive-statistics-write",
                )
                check(saved is SaveResult.Success)
            }
        }
        composeRule.waitForIdle()
        assertEquals("后台写入不能唤醒已暂停的统计订阅", 0, repository.count("monthAnalysis"))
        composeRule.onNodeWithTag("tab_statistics").performClick()
        composeRule.onNodeWithTag(StatisticsTags.SUMMARY_CARD).assertTextContains("12.50", substring = true)
        assertEquals(1, repository.count("transactions"))
    }

    private fun openApp() {
        runBlocking { repository.initializeIfNeeded() }
        composeRule.setContent { BlueLedgerApp(container) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(TAG_OVERVIEW_SCREEN).assertIsDisplayed()
    }

    private class TrackingRepository(private val source: LedgerRepository) : LedgerRepository by source {
        val active = java.util.concurrent.ConcurrentHashMap<String, Int>()
        var settingsFactories = 0
        var settingsStarts = 0
        fun count(key: String) = active[key] ?: 0
        private fun <T> track(key: String, upstream: Flow<T>): Flow<T> = flow {
            active.compute(key) { _, value -> (value ?: 0) + 1 }
            if (key == "settings") settingsStarts++
            try { emitAll(upstream) } finally { active.compute(key) { _, value -> (value ?: 0) - 1 } }
        }
        override fun observeSettings(): Flow<LedgerSettings> {
            settingsFactories++
            return track("settings", source.observeSettings())
        }
        override fun observeMonthAnalysis(month: YearMonth, type: TransactionType) =
            track("monthAnalysis", source.observeMonthAnalysis(month, type))
        override fun observeYearAnalysis(year: Int) = track("yearAnalysis", source.observeYearAnalysis(year))
        override fun observeMonthSummary(month: YearMonth) = track("monthSummary", source.observeMonthSummary(month))
        override fun observeTransactions(filter: TransactionFilter) = track("transactions", source.observeTransactions(filter))
        override fun observeFilteredSummary(filter: TransactionFilter) = track("filteredSummary", source.observeFilteredSummary(filter))
        override fun observeAccounts(includeArchived: Boolean) = track("accounts", source.observeAccounts(includeArchived))
        override fun observeCategories(type: TransactionType, includeArchived: Boolean) =
            track("categories", source.observeCategories(type, includeArchived))
        override fun observeBudget(month: YearMonth) = track("budget", source.observeBudget(month))
        override fun observeBackupRecord() = track("backup", source.observeBackupRecord())
    }
}
