package com.blueledger.app.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts

/** Flat summary shared by total and category budgets. All calculations belong to the page. */
@Composable
fun LedgerBudgetSummary(
    title: String, remainingCent: Long, budgetCent: Long, usedCent: Long,
    remainingBasisPoints: Long, onEdit: () -> Unit, modifier: Modifier = Modifier,
    compact: Boolean = false, iconKey: String? = null, periodLabel: String = "本月",
    tag: String = "budget", onPeriodClick: (() -> Unit)? = null,
) {
    val hidden = LocalHideAmounts.current
    val risk = remainingCent < 0
    BoxWithConstraints(modifier.fillMaxWidth().testTag("${tag}_summary")) {
    // Reference geometry is based on a 361dp viewport; preserve its proportions on small screens.
    val scale = (maxWidth.value / 361f).coerceAtMost(1.5f)
    Column(Modifier.fillMaxWidth().background(T.Surface).padding(horizontal = (15 * scale).dp)
        .padding(top = (if (compact) 16 * scale else 0f).dp, bottom = (14 * scale).dp)) {
        Row(Modifier.fillMaxWidth().height((42 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
            iconKey?.let { LedgerCategoryIcon(it, false, diameter = (20 * scale).dp); Spacer(Modifier.width((8 * scale).dp)) }
            Box(Modifier.weight(1f).fillMaxHeight().then(if (onPeriodClick != null) Modifier
                .clickable(role = Role.Button, onClick = onPeriodClick).testTag("budget_month") else Modifier), contentAlignment = Alignment.CenterStart) {
                Text(title, fontSize = ((if (compact) 12 else 14) * scale).sp, lineHeight = (20 * scale).sp, color = T.TextPrimary)
            }
            Box(Modifier.width((56 * scale).dp).fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                LedgerActionButton("编辑", onEdit, Modifier.wrapContentSize(unbounded = true), style = LedgerActionStyle.NEUTRAL, testTag = "${tag}_edit",
                    textStyle = TextStyle(fontSize = ((if (compact) 12 else 13) * scale).sp, lineHeight = (18 * scale).sp, fontWeight = FontWeight.Normal))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(((if (compact) 20 else 42) * scale).dp)) {
            val remaining = remainingBasisPoints.coerceIn(0, 10000)
            LedgerDonutChart(if (hidden) emptyList() else listOf(
                LedgerChartSlice("剩余", remaining, T.Primary),
                LedgerChartSlice("已用", 10000 - remaining, if (risk) T.Risk else T.Background),
            ), modifier = Modifier.testTag("${tag}_ring"), diameter = ((if (compact) 66 else 87) * scale).dp,
                thickness = ((if (compact) 6 else 10) * scale).dp, inset = ((if (compact) 4 else 5) * scale).dp) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("剩余", color = T.TextSecondary.copy(alpha = .7f), fontSize = ((if (compact) 9 else 12) * scale).sp, lineHeight = ((if (compact) 12 else 17) * scale).sp)
                    Text(if (hidden) "••••" else "${(remaining + 50) / 100}%", color = T.TextPrimary,
                        fontSize = ((if (compact) 11 else 15) * scale).sp, lineHeight = ((if (compact) 14 else 20) * scale).sp, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(((if (compact) 4 else 7) * scale).dp)) {
                LedgerBudgetMetric("剩余预算:", remainingCent, "${tag}_remaining", if (risk) T.Risk else T.TextPrimary, (if (compact) 12f else 18f) * scale, (if (compact) 11f else 13f) * scale)
                HorizontalDivider(thickness = .5.dp, color = T.Border.copy(alpha = .45f))
                LedgerBudgetMetric(if (compact) "预算:" else "${periodLabel}预算:", budgetCent, "${tag}_total", T.TextSecondary, (if (compact) 11f else 13f) * scale, (if (compact) 11f else 13f) * scale)
                LedgerBudgetMetric(if (compact) "支出:" else "${periodLabel}支出:", usedCent, "${tag}_used", T.TextSecondary, (if (compact) 11f else 13f) * scale, (if (compact) 11f else 13f) * scale)
            }
        }
    }
    }
}

@Composable
private fun LedgerBudgetMetric(label: String, cent: Long, tag: String, color: Color, size: Float, labelSize: Float) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = labelSize.sp, lineHeight = (labelSize * 1.25f).sp, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1.15f)) {
            LedgerFittedAmountText(cent, Modifier.testTag(tag), alignEnd = true, color = color,
                fontSizeSp = size, groupThousands = false, weight = FontWeight.Normal)
        }
    }
}

/** Neutral scroll illustration used in both budget empty states. */
@Composable
fun LedgerBudgetEmptyIllustration(scale: Float = 1f) {
    Canvas(Modifier.size((64 * scale).dp)) {
        val sx = size.width / 64f; val sy = size.height / 64f
        fun path(block: Path.() -> Unit) = Path().apply(block)
        val gray = Color(0xFFCACCCD)
        drawOval(Color(0xFFF4F4F4), topLeft = androidx.compose.ui.geometry.Offset(8*sx, 57*sy), size = androidx.compose.ui.geometry.Size(40*sx, 7*sy))
        drawPath(path { moveTo(13*sx, 44*sy); lineTo(13*sx, 11*sy); quadraticTo(13*sx, 4*sy, 21*sx, 4*sy); lineTo(53*sx, 4*sy); quadraticTo(59*sx, 4*sy, 59*sx, 11*sy); lineTo(59*sx, 17*sy); lineTo(49*sx, 17*sy) }, gray, style = Stroke(2.2f*sx))
        drawPath(path { moveTo(53*sx, 4*sy); quadraticTo(49*sx, 4*sy, 49*sx, 11*sy); lineTo(49*sx, 47*sy); quadraticTo(49*sx, 54*sy, 42*sx, 54*sy); lineTo(12*sx, 54*sy); quadraticTo(4*sx, 54*sy, 4*sx, 44*sy); lineTo(34*sx, 44*sy); quadraticTo(34*sx, 54*sy, 42*sx, 54*sy) }, gray, style = Stroke(2.2f*sx))
        listOf(22f to 41f, 29f to 41f, 36f to 32f).forEach { (y, end) -> drawLine(gray.copy(alpha = .7f), androidx.compose.ui.geometry.Offset(19*sx,y*sy), androidx.compose.ui.geometry.Offset(end*sx,y*sy), 1.6f*sx) }
    }
}

