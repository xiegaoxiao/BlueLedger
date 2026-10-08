package com.blueledger.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 一笔账单。
 *
 * 不可变约束：
 * - [amountCent] 恒为正整数分（1..999_999_999）；收支方向由 [type] 表达，绝不把符号写进金额。
 * - [occurredOn] 是用户选择的“发生日期”，不带时区；月份/年份归属直接由它决定。
 * - [createdAt]/[updatedAt]/[deletedAt] 是带时区含义的审计时间戳。
 * - [deletedAt] != null 表示软删除；软删除账单不参与列表、聚合、账户余额、CSV 与正式备份。
 */
data class LedgerTransaction(
    val id: String,
    val type: TransactionType,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val occurredOn: LocalDate,
    val note: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
) {
    val isDeleted: Boolean get() = deletedAt != null
}

/** 分类。ID 稳定；重命名不改变历史关联。 */
data class LedgerCategory(
    val id: String,
    val type: TransactionType,
    val name: String,
    val iconKey: String,
    val sortOrder: Int,
    val isArchived: Boolean = false,
    val isFallback: Boolean = false,
)

/** 账户。余额是派生值，见 [com.blueledger.app.core.model.AccountWithBalance]。 */
data class LedgerAccount(
    val id: String,
    val name: String,
    val kind: AccountKind,
    val openingBalanceCent: Long,
    val isArchived: Boolean = false,
    val note: String = "",
    /** 按日期保存期初基准调整：epochDay:cent，以分号分隔；余额仍包含对应账单。 */
    val openingHistory: String = "",
)

/**
 * 月预算。按 [yearMonth] 独立保存；零预算不是有效预算，
 * 因此“未设置”用记录不存在表达，而不是 amountCent = 0。
 */
data class MonthlyBudget(
    val yearMonth: YearMonth,
    val amountCent: Long,
)

/**
 * 全局设置（数据库中的单例行，与业务数据同库同事务，便于原子恢复）。
 *
 * [lastBackupAt] / [lastBackupFileName] 只记录“最近一次成功备份/导出”，
 * 不写入备份文件，恢复时保留设备当前值，避免把旧时间当成刚备份成功。
 */
data class LedgerSettings(
    val defaultAccountId: String,
    val lastUsedAccountId: String? = null,
    val hideAmounts: Boolean = false,
    val currency: String = CURRENCY_CNY,
    val lastBackupAt: Instant? = null,
    val lastBackupFileName: String? = null,
    val monthlyBudgetCent: Long? = null,
) {
    companion object {
        const val CURRENCY_CNY: String = "CNY"
    }
}
