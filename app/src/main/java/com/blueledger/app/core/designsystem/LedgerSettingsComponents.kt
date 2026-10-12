package com.blueledger.app.core.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

@Composable
fun LedgerMenuDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier.padding(start = T.SpaceL + T.IconMedium + T.SpaceM, end = T.SpaceL),
        color = T.Border, thickness = .5.dp)
}

@Composable
fun LedgerMenuRow(title: String, icon: ImageVector, value: String = "", tag: String = "", modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = T.MenuRowMinHeight).clickable(onClick = onClick)
        .testTag(tag).padding(horizontal = T.SpaceL, vertical = T.SpaceM), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(T.IconMedium), tint = T.Primary)
        Text(title, Modifier.weight(1f).padding(horizontal = T.SpaceM), fontSize = T.Body, color = T.TextPrimary)
        if (value.isNotEmpty()) Text(value, fontSize = T.BodySmall, color = T.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = T.SpaceS).size(T.IconSmall), tint = T.TextSecondary)
    }
}

@Composable
fun LedgerSectionLabel(title: String, modifier: Modifier = Modifier) { Text(title, fontSize = 12.sp, color = T.TextSecondary, modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }

@Composable
fun LedgerSwitchRow(title: String, selected: Boolean, tag: String = "", subtitle: String = "", modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().background(T.Surface).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = T.TextPrimary)
            if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        Switch(selected, onChange, Modifier.testTag(tag))
    }
    HorizontalDivider(color = T.Border, thickness = .5.dp)
}


/** 单选列表行；选中语义与标记由组件统一提供，选择后的业务操作交给页面。 */
@Composable
fun LedgerChoiceRow(title: String, selected: Boolean, modifier: Modifier = Modifier,
    tag: String = "", height: Dp? = null, textSizeSp: Float = 16f,
    horizontalPadding: Dp = 16.dp, dividerStart: Dp = 0.dp,
    leadingIcon: (@Composable () -> Unit)? = null, onSelect: () -> Unit) {
    Row(modifier.fillMaxWidth().background(T.Surface)
        .selectable(selected, role = Role.RadioButton, onClick = onSelect)
        .then(if (height == null) Modifier.heightIn(min = 50.dp) else Modifier.height(height))
        .padding(horizontal = horizontalPadding, vertical = if (height == null) 12.dp else 0.dp)
        .testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        if (leadingIcon != null) { leadingIcon(); Spacer(Modifier.width(12.dp)) }
        Text(title, fontSize = textSizeSp.sp, lineHeight = if (height == null) 24.sp else (textSizeSp * 1.45f).sp, color = T.TextPrimary, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Outlined.Check, "已选择", tint = T.TextPrimary, modifier = Modifier.size(if (height == null) 24.dp else (textSizeSp * 1.43f).dp))
    }
    HorizontalDivider(Modifier.padding(start = dividerStart), color = T.Border.copy(alpha = .45f), thickness = .5.dp)
}
