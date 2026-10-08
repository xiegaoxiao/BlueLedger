package com.blueledger.app.core.model

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/**
 * 新增/编辑账单的提交载荷。
 *
 * 校验责任在数据层（业务边界），UI 只做即时反馈：
 * - [amountCent] 必须落在 [Limits.MIN_TRANSACTION_CENT]..[Limits.MAX_TRANSACTION_CENT]
 * - [occurredOn] 不得晚于设备今日
 * - [categoryId] 必须存在、类型与 [type] 一致；新增时不得使用已归档分类，
 *   编辑时允许保留“数据库原记录中的同一分类”（由数据层核对，不信任 UI 自称的“原值”）
 * - [accountId] 必须存在；新增时不得使用已归档账户，编辑时同理受同一规则约束
 * - [note] 长度不得超过 [Limits.MAX_NOTE_LENGTH]
 */
data class TransactionDraft(
    val type: TransactionType,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val occurredOn: LocalDate,
    val note: String = "",
)

/**
 * 分类新增/编辑命令。[id] 为 null 表示新增。
 * 新建分类的 [type] 一经确定不可更改（数据层拒绝改类型的“编辑”请求）。
 */
data class CategoryCommand(
    val id: String? = null,
    val type: TransactionType,
    val name: String,
    val iconKey: String,
    val sortOrder: Int? = null,
)

/**
 * 账户新增/编辑命令。[id] 为 null 表示新增。
 * [openingBalanceCent] 允许负数，绝对值不得超过 [Limits.MAX_OPENING_BALANCE_CENT]。
 */
data class AccountCommand(
    val id: String? = null,
    val name: String,
    val kind: AccountKind,
    val openingBalanceCent: Long,
    val note: String? = null,
)

/** 一次删除操作的撤销凭据。与本次删除事件绑定，不可串用到另一次删除。 */
data class DeleteReceipt(
    val transactionId: String,
    /** 与本次删除事件绑定的令牌；过期/重复使用/已被新的删除替换时撤销必须失败。 */
    val token: String,
    val deletedAt: Instant,
    /** 删除前的原记录，用于恢复原 id 与原字段。 */
    val original: LedgerTransaction,
)

/** 备份文件解码后的摘要，用于恢复确认页。 */
data class BackupSummary(
    val product: String,
    val schemaVersion: Int,
    val exportedAt: Instant,
    val currency: String,
    val transactionCount: Int,
    val categoryCount: Int,
    val accountCount: Int,
    val budgetCount: Int,
    val earliestOccurredOn: LocalDate? = null,
    val latestOccurredOn: LocalDate? = null,
    val budgetMonths: List<YearMonth> = emptyList(),
    val fileName: String? = null,
)
