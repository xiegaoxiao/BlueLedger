package com.blueledger.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.TransactionType

/**
 * 账单表。
 *
 * 存储约定（与全项目一致，见 docs/实现决策.md）：
 * - `occurredOnEpochDay`：用户选择的发生日期，存 epochDay（Long），不带时区。
 * - `createdAtEpochMillis` / `updatedAtEpochMillis` / `deletedAtEpochMillis`：审计时间戳，存 epochMillis。
 * - `type` / `amountCent`：方向与金额分离，amountCent 恒为正整数分。
 * - `noteKey`：note 的归一化副本（trim + lowercase），只用于不区分大小写的包含搜索。
 * - `requestId`：同一次提交的幂等键；唯一索引保证重复提交只产生一条记录
 *   （SQLite 允许唯一索引中存在多行 NULL，因此没有 requestId 的写入不受影响）。
 */
@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["occurredOnEpochDay"]),
        Index(value = ["type"]),
        Index(value = ["categoryId"]),
        Index(value = ["accountId"]),
        Index(value = ["deletedAtEpochMillis"]),
        Index(value = ["occurredOnEpochDay", "createdAtEpochMillis", "id"]),
        Index(value = ["requestId"], unique = true),
    ],
)
data class TransactionEntity(
    @PrimaryKey val id: String,
    val type: TransactionType,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val occurredOnEpochDay: Long,
    val note: String,
    val noteKey: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long? = null,
    val requestId: String? = null,
)

/**
 * 分类表。
 *
 * `(type, nameKey)` 唯一索引覆盖**归档分类**，落实“同类型分类名包含归档一起查重”，
 * 从而不会出现归档后新建同名分类、恢复时产生歧义的情况。
 */
@Entity(
    tableName = "categories",
    indices = [
        Index(value = ["type", "nameKey"], unique = true),
        Index(value = ["type", "isArchived", "sortOrder"]),
        Index(value = ["nameKey"]),
    ],
)
data class CategoryEntity(
    @PrimaryKey val id: String,
    val type: TransactionType,
    val name: String,
    val nameKey: String,
    val iconKey: String,
    val sortOrder: Int,
    val isArchived: Boolean,
    val isFallback: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * 账户表。余额不是列，而是由有效账单派生（见 [AccountDao.observeWithBalance]）。
 * 账户名唯一性只在“未归档账户范围”内成立，因此由数据层在事务中校验，不用唯一索引。
 */
@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["nameKey"]),
        Index(value = ["isArchived"]),
    ],
)
data class AccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val nameKey: String,
    val kind: AccountKind,
    val openingBalanceCent: Long,
    val isArchived: Boolean,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    @androidx.room.ColumnInfo(defaultValue = "''") val note: String = "",
    @androidx.room.ColumnInfo(defaultValue = "''") val openingHistory: String = "",
)

/** 月预算表。`yearMonth`（`YYYY-MM`）为主键，天然保证每月独立且唯一。 */
@Entity(tableName = "monthly_budgets")
data class BudgetEntity(
    @PrimaryKey val yearMonth: String,
    val amountCent: Long,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

/**
 * 设置表：同库单例行（id = 1），与业务数据同事务提交，便于备份恢复原子替换。
 *
 * [lastBackupAtEpochMillis] / [lastBackupFileName] 记录最近一次成功备份/导出，
 * 不写入备份文件；恢复时保留设备当前值。
 */
@Entity(tableName = "ledger_settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val defaultAccountId: String,
    val lastUsedAccountId: String? = null,
    val hideAmounts: Boolean = false,
    val currency: String = "CNY",
    val lastBackupAtEpochMillis: Long? = null,
    val lastBackupFileName: String? = null,
    val updatedAtEpochMillis: Long,
    @androidx.room.ColumnInfo(defaultValue = "NULL") val monthlyBudgetCent: Long? = null,
) {
    companion object {
        const val SINGLETON_ID: Int = 1
    }
}
