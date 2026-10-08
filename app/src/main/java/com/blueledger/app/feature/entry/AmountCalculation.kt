package com.blueledger.app.feature.entry

import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.money.Money

/** 加减计算只使用整数分，运算结果仍须通过正常账单金额校验。 */
data class AmountCalculation(val leftCent: Long, val operator: Char) {
    init { require(operator == '+' || operator == '-') }
    fun result(right: AmountInput): Long? {
        val rightCent = right.centsOrNull() ?: return null
        val result = if (operator == '+') leftCent + rightCent else leftCent - rightCent
        return result.takeIf { it in 0..Limits.MAX_TRANSACTION_CENT }
    }
    fun display(right: AmountInput): String = Money.format(leftCent) + " $operator " +
        if (right.isEmpty) "" else right.displayText
}
