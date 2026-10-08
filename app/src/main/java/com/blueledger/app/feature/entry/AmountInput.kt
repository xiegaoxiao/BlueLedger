package com.blueledger.app.feature.entry

import com.blueledger.app.core.model.FieldRef
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.ValidationCode

/**
 * S02 金额输入的**纯函数**状态机。
 *
 * 设计约束（docs/AI开发提示词.md §4.2）：
 * - 只保存用户输入的十进制字符串，解析到分一律使用整数拆分，
 *   **严禁 Double × 100**，不出现任何浮点中间值。
 * - 最多两位小数、最多一个小数点；整数部分最多 7 位，保证不超过
 *   9,999,999.99 元（[Limits.MAX_TRANSACTION_CENT]）。
 * - `0.` 是合法中间态，可以显示但保存时必须解析为 0 并给出「请输入大于 0 的金额」。
 * - 「12」保存为 1200 分，「12.5」与「12.50」都保存为 1250 分，「0.01」保存为 1 分。
 *
 * 该状态机的行为与键盘按键一一对应，不依赖 Compose 或 Android。
 */
data class AmountInput(val text: String = "") {

    val isEmpty: Boolean get() = text.isEmpty()

    /** 展示文本：未输入时显示占位 `0.00`。 */
    val displayText: String get() = text.ifEmpty { PLACEHOLDER }

    val hasDecimalPoint: Boolean get() = text.contains('.')

    private val decimalDigits: Int
        get() = text.indexOf('.').let { index -> if (index < 0) 0 else text.length - index - 1 }

    private val integerDigits: Int
        get() = text.indexOf('.').let { index -> if (index < 0) text.length else index }

    /** 追加一个数字键。超出小数位/整数位限制时**忽略**该次输入（不静默四舍五入）。 */
    fun appendDigit(digit: Char): AmountInput {
        require(digit in '0'..'9') { "digit must be 0..9" }
        if (hasDecimalPoint) {
            if (decimalDigits >= Limits.MAX_AMOUNT_DECIMALS) return this
            return copy(text = text + digit)
        }
        // 没有小数点：前导零处理 + 整数位上限
        if (text == "0") {
            return if (digit == '0') this else copy(text = digit.toString())
        }
        if (integerDigits >= MAX_INTEGER_DIGITS) return this
        return copy(text = text + digit)
    }

    /** 追加小数点。空输入时补成 `0.`；已有一个小数点时忽略。 */
    fun appendDecimalPoint(): AmountInput = when {
        text.isEmpty() -> AmountInput("0.")
        hasDecimalPoint -> this
        else -> copy(text = text + ".")
    }

    /** 退格。 */
    fun deleteLast(): AmountInput = if (text.isEmpty()) this else AmountInput(text.dropLast(1))

    /** 长按删除/清空。 */
    fun clear(): AmountInput = AmountInput()

    /** 解析为整数分；无法解析（空、非数字、多小数点、超两位小数、超长）时返回 null。 */
    fun centsOrNull(): Long? = parseCents(text)

    fun validate(): AmountValidation {
        val raw = text.trim()
        if (raw.isEmpty()) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_EMPTY,
                "请输入金额",
                FieldRef.AMOUNT,
            )
        }
        if (raw.startsWith('-')) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_NEGATIVE,
                "金额必须大于 0，收入与支出请用类型切换",
                FieldRef.AMOUNT,
            )
        }
        if (raw.count { it == '.' } > 1) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_MULTIPLE_DECIMAL_POINTS,
                "金额只能有一个小数点",
                FieldRef.AMOUNT,
            )
        }
        if (raw.any { !it.isDigit() && it != '.' }) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_NOT_A_NUMBER,
                "请输入有效的数字金额",
                FieldRef.AMOUNT,
            )
        }
        if (raw.substringAfter('.', "").length > Limits.MAX_AMOUNT_DECIMALS) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_TOO_MANY_DECIMALS,
                "金额最多两位小数",
                FieldRef.AMOUNT,
            )
        }
        val cents = parseCents(raw)
            ?: return AmountValidation.Invalid(
                ValidationCode.AMOUNT_OUT_OF_RANGE,
                "金额不能超过 9,999,999.99 元",
                FieldRef.AMOUNT,
            )
        if (cents < Limits.MIN_TRANSACTION_CENT) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_ZERO,
                "请输入大于 0 的金额",
                FieldRef.AMOUNT,
            )
        }
        if (cents > Limits.MAX_TRANSACTION_CENT) {
            return AmountValidation.Invalid(
                ValidationCode.AMOUNT_OUT_OF_RANGE,
                "金额不能超过 9,999,999.99 元",
                FieldRef.AMOUNT,
            )
        }
        return AmountValidation.Valid(cents)
    }

    companion object {
        const val PLACEHOLDER: String = "0.00"

        /** 整数部分最大位数：9,999,999 元 → 999,999,999 分。 */
        const val MAX_INTEGER_DIGITS: Int = 7

        /**
         * 十进制字符串 → 整数分（纯整数运算，无浮点）。
         * 无法解析或超出产品上限时返回 null。
         */
        fun parseCents(text: String): Long? {
            val raw = text.trim()
            if (raw.isEmpty()) return null
            if (raw.count { it == '.' } > 1) return null
            val dot = raw.indexOf('.')
            val intPart = if (dot < 0) raw else raw.substring(0, dot)
            val fracPart = if (dot < 0) "" else raw.substring(dot + 1)
            if (intPart.isEmpty() && fracPart.isEmpty()) return null
            if (intPart.isEmpty() && dot < 0) return null
            val intDigits = intPart.ifEmpty { "0" }
            if (intDigits.any { !it.isDigit() }) return null
            if (fracPart.any { !it.isDigit() }) return null
            if (fracPart.length > Limits.MAX_AMOUNT_DECIMALS) return null
            if (intDigits.length > MAX_INTEGER_DIGITS + 1) return null
            val yuan = intDigits.toLongOrNull() ?: return null
            if (yuan > Limits.MAX_TRANSACTION_CENT / Limits.CENT_PER_YUAN) return null
            val fen = when (fracPart.length) {
                0 -> 0L
                1 -> fracPart.toLong() * 10L
                else -> fracPart.toLong()
            }
            val cents = yuan * Limits.CENT_PER_YUAN + fen
            return if (cents > Limits.MAX_TRANSACTION_CENT) null else cents
        }

        /** 整数分 → 编辑用十进制字符串（不含千分位），用于编辑模式回填。 */
        fun fromCents(cents: Long): AmountInput {
            if (cents <= 0L) return AmountInput()
            val yuan = cents / Limits.CENT_PER_YUAN
            val fen = cents % Limits.CENT_PER_YUAN
            val text = if (fen == 0L) {
                yuan.toString()
            } else {
                yuan.toString() + "." + if (fen < 10L) "0$fen" else fen.toString()
            }
            return AmountInput(text)
        }
    }
}

/** 金额校验结果。 */
sealed interface AmountValidation {
    data class Valid(val cents: Long) : AmountValidation

    data class Invalid(
        val code: ValidationCode,
        val message: String,
        val field: FieldRef = FieldRef.AMOUNT,
    ) : AmountValidation
}
