package com.blueledger.app.core.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CurrencyYen
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

@Composable
fun LedgerAssetSelectionRow(title: String, subtitle: String = "", icon: ImageVector,
    scale: Float = 1f, tag: String = "", iconContent: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height((58 * scale).dp).background(T.Surface)
        .clickable(onClick = onClick).testTag(tag).padding(horizontal = (15 * scale).dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (iconContent != null) iconContent() else LedgerAssetIcon(icon, (32 * scale).dp)
        Column(Modifier.weight(1f).padding(start = (10 * scale).dp)) {
            Text(title, fontSize = (14 * scale).sp, color = T.TextPrimary)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = (11 * scale).sp, color = T.TextSecondary)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size((20 * scale).dp), tint = T.TextSecondary)
    }
    HorizontalDivider(color = T.Border.copy(alpha = .45f), thickness = .5.dp)
}

@Composable
fun LedgerAssetIcon(icon: ImageVector, diameter: androidx.compose.ui.unit.Dp = 32.dp) {
    Surface(Modifier.size(diameter), shape = androidx.compose.foundation.shape.CircleShape, color = T.Primary) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(diameter * .65f), tint = T.OnBrand) }
    }
}

@Composable
fun LedgerAssetIconTile(icon: ImageVector, scale: Float = 1f, circular: Boolean = false) {
    Surface(shape = if (circular) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape((8 * scale).dp), color = T.Background) {
        Box(Modifier.size((34 * scale).dp), contentAlignment = Alignment.Center) { LedgerAssetIcon(icon, (22 * scale).dp) }
    }
}

/** A flat labelled field. Parsing, validation and account rules belong to the caller. */
@Composable
fun LedgerInlineField(label: String, value: String, onChange: (String) -> Unit,
    tag: String, scale: Float = 1f, optional: Boolean = false, enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text) {
    Row(Modifier.fillMaxWidth().heightIn(min = (48 * scale).dp).background(T.Surface)
        .padding(horizontal = (15 * scale).dp, vertical = (10 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width((125 * scale).dp), fontSize = (15 * scale).sp, color = T.TextPrimary)
        BasicTextField(value, onChange, Modifier.weight(1f).testTag(tag).semantics { contentDescription = label }, enabled = enabled, singleLine = true,
            textStyle = TextStyle(color = T.TextPrimary, fontSize = (14 * scale).sp, textAlign = TextAlign.End),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType), cursorBrush = SolidColor(T.Primary),
            decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty() && optional) Text("（选填）", color = T.TextSecondary.copy(alpha = .5f), fontSize = (13 * scale).sp)
                field()
            } })
    }
    HorizontalDivider(color = T.Border.copy(alpha = .45f), thickness = .5.dp)
}

@Composable
fun LedgerOverviewCard(primaryCent: Long, leftCent: Long, rightCent: Long, scale: Float = 1f,
    title: String = "净资产", leftLabel: String = "资产", rightLabel: String = "负债",
    tag: String = "asset_overview_card", primaryTag: String = "asset_net_total", leftTag: String = "asset_total") {
    Surface(Modifier.fillMaxWidth().height((124 * scale).dp).testTag(tag), color = T.Primary,
        shape = RoundedCornerShape((12 * scale).dp)) {
        Box {
        Icon(Icons.Outlined.CurrencyYen, null, Modifier.align(Alignment.TopEnd).padding(top = (10 * scale).dp, end = (8 * scale).dp).size((82 * scale).dp), tint = T.OnBrand.copy(alpha = .14f))
        Column(Modifier.padding(horizontal = (27 * scale).dp, vertical = (16 * scale).dp)) {
            Text(title, fontSize = (13 * scale).sp, color = T.OnBrand)
            Spacer(Modifier.height((5 * scale).dp))
            LedgerFittedAmountText(primaryCent, Modifier.testTag(primaryTag), color = T.OnBrand,
                fontSizeSp = 28 * scale, groupThousands = false, weight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text("$leftLabel ", color = T.OnBrand, fontSize = (13 * scale).sp)
                    Box(Modifier.weight(1f)) { LedgerFittedAmountText(leftCent, Modifier.testTag(leftTag), color = T.OnBrand, fontSizeSp = 16 * scale, groupThousands = false) }
                }
                Row(Modifier.weight(1f).padding(start = (5 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$rightLabel ", color = T.OnBrand, fontSize = (13 * scale).sp)
                    Box(Modifier.weight(1f)) { LedgerFittedAmountText(rightCent, color = T.OnBrand, fontSizeSp = 16 * scale, groupThousands = false) }
                }
            }
        }
        }
    }
}

@Composable
fun LedgerAssetListRow(name: String, cent: Long, icon: ImageVector, scale: Float = 1f,
    tag: String, amountTag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().height((50 * scale).dp).clickable(onClick = onClick).testTag(tag)
        .padding(horizontal = (12 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
        LedgerAssetIconTile(icon, scale)
        Text(name, Modifier.weight(1f).padding(start = (12 * scale).dp), fontSize = (14 * scale).sp, color = T.TextPrimary)
        Box(Modifier.widthIn(max = (145 * scale).dp)) { LedgerFittedAmountText(cent, Modifier.testTag(amountTag), alignEnd = true,
            fontSizeSp = 16 * scale, groupThousands = false) }
    }
}

@Composable
fun LedgerAssetDetailCard(name: String, cent: Long, icon: ImageVector, onAdjust: () -> Unit, scale: Float = 1f) {
    Surface(Modifier.fillMaxWidth().height((127 * scale).dp).testTag("asset_detail_card"), color = T.Surface,
        shape = RoundedCornerShape((12 * scale).dp), shadowElevation = 2.dp) {
        Box(Modifier.padding(horizontal = (24 * scale).dp, vertical = (16 * scale).dp)) {
            Icon(Icons.Outlined.Security, null, Modifier.align(Alignment.CenterEnd).size((105 * scale).dp), tint = T.Background.copy(alpha = .7f))
            Text(name, Modifier.fillMaxWidth(.75f), fontSize = (15 * scale).sp, fontWeight = FontWeight.Bold, color = T.TextPrimary)
            Box(Modifier.align(Alignment.TopEnd)) { LedgerAssetIcon(icon, (28 * scale).dp) }
            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(.66f)) {
                LedgerFittedAmountText(cent, Modifier.testTag("asset_detail_balance"), fontSizeSp = 28 * scale,
                    groupThousands = false, weight = FontWeight.Bold)
                Text("余额", fontSize = (12 * scale).sp, lineHeight = (14 * scale).sp, color = T.TextSecondary)
            }
            LedgerActionButton("调整余额", onAdjust, Modifier.align(Alignment.BottomEnd).height((28 * scale).dp),
                testTag = "asset_adjust_balance", contentPadding = PaddingValues(horizontal = (12 * scale).dp),
                textStyle = LedgerTextStyles.body.copy(fontSize = (12 * scale).sp, lineHeight = (16 * scale).sp))
        }
    }
}
