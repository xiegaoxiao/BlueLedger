package com.blueledger.app.feature.entry

import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.ValidationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 金额输入的纯 JVM 单测（无 Robolectric）。
 *
 * 覆盖 docs/AI开发提示词.md §13「金额与业务单测」中与输入解析相关的部分：
 * 0.01 / 12 / 12.5 / 12.50 / 上限 9,999,999.99；空、0、负、第三位小数、
 * 多个小数点、超上限全部被拒绝，且**不出现任何 Double×100**。
 */
class AmountInputTest {

    private fun type(vararg keys: String): AmountInput {
        var input = AmountInput()
        keys.forEach { key ->
            input = when (key) {
                "." -> input.appendDecimalPoint()
                "del" -> input.deleteLast()
                else -> input.appendDigit(key[0])
            }
        }
        return input
    }

    // ── 核心验收：逐键输入 12.50 → 1250 分 ──

    @Test
    fun `逐键输入 12 点 5 0 得到 1250 分`() {
        val input = type("1", "2", ".", "5", "0")
        assertEquals("12.50", input.text)
        assertEquals(1250L, input.centsOrNull())
        assertTrue(input.validate() is AmountValidation.Valid)
        assertEquals(1250L, (input.validate() as AmountValidation.Valid).cents)
    }

    @Test
    fun `12 保存为 1200 分`() {
        assertEquals(1200L, type("1", "2").centsOrNull())
    }

    @Test
    fun `12 点 5 与 12 点 50 都是 1250 分`() {
        assertEquals(1250L, type("1", "2", ".", "5").centsOrNull())
        assertEquals(1250L, type("1", "2", ".", "5", "0").centsOrNull())
    }

    @Test
    fun `0 点 01 是 1 分`() {
        assertEquals(1L, type("0", ".", "0", "1").centsOrNull())
    }

    @Test
    fun `上限 9999999 点 99 是 999999999 分`() {
        val input = type("9", "9", "9", "9", "9", "9", "9", ".", "9", "9")
        assertEquals("9999999.99", input.text)
        assertEquals(Limits.MAX_TRANSACTION_CENT, input.centsOrNull())
        assertTrue(input.validate() is AmountValidation.Valid)
    }

    @Test
    fun `超过 7 位整数部分被忽略而不是溢出`() {
        val input = type("9", "9", "9", "9", "9", "9", "9", "9", "9")
        assertEquals("9999999", input.text)
        assertEquals(999_999_900L, input.centsOrNull())
    }

    // ── 中间态与按键行为 ──

    @Test
    fun `小数点作为首个按键补成 0 点（合法中间态）`() {
        val input = type(".")
        assertEquals("0.", input.text)
        assertEquals(0L, input.centsOrNull())
        val validation = input.validate()
        assertTrue(validation is AmountValidation.Invalid)
        assertEquals(ValidationCode.AMOUNT_ZERO, (validation as AmountValidation.Invalid).code)
    }

    @Test
    fun `只允许一个小数点`() {
        val input = type("1", ".", "2", ".", "3")
        assertEquals("1.23", input.text)
        assertEquals(123L, input.centsOrNull())
    }

    @Test
    fun `最多两位小数，第三位被忽略不静默四舍五入`() {
        val input = type("1", ".", "2", "3", "4")
        assertEquals("1.23", input.text)
        assertEquals(123L, input.centsOrNull())
    }

    @Test
    fun `前导零处理`() {
        assertEquals("0", type("0").text)
        assertEquals("0", type("0", "0").text)
        assertEquals("5", type("0", "5").text)
        assertEquals("0.50", type("0", ".", "5", "0").text)
        assertEquals(50L, type("0", ".", "5", "0").centsOrNull())
    }

    @Test
    fun `删除键退格到空并回到占位显示`() {
        val input = type("1", "2", ".", "5")
        assertEquals("12.5", input.text)
        assertEquals("12.", input.deleteLast().text)
        assertEquals("12", input.deleteLast().deleteLast().text)
        assertEquals("1", input.deleteLast().deleteLast().deleteLast().text)
        assertEquals("", input.deleteLast().deleteLast().deleteLast().deleteLast().text)
        assertEquals("0.00", AmountInput().displayText)
    }

    @Test
    fun `空输入与零金额分别给出可理解提示`() {
        val empty = AmountInput().validate()
        assertEquals(ValidationCode.AMOUNT_EMPTY, (empty as AmountValidation.Invalid).code)

        val zero = type("0").validate()
        assertEquals(ValidationCode.AMOUNT_ZERO, (zero as AmountValidation.Invalid).code)
        assertEquals("请输入大于 0 的金额", zero.message)

        val dotZero = type("0", ".").validate()
        assertEquals(ValidationCode.AMOUNT_ZERO, (dotZero as AmountValidation.Invalid).code)
    }

    @Test
    fun `非数字与多小数点分别给不同提示`() {
        assertEquals(
            ValidationCode.AMOUNT_MULTIPLE_DECIMAL_POINTS,
            (AmountInput("1.2.3").validate() as AmountValidation.Invalid).code,
        )
        assertEquals(
            ValidationCode.AMOUNT_NOT_A_NUMBER,
            (AmountInput("1a").validate() as AmountValidation.Invalid).code,
        )
        assertEquals(
            ValidationCode.AMOUNT_NEGATIVE,
            (AmountInput("-1").validate() as AmountValidation.Invalid).code,
        )
        assertEquals(
            ValidationCode.AMOUNT_TOO_MANY_DECIMALS,
            (AmountInput("1.234").validate() as AmountValidation.Invalid).code,
        )
        assertEquals(
            ValidationCode.AMOUNT_OUT_OF_RANGE,
            (AmountInput("99999999.99").validate() as AmountValidation.Invalid).code,
        )
        assertEquals("金额不能超过 9,999,999.99 元", (AmountInput("99999999.99").validate() as AmountValidation.Invalid).message)
    }

    @Test
    fun `解析失败一律返回 null 而不是抛异常`() {
        assertNull(AmountInput.parseCents(""))
        assertNull(AmountInput.parseCents("abc"))
        assertNull(AmountInput.parseCents("1.2.3"))
        assertNull(AmountInput.parseCents("1.234"))
        assertNull(AmountInput.parseCents("-5"))
        assertNull(AmountInput.parseCents("99999999"))
        assertNull(AmountInput.parseCents("99999999999999999999999"))
    }

    // ── 编辑模式回填 ──

    @Test
    fun `整数分回填为编辑字符串`() {
        assertEquals("12.50", AmountInput.fromCents(1250).text)
        assertEquals("12", AmountInput.fromCents(1200).text)
        assertEquals("0.01", AmountInput.fromCents(1).text)
        assertEquals("0.10", AmountInput.fromCents(10).text)
        assertEquals("9999999.99", AmountInput.fromCents(Limits.MAX_TRANSACTION_CENT).text)
        assertEquals("", AmountInput.fromCents(0).text)
    }

    @Test
    fun `整数分累加不产生浮点误差`() {
        val ten = AmountInput.parseCents("0.10")!!
        val twenty = AmountInput.parseCents("0.20")!!
        assertEquals(30L, ten + twenty)
        assertEquals("0.30", com.blueledger.app.core.designsystem.LedgerMoney.format(ten + twenty))
    }
}
