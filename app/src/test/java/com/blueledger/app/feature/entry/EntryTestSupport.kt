package com.blueledger.app.feature.entry

import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.TransactionWithRefs
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * A2 测试用固定时钟：冻结在验收夹具时间 2026-10-07T00:00:00+08:00[Asia/Shanghai]。
 * 定义在本代理的测试目录内，避免依赖 `demo/` 下的引导实现（G0 结束后会被删除）。
 */
internal class FixedTestClock(
    private val instant: Instant = LocalDate.of(2026, 10, 7).atStartOfDay(ZONE_ID).toInstant(),
    private val zone: ZoneId = ZONE_ID,
) : Clock {
    override fun now(): Instant = instant

    override fun zoneId(): ZoneId = zone

    companion object {
        val ZONE_ID: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/**
 * A2 测试夹具。
 *
 * 使用总控提供的**共享** [InMemoryLedgerRepository]（与真实 Room 实现同契约），
 * 不另建第二套 Fake；测试时钟固定为验收夹具时间 2026-10-07 Asia/Shanghai。
 */
internal class EntryTestHarness {

    val clock: FixedTestClock = FixedTestClock()
    val repository: InMemoryLedgerRepository = InMemoryLedgerRepository(clock)

    /** 每次 onExit 回调计数，用于断言「保存成功才返回」。 */
    var exitCount: Int = 0

    /** 可运行时放大的系统字体比例，用于真实重排布局。 */
    val fontScale = mutableStateOf(1f)

    init {
        runBlocking { repository.initializeIfNeeded() }
    }

    fun transactions(): List<TransactionWithRefs> =
        runBlocking { repository.observeTransactions(TransactionFilter()).first() }.items

    fun transaction(id: String): LedgerTransaction? =
        runBlocking { repository.observeTransaction(id).first() }

    /** 预置一笔账单（编辑模式测试用）。 */
    fun seed(
        amountCent: Long = 1250L,
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = "cat_expense_food",
        accountId: String = "acc_default",
        occurredOn: LocalDate = LocalDate.of(2026, 10, 6),
        note: String = "旧备注",
    ): String {
        val draft = TransactionDraft(
            type = type,
            amountCent = amountCent,
            categoryId = categoryId,
            accountId = accountId,
            occurredOn = occurredOn,
            note = note,
        )
        val result = runBlocking { repository.createTransaction(draft, "seed-" + amountCent + "-" + note) }
        return (result as SaveResult.Success).transactionId
    }
}

/** 渲染冻结入口 [EntryRoute]。 */
internal fun ComposeContentTestRule.setEntryContent(
    harness: EntryTestHarness,
    editTransactionId: String? = null,
    initialType: TransactionType? = null,
    initialCategoryId: String? = null,
) {
    setContent {
        val base = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(base.density, harness.fontScale.value),
        ) {
            BlueLedgerTheme {
                EntryRoute(
                    repository = harness.repository,
                    clock = harness.clock,
                    editTransactionId = editTransactionId,
                    initialType = initialType,
                    initialCategoryId = initialCategoryId,
                    onExit = { harness.exitCount++ },
                )
            }
        }
    }
}

/** 让主线程队列与 Compose 组合都稳定下来（Robolectric 的 Looper 是 PAUSED 模式）。 */
internal fun ComposeContentTestRule.settle() {
    waitForIdle()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    waitForIdle()
}

/** 等待某个 tag 出现（数据来自仓库 Flow，是异步的）。 */
internal fun ComposeContentTestRule.awaitTag(tag: String, timeoutMillis: Long = 10_000L) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    settle()
}

/** 金额可直接点开；也可先选分类，再自动展开键盘。 */
internal fun ComposeContentTestRule.openAmountKeyboard() {
    awaitTag(TAG_AMOUNT_DISPLAY)
    onNodeWithTag(TAG_AMOUNT_DISPLAY).performClick()
    awaitTag("key_dot")
}

/** 让 Robolectric 主 Looper 执行队列中的任务。 */
internal fun idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}
