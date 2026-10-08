package com.blueledger.app.feature.overview

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.lifecycle.ScreenSubscriptionOwner
import com.blueledger.app.core.lifecycle.ScreenSubscriptions
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionPageState
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.TransactionWithRefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * S01 总览首页的 ViewModel。
 *
 * 口径与边界：
 * - 月度摘要、预算、最近账单**全部来自仓库**，不改用截图常量，也不在本页重算金额；
 * - 最近账单用与列表**完全相同的排序键**（仓库统一排序：occurredOn DESC → createdAt DESC → id ASC）；
 * - 首页只浏览月份，不参与记账日期：切到历史月份后点「记一笔」不会把月份写进 [com.blueledger.app.core.model.EntryLaunch]
 *   （该类型没有日期字段，发生日期永远由 S02 取设备今日）。
 */
class OverviewViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
) : ViewModel(), ScreenSubscriptionOwner {

    private data class MonthSnapshot(
        val month: YearMonth,
        val summary: MoneySummary,
        val budget: BudgetState,
        val recent: TransactionPageState,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptions = ScreenSubscriptions(scope)
    private val monthState = MutableStateFlow(clock.currentYearMonth())
    private val retryTrigger = MutableStateFlow(0)

    private val _state = MutableStateFlow(
        OverviewUiState(
            month = monthState.value,
            currentMonth = clock.currentYearMonth(),
            today = clock.today(),
        ),
    )
    val state: StateFlow<OverviewUiState> = _state.asStateFlow()

    init {
        setSubscriptionsActive(true)
    }

    override fun setSubscriptionsActive(active: Boolean) {
        subscriptions.setActive(active) { readScope ->
            observeSettings(readScope)
            observeQuickCategories(readScope)
            observeLedgerCount(readScope)
            observeSelectedMonth(readScope)
        }
    }

    // ───────────────────────── 数据订阅 ─────────────────────────

    private fun observeSettings(readScope: CoroutineScope) {
        readScope.launch {
            retryTrigger
                .flatMapLatest { repository.observeSettings().map { it.hideAmounts }.onReadFailure() }
                .collect { hideAmounts ->
                    // 注意：这里**不**结束 loading。月度摘要/预算/最近账单是首页的主数据，
                    // 由 observeSelectedMonth 统一结束加载，否则会出现「预算卡先显示未设置、
                    // 数据到位后突然变成有预算」的假状态。
                    _state.update { it.copy(hideAmounts = hideAmounts) }
                }
        }
    }

    /**
     * 快捷分类：只取未归档分类（归档分类不再出现在快捷记账入口），
     * 顺序沿用 [Defaults.QUICK_PICK_EXPENSE_CATEGORY_IDS]，用 ID 关联而不是名称。
     */
    private fun observeQuickCategories(readScope: CoroutineScope) {
        readScope.launch {
            retryTrigger
                .flatMapLatest {
                    repository.observeCategories(TransactionType.EXPENSE, includeArchived = false).onReadFailure()
                }
                .collect { categories ->
                    val byId = categories.associateBy { it.id }
                    val picks = Defaults.QUICK_PICK_EXPENSE_CATEGORY_IDS.mapNotNull { id ->
                        byId[id]?.let { QuickPickCategory(id = it.id, name = it.name, iconKey = it.iconKey) }
                    }
                    _state.update { it.copy(quickCategories = picks) }
                }
        }
    }

    private fun observeLedgerCount(readScope: CoroutineScope) {
        readScope.launch {
            retryTrigger
                .flatMapLatest { repository.observeTransactions(TransactionFilter(limit = 1)).onReadFailure() }
                .collect { page ->
                    // 同上：账本总数先到也不能结束 loading，避免「首次空账本」在数据未齐时闪现。
                    _state.update { it.copy(ledgerTransactionCount = page.totalCount) }
                }
        }
    }

    /** 月度摘要 + 预算 + 最近 5 条账单：同一查询快照一起更新，避免摘要与列表口径错位。 */
    private fun observeSelectedMonth(readScope: CoroutineScope) {
        readScope.launch {
            combine(monthState, retryTrigger) { month, _ -> month }
                .flatMapLatest { month ->
                    combine(
                        repository.observeMonthSummary(month),
                        repository.observeBudget(month),
                        repository.observeTransactions(TransactionFilter(yearMonth = month, limit = RECENT_LIMIT)),
                    ) { summary, budget, recent -> MonthSnapshot(month, summary, budget, recent) }
                        .onReadFailure()
                }
                .collect { snapshot ->
                    _state.update {
                        it.copy(
                            // 月份必须跟着走：摘要 / 预算 / 最近账单 / 月份栏是同一个所选月份。
                            month = snapshot.month,
                            summary = snapshot.summary,
                            budget = snapshot.budget,
                            recentTransactions = snapshot.recent.items.take(RECENT_LIMIT),
                            loading = false,
                        )
                    }
                }
        }
    }

    // ───────────────────────── 动作 ─────────────────────────

    fun onPreviousMonth() {
        monthState.update { it.minusMonths(1) }
        syncSelectedMonth()
    }

    /** 不允许浏览未来月份：未来月份没有有效统计口径。 */
    fun onNextMonth() {
        val current = clock.currentYearMonth()
        monthState.update { if (it.isBefore(current)) it.plusMonths(1) else it }
        syncSelectedMonth()
    }

    fun onCurrentMonth() {
        monthState.value = clock.currentYearMonth()
        syncSelectedMonth()
    }

    /** 月份栏立刻反映所选月份，不必等仓库的下一次发射（数据仍以仓库为准）。 */
    private fun syncSelectedMonth() {
        _state.update { it.copy(month = monthState.value) }
    }

    /** 首页顶部的金额显示开关（与 S10 的开关是同一个仓库设置，不是本页局部状态）。 */
    fun onToggleHideAmounts() {
        val next = !_state.value.hideAmounts
        scope.launch { repository.setHideAmounts(next) }
    }

    fun onRetry() {
        _state.update { it.copy(loading = true, readErrorMessage = null) }
        retryTrigger.update { it + 1 }
    }

    /** 页面重新进入时刷新「设备今日 / 当前月」，跨天后月份栏与日期说明仍然正确。 */
    fun refreshDate() {
        _state.update { it.copy(today = clock.today(), currentMonth = clock.currentYearMonth()) }
    }

    private fun <T> Flow<T>.onReadFailure(): Flow<T> = catch {
        // 读取失败必须显示错误态并可重试，绝不伪装成零账单的空账本。
        _state.update { current ->
            current.copy(loading = false, readErrorMessage = READ_ERROR_MESSAGE)
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        /** 首页「最近账单」最多 5 条（PRD S01）。 */
        const val RECENT_LIMIT: Int = 5

        const val READ_ERROR_MESSAGE: String = "暂时无法读取数据"
    }
}
