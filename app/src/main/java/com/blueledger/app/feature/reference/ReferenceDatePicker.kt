package com.blueledger.app.feature.reference

import android.os.Build
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.*
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

internal fun adjustedDate(date: LocalDate, today: LocalDate, year: Int = date.year,
    month: Int = date.monthValue, day: Int = date.dayOfMonth): LocalDate {
    val selectedMonth = YearMonth.of(year.coerceAtMost(today.year), month)
    return selectedMonth.atDay(day.coerceIn(1, selectedMonth.lengthOfMonth())).coerceAtMost(today)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceDatePicker(initialDate: LocalDate, today: LocalDate,
    onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    var value by rememberSaveable(initialDate, today) { mutableStateOf(initialDate.coerceAtMost(today).toString()) }
    val date = LocalDate.parse(value)
    ReferencePickerSheet("选择日期", "date_picker", "date_dismiss", "date_confirm", onDismiss, { onConfirm(date) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 38.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReferenceWheelColumn(minOf(1970, initialDate.year)..today.year, date.year, "年份", "date_year", Modifier.weight(1f)) {
                value = adjustedDate(date, today, year = it).toString()
            }
            ReferenceWheelColumn(1..(if (date.year == today.year) today.monthValue else 12), date.monthValue, "月份", "date_month", Modifier.weight(1f)) {
                value = adjustedDate(date, today, month = it).toString()
            }
            val lastDay = if (YearMonth.from(date) == YearMonth.from(today)) today.dayOfMonth else YearMonth.from(date).lengthOfMonth()
            ReferenceWheelColumn(1..lastDay, date.dayOfMonth, "日期", "date_day", Modifier.weight(1f), padded = true) {
                value = adjustedDate(date, today, day = it).toString()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferencePickerSheet(title: String, tag: String, cancelTag: String, confirmTag: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = T.Surface,
        shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = null) {
        Column(Modifier.testTag(tag).background(T.Surface)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag(cancelTag)) { Text("取消", color = T.TextPrimary, fontSize = 16.sp) }
                Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, color = T.TextPrimary, fontSize = 17.sp)
                TextButton(onClick = onConfirm, modifier = Modifier.testTag(confirmTag)) { Text("确定", color = T.TextPrimary, fontSize = 16.sp) }
            }
            HorizontalDivider(thickness = .5.dp, color = T.Border)
            Box(Modifier.padding(top = 12.dp, bottom = 8.dp)) { content() }
        }
    }
}

@Composable
internal fun ReferenceWheelColumn(range: IntRange, value: Int, label: String, tag: String,
    modifier: Modifier = Modifier, padded: Boolean = false, onChange: (Int) -> Unit) {
    val textPixels = with(LocalDensity.current) { 18.sp.toPx() }
    AndroidView(factory = { context -> NumberPicker(ContextThemeWrapper(context, android.R.style.Theme_Material_Light)).apply {
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        wrapSelectorWheel = false
    } }, modifier = modifier.height(180.dp).testTag(tag).semantics {
        contentDescription = "$label，$value"
        stateDescription = value.toString()
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), (range.last - range.first - 1).coerceAtLeast(0))
        setProgress { position -> onChange(position.roundToInt().coerceIn(range)); true }
        customActions = listOf(
            CustomAccessibilityAction("上一个$label") { if (value > range.first) { onChange(value - 1); true } else false },
            CustomAccessibilityAction("下一个$label") { if (value < range.last) { onChange(value + 1); true } else false },
        )
    }, update = { picker ->
        if (picker.minValue != range.first || picker.maxValue != range.last) {
            picker.displayedValues = null
            picker.minValue = range.first
            picker.maxValue = range.last
        }
        picker.displayedValues = range.map { if (padded) it.toString().padStart(2, '0') else it.toString() }.toTypedArray()
        picker.value = value.coerceIn(range)
        picker.wrapSelectorWheel = false
        picker.contentDescription = "$label，${picker.value}"
        if (Build.VERSION.SDK_INT >= 29) {
            picker.textSize = textPixels
            picker.textColor = T.TextPrimary.toArgb()
            picker.selectionDividerHeight = 1
        }
        picker.setOnValueChangedListener { _, _, selected -> onChange(selected) }
    })
}
