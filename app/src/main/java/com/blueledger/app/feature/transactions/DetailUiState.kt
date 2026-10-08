package com.blueledger.app.feature.transactions

import com.blueledger.app.core.model.LedgerTransaction

/**
 * S04 账单详情的 UI 状态。
 *
 * 关键约定：
 * - [transaction] 为 null 且不在加载中，就是「记录不存在 / 已删除」状态；
 *   此时页面**不再显示任何旧内容**，只给友好提示和返回动作。
 * - 名称（分类 / 账户）由分类、账户流单独解析，重命名后详情自动更新。
 */
data class DetailUiState(
    val loading: Boolean = true,
    val transaction: LedgerTransaction? = null,
    val categoryName: String = "",
    val categoryIconKey: String = "",
    val categoryArchived: Boolean = false,
    val accountName: String = "",
    val showDeleteDialog: Boolean = false,
    val deleting: Boolean = false,
    val deleteError: String? = null,
    val readErrorMessage: String? = null,
    /** 本次会话内已经删除成功（等待上层返回），此时不再显示「记录不存在」。 */
    val deleted: Boolean = false,
) {

    /** 记录不存在或已软删除。 */
    val isMissing: Boolean get() = !loading && transaction == null && !deleted && readErrorMessage == null

    val isIncome: Boolean get() = transaction?.type == com.blueledger.app.core.model.TransactionType.INCOME

    /** 是否显示「最近修改」：仅当 updatedAt 与 createdAt 不同（创建时间不能当作发生日期）。 */
    val hasBeenUpdated: Boolean
        get() = transaction?.let { it.updatedAt != it.createdAt } == true
}
