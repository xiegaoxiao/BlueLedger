package com.blueledger.app.core.designsystem

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T

@Composable
fun LedgerCenteredTopBar(title: String, onBack: (() -> Unit)? = null, blue: Boolean = false, brand: Boolean = false, onTitleClick: (() -> Unit)? = null, titleTag: String = "", height: Dp? = null, fontSizeSp: Float = 19f, backTag: String = "btn_back", backgroundColor: Color? = null, action: @Composable () -> Unit = {}) {
    val color = if (brand) T.OnBrand else T.TextPrimary
    Box(Modifier.fillMaxWidth().then(if (height != null) Modifier.height(height) else Modifier.heightIn(min = if (brand) T.BrandTitleHeight else 52.dp)).background(backgroundColor ?: if (brand) T.Primary else if (blue) T.PrimarySoft else T.Surface)) {
        Row(Modifier.align(Alignment.Center).heightIn(min = T.MinTouchTarget)
            .then(if (onTitleClick != null) Modifier.clickable(role = Role.Button, onClick = onTitleClick) else Modifier)
            .testTag(titleTag).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, fontSize = fontSizeSp.sp, color = color)
            if (onTitleClick != null) LedgerDropdownArrow(color = color)
        }
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart).testTag(backTag)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = color) }
        Box(Modifier.align(Alignment.CenterEnd)) { action() }
    }
}

@Composable
fun <V> LedgerUnderlineTabs(options: List<V>, selected: V, label: (V) -> String, onSelect: (V) -> Unit, modifier: Modifier = Modifier, tag: (V) -> String = { "" }, onBrand: Boolean = false, underline: Boolean = true, icon: ((V) -> androidx.compose.ui.graphics.vector.ImageVector)? = null) {
    Row(modifier.fillMaxWidth().background(if (onBrand) T.Primary else T.Surface)) {
        options.forEach { value ->
            Column(Modifier.weight(1f).heightIn(min = 48.dp).selectable(value == selected, role = Role.Tab, onClick = { onSelect(value) }).testTag(tag(value)), horizontalAlignment = Alignment.CenterHorizontally) {
                if (icon != null) Icon(icon(value), null, Modifier.padding(top = 5.dp).size(22.dp), tint = if (value == selected) T.Primary else T.TextSecondary)
                Text(label(value), fontSize = if (icon != null) 10.sp else 16.sp, color = if (onBrand) T.OnBrand else if (value == selected) T.Primary else T.TextSecondary,
                    fontWeight = if (value == selected) FontWeight.Medium else FontWeight.Normal, modifier = Modifier.padding(vertical = if (icon != null) 2.dp else 12.dp))
                if (underline) Box(Modifier.width(40.dp).height(2.dp).background(if (value != selected) Color.Transparent else if (onBrand) T.OnBrand else T.Primary))
            }
        }
    }
}

@Composable
fun LedgerDropdownArrow(modifier: Modifier = Modifier, color: Color = T.TextPrimary) {
    Canvas(modifier.size(width = 11.dp, height = 7.dp)) {
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() }, color)
    }
}

/** A labelled picker entry with a real button target and the shared dropdown icon. */
@Composable
fun LedgerDropdownButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    height: Dp? = null, textStyle: TextStyle = LedgerTextStyles.bodyStrong) {
    TextButton(onClick = onClick, enabled = enabled, modifier = modifier.then(if (height == null) Modifier.heightIn(min = T.MinTouchTarget) else Modifier.height(height)),
        contentPadding = if (height == null) ButtonDefaults.TextButtonContentPadding else PaddingValues(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(T.SpaceS)) {
            Text(text, Modifier.weight(1f, fill = false), style = textStyle)
            LedgerDropdownArrow(color = if (enabled) T.Primary else T.TextSecondary)
        }
    }
}

@Composable
fun LedgerSheetHeader(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, closeTag: String? = null,
    titleStyle: TextStyle = LedgerTextStyles.cardTitle, closeDiameter: Dp = 32.dp) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = T.SpaceS), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.size(T.MinTouchTarget))
        Text(title, Modifier.weight(1f), style = titleStyle, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        IconButton(onClick = onClose, enabled = enabled, modifier = if (closeTag != null) Modifier.testTag(closeTag) else Modifier) {
            Icon(Icons.Outlined.Close, "取消", modifier = Modifier.size(closeDiameter).background(T.Background, androidx.compose.foundation.shape.CircleShape).padding(T.SpaceXs),
                tint = if (enabled) T.TextPrimary else T.TextSecondary)
        }
    }
}

@Composable
fun LedgerNavigationRow(title: String, value: String = "", subtitle: String = "", enabled: Boolean = true, tag: String = "", modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.testTag(tag).fillMaxWidth().heightIn(min = 56.dp).background(T.Surface).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, color = if (enabled) T.TextPrimary else T.TextSecondary)
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(top = 4.dp))
        }
        Text(value, fontSize = 14.sp, color = T.TextSecondary)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = T.TextSecondary, modifier = Modifier.padding(start = 12.dp).size(24.dp))
    }
    HorizontalDivider(color = T.Border.copy(alpha = .45f), thickness = .5.dp)
}
