package com.blueledger.app.core.model

/**
 * 业务常量上限。这些值在数据层、UI 层与备份校验层共用，任何一层都不得放宽或收窄。
 * 详见 docs/产品需求文档.md §6.1 与 docs/AI开发提示词.md §4.2。
 */
object Limits {
    /** 单笔账单金额下限：1 分（0.01 元）。 */
    const val MIN_TRANSACTION_CENT: Long = 1L

    /** 单笔账单金额上限：999_999_999 分（9,999,999.99 元）。 */
    const val MAX_TRANSACTION_CENT: Long = 999_999_999L

    /** 账户期初余额绝对值上限：999_999_999 分，允许正、零、负。 */
    const val MAX_OPENING_BALANCE_CENT: Long = 999_999_999L

    /** 月预算金额上限，与账单一致；预算必须 > 0。 */
    const val MAX_BUDGET_CENT: Long = 999_999_999L

    /** 备注最大字符数（按 UTF-16 code unit 计，与 EditText/TextField 的 length 一致）。 */
    const val MAX_NOTE_LENGTH: Int = 200

    /** 分类名长度：trim 后 1—12 字符。 */
    const val MIN_CATEGORY_NAME_LENGTH: Int = 1
    const val MAX_CATEGORY_NAME_LENGTH: Int = 12

    /** 账户名长度：trim 后 1—20 字符。 */
    const val MIN_ACCOUNT_NAME_LENGTH: Int = 1
    const val MAX_ACCOUNT_NAME_LENGTH: Int = 20

    /** 金额最多两位小数。 */
    const val MAX_AMOUNT_DECIMALS: Int = 2

    /** 分转元的展示比例。仅用于展示格式化，禁止用于存储或聚合。 */
    const val CENT_PER_YUAN: Long = 100L

    /** 撤销删除窗口：正式规则 5 秒（网页原型的 12 秒只是演示）。 */
    const val UNDO_WINDOW_MILLIS: Long = 5_000L
}
