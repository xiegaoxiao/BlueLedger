package com.blueledger.app.core.model

import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 一段时间范围的收支汇总。所有字段都是精确的整数分。
 *
 * [balanceCent] 允许为负；它是「期间收入 − 期间支出」，
 * 与账户余额（含期初余额）是两个不同的量，标签不得混用。
 */
data class MoneySummary(
    val incomeCent: Long = 0L,
    val expenseCent: Long = 0L,
    val count: Int = 0,
) {
    val balanceCent: Long get() = incomeCent - expenseCent

    companion object {
        val EMPTY = MoneySummary()
    }
}

/** 账单 + 展示所需的分类/账户名称快照。名称随分类/账户重命名自动更新。 */
data class TransactionWithRefs(
    val transaction: LedgerTransaction,
    val categoryName: String,
    val categoryIconKey: String,
    val categoryArchived: Boolean,
    val accountName: String,
) {
    val id: String get() = transaction.id
    val type: TransactionType get() = transaction.type
    val amountCent: Long get() = transaction.amountCent
    val occurredOn: LocalDate get() = transaction.occurredOn
    val note: String get() = transaction.note
}

/** 账单列表的一页。[totalCount] 是符合谓词的全部记录数，不受分页影响。 */
data class TransactionPageState(
    val items: List<TransactionWithRefs> = emptyList(),
    val totalCount: Int = 0,
    val hasMore: Boolean = false,
) {
    companion object {
        val EMPTY = TransactionPageState()
    }
}

/** 日趋势上的一个点。区间内无记录的日子必须补 0，不能跳过。 */
data class DailyAmount(
    val date: LocalDate,
    val amountCent: Long,
)

/**
 * 分类占比切片。
 *
 * [ratioPermille] 是「百分比 × 10」的整数（767 表示 76.7%），一位小数，四舍五入。
 * 当该类型总额为 0 时 [ratioPermille] 恒为 0，不产生 NaN，也不绘制假环图。
 */
data class CategorySlice(
    val categoryId: String,
    val name: String,
    val iconKey: String,
    val amountCent: Long,
    val ratioPermille: Int,
) {
    /** 仅供绘图使用的占比（0..1），不得回写为业务数据。 */
    val ratioFraction: Float get() = ratioPermille / 1000f
}

/**
 * 月度分析。[slices] 是当前 [type] 下的全部分类（金额 > 0），按金额降序、
 * 同额按 sortOrder 再按 id 稳定排序；UI 若要合并尾部的「其余分类」，
 * 必须基于完整 [slices] 自行合并，不得让仓库提前截断。
 */
data class MonthAnalysis(
    val month: YearMonth,
    val type: TransactionType,
    val incomeCent: Long = 0L,
    val expenseCent: Long = 0L,
    val count: Int = 0,
    /** 当前类型在该月的总额（支出模式 = expenseCent，收入模式 = incomeCent）。 */
    val typeTotalCent: Long = 0L,
    /** 完整日趋势：当月 1 日→今天；历史月 1 日→月末；未来月为空列表。 */
    val daily: List<DailyAmount> = emptyList(),
    val slices: List<CategorySlice> = emptyList(),
    /** 该月的统计截止日；未来月为 null。 */
    val periodEnd: LocalDate? = null,
) {
    val balanceCent: Long get() = incomeCent - expenseCent
}

/**
 * 年图中的一个月。
 *
 * [reached] = false 表示「未到月份」：它既不是 0 也不是有记录，
 * 必须用占位符呈现、不计入月均分母、不提供钻取。
 * [hasRecords] 只在 [reached] = true 时有意义，用来区分「已到的零记录月」与「有记录月」。
 */
data class YearMonthAmount(
    val month: Int,
    val incomeCent: Long = 0L,
    val expenseCent: Long = 0L,
    val reached: Boolean = false,
    val hasRecords: Boolean = false,
) {
    val balanceCent: Long get() = incomeCent - expenseCent
}

/**
 * 年度分析。[months] 恒为 12 项，1—12 月顺序。
 * [reachedMonthCount] 是已经进入的月份数：当年为当前月序号，历史年为 12。
 * 月均的分母用它，且包含「已到但无记录」的月份。
 */
data class YearAnalysis(
    val year: Int,
    val incomeCent: Long = 0L,
    val expenseCent: Long = 0L,
    val count: Int = 0,
    val months: List<YearMonthAmount> = emptyList(),
    val cutoff: LocalDate? = null,
    val isCurrentYear: Boolean = false,
    val reachedMonthCount: Int = 0,
) {
    val balanceCent: Long get() = incomeCent - expenseCent

    /** 截至目前月均支出（分母 = 已到月份数），分母为 0 时返回 null。 */
    val averageMonthlyExpenseCent: Long?
        get() = if (reachedMonthCount <= 0) null else expenseCent / reachedMonthCount
}

/** 账户 + 派生余额。余额按全历史有效记录计算，不随月份筛选改变。 */
data class AccountWithBalance(
    val account: LedgerAccount,
    val balanceCent: Long,
    val transactionCount: Int = 0,
) {
    val openingBalanceCent: Long get() = account.openingBalanceCent
    /** 期初之外的净变动，用于 S09「初始余额修改前后对比」提示。 */
    val netChangeCent: Long get() = balanceCent - account.openingBalanceCent
}

/** 预算状态。UNSET 只在 [BudgetState.budgetCent] 为 null 时出现，绝不用 0 或 NaN 表达未设置。 */
enum class BudgetStatus { UNSET, NORMAL, NEAR_LIMIT, EXHAUSTED, EXCEEDED }

/**
 * 某个月的预算状态。
 *
 * 口径：
 * - 预算已用 = 该月全部有效支出（收入不参与，收入不会降低已用）
 * - 预算剩余 = 预算 − 已用（可为负）
 * - 使用率 = 已用 ÷ 预算（真实值可超过 100%）
 * - 进度条宽度最多 100%（见 [progressFraction]），文字保留真实使用率
 */
data class BudgetState(
    val yearMonth: YearMonth,
    val budgetCent: Long? = null,
    val usedCent: Long = 0L,
) {
    val isSet: Boolean get() = budgetCent != null && budgetCent > 0L

    /** 预算剩余（可为负）；未设置时为 null。 */
    val remainingCent: Long?
        get() = budgetCent?.takeIf { it > 0L }?.let { it - usedCent }

    /** 超支金额；未超支或未设置时为 null。 */
    val exceededCent: Long?
        get() = budgetCent?.takeIf { it > 0L }?.let { if (usedCent > it) usedCent - it else null }

    /** 使用率的万分比（10000 = 100%），已四舍五入到整数；未设置时为 null。 */
    val ratioBasisPoint: Long?
        get() = budgetCent?.takeIf { it > 0L }?.let { ratioBasisPoint(usedCent, it) }

    /**
     * 使用率的百分数一位小数（767 表示 76.7%）；未设置时为 null。
     *
     * 由 [ratioBasisPoint] **四舍五入**到一位小数，不是截断：
     * 7697（76.97%）→ 770（77.0%），与 PRD §9.1 的「76.97%，若一位小数展示为 77.0%」一致。
     * 截断会得到 76.9%，属于展示口径错误，已由 A7 的契约测试固定住。
     */
    val ratioPermille: Int?
        get() = ratioBasisPoint?.let { ((it + 5L) / 10L).toInt() }

    /** 进度条宽度比例，最多 1.0；未设置时为 null。 */
    val progressFraction: Float?
        get() = ratioBasisPoint?.let { (it / 10_000.0).coerceIn(0.0, 1.0).toFloat() }

    val status: BudgetStatus
        get() {
            val budget = budgetCent?.takeIf { it > 0L } ?: return BudgetStatus.UNSET
            return when {
                usedCent > budget -> BudgetStatus.EXCEEDED
                usedCent == budget -> BudgetStatus.EXHAUSTED
                usedCent * 10_000L >= budget * 8_000L -> BudgetStatus.NEAR_LIMIT
                else -> BudgetStatus.NORMAL
            }
        }

    companion object {
        /** 使用率的万分比（10000 = 100%）。极端大额时退化为 BigDecimal，避免 Long 溢出。 */
        fun ratioBasisPoint(usedCent: Long, budgetCent: Long): Long {
            require(budgetCent > 0L) { "budgetCent must be > 0" }
            if (usedCent <= Long.MAX_VALUE / 10_000L) {
                return (usedCent * 10_000L + budgetCent / 2L) / budgetCent
            }
            return BigDecimal.valueOf(usedCent)
                .multiply(BigDecimal.valueOf(10_000L))
                .divide(BigDecimal.valueOf(budgetCent), 0, RoundingMode.HALF_UP)
                .toLong()
        }
    }
}
