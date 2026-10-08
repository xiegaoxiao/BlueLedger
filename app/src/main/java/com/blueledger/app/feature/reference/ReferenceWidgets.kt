package com.blueledger.app.feature.reference

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.NumberPicker
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.money.Money
import java.time.YearMonth
import kotlin.math.roundToInt

@Composable
internal fun ReferenceTitle(title: String, onBack: (() -> Unit)? = null, blue: Boolean = false, action: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (blue) T.PrimarySoft else T.Surface)) {
        Text(title, fontSize = 19.sp, color = T.TextPrimary, modifier = Modifier.align(Alignment.Center))
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).testTag("btn_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = T.TextPrimary)
        }
        Box(Modifier.align(Alignment.CenterEnd)) { action() }
    }
}

@Composable
internal fun <V> ReferenceTabs(options: List<V>, selected: V, label: (V) -> String, onSelect: (V) -> Unit, tag: (V) -> String = { "" }) {
    Row(Modifier.fillMaxWidth()) {
        options.forEach { value ->
            Column(Modifier.weight(1f).clickable { onSelect(value) }.testTag(tag(value)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label(value), fontSize = 16.sp, color = T.TextPrimary, fontWeight = if (value == selected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 12.dp))
                Box(Modifier.width(40.dp).height(2.dp).background(if (value == selected) T.TextPrimary else Color.Transparent))
            }
        }
    }
}

@Composable
internal fun ReferenceMoney(cent: Long, modifier: Modifier = Modifier, large: Boolean = false, prefix: String = "") {
    Text(if (LocalHideAmounts.current) "••••" else prefix + Money.format(cent), modifier = modifier,
        color = T.TextPrimary, fontSize = if (large) 26.sp else 16.sp, maxLines = 1)
}

/** A drawn triangle keeps date selectors independent of the font's chevron glyph. */
@Composable
internal fun ReferenceDropdownArrow(modifier: Modifier = Modifier) {
    Canvas(modifier.size(width = 11.dp, height = 7.dp)) {
        drawPath(Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
        }, T.TextPrimary)
    }
}

@Composable
internal fun ReferenceRow(title: String, value: String = "", tag: String = "", onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(T.Surface).clickable(onClick = onClick)
        .testTag(tag).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, color = T.TextPrimary, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = T.TextSecondary)
        Text("›", color = T.TextSecondary, fontSize = 24.sp, modifier = Modifier.padding(start = 12.dp))
    }
    HorizontalDivider(color = T.Border.copy(alpha = .45f), thickness = .5.dp)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReferenceMonthPicker(month: YearMonth, current: YearMonth, onDismiss: () -> Unit, onConfirm: (YearMonth) -> Unit) {
    var year by remember { mutableIntStateOf(month.year) }
    var number by remember { mutableIntStateOf(month.monthValue) }
    ReferencePickerSheet("选择月份", "month_picker", "month_dismiss", "month_confirm", onDismiss,
        { onConfirm(YearMonth.of(year, number).coerceAtMost(current)) }) {
        Row(Modifier.fillMaxWidth().padding(horizontal = if (LocalDensity.current.fontScale > 1.4f) 32.dp else 90.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ReferenceWheelColumn(minOf(1970, month.year)..current.year, year, "年份", "month_year", Modifier.weight(1f)) {
                year = it
                number = number.coerceAtMost(if (year == current.year) current.monthValue else 12)
            }
            ReferenceWheelColumn(1..(if (year == current.year) current.monthValue else 12), number, "月份", "month_number", Modifier.weight(1f)) { number = it }
        }
    }
}

/** 所有点都可从整块绘图区选择，零数据也有完整时间轴；不在主线程做账本聚合。 */
@Composable
internal fun ReferenceLineChart(values: List<Long>, labels: List<String>, modifier: Modifier = Modifier, futureFrom: Int = values.size) {
    var selected by remember(values) { mutableIntStateOf(-1) }
    val hidden = LocalHideAmounts.current
    val color = T.Primary
    val border = T.Border
    val max = maxOf(1L, values.maxOrNull() ?: 0L)
    val min = minOf(0L, values.minOrNull() ?: 0L)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (hidden) "••••" else Money.format(max), fontSize = 12.sp, color = T.TextSecondary)
            Text(if (selected in values.indices) labels.getOrElse(selected) { "" } + "  " + if (hidden) "••••" else Money.format(values[selected]) else "", fontSize = 12.sp, color = T.TextSecondary)
        }
        Canvas(Modifier.fillMaxWidth().height(180.dp).testTag("reference_line_chart")
            .semantics { contentDescription = "收支趋势，${values.size} 个时间点" }
            .pointerInput(values) { detectTapGestures { point ->
                if (values.isNotEmpty()) selected = ((point.x / size.width) * (values.size - 1)).roundToInt().coerceIn(values.indices)
            } }) {
            val bottom = size.height - 12.dp.toPx()
            val top = 14.dp.toPx()
            repeat(4) { row ->
                val y = top + (bottom - top) * row / 3f
                drawLine(border, Offset(0f, y), Offset(size.width, y), .5.dp.toPx())
            }
            if (values.isNotEmpty()) {
                val points = values.mapIndexed { i, value ->
                    Offset(if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1), bottom - (((value - min).toDouble() / (max - min)) * (bottom - top)).toFloat())
                }
                val path = Path().apply { points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(path, color, style = Stroke(1.5.dp.toPx()))
                points.forEachIndexed { i, point ->
                    drawCircle(if (i >= futureFrom) Color.White else color, if (i == selected) 5.dp.toPx() else 3.dp.toPx(), point)
                    if (i >= futureFrom) drawCircle(color, 3.dp.toPx(), point, style = Stroke(1.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0, labels.size / 2, labels.lastIndex).distinct().filter { it >= 0 }.forEach { i ->
                Text(labels.getOrElse(i) { "" }, color = T.TextSecondary, fontSize = 12.sp)
            }
        }
    }
}

@Composable
internal fun ReferenceCategoryRing(categories: List<ReferenceCategory>) {
    val colors = listOf(T.Primary, Color(0xFF77A9F5), Color(0xFF8BCDC2), Color(0xFFA998DA), Color(0xFFE0BC87))
    val total = categories.sumOf { it.cent }
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(180.dp).padding(15.dp)) {
            if (total == 0L) drawArc(T.Border, 0f, 360f, false, style = Stroke(24.dp.toPx()))
            else {
                var start = -90f
                categories.forEachIndexed { index, category ->
                    val sweep = (category.cent.toDouble() / total * 360).toFloat()
                    drawArc(colors[index % colors.size], start, sweep, false, style = Stroke(24.dp.toPx()))
                    start += sweep
                }
            }
        }
        if (total == 0L) Text("暂无支出", color = T.TextSecondary) else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("支出", color = T.TextSecondary, fontSize = 12.sp); ReferenceMoney(total)
        }
    }
}
