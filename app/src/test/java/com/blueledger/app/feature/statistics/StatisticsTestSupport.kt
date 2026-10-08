package com.blueledger.app.feature.statistics

import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import com.blueledger.app.acceptance.SeededFixture
import com.blueledger.app.acceptance.seedAcceptanceFixture
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.demo.BootstrapFixedClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.robolectric.Shadows
import java.time.LocalDate
import java.time.YearMonth

/**
 * A4（S05/S06）测试夹具。
 *
 * - 使用总控提供的共享 [InMemoryLedgerRepository]（与真实 Room 实现同契约、同口径），
 *   不另建第二套 Fake。
 * - 时钟冻结在验收夹具时间 2026-10-07 Asia/Shanghai。
 * - 期望值全部来自 A7 的 `acceptance/AcceptanceFixture.kt`（只读引用，不修改）。
 */
internal class StatisticsHarness {

    val clock: BootstrapFixedClock = BootstrapFixedClock()

    val repository: InMemoryLedgerRepository = InMemoryLedgerRepository(clock)

    /** §12 夹具写入后的 ID 映射。 */
    lateinit var fixture: SeededFixture

    /** 导航回调记录（验证钻取参数真的正确，而不是只有文字）。 */
    val drillDowns: MutableList<TransactionFilterSeed> = mutableListOf()
    val entryLaunches: MutableList<EntryLaunch> = mutableListOf()

    init {
        // 显式超时：内存仓库的挂起函数不会真的挂起，但绝不允许测试因等待而永久阻塞。
        runBlocking { withTimeout(FIXTURE_TIMEOUT_MILLIS) { repository.initializeIfNeeded() } }
    }

    /** 写入 §12 的 T1—T9、两个账户与 2026-10 预算。 */
    fun seedAcceptance(): SeededFixture {
        fixture = runBlocking { withTimeout(FIXTURE_TIMEOUT_MILLIS) { repository.seedAcceptanceFixture() } }
        settle()
        return fixture
    }

    /** 追加一笔支出（用于构造「分类很多 → 前 5 类 + 其余分类」的场景）。 */
    fun seedExpense(
        amountCent: Long,
        date: LocalDate,
        categoryId: String,
        accountId: String = DEFAULT_ACCOUNT_ID,
        note: String = "",
    ): String {
        val draft = TransactionDraft(
            type = TransactionType.EXPENSE,
            amountCent = amountCent,
            categoryId = categoryId,
            accountId = accountId,
            occurredOn = date,
            note = note,
        )
        val result = runBlocking {
            withTimeout(FIXTURE_TIMEOUT_MILLIS) {
                repository.createTransaction(draft, "stats-$categoryId-$date-$amountCent-$note")
            }
        }
        return (result as? SaveResult.Success)?.transactionId
            ?: error("追加支出失败：$result")
    }

    fun viewModel(
        initialTab: StatisticsTab? = null,
        initialYearMonth: YearMonth? = null,
        initialYear: Int? = null,
    ): StatisticsViewModel {
        val viewModel = StatisticsViewModel(repository, clock, initialTab, initialYearMonth, initialYear)
        settle()
        return viewModel
    }

    /** 让 Robolectric 主 Looper 跑完 ViewModel 里 `Dispatchers.Main.immediate` 派发的任务。 */
    fun settle() {
        idleMainLooper()
        idleMainLooper()
    }

    companion object {
        /** 夹具写入的显式超时，避免任何等待无限期阻塞测试 JVM。 */
        const val FIXTURE_TIMEOUT_MILLIS: Long = 10_000L
        const val DEFAULT_ACCOUNT_ID: String = "acc_default"
        val TODAY: LocalDate = LocalDate.of(2026, 10, 7)
        val OCT: YearMonth = YearMonth.of(2026, 10)
        val SEP: YearMonth = YearMonth.of(2026, 9)
        val AUG: YearMonth = YearMonth.of(2026, 8)
        val MAY: YearMonth = YearMonth.of(2026, 5)
        val NOV: YearMonth = YearMonth.of(2026, 11)
        const val YEAR: Int = 2026
    }
}

/** 让 Robolectric 主 Looper 执行队列中的任务。 */
internal fun idleMainLooper() {
    Shadows.shadowOf(Looper.getMainLooper()).idle()
}

/**
 * Robolectric 下不能用 `waitForIdle()` / `waitUntil`：
 * 页面首帧会渲染 `LedgerLoadingState` 的无限旋转动画，自动推进帧会让 Robolectric
 * 的 ShadowLineBreaker/NativeObjRegistry 溢出。统一做法：`mainClock.autoAdvance = false`
 * + 手动推帧并让主 Looper 跑任务（与 A5 的做法一致），帧数保持小步长。
 */
internal fun ComposeContentTestRule.pump(frames: Int = 12) {
    repeat(frames) {
        idleMainLooper()
        mainClock.advanceTimeByFrame()
    }
    idleMainLooper()
}

/** 逐帧等待 tag 出现（数据来自仓库 Flow，需要状态回流后再断言）。 */
internal fun ComposeContentTestRule.awaitTag(tag: String, maxFrames: Int = 90): SemanticsNodeInteraction {
    repeat(maxFrames) {
        if (onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) return onNodeWithTag(tag)
        idleMainLooper()
        mainClock.advanceTimeByFrame()
    }
    throw AssertionError("等待 $tag 出现超时（已推 $maxFrames 帧）")
}

/** 冻结入口 [StatisticsRoute] 的渲染宿主（含金额隐藏开关）。 */
@Composable
internal fun StatisticsRouteHost(
    harness: StatisticsHarness,
    hideAmounts: Boolean = false,
    initialTab: StatisticsTab? = null,
    initialYearMonth: YearMonth? = null,
    initialYear: Int? = null,
    fontScale: Float = 1f,
) {
    val base = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
        BlueLedgerTheme(hideAmounts = hideAmounts) {
            StatisticsRoute(
                repository = harness.repository,
                clock = harness.clock,
                initialTab = initialTab,
                initialYearMonth = initialYearMonth,
                initialYear = initialYear,
                onDrillDown = { seed -> harness.drillDowns += seed },
                onOpenEntry = { launch -> harness.entryLaunches += launch },
            )
        }
    }
}

/** 收集语义树中的全部文本与内容描述，用于「金额隐藏不得泄露」这类整体断言。 */
internal fun androidx.compose.ui.semantics.SemanticsNode.allSemanticTexts(): List<String> {
    val collected = mutableListOf<String>()
    fun walk(node: androidx.compose.ui.semantics.SemanticsNode) {
        node.config.getOrNull(SemanticsProperties.Text)?.forEach { collected += it.text }
        node.config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { collected += it }
        node.children.forEach(::walk)
    }
    walk(this)
    return collected
}

/** 金额泄露探针：任何 `数字.数字` 形式（金额或百分比）都视为泄露。 */
internal val MONEY_LEAK_PATTERN: Regex = Regex("""\d+\.\d""")
