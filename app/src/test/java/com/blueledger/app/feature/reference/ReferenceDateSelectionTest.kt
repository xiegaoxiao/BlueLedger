package com.blueledger.app.feature.reference

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReferenceDateSelectionTest {
    private val today = LocalDate.of(2026, 10, 8)
    @Test fun switchingFromJanuary31ToFebruaryUsesLastValidDay() {
        assertEquals(LocalDate.of(2026, 2, 28), adjustedDate(LocalDate.of(2026, 1, 31), today, month = 2))
    }
    @Test fun leapYearRetainsFebruary29() {
        assertEquals(LocalDate.of(2024, 2, 29), adjustedDate(LocalDate.of(2024, 1, 31), today, month = 2))
    }
    @Test fun leavingLeapYearClampsDayTo28() {
        assertEquals(LocalDate.of(2025, 2, 28), adjustedDate(LocalDate.of(2024, 2, 29), today, year = 2025))
    }
    @Test fun selectingAFutureMonthKeepsTheExistingNoFutureDateRule() {
        assertEquals(today, adjustedDate(LocalDate.of(2026, 9, 30), today, month = 12))
    }
}
