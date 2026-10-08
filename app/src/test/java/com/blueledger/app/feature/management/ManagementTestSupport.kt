package com.blueledger.app.feature.management

import android.os.Looper
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.designsystem.LedgerAmountText
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.LedgerAccount
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.TransactionWithRefs
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.demo.BootstrapFixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * A5（S07—S10）测试夹具。
 *
 * 使用总控提供的**共享** [InMemoryLedgerRepository]（与真实 Room 实现同契约、同口径），
 * 时钟冻结在验收夹具时间 2026-10-07T00:00:00+08:00[Asia/Shanghai]；
 * 不另建第二套 Fake，也不修改 A7 的 `acceptance/` 文件。
 */
internal class ManagementHarness(
    transactions: List<LedgerTransaction> = emptyList(),
    budgets: Map<YearMonth, Long> = emptyMap(),
    accounts: List<LedgerAccount> = emptyList(),
    categories: List<LedgerCategory> = emptyList(),
) {
    val clock: BootstrapFixedClock = BootstrapFixedClock()

    val repository: InMemoryLedgerRepository = InMemoryLedgerRepository(
        clock = clock,
        initialCategories = categories,
        initialAccounts = accounts,
        initialTransactions = transactions,
        initialBudgets = budgets,
    )

    init {
        runBlocking { repository.initializeIfNeeded() }
    }

    // ── 导航回调计数（验证入口真的接线，而不是只有文字） ──
    var backCount: Int = 0
    var openCategoriesCount: Int = 0
    var openBudgetCount: Int = 0
    var openAccountsCount: Int = 0
    var openDataCount: Int = 0
    var lastTransactionsSeed: com.blueledger.app.core.model.TransactionFilterSeed? = null

    /** 让 Robolectric 主 Looper 跑完 ViewModel 里 `Dispatchers.Main.immediate` 派发的任务。 */
    fun settle() {
        idleMainLooper()
        idleMainLooper()
    }

    fun budget(month: YearMonth): BudgetState = runBlocking { repository.observeBudget(month).first() }

    fun settings(): LedgerSettings = runBlocking { repository.observeSettings().first() }

    fun accounts(includeArchived: Boolean = true): List<AccountWithBalance> =
        runBlocking { repository.observeAccounts(includeArchived).first() }

    fun account(id: String): AccountWithBalance? = accounts().firstOrNull { it.account.id == id }

    fun categories(type: TransactionType, includeArchived: Boolean = true): List<LedgerCategory> =
        runBlocking { repository.observeCategories(type, includeArchived).first() }

    fun transactions(): List<TransactionWithRefs> =
        runBlocking { repository.observeTransactions(TransactionFilter()).first() }.items

    fun monthSummary(month: YearMonth): MoneySummary =
        runBlocking { repository.observeMonthSummary(month).first() }

    fun seedExpense(
        amountCent: Long,
        month: YearMonth,
        day: Int = 1,
        categoryId: String = "cat_expense_food",
        accountId: String = "acc_default",
        note: String = "",
    ): String = create(
        TransactionDraft(
            type = TransactionType.EXPENSE,
            amountCent = amountCent,
            categoryId = categoryId,
            accountId = accountId,
            occurredOn = month.atDay(day),
            note = note,
        ),
        requestId = "exp-$month-$day-$amountCent-$categoryId-$accountId-$note",
    )

    fun seedIncome(
        amountCent: Long,
        month: YearMonth,
        day: Int = 1,
        categoryId: String = "cat_income_salary",
        accountId: String = "acc_default",
        note: String = "",
    ): String = create(
        TransactionDraft(
            type = TransactionType.INCOME,
            amountCent = amountCent,
            categoryId = categoryId,
            accountId = accountId,
            occurredOn = month.atDay(day),
            note = note,
        ),
        requestId = "inc-$month-$day-$amountCent-$categoryId-$accountId-$note",
    )

    fun seedAccount(
        name: String,
        kind: AccountKind = AccountKind.CASH,
        openingBalanceCent: Long = 0L,
    ): String {
        val result = runBlocking {
            repository.upsertAccount(
                com.blueledger.app.core.model.AccountCommand(
                    id = null,
                    name = name,
                    kind = kind,
                    openingBalanceCent = openingBalanceCent,
                ),
            )
        }
        return (result as? MutationResult.Success)?.id
            ?: error("夹具新增账户「$name」失败：$result")
    }

    /** 直接经契约写入一笔账单（夹具只走冻结契约，不碰内部状态）。 */
    private fun create(draft: TransactionDraft, requestId: String): String {
        val result = runBlocking { repository.createTransaction(draft, requestId) }
        return (result as? SaveResult.Success)?.transactionId
            ?: error("夹具写入账单失败：$result")
    }

    companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 7)
        val OCT_2026: YearMonth = YearMonth.of(2026, 10)
        val SEP_2026: YearMonth = YearMonth.of(2026, 9)
        val CLOCK_INSTANT: Instant = BootstrapFixedClock.DEFAULT_INSTANT
    }
}

/** 让 Robolectric 主 Looper 执行队列中的任务。 */
internal fun idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}

/**
 * Robolectric 下 **不能用** `waitForIdle()` / `waitUntil`：
 * 页面首帧会渲染 `LedgerLoadingState` 的无限旋转动画，Compose 测试的自动时钟会一直
 * 推进帧直到 Robolectric 的 `NativeObjRegistry` / `ShadowLineBreaker` 溢出
 * （`ArrayIndexOutOfBoundsException` / `OutOfMemoryError`，A5 已实测复现）。
 *
 * 统一做法：`mainClock.autoAdvance = false` + 手动「推帧 + 让主 Looper 跑任务」，
 * 帧数保持小步长，避免大页面上反复测量文本把测试 JVM 内存耗光。
 */
internal fun ComposeContentTestRule.pump(frames: Int = 6) {
    repeat(frames) {
        idleMainLooper()
        mainClock.advanceTimeByFrame()
    }
    idleMainLooper()
}

/** 组合 + 主线程都稳定下来（手动推帧，不依赖 waitForIdle 收敛）。 */
internal fun ComposeContentTestRule.settleUi() {
    pump()
}

/**
 * 逐帧等待某个 tag 出现（数据来自仓库 Flow，需要推帧让状态回流后再断言）。
 *
 * 每轮推 4 帧再查一次语义树：语义树重建很贵，逐帧查询会把一个用例拖到几十秒。
 */
internal fun ComposeContentTestRule.awaitTag(
    tag: String,
    maxRounds: Int = 15,
): SemanticsNodeInteraction {
    repeat(maxRounds) {
        if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return onNodeWithTag(tag)
        pump(4)
    }
    throw AssertionError("等待 $tag 出现超时（已推 ${maxRounds * 4} 帧）")
}

/**
 * 断言界面上（未合并语义树）存在某段文本。
 *
 * 不用 `assertTextContains` 打在容器 tag 上：非合并容器节点自身的 Text 属性为空，
 * 文本在子节点里，会得到「文本不包含」的假失败。
 */
internal fun ComposeContentTestRule.assertTextVisible(
    text: String,
    substring: Boolean = true,
) {
    pump()
    val found = onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
    if (found.isEmpty()) throw AssertionError("界面上找不到文本「$text」")
}

/** 断言界面上不再出现某段文本（用于金额隐藏等「不泄露」判据）。 */
internal fun ComposeContentTestRule.assertTextAbsent(
    text: String,
    substring: Boolean = true,
) {
    pump()
    val found = onAllNodesWithText(text, substring = substring, useUnmergedTree = true)
        .fetchSemanticsNodes()
    if (found.isNotEmpty()) throw AssertionError("界面不应出现文本「$text」，实际找到 ${found.size} 个")
}

/**
 * B10 判据：金额隐藏后，界面文本**与无障碍语义**都不能再读到真实金额。
 *
 * 只断言显示 `••••` 不够：`LedgerAmountText` 隐藏时会把语义描述换成「金额已隐藏」，
 * 这里把「文本」和「contentDescription」两条通道一起断言，避免 TalkBack 泄露。
 */
internal fun ComposeContentTestRule.assertAmountFullyHidden(vararg amounts: String) {
    pump()
    amounts.forEach { amount ->
        val texts = onAllNodesWithText(amount, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
        val descriptions = onAllNodesWithContentDescription(amount, substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (texts.isNotEmpty() || descriptions.isNotEmpty()) {
            throw AssertionError(
                "金额隐藏后仍能读到「$amount」：文本节点 ${texts.size} 个，无障碍描述 ${descriptions.size} 个",
            )
        }
    }
}

/**
 * 金额隐藏的**全局传播**宿主：模拟总控在 `BlueLedgerApp` 里的接线
 * （`observeSettings().hideAmounts` → `BlueLedgerTheme(hideAmounts = ...)`）。
 */
@Composable
internal fun HideAmountsHost(
    repository: com.blueledger.app.core.contract.LedgerRepository,
    content: @Composable () -> Unit,
) {
    val settings by repository.observeSettings().collectAsState(initial = null)
    BlueLedgerTheme(hideAmounts = settings?.hideAmounts == true) {
        content()
    }
}

// ───────────────────────── 冻结入口的渲染辅助 ─────────────────────────

@Composable
internal fun BudgetRouteHost(
    harness: ManagementHarness,
    initialYearMonth: YearMonth? = ManagementHarness.OCT_2026,
    hideAmounts: Boolean = false,
) {
    com.blueledger.app.app.ui.BlueLedgerTheme(hideAmounts = hideAmounts) {
        BudgetRoute(
            repository = harness.repository,
            clock = harness.clock,
            initialYearMonth = initialYearMonth,
            onBack = { harness.backCount++ },
        )
    }
}

@Composable
internal fun CategoriesRouteHost(
    harness: ManagementHarness,
    hideAmounts: Boolean = false,
) {
    com.blueledger.app.app.ui.BlueLedgerTheme(hideAmounts = hideAmounts) {
        CategoriesRoute(
            repository = harness.repository,
            clock = harness.clock,
            onBack = { harness.backCount++ },
        )
    }
}

@Composable
internal fun AccountsRouteHost(
    harness: ManagementHarness,
    hideAmounts: Boolean = false,
) {
    com.blueledger.app.app.ui.BlueLedgerTheme(hideAmounts = hideAmounts) {
        AccountsRoute(
            repository = harness.repository,
            clock = harness.clock,
            onBack = { harness.backCount++ },
            onOpenTransactions = { seed -> harness.lastTransactionsSeed = seed },
        )
    }
}

@Composable
internal fun MineRouteHost(
    harness: ManagementHarness,
    hideAmounts: Boolean = false,
) {
    com.blueledger.app.app.ui.BlueLedgerTheme(hideAmounts = hideAmounts) {
        MineRoute(
            repository = harness.repository,
            clock = harness.clock,
            onOpenCategories = { harness.openCategoriesCount++ },
            onOpenBudget = { harness.openBudgetCount++ },
            onOpenAccounts = { harness.openAccountsCount++ },
            onOpenData = { harness.openDataCount++ },
        )
    }
}

/**
 * 「我的」+ 敏感金额探针：用一个和首页/统计/账户同样读取 `LocalHideAmounts`
 * 的金额节点，验证在「我的」里切换开关后**全局**都会变成 `••••`。
 */
@Composable
internal fun MineWithAmountProbeHost(harness: ManagementHarness) {
    HideAmountsHost(harness.repository) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f)) {
                MineRoute(
                    repository = harness.repository,
                    clock = harness.clock,
                    onOpenCategories = { harness.openCategoriesCount++ },
                    onOpenBudget = { harness.openBudgetCount++ },
                    onOpenAccounts = { harness.openAccountsCount++ },
                    onOpenData = { harness.openDataCount++ },
                )
            }
            LedgerAmountText(
                cents = 1_080_000L,
                prefix = "¥",
                testTag = "probe_amount",
            )
        }
    }
}
