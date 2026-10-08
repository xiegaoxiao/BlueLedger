package com.blueledger.app.feature.overview

import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionWithRefs
import java.time.LocalDate
import java.time.YearMonth

/** 首页快捷分类项（只含未归档分类，顺序取自 `Defaults.QUICK_PICK_EXPENSE_CATEGORY_IDS`）。 */
data class QuickPickCategory(
    val id: String,
    val name: String,
    val iconKey: String,
)

/**
 * S01 总览首页的 UI 状态。
 *
 * 所有金额都是仓库返回的 Long 整数分（[summary] / [budget]），首页**不做任何二次聚合**；
 * 结余标签绝不写成「余额」（余额含期初，两者口径不同）。
 */
data class OverviewUiState(
    val loading: Boolean = true,
    val readErrorMessage: String? = null,
    val month: YearMonth,
    val currentMonth: YearMonth,
    val today: LocalDate,
    val summary: MoneySummary = MoneySummary.EMPTY,
    val budget: BudgetState? = null,
    val quickCategories: List<QuickPickCategory> = emptyList(),
    val recentTransactions: List<TransactionWithRefs> = emptyList(),
    /** 全账本有效账单数：用来区分「首次空账本」与「当前月为空」。 */
    val ledgerTransactionCount: Int = 0,
    val hideAmounts: Boolean = false,
) {

    val isCurrentMonth: Boolean get() = month == currentMonth

    val canGoToNextMonth: Boolean get() = month.isBefore(currentMonth)

    val isLedgerEmpty: Boolean get() = ledgerTransactionCount == 0

    val isSelectedMonthEmpty: Boolean get() = summary.count == 0

    /** 总额值标签：本月 / 指定月份，绝不出现「余额」。 */
    val balanceLabel: String
        get() = if (isCurrentMonth) "本月结余" else "${month.monthValue} 月结余"

    val incomeLabel: String
        get() = if (isCurrentMonth) "本月收入" else "${month.monthValue} 月收入"

    val expenseLabel: String
        get() = if (isCurrentMonth) "本月支出" else "${month.monthValue} 月支出"

    val budgetLabel: String
        get() = if (isCurrentMonth) "本月预算" else "${month.monthValue} 月预算"

    /** 是否有可展示的预算（预算为 0 或不存在都算未设置，不显示假进度条）。 */
    val hasBudget: Boolean get() = budget?.isSet == true
}

/** 预算卡片文案（与 S08 使用同一套状态语义，避免两页说法不一致）。 */
internal object BudgetCopy {

    fun statusLabel(status: BudgetStatus): String = when (status) {
        BudgetStatus.UNSET -> "未设置"
        BudgetStatus.NORMAL -> "预算内"
        BudgetStatus.NEAR_LIMIT -> "接近预算"
        BudgetStatus.EXHAUSTED -> "预算已用完"
        BudgetStatus.EXCEEDED -> "已超支"
    }

    /** 预算卡片的行动号召：未设置时给明确设置入口，已设置时提示可调整。 */
    fun actionLabel(hasBudget: Boolean): String = if (hasBudget) "调整预算" else "设置本月预算"
}
