package com.blueledger.app.core.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

@Composable
fun LedgerBillToolbar(yearLabel: String, annual: Boolean, onYear: () -> Unit, onSelect: (Boolean) -> Unit,
    scale: Float = 1f, onBack: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height((50 * scale).dp)) {
        Row(Modifier.align(Alignment.CenterStart).padding(start = ((if (onBack == null) 15 else 5) * scale).dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size((22 * scale).dp).testTag("btn_back")) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", Modifier.size((18 * scale).dp), tint = T.TextPrimary)
            }
            if (!annual) Row(Modifier.height((48 * scale).dp).clickable(onClick = onYear).testTag("bills_year_selector"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((4 * scale).dp)) {
                Text(yearLabel, fontSize = ((if (onBack == null) 16 else 14) * scale).sp, color = T.TextPrimary)
                if (!annual) LedgerDropdownArrow(Modifier.size((10 * scale).dp).testTag("bills_year_arrow"))
            }
        }
        LedgerSegmentedControl(listOf(false, true), annual, { if (it) "年账单" else "月账单" }, onSelect,
            Modifier.align(Alignment.Center).width((140 * scale).dp).testTag("bills_period_switch"),
            compactHeight = (29 * scale).dp, tag = { if (it) "bills_tab_year" else "bills_tab_month" }, textSizeSp = 13 * scale,
            shape = androidx.compose.foundation.shape.RoundedCornerShape((8 * scale).dp))
    }
}

/** The page supplies period totals and labels; this component only lays out columns. */
@Composable
fun LedgerBillTableRow(label: String, amounts: List<Long>, scale: Float = 1f, annual: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height((44 * scale).dp).clickable(onClick = onClick).testTag("bills_row_$label")
        .padding(horizontal = (24 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(if (annual) .96f else 1f), fontSize = (15 * scale).sp, color = T.TextPrimary)
        amounts.forEachIndexed { index, cent -> Box(Modifier.weight(if (annual) if (index == 2) .82f else 1.11f else 1f)) { LedgerFittedAmountText(cent, fontSizeSp = 14 * scale, groupThousands = false) } }
        if (!annual) Icon(Icons.Outlined.ChevronRight, "查看${label}账单", Modifier.size((16 * scale).dp), tint = T.TextSecondary.copy(alpha = .45f))
    }
    HorizontalDivider(Modifier.padding(horizontal = (15 * scale).dp), thickness = .5.dp, color = T.Border.copy(alpha = .5f))
}
