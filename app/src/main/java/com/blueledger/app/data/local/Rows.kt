package com.blueledger.app.data.local

import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.TransactionType

/** 汇总查询结果：期间收入、支出与笔数。SUM 无行时用 COALESCE 归零。 */
data class SummaryRow(
    val incomeCent: Long,
    val expenseCent: Long,
    val totalCount: Int,
)

/** 账单 + 分类/账户展示字段。分类或账户缺失时用 COALESCE 兜底为空串（正常数据不会发生）。 */
data class TransactionRefRow(
    val id: String,
    val type: TransactionType,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val occurredOnEpochDay: Long,
    val note: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
    val categoryName: String,
    val categoryIconKey: String,
    val categoryArchived: Boolean,
    val accountName: String,
)

/** 月度分析用的原始行：一次查询拿到该月全部有效账单，摘要/日趋势/分类切片由同一批数据推出。 */
data class MonthRow(
    val categoryId: String,
    val amountCent: Long,
    val type: TransactionType,
    val occurredOnEpochDay: Long,
    val categoryName: String,
    val categoryIconKey: String,
    val categorySortOrder: Int,
)

/** 年度分析用的原始行。 */
data class YearRow(
    val amountCent: Long,
    val type: TransactionType,
    val occurredOnEpochDay: Long,
)

/** 账户 + 派生余额（期初 + 全部有效收入 − 全部有效支出）与有效账单笔数。 */
data class AccountBalanceRow(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val openingBalanceCent: Long,
    val isArchived: Boolean,
    val balanceCent: Long,
    val transactionCount: Int,
    val note: String = "",
    val openingHistory: String = "",
)

/** 某月预算状态：budgetCent 为 null 表示未设置（绝不用 0 表达未设置）。 */
data class BudgetStateRow(
    val budgetCent: Long?,
    val usedCent: Long,
)
