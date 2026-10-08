package com.blueledger.app.feature.overview

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.blueledger.app.acceptance.SeededFixture
import com.blueledger.app.acceptance.seedAcceptanceFixture
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.demo.BootstrapFixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows
import java.time.LocalDate
import java.time.YearMonth

/**
 * A3 首页测试夹具。
 *
 * - 时钟：[BootstrapFixedClock]（冻结在 2026-10-07T00:00+08:00[Asia/Shanghai]，总控提供）。
 * - 仓库：总控提供的共享 [InMemoryLedgerRepository]（与真实 Room 实现同契约），
 *   不另建第二套 Fake。
 * - 数据：A7 的验收夹具 `seedAcceptanceFixture()`（docs/AI开发提示词.md §12 的可执行版本），
 *   期望值直接引用 A7 的 `Expect` 常量，不在这里重新算一遍。
 */
internal class OverviewTestHarness {

    val clock: BootstrapFixedClock = BootstrapFixedClock()
    val repository: InMemoryLedgerRepository = InMemoryLedgerRepository(clock)

    /** 页面回调记录（用于断言导航参数，不依赖真实 NavHost）。 */
    val entryLaunches = mutableListOf<EntryLaunch>()
    val openedDetails = mutableListOf<String>()
    val openedTransactions = mutableListOf<YearMonth>()
    val openedStatistics = mutableListOf<YearMonth>()
    val openedBudgets = mutableListOf<YearMonth>()

    private var fixture: SeededFixture? = null

    /** 初始化默认分类/默认账户/设置（零账单）。 */
    fun initialize() {
        runBlocking { repository.initializeIfNeeded() }
    }

    /** 写入 §12 验收夹具（含 2026-10 的 4,000 元预算，2026-09 无预算）。 */
    fun seedAcceptance(): SeededFixture {
        val seeded = runBlocking { repository.seedAcceptanceFixture() }
        fixture = seeded
        return seeded
    }

    fun transactionId(label: String): String =
        requireNotNull(fixture) { "尚未写入验收夹具" }.transactionId(label)

    /** 追加一笔自建账单，用于归档、空月份等独立场景。 */
    fun addTransaction(
        amountCent: Long = 1_000L,
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = "cat_expense_food",
        accountId: String = "acc_default",
        occurredOn: LocalDate = LocalDate.of(2026, 10, 6),
        note: String = "",
        requestId: String = "a3-overview-" + amountCent + "-" + note + "-" + occurredOn,
    ): String = runBlocking {
        val result = repository.createTransaction(
            TransactionDraft(
                type = type,
                amountCent = amountCent,
                categoryId = categoryId,
                accountId = accountId,
                occurredOn = occurredOn,
                note = note,
            ),
            requestId,
        )
        (result as com.blueledger.app.core.model.SaveResult.Success).transactionId
    }

    fun viewModel(): OverviewViewModel {
        val model = OverviewViewModel(repository = repository, clock = clock)
        settleMainLooper()
        return model
    }
}

/** 让 Robolectric 主 Looper（PAUSED 模式）把 Flow 收集与 StateFlow 更新跑完。 */
internal fun settleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}

/**
 * 渲染冻结入口 [OverviewRoute]（真实 ViewModel + 真实仓库），记录所有导航回调。
 */
internal fun ComposeContentTestRule.setOverviewRouteContent(harness: OverviewTestHarness) {
    setContent {
        BlueLedgerTheme {
            OverviewRoute(
                repository = harness.repository,
                clock = harness.clock,
                onOpenEntry = { harness.entryLaunches.add(it) },
                onOpenDetail = { harness.openedDetails.add(it) },
                onOpenTransactions = { harness.openedTransactions.add(it) },
                onOpenStatistics = { harness.openedStatistics.add(it) },
                onOpenBudget = { harness.openedBudgets.add(it) },
            )
        }
    }
}

/** 等待数据从仓库流到界面。 */
internal fun ComposeContentTestRule.awaitTag(tag: String, timeoutMillis: Long = 10_000L) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    settleMainLooper()
    waitForIdle()
}
