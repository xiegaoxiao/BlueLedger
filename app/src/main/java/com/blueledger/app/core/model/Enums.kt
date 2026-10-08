package com.blueledger.app.core.model

/** 收支类型。收入与支出共用同一张账单表，用 type 区分；amountCent 始终为正整数。 */
enum class TransactionType {
    INCOME,
    EXPENSE;

    val isIncome: Boolean get() = this == INCOME
    val isExpense: Boolean get() = this == EXPENSE

    companion object {
        /** 取反类型，用于 UI 分段切换。 */
        fun of(type: TransactionType): TransactionType = type
    }
}

/** 账户类型。仅用于展示与分组，不参与统计口径。 */
enum class AccountKind {
    CASH,
    BANK_CARD,
    E_WALLET,
    OTHER,
    CREDIT_CARD,
    INVESTMENT,
    LIABILITY,
    RECEIVABLE;

    val isLiability: Boolean get() = this == CREDIT_CARD || this == LIABILITY

    companion object {
        val default: AccountKind = CASH
    }
}
