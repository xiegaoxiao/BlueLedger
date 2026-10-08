package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens

/**
 * 确认弹层。危险操作（删除、移除预算）使用风险色确认按钮；
 * 可选的 [content] 用于展示具体记录摘要（分类、金额、日期），避免笼统「确定删除吗」。
 */
@Composable
fun LedgerConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    dismissText: String = "取消",
    destructive: Boolean = false,
    testTag: String? = null,
    testTagConfirm: String = "dialog_confirm",
    testTagDismiss: String = "dialog_dismiss",
    content: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier.then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
        shape = RoundedCornerShape(BlueLedgerTokens.RadiusCard),
        containerColor = BlueLedgerTokens.Surface,
        title = {
            Text(
                text = title,
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM)) {
                Text(
                    text = message,
                    style = LedgerTextStyles.body,
                    color = BlueLedgerTokens.TextSecondary,
                )
                content?.invoke()
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag(testTagConfirm),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (destructive) BlueLedgerTokens.Risk else BlueLedgerTokens.Primary,
                ),
            ) {
                Text(text = confirmText, style = LedgerTextStyles.button)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag(testTagDismiss),
                colors = ButtonDefaults.textButtonColors(contentColor = BlueLedgerTokens.TextSecondary),
            ) {
                Text(text = dismissText, style = LedgerTextStyles.button)
            }
        },
    )
}

/** 空态：短标题 + 一句说明 + 主动作；不使用大插画挤压按钮。 */
@Composable
fun LedgerEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    icon: ImageVector = Icons.Outlined.Inbox,
    testTag: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(BlueLedgerTokens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        Box(
            modifier = Modifier
                .size(LedgerComponentSizes.emptyIllustration)
                .clip(CircleShape)
                .background(BlueLedgerTokens.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = BlueLedgerTokens.Primary,
                modifier = Modifier.size(LedgerComponentSizes.iconXLarge),
            )
        }
        Text(
            text = title,
            style = LedgerTextStyles.cardTitle,
            color = BlueLedgerTokens.TextPrimary,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            LedgerPrimaryButton(
                text = actionText,
                onClick = onAction,
                modifier = Modifier.padding(top = BlueLedgerTokens.SpaceXs),
                testTag = testTag?.let { "${it}_action" },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = BlueLedgerTokens.Surface,
                        modifier = Modifier.size(LedgerComponentSizes.iconSmall),
                    )
                },
            )
        }
    }
}

/** 加载态：用于读取数据库过程中的占位，不能把加载中显示成空账本。 */
@Composable
fun LedgerLoadingState(
    modifier: Modifier = Modifier,
    message: String = "正在读取…",
    testTag: String? = "state_loading",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(BlueLedgerTokens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        CircularProgressIndicator(
            color = BlueLedgerTokens.Primary,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = message,
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
        )
    }
}

/** 错误态：明确的失败说明 + 重试入口；不得伪装为空态。 */
@Composable
fun LedgerErrorState(
    message: String,
    modifier: Modifier = Modifier,
    title: String = "读取失败",
    retryText: String = "重试",
    onRetry: (() -> Unit)? = null,
    testTag: String? = "state_error",
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(BlueLedgerTokens.SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            tint = BlueLedgerTokens.Risk,
            modifier = Modifier.size(LedgerComponentSizes.iconXLarge),
        )
        Text(
            text = title,
            style = LedgerTextStyles.cardTitle,
            color = BlueLedgerTokens.TextPrimary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
            textAlign = TextAlign.Center,
        )
        if (onRetry != null) {
            LedgerSecondaryButton(
                text = retryText,
                onClick = onRetry,
                modifier = Modifier.padding(top = BlueLedgerTokens.SpaceXs),
                testTag = testTag?.let { "${it}_retry" },
            )
        }
    }
}

/** 行内错误横幅：保存失败等可恢复错误，附带重试动作。 */
@Composable
fun LedgerErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = "error_banner",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
            .background(LedgerTints.riskSoft())
            .border(
                width = LedgerComponentSizes.dividerThickness,
                color = BlueLedgerTokens.Risk.copy(alpha = 0.35f),
                shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(BlueLedgerTokens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            tint = BlueLedgerTokens.Risk,
            modifier = Modifier.size(LedgerComponentSizes.iconMedium),
        )
        Text(
            text = message,
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.Risk,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag("${testTag}_action"),
                colors = ButtonDefaults.textButtonColors(contentColor = BlueLedgerTokens.Risk),
            ) {
                Text(text = actionText, style = LedgerTextStyles.bodyStrong)
            }
        }
    }
}

/** 成功反馈横幅：保存成功提示（不能只依赖一闪而过的动画）。 */
@Composable
fun LedgerSuccessBanner(
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    testTag: String? = "success_banner",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
            .background(LedgerTints.incomeSoft())
            .border(
                width = LedgerComponentSizes.dividerThickness,
                color = BlueLedgerTokens.Income.copy(alpha = 0.3f),
                shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(BlueLedgerTokens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Text(
            text = message,
            style = LedgerTextStyles.bodyStrong,
            color = BlueLedgerTokens.Income,
            modifier = Modifier.weight(1f),
        )
        if (actionText != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag("${testTag}_action"),
                colors = ButtonDefaults.textButtonColors(contentColor = BlueLedgerTokens.Income),
            ) {
                Text(text = actionText, style = LedgerTextStyles.bodyStrong)
            }
        }
    }
}

/** 中性提示横幅。 */
@Composable
fun LedgerInfoBanner(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Outlined.Info,
    color: Color = BlueLedgerTokens.Primary,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
            .background(BlueLedgerTokens.PrimarySoft)
            .padding(BlueLedgerTokens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(LedgerComponentSizes.iconMedium),
        )
        Text(
            text = message,
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}
