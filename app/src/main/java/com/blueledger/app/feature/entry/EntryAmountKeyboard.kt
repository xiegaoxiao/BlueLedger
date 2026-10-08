package com.blueledger.app.feature.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.designsystem.LedgerComponentSizes
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTints

/** 键盘容器 testTag，供测试与无障碍检查复用。 */
const val TAG_ENTRY_KEYBOARD: String = "entry_keyboard"

/**
 * 自定义金额数字键盘：记账页四行四列，预算输入四行三列。
 *
 * ```text
 * 7 8 9 日期
 * 4 5 6 +
 * 1 2 3 −
 * . 0 删除 完成
 * ```
 *
 * - 每个按键最小高度 ≥48dp（默认 56dp）；大字号时随文字增高，不缩小关键文字。
 * - 每个按键有稳定 testTag（key_1..key_9 / key_0 / key_dot / key_delete）
 *   与 TalkBack contentDescription（「数字 1」「小数点」「删除」）。
 * - 键盘自身不滚动，由父级固定在底部安全区域内（见 EntryScreen）。
 */
@Composable
fun EntryAmountKeyboard(
    onDigit: (Char) -> Unit,
    onDecimalPoint: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    keyGap: Dp = LedgerComponentSizes.keypadGap,
    keyPadding: Dp = BlueLedgerTokens.SpaceS,
    dateLabel: String = "今天",
    onDate: (() -> Unit)? = null,
    onAdd: (() -> Unit)? = null,
    onSubtract: (() -> Unit)? = null,
    onDone: (() -> Unit)? = null,
    doneLabel: String = "完成",
    actionsEnabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(BlueLedgerTokens.Border)
            .testTag(TAG_ENTRY_KEYBOARD)
            .semantics { contentDescription = "金额数字键盘" }
            .padding(bottom = keyGap),
        verticalArrangement = Arrangement.spacedBy(keyGap),
    ) {
        AmountKeyRow(
            keys = listOf(
                AmountKey("7", "数字 7", "key_7") { onDigit('7') },
                AmountKey("8", "数字 8", "key_8") { onDigit('8') },
                AmountKey("9", "数字 9", "key_9") { onDigit('9') },
            ) + if (onDate != null) listOf(AmountKey(dateLabel, "选择日期", "key_date", onPress = onDate)) else emptyList(),
            keyGap = keyGap,
            keyPadding = keyPadding,
            enabled = actionsEnabled,
        )
        AmountKeyRow(
            keys = listOf(
                AmountKey("4", "数字 4", "key_4") { onDigit('4') },
                AmountKey("5", "数字 5", "key_5") { onDigit('5') },
                AmountKey("6", "数字 6", "key_6") { onDigit('6') },
            ) + if (onAdd != null) listOf(AmountKey("+", "加", "key_plus", onPress = onAdd)) else emptyList(),
            keyGap = keyGap,
            keyPadding = keyPadding,
            enabled = actionsEnabled,
        )
        AmountKeyRow(
            keys = listOf(
                AmountKey("1", "数字 1", "key_1") { onDigit('1') },
                AmountKey("2", "数字 2", "key_2") { onDigit('2') },
                AmountKey("3", "数字 3", "key_3") { onDigit('3') },
            ) + if (onSubtract != null) listOf(AmountKey("−", "减", "key_minus", onPress = onSubtract)) else emptyList(),
            keyGap = keyGap,
            keyPadding = keyPadding,
            enabled = actionsEnabled,
        )
        AmountKeyRow(
            keys = listOf(
                AmountKey(".", "小数点", "key_dot") { onDecimalPoint() },
                AmountKey("0", "数字 0", "key_0") { onDigit('0') },
                AmountKey("", "删除", "key_delete", isDelete = true) { onDelete() },
            ) + if (onDone != null) listOf(AmountKey(doneLabel, if (doneLabel == "=") "计算结果" else doneLabel, TAG_SAVE,
                isPrimary = true, enabled = actionsEnabled, onPress = onDone)) else emptyList(),
            keyGap = keyGap,
            keyPadding = keyPadding,
            enabled = actionsEnabled,
        )
    }
}

private class AmountKey(
    val label: String,
    val contentDescription: String,
    val testTag: String,
    val isDelete: Boolean = false,
    val isPrimary: Boolean = false,
    val enabled: Boolean = true,
    val onPress: () -> Unit,
)

@Composable
private fun AmountKeyRow(
    keys: List<AmountKey>,
    keyGap: Dp,
    keyPadding: Dp,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(keyGap),
    ) {
        keys.forEach { key ->
            AmountKeyButton(key = key, keyPadding = keyPadding, modifier = Modifier.weight(1f), enabled = enabled)
        }
    }
}

@Composable
private fun AmountKeyButton(
    key: AmountKey,
    keyPadding: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean,
) {
    val view = LocalView.current
    val preferences = remember(view) { com.blueledger.app.feature.reference.ReferencePreferences(view.context) }
    val shape = RoundedCornerShape(0.dp)
    Box(
        modifier = modifier
            .heightIn(min = LedgerComponentSizes.keypadKeyMinHeight)
            .clip(shape)
            .background(if (key.isPrimary) BlueLedgerTokens.Primary else BlueLedgerTokens.Surface)
            .testTag(key.testTag)
            .semantics(mergeDescendants = true) { contentDescription = key.contentDescription }
            .clickable(enabled = enabled && key.enabled, role = Role.Button, onClickLabel = key.contentDescription) {
                if (preferences.sound) view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                if (preferences.haptic) view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                key.onPress()
            }
            .padding(keyPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (key.isDelete) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Backspace,
                contentDescription = null,
                tint = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.size(LedgerComponentSizes.iconMedium),
            )
        } else {
            Text(
                text = key.label,
                style = if (key.isPrimary || key.testTag == "key_date") LedgerTextStyles.button else LedgerTextStyles.keypadDigit,
                color = if (key.isPrimary) BlueLedgerTokens.Surface else BlueLedgerTokens.TextPrimary,
            )
        }
    }
}
