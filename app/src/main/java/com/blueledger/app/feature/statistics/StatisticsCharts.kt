package com.blueledger.app.feature.statistics

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.TransactionType
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A4 自绘图表（**不引入第三方图表库**，见 docs/实现决策.md §7）。
 *
 * 三条硬性规则（AI 提示词 §7 A4-10/11/12）：
 * 1. 触控目标覆盖**整个数据点区域**（日趋势 = 整个绘图区；年柱 = 整个月份列，列宽 ≥48dp），
 *    不是只有细柱/圆点的像素可点。
 * 2. 图表只做坐标转换；数值全部来自仓库返回值，隐藏金额时**不画数字、不出标签**，但保留图形结构。
 * 3. 总量为 0、单点、极大金额、负值都不会产生 NaN/除零/假 100% 环。
 */

/** 环图每片的原始角度（按金额比例，合计 360°）。纯函数，便于单测。 */
internal fun ringSweeps(slices: List<RingSlice>): List<Float> {
    val total = slices.sumOf { it.amountCent }
    if (total <= 0L || slices.isEmpty()) return List(slices.size) { 0f }
    val denominator = total.toFloat()
    return slices.map { (it.amountCent.toFloat() / denominator) * 360f }
}

/**
 * 分类环图。
 *
 * - [totalCent] ≤ 0 或 [slices] 为空：只画一圈空心轨道 + [emptyText]，
 *   **不绘制任何扇区、不产生百分比**。
 * - 点按环带内任意位置都会命中角度对应的切片（触控区域远大于细弧线本身）。
 * - 中心展示当前类型总额；金额隐藏时中心与语义描述都不含数值。
 */
@Composable
fun StatisticsRingChart(
    slices: List<RingSlice>,
    totalCent: Long,
    centerLabel: String,
    emptyText: String,
    hidden: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = 176.dp,
    strokeWidth: Dp = 26.dp,
    onSliceClick: ((RingSlice) -> Unit)? = null,
) {
    val hasData = totalCent > 0L && slices.any { it.amountCent > 0L }
    val sweeps = remember(slices, totalCent) { ringSweeps(slices) }
    val touchRadiusPadding = 16.dp
    val description = remember(slices, totalCent, hidden, hasData, emptyText) {
        ringDescription(slices, totalCent, hidden, hasData, emptyText)
    }
    Box(
        modifier = modifier
            .testTag(StatisticsTags.RING_CHART)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(
            modifier = Modifier
                .size(diameter)
                .pointerInput(slices, sweeps, hasData) {
                    detectTapGestures { position ->
                        if (!hasData || onSliceClick == null) return@detectTapGestures
                        val radius = min(size.width, size.height) / 2f - strokeWidth.toPx() / 2f
                        val centerX = size.width / 2f
                        val centerY = size.height / 2f
                        val dx = position.x - centerX
                        val dy = position.y - centerY
                        val distance = kotlin.math.sqrt(dx * dx + dy * dy)
                        val inner = max(0f, radius - strokeWidth.toPx() / 2f - touchRadiusPadding.toPx())
                        val outer = radius + strokeWidth.toPx() / 2f + touchRadiusPadding.toPx()
                        if (distance < inner || distance > outer) return@detectTapGestures
                        var degrees = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f
                        degrees = (degrees % 360f + 360f) % 360f
                        var start = 0f
                        slices.forEachIndexed { index, slice ->
                            val sweep = sweeps.getOrElse(index) { 0f }
                            if (sweep > 0f && degrees >= start && degrees < start + sweep) {
                                onSliceClick(slice)
                                return@detectTapGestures
                            }
                            start += sweep
                        }
                    }
                },
        ) {
            val stroke = strokeWidth.toPx()
            val radius = min(size.width, size.height) / 2f - stroke / 2f
            val topLeft = Offset((size.width / 2f) - radius, (size.height / 2f) - radius)
            val arcSize = Size(radius * 2f, radius * 2f)

            // 空心轨道：无论有无数据都存在，保证 0 数据时是「空心占位」而不是假扇区。
            drawArc(
                color = BlueLedgerTokens.Border,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )

            if (hasData) {
                val gap = if (slices.size > 1) 1.4f else 0f
                var start = -90f
                slices.forEachIndexed { index, slice ->
                    val sweep = sweeps.getOrElse(index) { 0f }
                    if (sweep > 0f) {
                        drawArc(
                            color = CategoryPalette.colorOf(slice),
                            startAngle = start + gap / 2f,
                            sweepAngle = max(0.6f, sweep - gap),
                            useCenter = false,
                            topLeft = topLeft,
                            size = arcSize,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    start += sweep
                }
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = centerLabel,
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
            Text(
                text = StatisticsText.amountText(totalCent, hidden),
                style = LedgerTextStyles.largeAmount,
                color = BlueLedgerTokens.PrimaryDeep,
                modifier = Modifier.testTag(StatisticsTags.RING_CENTER_TOTAL),
            )
            if (!hasData) {
                Text(
                    text = emptyText,
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(horizontal = BlueLedgerTokens.SpaceS)
                        .testTag(StatisticsTags.RING_EMPTY),
                )
            }
        }
    }
}

private fun ringDescription(
    slices: List<RingSlice>,
    totalCent: Long,
    hidden: Boolean,
    hasData: Boolean,
    emptyText: String,
): String {
    if (!hasData) return "$emptyText，环形图无数据"
    if (hidden) return "分类环形图，共 ${slices.size} 项，金额已隐藏"
    val detail = slices.joinToString("，") { slice ->
        "${slice.name} ${StatisticsText.money(slice.amountCent)} 元 占 ${StatisticsText.ratio(slice.ratioPermille)}"
    }
    return "分类环形图，共 ${slices.size} 项，总计 ${StatisticsText.money(totalCent)} 元：$detail"
}

/**
 * 单系列日趋势图（支出模式蓝色、收入模式青绿色）。
 *
 * - 数据点来自 `MonthAnalysis.daily`（数据层已按「当月 1 日→今天 / 历史月整月」补零），
 *   界面不重新构造日期轴。
 * - **整个绘图区都是触控目标**：点按任意横向位置都会选中最近的一天。
 * - 纵轴刻度来自 [StatisticsText.axisScale]；金额隐藏时刻度与读数都是掩码，网格线结构保留。
 */
@Composable
fun StatisticsDailyTrendChart(
    points: List<DailyAmount>,
    type: TransactionType,
    hidden: Boolean,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 168.dp,
) {
    val accent = if (type == TransactionType.INCOME) BlueLedgerTokens.Income else BlueLedgerTokens.Primary
    val maxCent = remember(points) { points.maxOfOrNull { it.amountCent } ?: 0L }
    val scale = remember(maxCent) { StatisticsText.axisScale(maxCent) }
    val typeLabel = StatisticsText.typeLabel(type)

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val narrow = maxWidth < 340.dp
        val axisWidth = if (narrow) 40.dp else 48.dp
        Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
                // 纵轴刻度：真实 Text 节点，屏幕阅读器与放大字体都能读取。
                Column(
                    modifier = Modifier
                        .width(axisWidth)
                        .height(plotHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    scale.ticks.reversed().forEach { tick ->
                        Text(
                            text = scale.label(tick, hidden),
                            style = LedgerTextStyles.caption,
                            color = BlueLedgerTokens.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f)) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(plotHeight)
                            .testTag(StatisticsTags.TREND_CHART)
                            .semantics(mergeDescendants = true) {
                                contentDescription = trendDescription(points, type, hidden, maxCent)
                            }
                            .pointerInput(points.size, points.firstOrNull()?.date, points.lastOrNull()?.date) {
                                detectTapGestures { position ->
                                    if (points.isEmpty()) return@detectTapGestures
                                    val width = size.width.toFloat().coerceAtLeast(1f)
                                    val fraction = (position.x / width).coerceIn(0f, 1f)
                                    val index = if (points.size == 1) {
                                        0
                                    } else {
                                        (fraction * (points.size - 1)).roundToInt().coerceIn(0, points.size - 1)
                                    }
                                    onSelectIndex(index)
                                }
                            },
                    ) {
                        val top = scale.topCent.coerceAtLeast(0L).toFloat()
                        val baseline = size.height
                        fun yOf(cent: Long): Float =
                            if (top <= 0f) baseline else baseline - (cent.toFloat() / top) * (baseline - 4f)

                        // 网格线（结构保留，即使金额隐藏也照画）
                        scale.ticks.forEach { tick ->
                            val y = yOf(tick)
                            drawLine(
                                color = BlueLedgerTokens.Border,
                                start = Offset(0f, y),
                                end = Offset(size.width, y),
                                strokeWidth = 1f,
                            )
                        }

                        if (points.isEmpty()) return@Canvas
                        val step = if (points.size == 1) 0f else size.width / (points.size - 1).toFloat()
                        fun xOf(index: Int): Float = if (points.size == 1) size.width / 2f else index * step

                        val linePath = Path()
                        val areaPath = Path()
                        points.forEachIndexed { index, point ->
                            val x = xOf(index)
                            val y = yOf(point.amountCent)
                            if (index == 0) {
                                linePath.moveTo(x, y)
                                areaPath.moveTo(x, baseline)
                                areaPath.lineTo(x, y)
                            } else {
                                linePath.lineTo(x, y)
                                areaPath.lineTo(x, y)
                            }
                        }
                        areaPath.lineTo(xOf(points.size - 1), baseline)
                        areaPath.close()

                        drawPath(
                            path = areaPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.02f)),
                                startY = 0f,
                                endY = baseline,
                            ),
                        )
                        drawPath(
                            path = linePath,
                            color = accent,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                        )
                        points.forEachIndexed { index, point ->
                            val x = xOf(index)
                            val y = yOf(point.amountCent)
                            val selected = index == selectedIndex
                            drawCircle(
                                color = BlueLedgerTokens.Surface,
                                radius = if (selected) 6.dp.toPx() else 4.5.dp.toPx(),
                                center = Offset(x, y),
                            )
                            drawCircle(
                                color = accent,
                                radius = if (selected) 4.5.dp.toPx() else 3.dp.toPx(),
                                center = Offset(x, y),
                            )
                        }
                    }
                }
            }

            // 横轴日期：最多 6 个标签，首尾必含。
            if (points.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(start = axisWidth + BlueLedgerTokens.SpaceXs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    dayAxisLabels(points).forEach { (_, label) ->
                        Text(
                            text = label,
                            style = LedgerTextStyles.caption,
                            color = BlueLedgerTokens.TextSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Text(
                text = "单位：${if (hidden) StatisticsText.MASK else scale.unitLabel}",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.testTag("${StatisticsTags.TREND_CHART}_unit"),
            )
            Text(
                text = if (points.isEmpty()) {
                    "该月尚未开始，暂无${typeLabel}趋势"
                } else {
                    "共 ${points.size} 天，点按图表任意位置可查看当天${typeLabel}金额"
                },
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.testTag(StatisticsTags.TREND_HINT),
            )
        }
    }
}

/** 横轴标签（最多 6 个、含首尾）；返回 (index, label)。 */
private fun dayAxisLabels(points: List<DailyAmount>): List<Pair<Int, String>> {
    if (points.isEmpty()) return emptyList()
    val maxLabels = 6
    val count = points.size
    val step: Float = if (count <= maxLabels) 1f else (count - 1).toFloat() / (maxLabels - 1).toFloat()
    val indices = if (count <= maxLabels) {
        points.indices.toList()
    } else {
        (0 until maxLabels).map { (it * step).roundToInt().coerceIn(0, count - 1) }.distinct()
    }
    return indices.map { "${points[it].date.dayOfMonth}日" }.mapIndexed { position, label -> indices[position] to label }
}

private fun trendDescription(
    points: List<DailyAmount>,
    type: TransactionType,
    hidden: Boolean,
    maxCent: Long,
): String {
    val typeLabel = StatisticsText.typeLabel(type)
    if (points.isEmpty()) return "每日${typeLabel}趋势图，该月尚未开始，暂无数据"
    val first = points.first().date
    val last = points.last().date
    val range = "${StatisticsText.day(first)}至${StatisticsText.day(last)}"
    if (hidden) return "每日${typeLabel}趋势图，$range，金额已隐藏"
    val peak = points.maxByOrNull { it.amountCent }
    val peakText = if (peak == null || maxCent <= 0L) {
        "全部为 0.00 元"
    } else {
        "最高 ${StatisticsText.day(peak.date)} ${StatisticsText.money(peak.amountCent)} 元"
    }
    return "每日${typeLabel}趋势图，$range，$peakText"
}

/**
 * 年度 12 个月双系列柱状图（支出蓝、收入青绿）。
 *
 * - 每个月份列宽 **≥48dp**（不足时整体横向滚动），整列（含月份标签）都是触控目标。
 * - `reached = false` 的月份画虚线占位并标注「未到」，不可点击；
 *   已到但无记录的月份显示 0（不画柱，走 0 基线）。
 * - 纵轴单位可缩写为「万元」，但月份明细与摘要保留准确金额。
 */
@Composable
fun StatisticsYearBarChart(
    columns: List<YearMonthColumn>,
    hidden: Boolean,
    selectedMonth: Int?,
    onSelectMonth: (Int) -> Unit,
    modifier: Modifier = Modifier,
    plotHeight: Dp = 168.dp,
) {
    val maxCent = remember(columns) { columns.maxOfOrNull { it.maxCent } ?: 0L }
    val scale = remember(maxCent) { StatisticsText.axisScale(maxCent) }
    val scrollState = rememberScrollState()
    val axisWidth = 44.dp

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val available = (maxWidth - axisWidth - BlueLedgerTokens.SpaceXs).coerceAtLeast(BlueLedgerTokens.MinTouchTarget)
        val columnWidth = maxOf(BlueLedgerTokens.MinTouchTarget, available / 12)
        val scrollable = columnWidth * 12 > available

        Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
            Row(horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
                Column(
                    modifier = Modifier
                        .width(axisWidth)
                        .height(plotHeight),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End,
                ) {
                    scale.ticks.reversed().forEach { tick ->
                        Text(
                            text = scale.label(tick, hidden),
                            style = LedgerTextStyles.caption,
                            color = BlueLedgerTokens.TextSecondary,
                            maxLines = 1,
                        )
                    }
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(scrollState)
                        .testTag(StatisticsTags.YEAR_CHART),
                ) {
                    columns.forEach { column ->
                        YearMonthColumnCell(
                            column = column,
                            width = columnWidth,
                            plotHeight = plotHeight,
                            hidden = hidden,
                            topCent = scale.topCent,
                            selected = column.month == selectedMonth,
                            onSelect = { onSelectMonth(column.month) },
                        )
                    }
                }
            }
            Text(
                text = "单位：${if (hidden) StatisticsText.MASK else scale.unitLabel}" +
                    if (scrollable) "・左右滑动查看 1—12 月" else "",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.testTag(StatisticsTags.YEAR_CHART_HINT),
            )
        }
    }
}

@Composable
private fun YearMonthColumnCell(
    column: YearMonthColumn,
    width: Dp,
    plotHeight: Dp,
    hidden: Boolean,
    topCent: Long,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val description = yearColumnDescription(column, hidden)
    Column(
        modifier = Modifier
            .width(width)
            .testTag(StatisticsTags.yearColumn(column.month))
            .then(
                if (column.drillable) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = if (hidden) "${column.month} 月，金额已隐藏" else "${column.month} 月账单",
                        onClick = onSelect,
                    )
                } else {
                    Modifier
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(plotHeight),
        ) {
            val baseline = size.height
            // 网格线
            val ticks = StatisticsText.axisScale(topCent).ticks
            val top = topCent.coerceAtLeast(0L).toFloat()
            fun yOf(cent: Long): Float =
                if (top <= 0f) baseline else baseline - (cent.toFloat() / top) * (baseline - 4f)
            ticks.forEach { tick ->
                drawLine(
                    color = BlueLedgerTokens.Border,
                    start = Offset(0f, yOf(tick)),
                    end = Offset(size.width, yOf(tick)),
                    strokeWidth = 1f,
                )
            }

            if (selected && column.reached) {
                drawRoundRect(
                    color = BlueLedgerTokens.PrimarySoft,
                    topLeft = Offset(0f, 0f),
                    size = Size(size.width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                )
            }

            if (!column.reached) {
                // 未到月份：明确占位，不是 0。
                drawRoundRect(
                    color = BlueLedgerTokens.Border,
                    topLeft = Offset(2.dp.toPx(), 2.dp.toPx()),
                    size = Size((size.width - 4.dp.toPx()).coerceAtLeast(1f), size.height - 4.dp.toPx()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
                    ),
                )
                return@Canvas
            }

            val outerPadding = 6.dp.toPx()
            val gap = 3.dp.toPx()
            val barWidth = ((size.width - outerPadding * 2 - gap) / 2f).coerceAtLeast(2.dp.toPx())
            val expenseLeft = outerPadding
            val incomeLeft = outerPadding + barWidth + gap
            val minBar = 2.dp.toPx()

            fun barHeight(cent: Long): Float {
                if (cent <= 0L || top <= 0f) return 0f
                return max(minBar, (cent.toFloat() / top) * (baseline - 4f))
            }

            val expenseHeight = barHeight(column.expenseCent)
            if (expenseHeight > 0f) {
                drawRoundRect(
                    color = BlueLedgerTokens.Primary,
                    topLeft = Offset(expenseLeft, baseline - expenseHeight),
                    size = Size(barWidth, expenseHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2.5f),
                )
            }
            val incomeHeight = barHeight(column.incomeCent)
            if (incomeHeight > 0f) {
                drawRoundRect(
                    color = BlueLedgerTokens.Income,
                    topLeft = Offset(incomeLeft, baseline - incomeHeight),
                    size = Size(barWidth, incomeHeight),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2.5f),
                )
            }
        }

        Text(
            text = "${column.month}",
            style = LedgerTextStyles.caption,
            color = if (column.reached) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                !column.reached -> "未到"
                column.selectedLabel(hidden) != null -> column.selectedLabel(hidden)!!
                else -> " "
            },
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/** 已到月份的金额概览标签。金额隐藏时一律掩码——连「0.00」也不展示，避免任何数值泄露。 */
private fun YearMonthColumn.selectedLabel(hidden: Boolean): String? = when {
    hidden -> StatisticsText.MASK
    !hasRecords -> "0.00"
    else -> StatisticsText.money(maxCent)
}

private fun yearColumnDescription(column: YearMonthColumn, hidden: Boolean): String = when {
    !column.reached -> "${column.month} 月，未到月份，不可查看"
    hidden -> "${column.month} 月，金额已隐藏"
    !column.hasRecords -> "${column.month} 月，无记录，支出 0.00 元，收入 0.00 元"
    else -> "${column.month} 月，支出 ${StatisticsText.money(column.expenseCent)} 元，" +
        "收入 ${StatisticsText.money(column.incomeCent)} 元"
}

/** 图例圆点 + 文字（支出蓝 / 收入青绿，文字图例与颜色一致）。 */
@Composable
fun StatisticsLegendDot(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    testTag: String? = null,
) {
    Row(
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(color),
        )
        Text(
            text = label,
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            maxLines = 1,
        )
    }
}
