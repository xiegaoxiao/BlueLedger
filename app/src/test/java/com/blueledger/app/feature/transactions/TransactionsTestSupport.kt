package com.blueledger.app.feature.transactions

import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import com.blueledger.app.acceptance.SeededFixture
import com.blueledger.app.acceptance.seedAcceptanceFixture
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.demo.BootstrapFixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows
import java.time.Instant
import java.time.LocalDate

/**
 * A3 账单列表 / 详情测试夹具。
 *
 * 使用总控的共享 [InMemoryLedgerRepository]（与真实 Room 同契约）与
 * [BootstrapFixedClock]（冻结 2026-10-07 Asia/Shanghai）；
 * 数据用 A7 的 §12 验收夹具，独立场景再按需追加。
 */
internal class TransactionsTestHarness {

    val clock: BootstrapFixedClock = BootstrapFixedClock()
    val repository: InMemoryLedgerRepository = InMemoryLedgerRepository(clock)

    val openedDetails = mutableListOf<String>()
    val openedEntries = mutableListOf<EntryLaunch>()
    val deletedReceipts = mutableListOf<DeleteReceipt>()
    var editRequests: Int = 0
    var backRequests: Int = 0

    private var fixture: SeededFixture? = null

    fun initialize() {
        runBlocking { repository.initializeIfNeeded() }
    }

    fun seedAcceptance(): SeededFixture {
        val seeded = runBlocking { repository.seedAcceptanceFixture() }
        fixture = seeded
        return seeded
    }

    fun transactionId(label: String): String =
        requireNotNull(fixture) { "尚未写入验收夹具" }.transactionId(label)

    fun acceptanceAccountId(ref: String): String =
        requireNotNull(fixture) { "尚未写入验收夹具" }.accountId(ref)

    fun addTransaction(
        amountCent: Long = 1_000L,
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = "cat_expense_food",
        accountId: String = "acc_default",
        occurredOn: LocalDate = LocalDate.of(2026, 10, 6),
        note: String = "",
        createdAt: Instant? = null,
        requestId: String = "a3-tx-" + amountCent + "-" + note + "-" + occurredOn,
    ): String = runBlocking {
        val created = repository.createTransaction(
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
        (created as SaveResult.Success).transactionId
    }

    /** 让全部有效账单落在同一天，用于验证同日排序（createdAt DESC → id ASC）。 */
    fun seedManyOnOneDay(count: Int, day: LocalDate = LocalDate.of(2026, 10, 6)): List<String> =
        (1..count).map { index ->
            addTransaction(
                amountCent = index.toLong() * 100L,
                occurredOn = day,
                note = "批量账单 " + index,
                requestId = "a3-bulk-" + index,
            )
        }

    fun listViewModel(
        seed: TransactionFilterSeed = TransactionFilterSeed(),
        initialPageSize: Int = 300,
    ): TransactionsViewModel {
        val model = TransactionsViewModel(
            repository = repository,
            clock = clock,
            seed = seed,
            initialPageSize = initialPageSize,
        )
        settleMainLooper()
        return model
    }

    fun detailViewModel(transactionId: String): DetailViewModel {
        val model = DetailViewModel(repository = repository, transactionId = transactionId)
        settleMainLooper()
        return model
    }
}

internal fun settleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}

/** 渲染冻结入口 [TransactionsRoute]。 */
internal fun ComposeContentTestRule.setTransactionsRouteContent(
    harness: TransactionsTestHarness,
    seed: TransactionFilterSeed = TransactionFilterSeed(),
) {
    setContent {
        BlueLedgerTheme {
            TransactionsRoute(
                repository = harness.repository,
                clock = harness.clock,
                initialSeed = seed,
                onOpenDetail = { harness.openedDetails.add(it) },
                onOpenEntry = { harness.openedEntries.add(it) },
            )
        }
    }
}

/** 渲染冻结入口 [DetailRoute]（onDeleted 携带撤销凭据交回上层）。 */
internal fun ComposeContentTestRule.setDetailRouteContent(
    harness: TransactionsTestHarness,
    transactionId: String,
) {
    setContent {
        BlueLedgerTheme {
            DetailRoute(
                repository = harness.repository,
                clock = harness.clock,
                transactionId = transactionId,
                onEdit = { harness.editRequests += 1 },
                onDeleted = { harness.deletedReceipts.add(it) },
                onBack = { harness.backRequests += 1 },
            )
        }
    }
}

internal fun ComposeContentTestRule.awaitTag(tag: String, timeoutMillis: Long = 10_000L) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
    settleMainLooper()
    waitForIdle()
}
