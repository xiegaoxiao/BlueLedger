package com.blueledger.app.feature.entry

import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.TransactionType
import java.time.LocalDate

/** 记账页的两种模式：新增与编辑使用同一个状态模型。 */
enum class EntryMode { CREATE, EDIT }

/**
 * S02 记账页的完整 UI 状态。
 *
 * 只包含展示所需的不可变数据；金额始终以 [AmountInput]（十进制字符串）
 * 与解析出的 Long 整数分表达，任何地方都不会出现 Double 金额。
 */
data class EntryUiState(
    val mode: EntryMode = EntryMode.CREATE,
    val loading: Boolean = false,
    val loadError: String? = null,
    /** 编辑模式下载入不到原账单（已被删除/不存在）时的专门状态。 */
    val missingTransaction: Boolean = false,
    val transactionId: String? = null,
    val type: TransactionType = TransactionType.EXPENSE,
    val amount: AmountInput = AmountInput(),
    val calculation: AmountCalculation? = null,
    val amountError: String? = null,
    val categories: List<LedgerCategory> = emptyList(),
    val selectedCategoryId: String? = null,
    val categoryError: String? = null,
    val occurredOn: LocalDate = LocalDate.of(1970, 1, 1),
    /** 设备今日（可注入时钟）。日期选择器用它禁止未来日期。 */
    val today: LocalDate = LocalDate.of(1970, 1, 1),
    val dateError: String? = null,
    val accounts: List<AccountWithBalance> = emptyList(),
    val selectedAccountId: String? = null,
    val accountError: String? = null,
    val note: String = "",
    val saving: Boolean = false,
    val saveError: String? = null,
    val savedMessage: String? = null,
    /** 金额模式显示自定义数字键盘；备注聚焦时隐藏，改由系统 IME 输入。 */
    val numericKeyboardVisible: Boolean = true,
    val showDiscardDialog: Boolean = false,
) {
    val isEditMode: Boolean get() = mode == EntryMode.EDIT

    /** 当前可保存的整数分；空或非法时为 null。 */
    val amountCents: Long? get() = amount.centsOrNull()
    val amountDisplayText: String get() = calculation?.display(amount) ?: amount.displayText

    val noteRemaining: Int get() = (Limits.MAX_NOTE_LENGTH - note.length).coerceAtLeast(0)

    /** 备注接近上限（≤20 字剩余）时显示剩余字数。 */
    val showNoteCounter: Boolean get() = note.length >= Limits.MAX_NOTE_LENGTH - 20

    val selectedCategory: LedgerCategory? get() = categories.firstOrNull { it.id == selectedCategoryId }

    val selectedAccount: AccountWithBalance? get() = accounts.firstOrNull { it.account.id == selectedAccountId }

    /** 保存按钮是否可点：保存中禁用两个按钮，避免重复提交。 */
    val actionsEnabled: Boolean get() = !saving && !loading && !missingTransaction

    val saveButtonText: String get() = if (isEditMode) "保存修改" else "保存账单"
}

/** 一次性事件（不放进 StateFlow，避免旋转/重建后重复消费历史事件）。 */
sealed interface EntryEvent {
    /** 保存成功（普通保存/编辑保存）或用户确认放弃修改后返回来源页。 */
    data object Exit : EntryEvent
}
