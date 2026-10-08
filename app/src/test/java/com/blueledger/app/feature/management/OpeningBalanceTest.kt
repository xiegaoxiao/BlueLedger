package com.blueledger.app.feature.management

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 账户期初余额解析单测（允许正、零、负，最多两位小数，绝对值上限 9,999,999.99 元）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OpeningBalanceTest {

    private fun ok(text: String): Long {
        val parsed = OpeningBalance.parse(text)
        assertTrue("「$text」应解析成功，实际 $parsed", parsed is OpeningBalanceParse.Success)
        return (parsed as OpeningBalanceParse.Success).cent
    }

    private fun failure(text: String): String {
        val parsed = OpeningBalance.parse(text)
        assertTrue("「$text」应被拒绝，实际 $parsed", parsed is OpeningBalanceParse.Failure)
        return (parsed as OpeningBalanceParse.Failure).message
    }

    @Test
    fun `正零负与小数解析`() {
        assertEquals(100_000L, ok("1000"))
        assertEquals(100_000L, ok("1000.00"))
        assertEquals(0L, ok("0"))
        assertEquals(0L, ok(""))
        assertEquals(0L, ok("0.00"))
        assertEquals(-20_000L, ok("-200"))
        assertEquals(-20_000L, ok("-200.00"))
        assertEquals(1L, ok("0.01"))
        assertEquals(999_999_999L, ok("9999999.99"))
        assertEquals(-999_999_999L, ok("-9999999.99"))
        assertEquals(1250L, ok("12.5"))
    }

    @Test
    fun `非法输入被拒绝`() {
        assertEquals("金额最多两位小数", failure("1.234"))
        assertEquals("金额只能有一个小数点", failure("1.2.3"))
        assertEquals("金额只能包含数字、小数点和负号", failure("12a"))
        assertEquals("请输入正确的金额", failure("-"))
        assertTrue(failure("10000000").contains("不能超过"))
        assertTrue(failure("-10000000").contains("不能超过"))
    }

    @Test
    fun `格式化保留两位小数与负号`() {
        assertEquals("1,000.00", Money.format(100_000L))
        assertEquals("-200.00", Money.format(-20_000L))
        assertEquals("0.00", Money.format(0L))
        assertEquals("-200.00", OpeningBalance.format(-20_000L))
    }

    @Test
    fun `账户类型都有中文名与图标`() {
        AccountKind.entries.forEach { kind ->
            assertTrue(com.blueledger.app.core.designsystem.LedgerIcons.accountKindLabel(kind).isNotBlank())
        }
        assertEquals("现金", com.blueledger.app.core.designsystem.LedgerIcons.accountKindLabel(AccountKind.CASH))
        assertEquals("储蓄卡", com.blueledger.app.core.designsystem.LedgerIcons.accountKindLabel(AccountKind.BANK_CARD))
    }

    @Test
    fun `存储失败类型不会被当成成功`() {
        val error: LedgerError = LedgerError.Storage("磁盘写入失败", "IOException")
        assertEquals("磁盘写入失败", error.readableMessage())
        assertEquals("操作失败，请重试", LedgerError.NotFound("account", "x", message = "").readableMessage())
    }
}
