package com.blueledger.app.feature.reference
import com.blueledger.app.app.ui.LocalHideAmounts

import com.blueledger.app.core.designsystem.LedgerPickerSheet
import com.blueledger.app.core.designsystem.LedgerWheelColumn
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.money.Money
import java.time.YearMonth

/**
 * 金额展示规则：整数分 → 去千分位、去掉多余小数零（`1250` → `12.5`，`2600` → `26`）。
 * 只用于展示，不参与任何计算或写入。
 */
internal fun referenceAmountText(cent: Long): String =
    Money.format(cent).replace(",", "").trimEnd('0').trimEnd('.')

@Composable
internal fun ReferenceMonthPicker(month: YearMonth, current: YearMonth, onDismiss: () -> Unit, onConfirm: (YearMonth) -> Unit) {
    var year by remember { mutableIntStateOf(month.year) }
    var number by remember { mutableIntStateOf(month.monthValue) }
    LedgerPickerSheet("选择月份", "month_picker", "month_dismiss", "month_confirm", onDismiss,
        { onConfirm(YearMonth.of(year, number).coerceAtMost(current)) }, compact = true) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)) {
            LedgerWheelColumn(minOf(1970, month.year)..current.year, year, "年份", "month_year", Modifier.width(64.dp),
                textSizeSp = 15f, neighborRange = minOf(1970, month.year)..(current.year + 1), softDividers = true) {
                year = it
                number = number.coerceAtMost(if (year == current.year) current.monthValue else 12)
            }
            LedgerWheelColumn(1..(if (year == current.year) current.monthValue else 12), number, "月份", "month_number", Modifier.width(64.dp),
                textSizeSp = 15f, neighborRange = 1..12, softDividers = true) { number = it }
        }
    }
}

@Composable
internal fun ReferenceLineChart(values: List<Long>, labels: List<String>, modifier: Modifier = Modifier, futureFrom: Int = values.size) {
    com.blueledger.app.core.designsystem.LedgerLineChart(values, labels, modifier, futureFrom, formatAmount = ::referenceAmountText)
}

@Composable
internal fun ReferenceCategoryRing(categories: List<ReferenceCategory>) {
    val colors = listOf(T.Primary, Color(0xFF078B78), Color(0xFF7C4DC4), Color(0xFFD46A20), Color(0xFFBB3D63))
    val total = categories.sumOf { it.cent }
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        com.blueledger.app.core.designsystem.LedgerDonutChart(if (LocalHideAmounts.current) emptyList() else categories.mapIndexed { index, item ->
            com.blueledger.app.core.designsystem.LedgerChartSlice(item.name, item.cent, colors[index % colors.size])
        }) {
            Column(Modifier.width(108.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("支出", color = T.TextSecondary, fontSize = 12.sp)
                com.blueledger.app.core.designsystem.LedgerFittedAmountText(total, alignCenter = true)
            }
        }
    }
}
