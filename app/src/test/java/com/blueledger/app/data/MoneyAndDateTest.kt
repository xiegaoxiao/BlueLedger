package com.blueledger.app.data

import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.money.AmountInput
import com.blueledger.app.core.money.AmountParseResult
import com.blueledger.app.core.money.Money
import com.blueledger.app.core.time.DateRanges
import com.blueledger.app.core.time.FixedClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** 金额解析/格式化/累加与日期范围的纯 JVM 单测（AI 提示词 §13「金额与业务单测」）。 */
class MoneyAndDateTest {

    private val today = LocalDate.of(2026, 10, 7)

    private fun code(raw: String): ValidationCode {
        val result = Money.parse(raw)
        assertTrue("期望 '$raw' 解析失败，实际是 $result", result is AmountParseResult.Failure)
        return (result as AmountParseResult.Failure).code
    }

    private fun cent(raw: String): Long {
        val result = Money.parse(raw)
        assertTrue("期望 '$raw' 解析成功，实际是 $result", result is AmountParseResult.Success)
        return (result as AmountParseResult.Success).amountCent
    }

    // ───────────────────── 解析 ─────────────────────

    @Test
    fun `parse_accepts_documented_amounts`() {
        assertEquals(1_200L, cent("12"))
        assertEquals(1_250L, cent("12.5"))
        assertEquals(1_250L, cent("12.50"))
        assertEquals(1L, cent("0.01"))
        assertEquals(999_999_999L, cent("9,999,999.99"))
        assertEquals(999_999_999L, cent("9999999.99"))
        assertEquals(1_200L, cent("12."))
        assertEquals(1_200L, cent(" 12 "))
        assertEquals(700L, cent("007"))
        assertEquals(1L, cent("+0.01"))
    }

    @Test
    fun `parse_rejects_invalid_inputs_with_stable_codes`() {
        assertEquals(ValidationCode.AMOUNT_EMPTY, code(""))
        assertEquals(ValidationCode.AMOUNT_EMPTY, code("   "))
        assertEquals(ValidationCode.AMOUNT_ZERO, code("0"))
        assertEquals(ValidationCode.AMOUNT_ZERO, code("0.00"))
        assertEquals(ValidationCode.AMOUNT_ZERO, code("0."))
        assertEquals(ValidationCode.AMOUNT_NEGATIVE, code("-5"))
        assertEquals(ValidationCode.AMOUNT_NEGATIVE, code("-0.01"))
        assertEquals(ValidationCode.AMOUNT_TOO_MANY_DECIMALS, code("1.234"))
        assertEquals(ValidationCode.AMOUNT_MULTIPLE_DECIMAL_POINTS, code("1.2.3"))
        assertEquals(ValidationCode.AMOUNT_NOT_A_NUMBER, code("abc"))
        assertEquals(ValidationCode.AMOUNT_NOT_A_NUMBER, code("."))
        assertEquals(ValidationCode.AMOUNT_NOT_A_NUMBER, code("1,2"))
        assertEquals(ValidationCode.AMOUNT_NOT_A_NUMBER, code("12元"))
        assertEquals(ValidationCode.AMOUNT_OUT_OF_RANGE, code("10000000"))
        assertEquals(ValidationCode.AMOUNT_OUT_OF_RANGE, code("100000000"))
        assertNull(Money.parseOrNull("abc"))
        assertNull(Money.parseOrNull("0"))
        assertEquals(1_250L, Money.parseOrNull("12.50"))
    }

    @Test
    fun `format_uses_two_decimals_and_thousand_separators`() {
        assertEquals("12.50", Money.format(1_250L))
        assertEquals("0.01", Money.format(1L))
        assertEquals("0.00", Money.format(0L))
        assertEquals("-921.20", Money.format(-92_120L))
        assertEquals("9,999,999.99", Money.format(999_999_999L))
        assertEquals("1,000.00", Money.format(100_000L))
        assertEquals("-28.50", Money.formatSigned(2_850L, TransactionType.EXPENSE))
        assertEquals("+5,000.00", Money.formatSigned(500_000L, TransactionType.INCOME))
        assertEquals("-28.50", Money.formatSigned(-2_850L, TransactionType.EXPENSE))
        assertEquals("77.0%", Money.formatBasisPoint(7_697L))
        assertEquals("76.9%", Money.formatPermille(769))
    }

    @Test
    fun `exact_sum_never_uses_floating_point`() {
        // 0.10 + 0.20 必须精确等于 0.30，不允许出现 0.30000000000000004。
        assertEquals(30L, Money.sumExact(listOf(10L, 20L)))
        val many = List(10_000) { 10L }
        assertEquals(100_000L, Money.sumExact(many))
        assertEquals(9_999_999_990_000L, Money.sumExact(List(10_000) { 999_999_999L }))
    }

    @Test
    fun `keypad_input_rules_produce_1250_for_12_50`() {
        var text = ""
        text = AmountInput.appendDigit(text, '1')
        text = AmountInput.appendDigit(text, '2')
        text = AmountInput.appendDot(text)
        text = AmountInput.appendDigit(text, '5')
        text = AmountInput.appendDigit(text, '0')
        assertEquals("12.50", text)
        assertEquals(1_250L, Money.parseOrNull(text))

        // 第二个小数点无效；第三位小数被忽略；上限内不会静默四舍五入。
        assertEquals("12.50", AmountInput.appendDot(text))
        assertEquals("12.50", AmountInput.appendDigit(text, '7'))
        assertEquals("12.5", AmountInput.backspace(text))
        assertEquals("0.", AmountInput.appendDot(""))
        assertEquals("0", AmountInput.appendDigit("", '0'))
        assertEquals("5", AmountInput.appendDigit("0", '5'))
        assertEquals("9999999", AmountInput.sanitize("99999999"))
        assertTrue(AmountInput.isSavable("12.50"))
        assertTrue(!AmountInput.isSavable("0.00"))
        assertTrue(!AmountInput.isSavable(""))
    }

    // ───────────────────── 日期范围 ─────────────────────

    @Test
    fun `month_range_follows_current_historical_and_future_rules`() {
        val current = DateRanges.monthEpochDayRange(YearMonth.of(2026, 10), today)!!
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), current.first)
        assertEquals(LocalDate.of(2026, 10, 7).toEpochDay(), current.last)

        val historical = DateRanges.monthEpochDayRange(YearMonth.of(2026, 9), today)!!
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), historical.first)
        assertEquals(LocalDate.of(2026, 9, 30).toEpochDay(), historical.last)

        assertNull(DateRanges.monthEpochDayRange(YearMonth.of(2026, 11), today))
        assertNull(DateRanges.monthEpochDayRange(YearMonth.of(2027, 1), today))
    }

    @Test
    fun `leap_year_february_has_29_days`() {
        val feb2024 = DateRanges.monthEpochDayRange(YearMonth.of(2024, 2), today)!!
        assertEquals(29, DateRanges.daysInMonth(YearMonth.of(2024, 2)))
        assertEquals(29, (feb2024.last - feb2024.first + 1).toInt())
        assertEquals(LocalDate.of(2024, 2, 29).toEpochDay(), feb2024.last)

        val feb2025 = DateRanges.monthEpochDayRange(YearMonth.of(2025, 2), today)!!
        assertEquals(28, (feb2025.last - feb2025.first + 1).toInt())
        assertEquals(LocalDate.of(2025, 2, 28).toEpochDay(), feb2025.last)

        assertEquals(29, DateRanges.daysBetween(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)).size)
    }

    @Test
    fun `year_range_and_month_average_denominator`() {
        val current = DateRanges.yearEpochDayRange(2026, today)!!
        assertEquals(LocalDate.of(2026, 1, 1).toEpochDay(), current.first)
        assertEquals(today.toEpochDay(), current.last)
        assertEquals(10, DateRanges.reachedMonthCount(2026, today))

        val historical = DateRanges.yearEpochDayRange(2025, today)!!
        assertEquals(LocalDate.of(2025, 12, 31).toEpochDay(), historical.last)
        assertEquals(12, DateRanges.reachedMonthCount(2025, today))

        assertNull(DateRanges.yearEpochDayRange(2027, today))
        assertEquals(0, DateRanges.reachedMonthCount(2027, today))

        assertTrue(DateRanges.isMonthReached(2026, 10, today))
        assertTrue(!DateRanges.isMonthReached(2026, 11, today))
        assertTrue(!DateRanges.isMonthReached(2026, 12, today))
        assertTrue(DateRanges.isMonthReached(2026, 1, today))
    }

    @Test
    fun `cross_year_boundary_dates_map_to_their_own_year`() {
        val lastDay = LocalDate.of(2025, 12, 31)
        val firstDay = LocalDate.of(2026, 1, 1)
        assertEquals(2025, DateRanges.dateOf(DateRanges.epochDay(lastDay)).year)
        assertEquals(2026, DateRanges.dateOf(DateRanges.epochDay(firstDay)).year)
        assertEquals(YearMonth.of(2025, 12), YearMonth.from(lastDay))
        assertEquals(YearMonth.of(2026, 1), YearMonth.from(firstDay))
    }

    @Test
    fun `timezone_change_does_not_rewrite_saved_dates`() {
        val clock = FixedClock()
        val savedDate = clock.today()
        assertEquals(LocalDate.of(2026, 10, 7), savedDate)

        // 每次读取当前时区，不缓存首次启动的时区。
        clock.setZone(java.time.ZoneId.of("America/New_York"))
        assertEquals(java.time.ZoneId.of("America/New_York"), clock.zoneId())

        // 已保存的发生日期是纯本地日历日期，月份/年份归属不因设备时区变化而改写。
        assertEquals(YearMonth.of(2026, 10), YearMonth.from(savedDate))
        assertEquals(LocalDate.of(2026, 10, 7).toEpochDay(), savedDate.toEpochDay())
    }
}
