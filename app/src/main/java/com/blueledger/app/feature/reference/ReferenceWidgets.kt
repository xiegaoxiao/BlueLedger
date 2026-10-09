package com.blueledger.app.feature.reference

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
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
internal fun ReferenceTitle(title: String, onBack: (() -> Unit)? = null, blue: Boolean = false, brand: Boolean = false, action: @Composable () -> Unit = {}) {
    val contentColor = if (brand) T.OnBrand else T.TextPrimary
    Box(Modifier.fillMaxWidth().heightIn(min = if (brand) T.BrandTitleHeight else 52.dp).background(if (brand) T.Primary else if (blue) T.PrimarySoft else T.Surface)) {
        Text(title, fontSize = 19.sp, color = contentColor, modifier = Modifier.align(Alignment.Center))
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).testTag("btn_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = contentColor)
        }
        Box(Modifier.align(Alignment.CenterEnd)) { action() }
    }
}

@Composable
internal fun <V> ReferenceTabs(options: List<V>, selected: V, label: (V) -> String, onSelect: (V) -> Unit, tag: (V) -> String = { "" }, onBrand: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        options.forEach { value ->
            Column(Modifier.weight(1f).clickable { onSelect(value) }.testTag(tag(value)), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label(value), fontSize = 16.sp, color = if (onBrand) T.OnBrand else T.TextPrimary, fontWeight = if (value == selected) FontWeight.Medium else FontWeight.Normal,
                    modifier = Modifier.padding(vertical = 12.dp))
                Box(Modifier.width(40.dp).height(2.dp).background(if (value != selected) Color.Transparent else if (onBrand) T.OnBrand else T.TextPrimary))
            }
        }
    }
}

/** 图表页品牌头部的反色分段框：白底蓝字选中段，半透明白底与白字未选中段。 */
@Composable
internal fun <V> ReferenceSegmentedControl(options: List<V>, selected: V, label: (V) -> String, onSelect: (V) -> Unit, tag: (V) -> String = { "" }) {
    val shape = RoundedCornerShape(T.RadiusSegment)
    Row(Modifier.fillMaxWidth().height(T.SegmentHeight).clip(shape).border(1.dp, T.OnBrandOutline, shape)) {
        options.forEach { value ->
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (value == selected) T.Surface else T.OnBrandSubtle)
                    .selectable(value == selected, role = Role.Tab, onClick = { onSelect(value) })
                    .testTag(tag(value)),
                contentAlignment = Alignment.Center,
            ) {
                Text(label(value), fontSize = T.Body, color = if (value == selected) T.Primary else T.OnBrand)
            }
        }
    }
}

/**
 * 与参考应用一致的金额写法：整数分 → 去千分位、去掉多余小数零（`1250` → `12.5`，`2600` → `26`）。
 * 只用于展示，不参与任何计算或写入。
 */
internal fun referenceAmountText(cent: Long): String =
    Money.format(cent).replace(",", "").trimEnd('0').trimEnd('.')

@Composable
internal fun ReferenceMoney(cent: Long, modifier: Modifier = Modifier, large: Boolean = false, prefix: String = "") {
    Text(if (LocalHideAmounts.current) "••••" else prefix + Money.format(cent), modifier = modifier,
        color = T.TextPrimary, fontSize = if (large) 26.sp else 16.sp, maxLines = 1)
}

/** A drawn triangle keeps date selectors independent of the font's chevron glyph. */
@Composable
internal fun ReferenceDropdownArrow(modifier: Modifier = Modifier, color: Color = T.TextPrimary) {
    Canvas(modifier.size(width = 11.dp, height = 7.dp)) {
        drawPath(Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2f, size.height); close()
        }, color)
    }
}

@Composable
internal fun ReferenceRow(title: String, value: String = "", tag: String = "", subtitle: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).background(T.Surface).clickable(enabled = enabled, onClick = onClick)
        .testTag(tag).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (enabled) T.TextPrimary else T.TextSecondary)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
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
    val secondary = T.TextSecondary
    val max = maxOf(1L, values.maxOrNull() ?: 0L)
    val min = minOf(0L, values.minOrNull() ?: 0L)
    val axisLabels = remember(labels) {
        if (labels.size <= 7) labels.indices.toList()
        else {
            val step = (labels.size - 1) / 5f
            (0..5).map { (it * step).roundToInt() }.distinct().filter { it <= labels.lastIndex }
        }
    }
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (selected in values.indices) labels.getOrElse(selected) { "" } + "  " + if (hidden) "••••" else referenceAmountText(values[selected]) else "",
                fontSize = 12.sp, color = secondary)
            Text(if (hidden) "••••" else referenceAmountText(max), fontSize = 12.sp, color = secondary)
        }
        Canvas(Modifier.fillMaxWidth().height(T.ReferenceChartHeight).testTag("reference_line_chart")
            .semantics { contentDescription = "收支趋势，${values.size} 个时间点" }
            .pointerInput(values) { detectTapGestures { point ->
                if (values.isNotEmpty()) selected = ((point.x / size.width) * (values.size - 1)).roundToInt().coerceIn(values.indices)
            } }) {
            val bottom = size.height - 12.dp.toPx()
            val top = 14.dp.toPx()
            fun yOf(value: Double) = bottom - (((value - min) / (max - min)) * (bottom - top)).toFloat()
            drawLine(border, Offset(0f, top), Offset(size.width, top), .5.dp.toPx())
            drawLine(border, Offset(0f, bottom), Offset(size.width, bottom), .5.dp.toPx())
            if (values.isNotEmpty()) {
                val average = values.average()
                drawLine(secondary.copy(alpha = .6f), Offset(0f, yOf(average)), Offset(size.width, yOf(average)), .8.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 11f)))
                val points = values.mapIndexed { i, value ->
                    Offset(if (values.size == 1) size.width / 2 else size.width * i / (values.size - 1), yOf(value.toDouble()))
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
            axisLabels.forEach { i -> Text(labels.getOrElse(i) { "" }, color = secondary, fontSize = 12.sp) }
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
