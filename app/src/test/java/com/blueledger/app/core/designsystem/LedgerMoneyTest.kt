package com.blueledger.app.core.designsystem

import com.blueledger.app.core.model.CategoryIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公共组件的纯逻辑单测：金额展示格式化与分类图标兜底。
 *
 * 注意：金额的**业务**口径归 A1 的 core/money；这里只验证设计系统的展示层，
 * 且所有输入输出都是 Long 整数分，不经过任何浮点。
 */
class LedgerMoneyTest {

    @Test
    fun `统一两位小数`() {
        assertEquals("0.00", LedgerMoney.format(0L))
        assertEquals("0.01", LedgerMoney.format(1L))
        assertEquals("0.10", LedgerMoney.format(10L))
        assertEquals("12.50", LedgerMoney.format(1250L))
        assertEquals("100.00", LedgerMoney.format(10000L))
    }

    @Test
    fun `千分位只属于展示`() {
        assertEquals("1,000.00", LedgerMoney.format(100_000L))
        assertEquals("12,800.00", LedgerMoney.format(1_280_000L))
        assertEquals("9,999,999.99", LedgerMoney.format(999_999_999L))
    }

    @Test
    fun `负结余带负号`() {
        assertEquals("-100.00", LedgerMoney.format(-10_000L))
        assertEquals("-0.05", LedgerMoney.format(-5L))
    }

    @Test
    fun `收支符号由类型决定而不是金额正负`() {
        assertEquals("+12.50", LedgerMoney.formatSigned(isIncome = true, cents = 1250L))
        assertEquals("−28.50", LedgerMoney.formatSigned(isIncome = false, cents = 2850L))
    }

    @Test
    fun `每个合法分类图标都有图形且未知 key 有兜底`() {
        CategoryIcons.SELECTABLE.forEach { key ->
            assertNotNull("图标 $key 必须有映射", LedgerIcons.category(key))
        }
        assertNotNull(LedgerIcons.category("不存在的图标"))
        assertEquals(LedgerIcons.category(CategoryIcons.FALLBACK), LedgerIcons.category("不存在的图标"))
        assertTrue(LedgerIcons.selectableKeys.containsAll(CategoryIcons.SELECTABLE))
    }

    @Test
    fun `账户类型标签完整`() {
        assertEquals("现金", LedgerIcons.accountKindLabel(com.blueledger.app.core.model.AccountKind.CASH))
        assertEquals("储蓄卡", LedgerIcons.accountKindLabel(com.blueledger.app.core.model.AccountKind.BANK_CARD))
        assertEquals("虚拟账户", LedgerIcons.accountKindLabel(com.blueledger.app.core.model.AccountKind.E_WALLET))
        assertEquals("自定义资产", LedgerIcons.accountKindLabel(com.blueledger.app.core.model.AccountKind.OTHER))
    }
}
