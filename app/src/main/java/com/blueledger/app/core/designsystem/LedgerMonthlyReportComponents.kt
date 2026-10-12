package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

/** Decorative header: no user profile or synthetic chart data. */
@Composable
fun LedgerMonthlyReportHeader(year: Int, month: Int, scale: Float, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth().height((200 * scale).dp)
        .background(Brush.verticalGradient(listOf(T.PrimarySoft, T.Background)))) {
        LedgerCenteredTopBar("", onBack, height = (50 * scale).dp, backgroundColor = androidx.compose.ui.graphics.Color.Transparent)
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = (20 * scale).dp)) {
            Box(Modifier.align(Alignment.CenterEnd).size((120 * scale).dp)
                .background(T.Primary.copy(alpha = .06f), RoundedCornerShape((36 * scale).dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.PieChart, null, Modifier.size((84 * scale).dp), tint = T.Primary.copy(alpha = .65f))
            }
            Column(Modifier.align(Alignment.CenterStart)) {
                Text("${year}年", fontSize = (13 * scale).sp, color = T.TextSecondary)
                Text("${month}月账单", Modifier.padding(top = (6 * scale).dp).testTag("month_report_title"),
                    fontSize = (24 * scale).sp, fontWeight = FontWeight.Bold, color = T.TextPrimary)
            }
        }
    }
}

@Composable
fun LedgerMonthlyBalanceCard(balance: Long, previousBalance: Long, income: Long, expense: Long, scale: Float) {
    Surface(Modifier.fillMaxWidth().height((180 * scale).dp).testTag("month_report_summary"),
        shape = RoundedCornerShape((8 * scale).dp), color = T.Surface) {
        Column(Modifier.padding(horizontal = (20 * scale).dp, vertical = (24 * scale).dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy((20 * scale).dp)) {
                listOf(Triple("本月结余", balance, T.TextPrimary), Triple("上月结余", previousBalance, T.TextSecondary.copy(alpha = .65f))).forEachIndexed { index, (label, cent, color) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, fontSize = (13 * scale).sp, color = T.TextSecondary)
                        Spacer(Modifier.height((5 * scale).dp))
                        LedgerFittedAmountText(cent, Modifier.testTag(if (index == 0) "month_report_balance" else "month_report_previous"),
                            color = color, fontSizeSp = 24 * scale, weight = FontWeight.Bold, groupThousands = false)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            listOf("支出" to expense, "收入" to income).forEachIndexed { index, (label, cent) ->
                Row(Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else (12 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.width((38 * scale).dp), fontSize = (13 * scale).sp, color = T.TextSecondary)
                    Box(Modifier.size((4 * scale).dp, (12 * scale).dp).background(T.Primary.copy(alpha = if (index == 0) 1f else .35f), RoundedCornerShape(3.dp)))
                    Box(Modifier.weight(1f).padding(start = (8 * scale).dp)) {
                        LedgerFittedAmountText(cent, Modifier.testTag(if (index == 0) "month_report_expense" else "month_report_income"), fontSizeSp = 14 * scale, groupThousands = false)
                    }
                }
            }
        }
    }
}

data class LedgerMetric(val label: String, val value: Int, val unit: String, val tag: String)

@Composable
fun LedgerMetricsCard(title: String, values: List<LedgerMetric>, scale: Float) {
    Surface(Modifier.fillMaxWidth().height((122 * scale).dp).testTag("month_report_metrics"), shape = RoundedCornerShape((8 * scale).dp), color = T.Surface) {
        Column(Modifier.padding((16 * scale).dp)) {
            Text(title, fontSize = (15 * scale).sp, fontWeight = FontWeight.Bold, color = T.TextPrimary)
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                values.forEach { metric ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy((5 * scale).dp)) {
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(metric.value.toString(), Modifier.testTag(metric.tag), fontSize = (20 * scale).sp, fontWeight = FontWeight.Medium, color = T.TextPrimary)
                            Text(metric.unit, fontSize = (12 * scale).sp, color = T.TextPrimary, modifier = Modifier.padding(bottom = (2 * scale).dp))
                        }
                        Text(metric.label, fontSize = (12 * scale).sp, color = T.TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
fun LedgerReportSection(title: String, scale: Float, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape((8 * scale).dp), color = T.Surface) {
        Column(Modifier.padding((16 * scale).dp)) {
            Text(title, fontSize = (15 * scale).sp, fontWeight = FontWeight.Bold, color = T.TextPrimary)
            Spacer(Modifier.height((12 * scale).dp))
            content()
        }
    }
}
