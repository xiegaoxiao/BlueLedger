package com.blueledger.app.core.contract

import com.blueledger.app.core.model.BackupDecodeResult
import com.blueledger.app.core.model.LedgerSnapshot
import kotlinx.serialization.Serializable

/**
 * 备份文件体积与条目上限。
 *
 * 这些是**拒绝阈值**而不是截断阈值：超过上限必须明确报错并保持原库不变，
 * 绝不截断后当作恢复成功。实际实现在设备上测过之后如有调整，需同步更新
 * docs/实现决策.md 与本文件。
 *
 * 体积检查必须在**读取过程中**累计执行，不能只依赖 ContentResolver 返回的
 * size 元数据（它可能为 -1 或与实际内容不符）。
 */
object BackupLimits {
    /** 顶层产品标识。恢复时不一致直接拒绝。 */
    const val PRODUCT_ID: String = "blueledger"

    /** 备份文件格式版本。与 Room 数据库 schema 版本**独立**管理。 */
    const val BACKUP_SCHEMA_VERSION: Int = 3

    /** 单个备份文件最大 32 MiB。 */
    const val MAX_BYTES: Long = 32L * 1024L * 1024L

    const val MAX_TRANSACTIONS: Int = 100_000
    const val MAX_CATEGORIES: Int = 2_000
    const val MAX_ACCOUNTS: Int = 2_000
    const val MAX_BUDGETS: Int = 1_200

    /** 备份文件扩展名（系统文件保存对话框的建议文件名后缀）。 */
    const val FILE_SUFFIX: String = ".blueledger.json"

    /** 备份文件 MIME 类型。 */
    const val MIME_TYPE: String = "application/json"
}

/**
 * 备份 / 恢复线格式。
 *
 * 约定：
 * - 金额一律为 Long 整数分。
 * - 发生日期一律为 ISO 本地日期 `yyyy-MM-dd`（不带时区）。
 * - 时间戳一律为 ISO-8601 UTC 瞬时字符串（如 `2026-10-07T01:23:45Z`）。
 * - 数组顺序不作为语义；实体关系一律通过稳定 ID 表达，不使用显示名称。
 * - [settings] 中不包含 `lastBackupAt` / `lastBackupFileName`：
 *   恢复后保留设备当前的“最近成功备份时间”，不能把备份文件里的旧时间当成刚备份成功。
 */
@Serializable
data class BackupEnvelope(
    val product: String = BackupLimits.PRODUCT_ID,
    val schemaVersion: Int = BackupLimits.BACKUP_SCHEMA_VERSION,
    val exportedAt: String,
    val currency: String = "CNY",
    val transactions: List<BackupTransactionDto> = emptyList(),
    val categories: List<BackupCategoryDto> = emptyList(),
    val accounts: List<BackupAccountDto> = emptyList(),
    val budgets: List<BackupBudgetDto> = emptyList(),
    val settings: BackupSettingsDto,
    val advanced: com.blueledger.app.core.model.AdvancedLedgerSettings = com.blueledger.app.core.model.AdvancedLedgerSettings(),
    val recycleBin: List<BackupDeletedTransactionDto> = emptyList(),
)

@Serializable
data class BackupTransactionDto(
    val id: String,
    val type: String,
    val amountCent: Long,
    val categoryId: String,
    val accountId: String,
    val occurredOn: String,
    val note: String = "",
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class BackupDeletedTransactionDto(val transaction: BackupTransactionDto, val deletedAt: String)

@Serializable
data class BackupCategoryDto(
    val id: String,
    val type: String,
    val name: String,
    val iconKey: String,
    val sortOrder: Int,
    val isArchived: Boolean = false,
    val isFallback: Boolean = false,
)

@Serializable
data class BackupAccountDto(
    val id: String,
    val name: String,
    val kind: String,
    val openingBalanceCent: Long,
    val isArchived: Boolean = false,
    val note: String = "",
    val openingHistory: String = "",
)

@Serializable
data class BackupBudgetDto(
    val yearMonth: String,
    val amountCent: Long,
)

@Serializable
data class BackupSettingsDto(
    val defaultAccountId: String,
    val lastUsedAccountId: String? = null,
    val hideAmounts: Boolean = false,
    val currency: String = "CNY",
    val monthlyBudgetCent: Long? = null,
)

/**
 * 备份编解码器。
 *
 * - [encode] 把 [LedgerSnapshot] 渲染为正式备份文本。
 * - [decode] 读取并**完整校验**备份文本；只有校验全部通过才返回
 *   [BackupDecodeResult.Valid]，其中的 [com.blueledger.app.core.model.ValidatedLedgerSnapshot]
 *   是唯一能进入恢复事务的类型。
 *
 * 实现必须处理：非 JSON、顶层标识/币种/版本不符、字段类型错误、ID 重复、
 * 外键缺失、分类类型与账单不一致、日期非法或未来、金额超限、名称约束、
 * 图标非法、预算月份唯一、默认账户无效、缺少可用账户、缺少各类型兜底分类、
 * 条目数/体积超限。任一失败都返回具体 [com.blueledger.app.core.model.LedgerError]，
 * 且**不修改任何当前数据**。
 */
interface LedgerBackupCodec {
    fun encode(snapshot: LedgerSnapshot): String

    fun decode(text: String): BackupDecodeResult
}
