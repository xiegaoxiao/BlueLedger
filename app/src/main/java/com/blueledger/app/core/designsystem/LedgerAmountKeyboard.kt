package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextStyle
import com.blueledger.app.app.ui.BlueLedgerTokens as T

const val TAG_LEDGER_AMOUNT_KEYBOARD = "entry_keyboard"

/** Flat amount keyboard: continuous white surface, fine rules, consistent touch targets. */
@Composable
fun LedgerAmountKeyboard(
    onDigit: (Char) -> Unit,
    onDecimalPoint: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    keyGap: Dp = .5.dp,
    keyPadding: Dp = 0.dp,
    dateLabel: String = "今天",
    onDate: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onSubtract: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    doneLabel: String = "完成",
    actionsEnabled: Boolean = true,
    keyMinHeight: Dp = LedgerComponentSizes.keypadKeyMinHeight,
    digitStyle: TextStyle = LedgerTextStyles.keypadDigit,
    deleteIconSize: Dp = LedgerComponentSizes.iconMedium,
    onKeyFeedback: () -> Unit = {},
) {
    val rows = listOf(
        listOf(Key("7", "数字 7", "key_7") { onDigit('7') }, Key("8", "数字 8", "key_8") { onDigit('8') }, Key("9", "数字 9", "key_9") { onDigit('9') }) +
            if (onDate != null) listOf(Key(dateLabel, "选择日期", "key_date", action = onDate)) else emptyList(),
        listOf(Key("4", "数字 4", "key_4") { onDigit('4') }, Key("5", "数字 5", "key_5") { onDigit('5') }, Key("6", "数字 6", "key_6") { onDigit('6') }) +
            if (onAdd != null) listOf(Key("+", "加", "key_plus", action = onAdd)) else emptyList(),
        listOf(Key("1", "数字 1", "key_1") { onDigit('1') }, Key("2", "数字 2", "key_2") { onDigit('2') }, Key("3", "数字 3", "key_3") { onDigit('3') }) +
            if (onSubtract != null) listOf(Key("−", "减", "key_minus", action = onSubtract)) else emptyList(),
        listOf(Key(".", "小数点", "key_dot", onDecimalPoint), Key("0", "数字 0", "key_0") { onDigit('0') }, Key("", "删除", "key_delete", onDelete)) +
            if (onDone != null) listOf(Key(doneLabel, if (doneLabel == "=") "计算结果" else doneLabel, "btn_save", action = onDone)) else emptyList(),
    )
    Column(modifier.fillMaxWidth().testTag(TAG_LEDGER_AMOUNT_KEYBOARD).semantics { contentDescription = "金额数字键盘" }
        .background(T.TextSecondary.copy(alpha = .16f)).padding(top = keyGap), verticalArrangement = Arrangement.spacedBy(keyGap)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(keyGap)) {
                row.forEach { key ->
                    val primary = key.tag == "btn_save"
                    Box(Modifier.weight(1f).heightIn(min = keyMinHeight.coerceAtLeast(T.MinTouchTarget))
                        .background(if (primary) if (actionsEnabled) T.Primary else T.Border else T.Surface)
                        .testTag(key.tag).semantics(mergeDescendants = true) { contentDescription = key.description }
                        .clickable(enabled = actionsEnabled, role = Role.Button, onClickLabel = key.description) { onKeyFeedback(); key.action() }
                        .padding(keyPadding), contentAlignment = Alignment.Center) {
                        val color = if (!actionsEnabled) T.TextSecondary else if (primary) T.OnBrand else T.TextPrimary
                        if (key.tag == "key_delete") Icon(Icons.AutoMirrored.Outlined.Backspace, null, tint = color, modifier = Modifier.size(deleteIconSize))
                        else Text(key.label, style = if (primary || key.tag == "key_date") LedgerTextStyles.button else digitStyle, color = color)
                    }
                }
            }
        }
    }
}

private data class Key(val label: String, val description: String, val tag: String, val action: () -> Unit)
