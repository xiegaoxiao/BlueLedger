package com.blueledger.app.core.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts

data class LedgerChartSlice(val label: String, val amountCent: Long, val color: Color)

/** Display-only ring and amount summary. The caller supplies all calculations and labels. */
@Composable
fun LedgerProgressSummary(
    slices: List<LedgerChartSlice>, centerText: String, primaryLabel: String, primaryCent: Long?,
    emptyValue: String, secondary: List<LedgerSummaryAmount>, modifier: Modifier = Modifier,
    primaryTag: String? = null, primaryColor: Color = T.TextPrimary,
) {
    val hidden = LocalHideAmounts.current
    val ring: @Composable () -> Unit = {
        // Do not expose monetary proportions in the chart when amounts are hidden.
        LedgerDonutChart(if (hidden) emptyList() else slices, diameter = 120.dp, thickness = 12.dp, inset = 12.dp) {
            Text(if (hidden && primaryCent != null) "••••" else centerText,
                Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall, color = T.TextPrimary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
    val amounts: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(T.SpaceS)) {
            Text(primaryLabel, style = LedgerTextStyles.body, color = T.TextSecondary)
            if (primaryCent == null) Text(emptyValue, style = LedgerTextStyles.largeAmount, color = T.TextPrimary)
            else LedgerFittedAmountText(primaryCent, large = true, color = primaryColor,
                modifier = if (primaryTag != null) Modifier.testTag(primaryTag) else Modifier)
            secondary.forEach { amount ->
                Column(Modifier.padding(top = T.SpaceS), verticalArrangement = Arrangement.spacedBy(T.SpaceXs)) {
                    Text(amount.label, style = LedgerTextStyles.caption, color = T.TextSecondary)
                    LedgerFittedAmountText(amount.cent, modifier = if (amount.tag != null) Modifier.testTag(amount.tag) else Modifier)
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 280.dp && LocalDensity.current.fontScale <= 1.4f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.SpaceL)) {
                ring(); Box(Modifier.weight(1f)) { amounts() }
            }
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(T.SpaceL)) { ring(); amounts() }
    }
}

@Composable
fun <V> LedgerSegmentedControl(options: List<V>, selected: V, label: (V) -> String, onSelect: (V) -> Unit, modifier: Modifier = Modifier, onBrand: Boolean = false, tag: (V) -> String = { "" }, compactHeight: Dp? = null, textSizeSp: Float = 14f, shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(T.RadiusSegment)) {
    Row(modifier.fillMaxWidth().clip(shape).border(1.dp, if (onBrand) T.OnBrandOutline else T.TextPrimary, shape)) {
        options.forEach { value ->
            val active = value == selected
            Box(Modifier.weight(1f).then(if (compactHeight != null) Modifier.height(compactHeight) else Modifier.heightIn(min = T.MinTouchTarget))
                .background(if (onBrand) if (active) T.Surface else T.OnBrandSubtle else if (active) T.TextPrimary else T.Surface)
                .selectable(active, role = Role.Tab, onClick = { onSelect(value) }).testTag(tag(value)).padding(horizontal = T.SpaceXs, vertical = if (compactHeight != null) 0.dp else T.SpaceS), contentAlignment = Alignment.Center) {
                Text(label(value), fontSize = textSizeSp.sp, color = if (onBrand) if (active) T.Primary else T.OnBrand else if (active) T.OnBrand else T.TextPrimary)
            }
        }
    }
}

@Composable
fun LedgerDonutChart(slices: List<LedgerChartSlice>, modifier: Modifier = Modifier, diameter: Dp = 156.dp, thickness: Dp = 22.dp, inset: Dp = 16.dp, center: @Composable () -> Unit = {}) {
    val total = slices.sumOf { it.amountCent.coerceAtLeast(0L) }
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(inset)) {
            if (total == 0L) drawCircle(Color(0xFFE2E6EA), style = Stroke(thickness.toPx())) else {
                var start = -90f
                slices.forEach { slice ->
                    val sweep = (slice.amountCent.coerceAtLeast(0L).toDouble() / total * 360).toFloat()
                    drawArc(slice.color, start, sweep, false, style = Stroke(thickness.toPx())); start += sweep
                }
            }
        }
        center()
    }
}

@Composable
fun LedgerBarComparisonRow(label: String, cent: Long, largestCent: Long, color: Color, modifier: Modifier = Modifier) {
    val share = (cent.toDouble() / largestCent.coerceAtLeast(1L)).toFloat().coerceIn(0f, 1f)
    if (LocalDensity.current.fontScale > 1.4f) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(T.SpaceXs)) {
            Text(label, color = T.TextSecondary, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { share }, modifier = Modifier.fillMaxWidth().height(10.dp), color = color, trackColor = T.Background)
        }
    } else Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(64.dp), fontSize = 12.sp, color = T.TextSecondary)
        LinearProgressIndicator(progress = { share }, modifier = Modifier.weight(1f).height(10.dp), color = color, trackColor = T.Background)
    }
}

@Composable
fun LedgerCompositionChart(slices: List<LedgerChartSlice>, totalCent: Long, totalLabel: String, modifier: Modifier = Modifier, amountTag: String? = null) {
    val sum = slices.sumOf { it.amountCent }
    val ring: @Composable () -> Unit = {
        LedgerDonutChart(slices) {
            Column(Modifier.width(108.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(totalLabel, color = Color(0xFF42526B), style = MaterialTheme.typography.bodySmall)
                LedgerFittedAmountText(totalCent, modifier = if (amountTag != null) Modifier.testTag(amountTag) else Modifier, alignCenter = true)
            }
        }
    }
    val legend: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(T.SpaceS)) {
            if (slices.isEmpty()) Text("暂无数据", color = Color(0xFF42526B))
            slices.forEach { slice ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.SpaceS)) {
                    Box(Modifier.size(10.dp).background(slice.color, RoundedCornerShape(2.dp)))
                    Column(Modifier.weight(1f)) {
                        Text(slice.label, color = T.TextPrimary, style = MaterialTheme.typography.bodyMedium)
                        LedgerFittedAmountText(slice.amountCent)
                    }
                    Text(if (LocalHideAmounts.current) "••" else "${kotlin.math.round(slice.amountCent.toDouble() / sum.coerceAtLeast(1L) * 1000) / 10}%", color = T.TextPrimary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth >= 300.dp && LocalDensity.current.fontScale <= 1.4f) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.SpaceM)) { ring(); Box(Modifier.weight(1f)) { legend() } }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(T.SpaceM), horizontalAlignment = Alignment.CenterHorizontally) { ring(); Box(Modifier.fillMaxWidth()) { legend() } }
        }
    }
}

/** Icon slots allow categories and accounts to share the same readable ranking layout. */
@Composable
fun LedgerRankingRow(name: String, amountCent: Long, share: Double, modifier: Modifier = Modifier, subtitle: String = "", amountTag: String? = null,
    color: Color = T.Primary, onClick: (() -> Unit)? = null, icon: @Composable () -> Unit) {
    val largeFont = LocalDensity.current.fontScale > 1.4f
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(T.SpaceM)) {
        icon()
        Column(Modifier.weight(1f)) {
            val amountModifier = if (amountTag != null) Modifier.testTag(amountTag) else Modifier
            if (largeFont) {
                Text(name, color = T.TextPrimary, style = MaterialTheme.typography.titleMedium)
                LedgerFittedAmountText(amountCent, modifier = amountModifier)
            } else Row(horizontalArrangement = Arrangement.spacedBy(T.SpaceS), verticalAlignment = Alignment.Top) {
                Text(name, Modifier.weight(1f), color = T.TextPrimary, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.width(108.dp)) { LedgerFittedAmountText(amountCent, modifier = amountModifier, alignEnd = true) }
            }
            val percent = if (LocalHideAmounts.current) "••" else "${kotlin.math.round(share.coerceIn(0.0, 1.0) * 1000) / 10}%"
            Text(listOf(subtitle, percent).filter { it.isNotBlank() }.joinToString(" · "), color = Color(0xFF42526B), style = MaterialTheme.typography.bodySmall)
            val progress = if (LocalHideAmounts.current) 0f else share.toFloat().coerceIn(0f, 1f)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = T.SpaceS).height(7.dp), color = color, trackColor = T.PrimarySoft)
        }
    }
}
