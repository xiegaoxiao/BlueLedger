package com.blueledger.app.core.model

/**
 * 可区分的业务/存储错误。任何写操作都不得吞掉异常后返回成功；
 * 失败必须带可读原因，UI 依此给用户具体的、可恢复的提示。
 */
sealed interface LedgerError {
    /** 面向用户的中文说明，可直接展示。 */
    val message: String

    /** 字段校验失败。[field] 用于把错误定位到具体输入项。 */
    data class Validation(
        val code: ValidationCode,
        override val message: String,
        val field: FieldRef? = null,
    ) : LedgerError

    /** 引用的实体不存在（含“已被删除”）。 */
    data class NotFound(
        val entity: String,
        val id: String,
        override val message: String = "记录不存在或已被删除",
    ) : LedgerError

    /** 业务冲突：重名、最后账户保护、归档约束等。 */
    data class Conflict(
        val code: ValidationCode,
        override val message: String,
    ) : LedgerError

    /** 存储层失败（磁盘、SQLite、事务回滚）。[causeClass] 只放异常类型名，不放用户数据。 */
    data class Storage(
        override val message: String,
        val causeClass: String? = null,
    ) : LedgerError

    /** 备份/恢复专用错误。 */
    data class Backup(
        val code: ValidationCode,
        override val message: String,
        val detail: String? = null,
    ) : LedgerError
}

/** 校验错误的稳定代码。测试按代码断言，界面按 message 展示。 */
enum class ValidationCode {
    // 金额
    AMOUNT_EMPTY,
    AMOUNT_NOT_A_NUMBER,
    AMOUNT_MULTIPLE_DECIMAL_POINTS,
    AMOUNT_TOO_MANY_DECIMALS,
    AMOUNT_ZERO,
    AMOUNT_NEGATIVE,
    AMOUNT_OUT_OF_RANGE,

    // 账单
    TRANSACTION_NOT_FOUND,
    TRANSACTION_TYPE_MISMATCH,
    CATEGORY_REQUIRED,
    CATEGORY_NOT_FOUND,
    CATEGORY_TYPE_MISMATCH,
    CATEGORY_ARCHIVED,
    ACCOUNT_REQUIRED,
    ACCOUNT_NOT_FOUND,
    ACCOUNT_ARCHIVED,
    DATE_FUTURE,
    DATE_INVALID,
    NOTE_TOO_LONG,
    DUPLICATE_REQUEST,

    // 分类
    CATEGORY_NAME_EMPTY,
    CATEGORY_NAME_TOO_LONG,
    CATEGORY_NAME_DUPLICATE,
    CATEGORY_FALLBACK_PROTECTED,
    CATEGORY_TYPE_IMMUTABLE,
    CATEGORY_LAST_ACTIVE,
    CATEGORY_REORDER_INVALID,
    ICON_KEY_INVALID,

    // 账户
    ACCOUNT_NAME_EMPTY,
    ACCOUNT_NAME_TOO_LONG,
    ACCOUNT_NAME_DUPLICATE,
    ACCOUNT_LAST_ACTIVE,
    ACCOUNT_ARCHIVE_REQUIRES_REPLACEMENT,
    ACCOUNT_NOT_FOUND_FOR_DEFAULT,
    OPENING_BALANCE_OUT_OF_RANGE,

    // 预算
    BUDGET_NOT_POSITIVE,
    BUDGET_OUT_OF_RANGE,

    // 撤销
    UNDO_TOKEN_INVALID,
    UNDO_TOKEN_EXPIRED,
    UNDO_TARGET_CONFLICT,

    // 备份 / 恢复
    BACKUP_NOT_JSON,
    BACKUP_PRODUCT_MISMATCH,
    BACKUP_UNSUPPORTED_VERSION,
    BACKUP_CURRENCY_MISMATCH,
    BACKUP_FIELD_TYPE_INVALID,
    BACKUP_DUPLICATE_ID,
    BACKUP_MISSING_REFERENCE,
    BACKUP_CATEGORY_TYPE_MISMATCH,
    BACKUP_DATE_INVALID,
    BACKUP_DATE_FUTURE,
    BACKUP_AMOUNT_OUT_OF_RANGE,
    BACKUP_NAME_CONSTRAINT,
    BACKUP_ICON_INVALID,
    BACKUP_BUDGET_DUPLICATE_MONTH,
    BACKUP_DEFAULT_ACCOUNT_INVALID,
    BACKUP_NO_ACTIVE_ACCOUNT,
    BACKUP_NO_FALLBACK_CATEGORY,
    BACKUP_TOO_LARGE,
    BACKUP_TOO_MANY_ENTRIES,
    BACKUP_WRITE_FAILED,

    // 通用存储
    STORAGE_FAILURE,
}

/** 错误定位到具体表单项。 */
enum class FieldRef { AMOUNT, CATEGORY, DATE, ACCOUNT, NOTE, NAME, ICON, OPENING_BALANCE, BUDGET }
