package com.blueledger.app.feature.transactions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.designsystem.LedgerTextStyles
import java.time.YearMonth

/**
 * 月份栏（S01 首页与 S03 账单列表共用同一实现，避免两页月份行为漂移）。
 *
 * - 默认设备当前月；只允许回看历史月份，「下一个月」在当前月禁用。
 * - 浏览历史月份时出现「回到本月」，避免用户以为可以翻到未来月份。
 * - 每个可点区域最小 48×48dp；testTag 由调用方传入，方便两页各自稳定定位。
 */
@Composable
internal fun LedgerMonthNavigator(
    month: YearMonth,
    isCurrentMonth: Boolean,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onCurrent: () -> Unit,
    labelTag: String,
    prevTag: String,
    nextTag: String,
    currentTag: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = BlueLedgerTokens.MinTouchTarget),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = TransactionFormat.monthLabel(month),
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.weight(1f).testTag(labelTag),
            )
            IconButton(
                onClick = onPrevious,
                modifier = Modifier
                    .size(BlueLedgerTokens.MinTouchTarget)
                    .testTag(prevTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = "上一个月",
                    tint = BlueLedgerTokens.TextPrimary,
                )
            }
            IconButton(
                onClick = onNext,
                enabled = canGoNext,
                modifier = Modifier
                    .size(BlueLedgerTokens.MinTouchTarget)
                    .testTag(nextTag),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = "下一个月",
                    tint = if (canGoNext) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
                )
            }
        }
        // 历史月份的返回入口独立一行，避免和月份、两个箭头争抢横向空间。
        if (!isCurrentMonth) {
            Text(
                text = "回到本月",
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.Primary,
                modifier = Modifier
                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                    .testTag(currentTag)
                    .clickable(role = Role.Button, onClickLabel = "回到本月", onClick = onCurrent)
                    .padding(horizontal = BlueLedgerTokens.SpaceS, vertical = BlueLedgerTokens.SpaceM),
            )
        }
    }
}
