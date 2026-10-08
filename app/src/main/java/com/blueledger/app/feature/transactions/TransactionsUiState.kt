package com.blueledger.app.feature.transactions

import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionPageState
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.TransactionWithRefs
import java.time.LocalDate
import java.time.YearMonth

/**
 * 账单列表的一个日期分组。
 *
 * [items] 的顺序即仓库返回的稳定顺序（occurredOn DESC → createdAt DESC → id ASC），
 * 页面不得在 UI 层重新排序；[incomeCent]/[expenseCent] 是**该组已加载行**的小计，
 * 用于组头的「收入 / 支出」提示（金额为 Long 整数分，无浮点累加）。
 */
data class TransactionDayGroup(
    val date: LocalDate,
    val items: List<TransactionWithRefs>,
    val incomeCent: Long,
    val expenseCent: Long,
)

/**
 * S03 账单列表的完整 UI 状态。
 *
 * 关键口径：
 * - [summary] 来自 `observeFilteredSummary`，覆盖**全部匹配记录**，不受分页影响；
 * - [page] 的 `totalCount` 同样不受分页影响，`items` 才受 limit 影响；
 * - 「没有找到符合条件的账单」只在**存在附加筛选条件**时出现，
 *   否则是「这个月还没有账单」或首次空账本，三者不能混用。
 */
data class TransactionsUiState(
    val loading: Boolean = true,
    val readErrorMessage: String? = null,
    val month: YearMonth,
    val currentMonth: YearMonth,
    val today: LocalDate,
    val query: String = "",
    val type: TransactionType? = null,
    val categoryId: String? = null,
    val accountId: String? = null,
    val categories: List<LedgerCategory> = emptyList(),
    val accounts: List<AccountWithBalance> = emptyList(),
    val page: TransactionPageState = TransactionPageState.EMPTY,
    val summary: MoneySummary = MoneySummary.EMPTY,
    val groups: List<TransactionDayGroup> = emptyList(),
    /** 全账本有效账单数（不受筛选影响），用于区分「首次空账本」与「当月/筛选为空」。 */
    val ledgerTransactionCount: Int = 0,
    val hideAmounts: Boolean = false,
) {

    val isCurrentMonth: Boolean get() = month == currentMonth

    val canGoToNextMonth: Boolean get() = month.isBefore(currentMonth)

    /** 是否有月份以外的附加筛选条件（「重置」只清这些，保留月份）。 */
    val hasExtraFilters: Boolean
        get() = type != null || categoryId != null || accountId != null || query.isNotBlank()

    /** 首次空账本：整本账没有任何有效账单。 */
    val isLedgerEmpty: Boolean get() = ledgerTransactionCount == 0 && !hasExtraFilters

    /** 筛选无结果：有附加条件但一条都没匹配。 */
    val isFilteredEmpty: Boolean get() = page.totalCount == 0 && hasExtraFilters

    /** 当月（或所选月）无账单：没有附加条件，且账本非空。 */
    val isMonthEmpty: Boolean
        get() = page.totalCount == 0 && !hasExtraFilters && ledgerTransactionCount > 0

    /** 分类下拉的可选项：类型筛选生效时只列出该类型的分类（含已归档，便于查历史）。 */
    val categoryOptions: List<LedgerCategory>
        get() = if (type == null) categories else categories.filter { it.type == type }

    val selectedCategoryName: String?
        get() = categories.firstOrNull { it.id == categoryId }?.name

    val selectedAccountName: String?
        get() = accounts.firstOrNull { it.account.id == accountId }?.account?.name

    /** 已加载的行数；与 [TransactionPageState.totalCount] 在没有分页截断时相等。 */
    val shownCount: Int get() = page.items.size

    /** 还有未加载的匹配记录。 */
    val hasMore: Boolean get() = page.hasMore

    /** 列表与计数一致（没有分页截断）时两者相等，供测试与文案使用。 */
    val countMatchesShownRows: Boolean get() = !page.hasMore && page.totalCount == page.items.size
}

/** 只做分组，不做排序：顺序完全沿用仓库返回的顺序。 */
object TransactionGrouping {

    fun byDay(items: List<TransactionWithRefs>): List<TransactionDayGroup> =
        items.groupBy { it.occurredOn }
            .toSortedMap(compareByDescending { it })
            .map { (date, dayItems) ->
                TransactionDayGroup(
                    date = date,
                    items = dayItems,
                    incomeCent = dayItems.filter { it.type == TransactionType.INCOME }.sumOf { it.amountCent },
                    expenseCent = dayItems.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCent },
                )
            }
}
