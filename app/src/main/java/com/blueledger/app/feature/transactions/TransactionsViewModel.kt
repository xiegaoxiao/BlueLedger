package com.blueledger.app.feature.transactions

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.lifecycle.ScreenSubscriptionOwner
import com.blueledger.app.core.lifecycle.ScreenSubscriptions
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionPageState
import com.blueledger.app.core.model.TransactionType
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

/**
 * S03 账单列表的 ViewModel。
 *
 * 设计要点（与冻结契约一致）：
 * 1. 列表与筛选汇总**共用同一个谓词**：`observeTransactions(filter)` 出 items，
 *    `observeFilteredSummary(filter)` 出覆盖全部匹配记录的汇总；后者忽略分页，
 *    所以「共 N 笔」与「收 / 支 / 结余」不会因为分页而少算。
 * 2. 分页只体现在 [pageLimit]：初始一页，用户点「加载更多」时递增。
 *    测试可以注入更小的初始页大小来验证「汇总不受分页影响」。
 * 3. 月份、搜索词、类型、分类、账户都在本 ViewModel 内保存，
 *    进入详情再返回不会重建（NavBackStackEntry 级作用域），因此筛选与滚动位置不丢。
 * 4. 钻取种子 [seed] 只在 ViewModel 创建时应用一次（见 init），
 *    不会在返回本页时把用户后来改过的筛选覆盖回种子值。
 */
class TransactionsViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
    seed: TransactionFilterSeed = TransactionFilterSeed(),
    initialPageSize: Int = TransactionFilter.DEFAULT_LIMIT,
) : ViewModel(), ScreenSubscriptionOwner {

    private data class FilterState(
        val month: YearMonth,
        val query: String = "",
        val type: TransactionType? = null,
        val categoryId: String? = null,
        val accountId: String? = null,
    ) {
        fun toFilter(limit: Int): TransactionFilter = TransactionFilter(
            yearMonth = month,
            type = type,
            categoryId = categoryId,
            accountId = accountId,
            query = query,
            limit = limit,
            offset = 0,
        )
    }

    private data class PageSnapshot(
        val page: TransactionPageState,
        val summary: MoneySummary,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptions = ScreenSubscriptions(scope)
    private val retryTrigger = MutableStateFlow(0)

    /** 页面级默认一页的条数；任何筛选变化都回到这一页大小。 */
    private val defaultPageSize: Int = initialPageSize.coerceAtLeast(1)

    private val filters = MutableStateFlow(
        FilterState(
            month = seed.yearMonth ?: clock.currentYearMonth(),
            query = seed.query,
            type = seed.type,
            categoryId = seed.categoryId,
            accountId = seed.accountId,
        ),
    )

    private val pageLimit = MutableStateFlow(defaultPageSize)

    private val _state = MutableStateFlow(
        TransactionsUiState(
            month = filters.value.month,
            currentMonth = clock.currentYearMonth(),
            today = clock.today(),
            query = filters.value.query,
            type = filters.value.type,
            categoryId = filters.value.categoryId,
            accountId = filters.value.accountId,
        ),
    )
    val state: StateFlow<TransactionsUiState> = _state.asStateFlow()

    /** 最近一次拉到的分类/账户（含归档），用于筛选下拉与类型切换时的关联清理。 */
    private var latestCategories: List<LedgerCategory> = emptyList()

    init {
        setSubscriptionsActive(true)
    }

    override fun setSubscriptionsActive(active: Boolean) {
        subscriptions.setActive(active) { readScope ->
            observeSettings(readScope)
            observeFilterOptions(readScope)
            observeLedgerCount(readScope)
            observePage(readScope)
        }
    }

    // ───────────────────────── 数据订阅 ─────────────────────────

    private fun observeSettings(readScope: CoroutineScope) {
        readScope.launch {
            retryTrigger
                .flatMapLatest { repository.observeSettings().onReadFailure() }
                .collect { settings ->
                    _state.update { it.copy(hideAmounts = settings.hideAmounts, loading = false) }
                }
        }
    }

    private fun observeFilterOptions(readScope: CoroutineScope) {
        readScope.launch {
            retryTrigger
                .flatMapLatest {
                    combine(
                        repository.observeCategories(TransactionType.EXPENSE, includeArchived = true),
                        repository.observeCategories(TransactionType.INCOME, includeArchived = true),
                        repository.observeAccounts(includeArchived = true),
                    ) { expense, income, accounts -> Triple(expense, income, accounts) }
                        .onReadFailure()
                }
                .collect { (expense, income, accounts) ->
                    val merged = mergeCategories(expense, income)
                    latestCategories = merged
                    _state.update { it.copy(categories = merged, accounts = sortAccounts(accounts)) }
                }
        }
    }

    private fun observeLedgerCount(readScope: CoroutineScope) {
        readScope.launch {
            retryTrigger
                .flatMapLatest { repository.observeTransactions(TransactionFilter(limit = 1)).onReadFailure() }
                .collect { page ->
                    _state.update { it.copy(ledgerTransactionCount = page.totalCount, loading = false) }
                }
        }
    }

    private fun observePage(readScope: CoroutineScope) {
        readScope.launch {
            combine(filters, pageLimit, retryTrigger) { current, limit, _ -> current to limit }
                .flatMapLatest { (current, limit) ->
                    val filter = current.toFilter(limit)
                    combine(
                        repository.observeTransactions(filter),
                        repository.observeFilteredSummary(filter),
                    ) { page, summary -> PageSnapshot(page, summary) }
                        .onReadFailure()
                }
                .collect { snapshot ->
                    _state.update {
                        it.copy(
                            page = snapshot.page,
                            summary = snapshot.summary,
                            groups = TransactionGrouping.byDay(snapshot.page.items),
                            loading = false,
                            readErrorMessage = null,
                        )
                    }
                }
        }
    }

    // ───────────────────────── 筛选动作 ─────────────────────────

    fun onQueryChanged(text: String) {
        apply(filters.value.copy(query = text))
    }

    fun onClearQuery() {
        apply(filters.value.copy(query = ""))
    }

    /**
     * 切换收支类型：若已选分类与新类型不兼容则清空分类，
     * 避免出现「支出 + 收入分类」这种零结果的组合。
     */
    fun onTypeSelected(type: TransactionType?) {
        val current = filters.value
        val selectedCategoryType = latestCategories.firstOrNull { it.id == current.categoryId }?.type
        val keepCategory = type == null || selectedCategoryType == null || selectedCategoryType == type
        apply(current.copy(type = type, categoryId = if (keepCategory) current.categoryId else null))
    }

    fun onCategorySelected(categoryId: String?) {
        apply(filters.value.copy(categoryId = categoryId))
    }

    fun onAccountSelected(accountId: String?) {
        apply(filters.value.copy(accountId = accountId))
    }

    /** 「重置」：清空类型、分类、账户与搜索词，**保留当前月份**（PRD S03）。 */
    fun onResetFilters() {
        apply(filters.value.copy(query = "", type = null, categoryId = null, accountId = null))
    }

    fun onPreviousMonth() {
        apply(filters.value.copy(month = filters.value.month.minusMonths(1)))
    }

    /** 不允许切到未来月份：未来月份没有有效统计口径。 */
    fun onNextMonth() {
        val current = clock.currentYearMonth()
        val month = filters.value.month
        if (month.isBefore(current)) apply(filters.value.copy(month = month.plusMonths(1)))
    }

    fun onCurrentMonth() {
        apply(filters.value.copy(month = clock.currentYearMonth()))
    }

    fun loadMore() {
        pageLimit.update { it + TransactionFilter.PAGE_SIZE }
    }

    fun onRetry() {
        _state.update { it.copy(loading = true, readErrorMessage = null) }
        retryTrigger.update { it + 1 }
    }

    /** 页面重新进入时刷新「设备今日」与「当前月」，避免跨天后月份栏仍停留在昨天。 */
    fun refreshDate() {
        _state.update { it.copy(today = clock.today(), currentMonth = clock.currentYearMonth()) }
    }

    private fun apply(next: FilterState) {
        val previous = filters.value
        if (next == previous) return
        filters.value = next
        // 筛选变化后回到第一页，避免旧分页把新条件的记录跳过。
        pageLimit.value = defaultPageSize
        _state.update {
            it.copy(
                month = next.month,
                query = next.query,
                type = next.type,
                categoryId = next.categoryId,
                accountId = next.accountId,
            )
        }
    }

    // ───────────────────────── 内部工具 ─────────────────────────

    private fun <T> Flow<T>.onReadFailure(): Flow<T> = catch {
        // 读取失败必须显示错误态，绝不伪装成空账本（PRD S12）。
        _state.update { current -> current.copy(loading = false, readErrorMessage = READ_ERROR_MESSAGE) }
    }

    private fun mergeCategories(
        expense: List<LedgerCategory>,
        income: List<LedgerCategory>,
    ): List<LedgerCategory> =
        (expense + income).sortedWith(
            compareBy(
                { if (it.type == TransactionType.EXPENSE) 0 else 1 },
                { it.sortOrder },
                { it.id },
            ),
        )

    private fun sortAccounts(accounts: List<AccountWithBalance>): List<AccountWithBalance> =
        accounts.sortedWith(compareBy({ it.account.isArchived }, { it.account.name }, { it.account.id }))

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        const val READ_ERROR_MESSAGE: String = "暂时无法读取数据"
    }
}
