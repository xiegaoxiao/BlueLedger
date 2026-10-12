package com.blueledger.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import kotlin.math.roundToInt

/** Null is an unknown balance, never zero. All callers share selection, axes and privacy behavior. */
@Composable
fun LedgerLineChart(
    values: List<Long?>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    futureFrom: Int = values.size,
    height: Dp = T.ReferenceChartHeight,
    description: String = "收支趋势",
    chartTag: String = "reference_line_chart",
    formatAmount: (Long) -> String = { com.blueledger.app.core.money.Money.format(it) },
    showAverage: Boolean = true,
    compact: Boolean = false,
    empty: Boolean = false,
    maxVisibleLabels: Int = 7,
) {
    require(values.size == labels.size) { "Every chart point needs a label" }
    var selected by remember(values, labels) { mutableIntStateOf(-1) }
    val hidden = LocalHideAmounts.current
    val known = values.filterNotNull()
    val max = maxOf(0L, known.maxOrNull() ?: 0L)
    val min = minOf(0L, known.minOrNull() ?: 0L)
    val axisLabels = remember(labels, maxVisibleLabels) {
        if (labels.size <= maxVisibleLabels.coerceAtLeast(2)) labels.indices.toList()
        else (0..5).map { (it * (labels.size - 1) / 5f).roundToInt() }.distinct()
    }
    Column(modifier.fillMaxWidth().then(if (compact) Modifier.height(height) else Modifier)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selected in values.indices && values[selected] != null) "${labels[selected]}  ${if (hidden) "••••" else formatAmount(values[selected]!!)}" else "", fontSize = 12.sp, color = T.TextSecondary)
            Text(if (empty) "" else if (hidden) "••••" else formatAmount(max), fontSize = 12.sp, color = T.TextSecondary)
        }
        Canvas(Modifier.fillMaxWidth().then(if (compact) Modifier.weight(1f) else Modifier.height(height)).testTag(chartTag)
            .semantics { contentDescription = "$description，${known.size} 个时间点" }
            .pointerInput(values) { detectTapGestures { point ->
                if (values.isNotEmpty()) selected = ((point.x / size.width) * (values.size - 1)).roundToInt().coerceIn(values.indices)
            } }) {
            val bottom = size.height - (if (compact) 4.dp else 12.dp).toPx(); val top = 14.dp.toPx()
            val inset = if (compact) 5.dp.toPx() else 0f
            fun x(index: Int) = if (values.size == 1) size.width / 2 else inset + (size.width - 2 * inset) * index / (values.size - 1)
            fun y(value: Double) = bottom - ((value - min) / (max.toDouble() - min).coerceAtLeast(1.0) * (bottom - top)).toFloat()
            if (!empty) drawLine(T.Border, Offset(0f, top), Offset(size.width, top), .5.dp.toPx())
            drawLine(if (empty) T.TextSecondary else T.Border, Offset(inset, bottom), Offset(size.width - inset, bottom), .5.dp.toPx())
            if (!empty && known.isNotEmpty() && showAverage) drawLine(T.TextSecondary.copy(alpha = .6f), Offset(0f, y(known.average())), Offset(size.width, y(known.average())), .8.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 11f)))
            val path = Path(); var connected = false
            values.forEachIndexed { i, value ->
                if (value == null) connected = false else {
                    val point = Offset(x(i), y(value.toDouble()))
                    if (connected) path.lineTo(point.x, point.y) else path.moveTo(point.x, point.y)
                    connected = true
                }
            }
            if (!empty) drawPath(path, T.Primary, style = Stroke(1.5.dp.toPx()))
            values.forEachIndexed { i, value -> if (value != null) {
                val point = Offset(x(i), y(value.toDouble()))
                drawCircle(if (empty || i >= futureFrom) Color.White else T.Primary, if (i == selected) 5.dp.toPx() else 3.dp.toPx(), point)
                if (empty || i >= futureFrom) drawCircle(if (empty) T.TextSecondary else T.Primary, 3.dp.toPx(), point, style = Stroke(.5.dp.toPx()))
            } }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            axisLabels.forEach { i -> Text(labels[i], fontSize = if (compact) 10.sp else 12.sp, lineHeight = if (compact) 14.sp else 18.sp, color = T.TextSecondary) }
        }
    }
}
