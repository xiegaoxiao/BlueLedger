package com.blueledger.app.core.designsystem

import android.os.Build
import android.view.ViewGroup
import android.view.ContextThemeWrapper
import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.semantics.*
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerPickerSheet(title: String, tag: String, cancelTag: String, confirmTag: String,
    onDismiss: () -> Unit, onConfirm: () -> Unit, compact: Boolean = false, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = T.Surface,
        scrimColor = if (compact) androidx.compose.ui.graphics.Color.Black.copy(alpha = .6f) else BottomSheetDefaults.ScrimColor,
        shape = androidx.compose.ui.graphics.RectangleShape, dragHandle = null) {
        Column(Modifier.testTag(tag).background(T.Surface)) {
            Row(Modifier.fillMaxWidth().then(if (compact) Modifier.height(32.dp) else Modifier.heightIn(min = 48.dp)), verticalAlignment = Alignment.CenterVertically) {
                val actionModifier = if (compact) Modifier.width(68.dp).height(32.dp) else Modifier
                val actionText = if (compact) TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal) else LedgerTextStyles.button
                LedgerActionButton("取消", onDismiss, actionModifier, style = LedgerActionStyle.NEUTRAL, testTag = cancelTag,
                    textStyle = actionText, contentPadding = if (compact) PaddingValues(0.dp) else null)
                Text(title, Modifier.weight(1f), textAlign = TextAlign.Center, color = T.TextPrimary, fontSize = if (compact) 16.sp else 17.sp)
                LedgerActionButton("确定", onConfirm, actionModifier, style = if (compact) LedgerActionStyle.TEXT else LedgerActionStyle.PRIMARY,
                    testTag = confirmTag, textStyle = actionText, contentPadding = if (compact) PaddingValues(0.dp) else null)
            }
            HorizontalDivider(thickness = .5.dp, color = T.Border)
            Box(Modifier.padding(top = if (compact) 16.dp else 12.dp, bottom = if (compact) 15.5.dp else 8.dp)) { content() }
        }
    }
}

@Composable
fun LedgerWheelColumn(range: IntRange, value: Int, label: String, tag: String,
    modifier: Modifier = Modifier, padded: Boolean = false, textSizeSp: Float = 18f,
    neighborRange: IntRange = range, softDividers: Boolean = false, onChange: (Int) -> Unit) {
    val textPixels = with(LocalDensity.current) { textSizeSp.sp.toPx() }
    // Adjacent labels preserve the three-row wheel at an endpoint; callbacks and semantics
    // still use the selectable range provided by the page.
    val displayRange = minOf(range.first, neighborRange.first)..maxOf(range.last, neighborRange.last)
    Box(modifier.height(180.dp).testTag(tag).semantics {
        contentDescription = "$label，$value"
        stateDescription = value.toString()
        progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(), (range.last - range.first - 1).coerceAtLeast(0))
        setProgress { position -> onChange(position.roundToInt().coerceIn(range)); true }
        customActions = listOf(
            CustomAccessibilityAction("上一个$label") { if (value > range.first) { onChange(value - 1); true } else false },
            CustomAccessibilityAction("下一个$label") { if (value < range.last) { onChange(value + 1); true } else false },
        )
    }) {
    AndroidView(factory = { context -> NumberPicker(ContextThemeWrapper(context, android.R.style.Theme_Material_Light)).apply {
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        wrapSelectorWheel = false
    } }, modifier = Modifier.fillMaxSize(), update = { picker ->
        if (picker.minValue != displayRange.first || picker.maxValue != displayRange.last) {
            picker.displayedValues = null
            picker.minValue = displayRange.first
            picker.maxValue = displayRange.last
        }
        picker.displayedValues = displayRange.map { if (padded) it.toString().padStart(2, '0') else it.toString() }.toTypedArray()
        picker.value = value.coerceIn(range)
        picker.wrapSelectorWheel = false
        picker.contentDescription = "$label，${picker.value}"
        if (Build.VERSION.SDK_INT >= 29) {
            picker.textSize = textPixels
            picker.textColor = T.TextPrimary.toArgb()
            picker.selectionDividerHeight = if (softDividers) 0 else 1
        }
        picker.setOnValueChangedListener { _, _, selected ->
            val allowed = selected.coerceIn(range)
            if (selected != allowed) picker.value = allowed
            onChange(allowed)
        }
    })
    if (softDividers) {
        Box(Modifier.fillMaxWidth().height(50.dp).align(Alignment.Center)) {
            HorizontalDivider(Modifier.align(Alignment.TopCenter), thickness = 2.dp, color = T.TextPrimary.copy(alpha = .06f))
            HorizontalDivider(Modifier.align(Alignment.BottomCenter), thickness = 2.dp, color = T.TextPrimary.copy(alpha = .06f))
        }
    }
    }
}
