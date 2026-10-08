package com.blueledger.app.core.model

import java.time.Instant

/**
 * 一次性一致快照：在同一个 SQLite 读事务中取得全部关联实体，
 * 保证导出的分类/账户/预算/设置与账单属于同一时刻。
 *
 * 包含：
 * - 全部**有效**账单（deletedAt == null）
 * - **全部**分类与账户，包括没有任何有效账单引用的归档项
 *   （用户的分类/账户配置本身就是需要保留的数据，不能只导出被引用到的实体）
 * - 全部月预算与设置
 *
 * 不包含：页面草稿、软删除账单。
 */
data class LedgerSnapshot(
    val exportedAt: Instant,
    val currency: String,
    val transactions: List<LedgerTransaction>,
    val categories: List<LedgerCategory>,
    val accounts: List<LedgerAccount>,
    val budgets: List<MonthlyBudget>,
    val settings: LedgerSettings,
)

/**
 * 只能由验证器创建的快照包装。
 *
 * 构造器私有：唯一的产生途径是 [fromVerified]，它的调用点必须是
 * 已经完整执行了备份校验（顶层标识/版本/币种、字段类型、ID 唯一、
 * 外键存在、分类类型一致、日期有效且不未来、金额范围、名称约束、
 * 图标合法、预算月份唯一、默认账户有效、至少一个可用账户、
 * 每类型至少一个兜底分类、条目数/体积上限）的代码。
 *
 * 数据层在写入前会**再次**核对日期、引用与业务限制，不单方面信任本对象。
 */
class ValidatedLedgerSnapshot private constructor(
    val snapshot: LedgerSnapshot,
) {
    companion object {
        /**
         * 仅供备份校验器在校验全部通过后调用。
         * 调用方必须已经在同一份数据上完成全部校验，否则会把非法数据带进恢复事务。
         */
        fun fromVerified(snapshot: LedgerSnapshot): ValidatedLedgerSnapshot =
            ValidatedLedgerSnapshot(snapshot)
    }
}
