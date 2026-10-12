package com.blueledger.app.feature.reference

import com.blueledger.app.core.designsystem.LedgerPickerSheet
import com.blueledger.app.core.designsystem.LedgerWheelColumn
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth

internal fun adjustedDate(date: LocalDate, today: LocalDate, year: Int = date.year,
    month: Int = date.monthValue, day: Int = date.dayOfMonth): LocalDate {
    val selectedMonth = YearMonth.of(year.coerceAtMost(today.year), month)
    return selectedMonth.atDay(day.coerceIn(1, selectedMonth.lengthOfMonth())).coerceAtMost(today)
}

@Composable
internal fun ReferenceDatePicker(initialDate: LocalDate, today: LocalDate,
    onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    var value by rememberSaveable(initialDate, today) { mutableStateOf(initialDate.coerceAtMost(today).toString()) }
    val date = LocalDate.parse(value)
    LedgerPickerSheet("选择日期", "date_picker", "date_dismiss", "date_confirm", onDismiss, { onConfirm(date) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 38.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerWheelColumn(minOf(1970, initialDate.year)..today.year, date.year, "年份", "date_year", Modifier.weight(1f)) {
                value = adjustedDate(date, today, year = it).toString()
            }
            LedgerWheelColumn(1..(if (date.year == today.year) today.monthValue else 12), date.monthValue, "月份", "date_month", Modifier.weight(1f)) {
                value = adjustedDate(date, today, month = it).toString()
            }
            val lastDay = if (YearMonth.from(date) == YearMonth.from(today)) today.dayOfMonth else YearMonth.from(date).lengthOfMonth()
            LedgerWheelColumn(1..lastDay, date.dayOfMonth, "日期", "date_day", Modifier.weight(1f), padded = true) {
                value = adjustedDate(date, today, day = it).toString()
            }
        }
    }
}
