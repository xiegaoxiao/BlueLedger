package com.blueledger.app.core.money

import com.blueledger.app.core.model.FieldRef
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import java.util.Locale

/**
 * 金额解析结果。
 *
 * 失败时携带稳定的 [ValidationCode]，测试按代码断言，界面按 [message] 展示。
 */
sealed interface AmountParseResult {

    data class Success(val amountCent: Long) : AmountParseResult {
        val isValid: Boolean get() = true
    }

    data class Failure(
        val code: ValidationCode,
        val message: String,
    ) : AmountParseResult {
        val isValid: Boolean get() = false

        /** 转换为通用的校验错误，便于写入层直接返回。 */
        fun toLedgerError(): LedgerError.Validation =
            LedgerError.Validation(code = code, message = message, field = FieldRef.AMOUNT)
    }
}

/**
 * 金额工具：十进制字符串 ↔ 整数分。
 *
 * 硬性约束（PRD §6.1、AI 提示词 §4.2）：
 * - 只使用整数拆分与十进制字符串解析，**禁止** `Double * 100`。
 * - 支持 `12` → 1200、`12.5` → 1250、`12.50` → 1250、`0.01` → 1。
 * - 拒绝空、0、负数、第三位小数、多个小数点、超上限、非数字，并给出对应 [ValidationCode]。
 * - 格式化统一两位小数；千分位与 ± 符号只属于展示，绝不写回业务数据。
 */
object Money {

    /** 允许出现的千分位分组写法，例如 `9,999,999.99`。分组位置错误一律按非数字拒绝。 */
    private val GROUPED_PATTERN = Regex("""^[+-]?\d{1,3}(,\d{3})+(\.\d*)?$""")

    // ───────────────────────── 解析 ─────────────────────────

    /**
     * 把用户输入解析为整数分。
     *
     * 说明：`0.`、`12.` 这类结尾小数点属于输入中间态，本方法按“数值 + 精度”处理：
     * `12.` → 1200（可保存），`0.` → 0 分（不可保存，返回 [ValidationCode.AMOUNT_ZERO]）。
     */
    fun parse(raw: String): AmountParseResult {
        var text = raw.trim()
        if (text.isEmpty()) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_EMPTY, "请输入金额")
        }

        // 千分位：只在完全符合分组规则时接受，其余一律按非数字拒绝，不做宽松清洗。
        if (text.contains(',')) {
            if (!GROUPED_PATTERN.matches(text)) {
                return AmountParseResult.Failure(ValidationCode.AMOUNT_NOT_A_NUMBER, "金额格式不正确")
            }
            text = text.replace(",", "")
        }

        var negative = false
        if (text.startsWith("-")) {
            negative = true
            text = text.substring(1)
        } else if (text.startsWith("+")) {
            text = text.substring(1)
        }

        if (text.isEmpty()) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_NOT_A_NUMBER, "金额格式不正确")
        }

        val dotCount = text.count { it == '.' }
        if (dotCount > 1) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_MULTIPLE_DECIMAL_POINTS, "金额只能有一个小数点")
        }
        if (!text.all { it in '0'..'9' || it == '.' }) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_NOT_A_NUMBER, "金额只能包含数字和小数点")
        }

        val parts = text.split('.')
        val intPart = parts[0]
        val fractionPart = if (parts.size == 2) parts[1] else ""

        if (intPart.isEmpty() && fractionPart.isEmpty()) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_NOT_A_NUMBER, "金额格式不正确")
        }
        if (fractionPart.length > Limits.MAX_AMOUNT_DECIMALS) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_TOO_MANY_DECIMALS, "金额最多两位小数")
        }
        // 整数部分超过 10 位一定超上限，提前拒绝，避免 toLong 溢出。
        if (intPart.length > 10) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_OUT_OF_RANGE, "金额超出上限")
        }

        val yuanPart = if (intPart.isEmpty()) 0L else intPart.toLongOrNull()
            ?: return AmountParseResult.Failure(ValidationCode.AMOUNT_NOT_A_NUMBER, "金额格式不正确")
        val centPart = when (fractionPart.length) {
            0 -> 0L
            1 -> fractionPart.toLong() * 10L
            else -> fractionPart.toLong()
        }
        val cent = yuanPart * Limits.CENT_PER_YUAN + centPart

        if (negative) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_NEGATIVE, "金额必须大于 0")
        }
        if (cent < Limits.MIN_TRANSACTION_CENT) {
            return AmountParseResult.Failure(ValidationCode.AMOUNT_ZERO, "金额必须大于 0")
        }
        if (cent > Limits.MAX_TRANSACTION_CENT) {
            return AmountParseResult.Failure(
                ValidationCode.AMOUNT_OUT_OF_RANGE,
                "金额不能超过 9,999,999.99 元",
            )
        }
        return AmountParseResult.Success(cent)
    }

    /** 解析成功返回分，失败返回 null。 */
    fun parseOrNull(raw: String): Long? = (parse(raw) as? AmountParseResult.Success)?.amountCent

    // ───────────────────────── 格式化（仅展示） ─────────────────────────

    /**
     * 格式化整数分为两位小数，带千分位；负数保留 `-`。
     * 例：`1250` → `12.50`，`-92120` → `-921.20`。
     */
    fun format(cent: Long): String {
        val negative = cent < 0L
        val abs = if (cent == Long.MIN_VALUE) Long.MAX_VALUE else if (negative) -cent else cent
        val yuan = abs / Limits.CENT_PER_YUAN
        val fen = abs % Limits.CENT_PER_YUAN
        val builder = StringBuilder(24)
        if (negative) builder.append('-')
        builder.append(groupThousands(yuan.toString()))
        builder.append('.')
        if (fen < 10L) builder.append('0')
        builder.append(fen)
        return builder.toString()
    }

    /**
     * 按收支类型格式化带符号金额：支出 `-28.50`、收入 `+5,000.00`。
     * 原始 amountCent 始终为正数，符号只属于展示。
     */
    fun formatSigned(cent: Long, type: TransactionType): String {
        val abs = if (cent < 0L) -cent else cent
        val sign = if (type == TransactionType.EXPENSE) "-" else "+"
        return sign + format(abs)
    }

    /** 万分比（10000 = 100%）格式化为一位小数百分比，四舍五入：`7697` → `77.0%`。 */
    fun formatBasisPoint(basisPoint: Long): String =
        String.format(Locale.ROOT, "%.1f%%", basisPoint / 100.0)

    /** 千分比（1000 = 100%，即百分比 × 10）格式化为一位小数百分比：`769` → `76.9%`。 */
    fun formatPermille(permille: Int): String =
        String.format(Locale.ROOT, "%.1f%%", permille / 10.0)

    /** 千分位分组（仅整数部分，输入必须是纯数字串）。 */
    private fun groupThousands(digits: String): String {
        if (digits.length <= 3) return digits
        val builder = StringBuilder(digits.length + digits.length / 3)
        val firstGroup = digits.length % 3
        var index = 0
        if (firstGroup > 0) {
            builder.append(digits, 0, firstGroup)
            index = firstGroup
        }
        while (index < digits.length) {
            if (builder.isNotEmpty()) builder.append(',')
            builder.append(digits, index, index + 3)
            index += 3
        }
        return builder.toString()
    }

    // ───────────────────────── 精确计算 ─────────────────────────

    /** 精确累加。任何溢出都抛出 [ArithmeticException]，绝不静默回绕成负数。 */
    fun sumExact(values: Iterable<Long>): Long {
        var total = 0L
        for (value in values) total = Math.addExact(total, value)
        return total
    }

    fun addExact(a: Long, b: Long): Long = Math.addExact(a, b)

    fun subtractExact(a: Long, b: Long): Long = Math.subtractExact(a, b)
}

/**
 * 金额键盘的编辑规则（纯逻辑，供 A2 的记账键盘直接调用）。
 *
 * 目的：避免 UI 各写一份 `appendDot`/`backspace` 后出现“小数点被裁掉”“第三位小数被静默四舍五入”一类问题。
 * 上限规则与 [Limits.MAX_TRANSACTION_CENT] 一致：整数部分最多 7 位、小数最多 2 位。
 */
object AmountInput {

    const val MAX_INTEGER_DIGITS: Int = 7

    /** 追加数字；超出整数位上限时保持原值不变。 */
    fun appendDigit(current: String, digit: Char): String {
        if (digit !in '0'..'9') return current
        val safe = sanitize(current)
        val dotIndex = safe.indexOf('.')
        if (dotIndex < 0) {
            val digits = safe.ifEmpty { "0" }
            if (digits == "0") return digit.toString()
            if (digits.length >= MAX_INTEGER_DIGITS) return safe
            return digits + digit
        }
        val fraction = safe.substring(dotIndex + 1)
        if (fraction.length >= Limits.MAX_AMOUNT_DECIMALS) return safe
        return safe + digit
    }

    /** 追加小数点；已有小数点或已达上限时保持原值不变。 */
    fun appendDot(current: String): String {
        val safe = sanitize(current)
        if (safe.contains('.')) return safe
        if (safe.length >= MAX_INTEGER_DIGITS) return safe
        return if (safe.isEmpty()) "0." else "$safe."
    }

    /** 删除最后一位；空串保持为空。 */
    fun backspace(current: String): String {
        val safe = sanitize(current)
        return if (safe.isEmpty()) safe else safe.substring(0, safe.length - 1)
    }

    /** 只保留数字与一个小数点，并去掉小数点后多于两位的部分。 */
    fun sanitize(current: String): String {
        val builder = StringBuilder(current.length)
        var dotUsed = false
        var fractionDigits = 0
        for (ch in current) {
            when {
                ch in '0'..'9' -> {
                    if (dotUsed) {
                        if (fractionDigits >= Limits.MAX_AMOUNT_DECIMALS) continue
                        fractionDigits++
                    }
                    builder.append(ch)
                }

                ch == '.' && !dotUsed -> {
                    dotUsed = true
                    builder.append(ch)
                }

                else -> Unit
            }
        }
        var text = builder.toString()
        val dotIndex = text.indexOf('.')
        if (dotIndex >= 0) {
            val integerPart = text.substring(0, dotIndex)
            if (integerPart.length > MAX_INTEGER_DIGITS) {
                text = integerPart.takeLast(MAX_INTEGER_DIGITS) + text.substring(dotIndex)
            }
        } else if (text.length > MAX_INTEGER_DIGITS) {
            text = text.takeLast(MAX_INTEGER_DIGITS)
        }
        return if (text.length > 1 && text.startsWith("0") && !text.startsWith("0.")) {
            text.trimStart('0').ifEmpty { "0" }
        } else {
            text
        }
    }

    /** 当前输入是否可以直接保存（非空、非 0、合法、不超上限）。 */
    fun isSavable(current: String): Boolean = Money.parseOrNull(current) != null
}
