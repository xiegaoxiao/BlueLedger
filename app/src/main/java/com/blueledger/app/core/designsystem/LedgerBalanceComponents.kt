package com.blueledger.app.core.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

data class LedgerSummaryAmount(val label: String, val cent: Long, val tag: String? = null)

@Composable
fun LedgerBalanceSummaryCard(title: String, cent: Long, footer: String, modifier: Modifier = Modifier, amountTag: String? = null, secondary: List<LedgerSummaryAmount> = emptyList()) {
    Surface(modifier, color = T.Primary, contentColor = T.OnBrand, shape = RoundedCornerShape(T.RadiusCard)) {
        Column(Modifier.fillMaxWidth().padding(T.SpaceL), verticalArrangement = Arrangement.spacedBy(T.SpaceS)) {
            Text(title, color = T.OnBrand)
            LedgerFittedAmountText(cent, large = true, color = T.OnBrand, modifier = if (amountTag != null) Modifier.testTag(amountTag) else Modifier)
            secondary.forEach { amount ->
                Text(amount.label, color = T.OnBrand)
                LedgerFittedAmountText(amount.cent, color = T.OnBrand, modifier = if (amount.tag != null) Modifier.testTag(amount.tag) else Modifier)
            }
            Text(footer, color = T.OnBrand, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun LedgerChartCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    LedgerSectionCard(modifier.border(1.dp, Color(0xFFCCD5E2), RoundedCornerShape(T.RadiusCard)), contentPadding = PaddingValues(0.dp), elevation = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(T.SpaceM), verticalArrangement = Arrangement.spacedBy(T.SpaceS), content = content)
    }
}

@Composable
fun LedgerYearNavigation(year: Int, previousEnabled: Boolean, nextEnabled: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, modifier: Modifier = Modifier, yearTag: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        IconButton(enabled = previousEnabled, onClick = onPrevious) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "上一年") }
        Text("${year}年", color = T.TextPrimary, modifier = if (yearTag != null) Modifier.testTag(yearTag) else Modifier)
        IconButton(enabled = nextEnabled, onClick = onNext) { Icon(Icons.AutoMirrored.Outlined.ArrowForward, "下一年") }
    }
}

@Composable
fun LedgerExpandableGroupHeader(title: String, subtitle: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, totalCent: Long? = null, amountTag: String? = null) {
    Column(modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "展开或收起$title", onClick = onToggle).padding(T.SpaceS)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color(0xFF42526B))
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开", tint = T.Primary)
        }
        totalCent?.let { LedgerFittedAmountText(it, modifier = if (amountTag != null) Modifier.testTag(amountTag) else Modifier) }
    }
}

@Composable
fun LedgerBalanceAccountRow(name: String, cent: Long, subtitle: String, onDetail: () -> Unit, onUpdate: () -> Unit, modifier: Modifier = Modifier,
    accountTag: String? = null, amountTag: String? = null, updateTag: String? = null) {
    Column(modifier.fillMaxWidth().padding(top = T.SpaceS)) {
        HorizontalDivider(color = Color(0xFFCCD5E2))
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "查看$name", onClick = onDetail)
            .then(if (accountTag != null) Modifier.testTag(accountTag) else Modifier).padding(horizontal = T.SpaceS, vertical = T.SpaceM), verticalArrangement = Arrangement.spacedBy(T.SpaceXs)) {
            val amountModifier = if (amountTag != null) Modifier.testTag(amountTag) else Modifier
            if (LocalDensity.current.fontScale > 1.4f) {
                Text(name, color = T.TextPrimary, style = MaterialTheme.typography.titleMedium)
                LedgerFittedAmountText(cent, modifier = amountModifier)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.SpaceS)) {
                Text(name, Modifier.weight(1f), color = T.TextPrimary, style = MaterialTheme.typography.titleMedium)
                Box(Modifier.width(116.dp)) { LedgerFittedAmountText(cent, modifier = amountModifier, alignEnd = true) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = T.SpaceS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.SpaceS)) {
            Text(subtitle, Modifier.weight(1f), color = Color(0xFF42526B), style = MaterialTheme.typography.bodySmall)
            LedgerActionButton("更新", onUpdate, style = LedgerActionStyle.SECONDARY, testTag = updateTag)
        }
    }
}
