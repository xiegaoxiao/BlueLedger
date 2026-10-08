package com.blueledger.app.feature.entry

import com.blueledger.app.core.model.Limits
import org.junit.Assert.*
import org.junit.Test

class AmountCalculationTest {
    @Test fun additionUsesExactCents() { assertEquals(30L, AmountCalculation(10, '+').result(AmountInput("0.20"))) }
    @Test fun subtractionCanReachZero() { assertEquals(0L, AmountCalculation(1250, '-').result(AmountInput("12.50"))) }
    @Test fun negativeIsRejected() { assertNull(AmountCalculation(10, '-').result(AmountInput("0.11"))) }
    @Test fun overflowIsRejected() { assertNull(AmountCalculation(Limits.MAX_TRANSACTION_CENT, '+').result(AmountInput("0.01"))) }
    @Test fun unfinishedOperandIsRejected() { assertNull(AmountCalculation(1250, '+').result(AmountInput())) }
}
