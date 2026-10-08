package com.blueledger.app.core.model

/** 携带业务返回值的操作结果（例如软删除返回撤销凭据）。 */
sealed interface Outcome<out T> {
    data class Success<T>(val value: T) : Outcome<T>
    data class Failure(val error: LedgerError) : Outcome<Nothing>

    val isSuccess: Boolean get() = this is Success
}

/** 通用写操作结果（分类、账户、预算、设置）。 */
sealed interface MutationResult {
    data class Success(val id: String? = null) : MutationResult
    data class Failure(val error: LedgerError) : MutationResult

    val isSuccess: Boolean get() = this is Success
}

/** 账单保存结果。[created] 区分新增与更新，便于 UI 选择「返回」或「留在本页」。 */
sealed interface SaveResult {
    data class Success(
        val transactionId: String,
        val created: Boolean,
    ) : SaveResult

    data class Failure(val error: LedgerError) : SaveResult

    val isSuccess: Boolean get() = this is Success
}

/** 备份恢复结果。[restoredFrom] 为备份文件中的导出时间，用于成功提示。 */
sealed interface RestoreResult {
    data class Success(
        val transactionCount: Int,
        val categoryCount: Int,
        val accountCount: Int,
        val budgetCount: Int,
    ) : RestoreResult

    data class Failure(val error: LedgerError) : RestoreResult

    val isSuccess: Boolean get() = this is Success
}

/** 备份文件解码结果。只有 [Valid] 携带能进入事务的已验证快照。 */
sealed interface BackupDecodeResult {
    data class Valid(
        val validated: ValidatedLedgerSnapshot,
        val summary: BackupSummary,
    ) : BackupDecodeResult

    data class Invalid(val error: LedgerError) : BackupDecodeResult
}
