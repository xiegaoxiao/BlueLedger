package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens

/**
 * 顶部栏：左侧返回 + 标题，右侧最多一至两个与本页相关的动作。
 *
 * 注意：本组件**不自带系统栏内边距**。二级页面在自己容器上使用
 * `Modifier.statusBarsPadding()`；一级页面由承载它们的 Scaffold 统一处理。
 */
@Composable
fun LedgerTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backContentDescription: String = "返回",
    showDivider: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = LedgerComponentSizes.topBarHeight)
                .padding(start = BlueLedgerTokens.SpaceXs, end = BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(BlueLedgerTokens.MinTouchTarget)
                        .testTag("btn_back"),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = backContentDescription,
                        tint = BlueLedgerTokens.TextPrimary,
                        modifier = Modifier.size(LedgerComponentSizes.iconMedium),
                    )
                }
            } else {
                Box(modifier = Modifier.width(BlueLedgerTokens.SpaceS))
            }

            Text(
                text = title,
                style = LedgerTextStyles.pageTitle,
                color = BlueLedgerTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (onBack != null) BlueLedgerTokens.SpaceXs else 0.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
                content = actions,
            )
        }

        if (showDivider) LedgerDivider()
    }
}

/** 1dp 边线分割。 */
@Composable
fun LedgerDivider(modifier: Modifier = Modifier, color: Color = BlueLedgerTokens.Border) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LedgerComponentSizes.dividerThickness)
            .background(color),
    )
}

/** 白色卡片：内部留白独立于页面区块间距。 */
@Composable
fun LedgerSectionCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(BlueLedgerTokens.CardPadding),
    shape: RoundedCornerShape = RoundedCornerShape(BlueLedgerTokens.RadiusCard),
    elevation: androidx.compose.ui.unit.Dp = BlueLedgerTokens.CardElevation,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation, shape, clip = false)
            .background(BlueLedgerTokens.Surface, shape)
            .padding(contentPadding),
        content = content,
    )
}

/** 区块小标题（如「最近账单」「分类排行」）。 */
@Composable
fun LedgerSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = BlueLedgerTokens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = title,
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                )
            }
        }
        if (action != null) {
            Box(modifier = Modifier.padding(start = BlueLedgerTokens.SpaceS)) { action() }
        }
    }
}

/**
 * 表单行：左侧图标 + 标签，右侧值或自定义内容。
 *
 * 整行最小高度 56dp；[onClick] 非空时整行可点击并具备按钮语义，
 * 触控区不小于 48×48dp。
 */
@Composable
fun LedgerFormRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    valueColor: Color = BlueLedgerTokens.TextPrimary,
    placeholder: String? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    testTag: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = BlueLedgerTokens.TextSecondary,
                    modifier = Modifier
                        .size(LedgerComponentSizes.iconMedium)
                        .padding(end = BlueLedgerTokens.SpaceXs),
                )
            }
            Text(
                text = label,
                style = LedgerTextStyles.fieldLabel,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.padding(end = BlueLedgerTokens.SpaceM),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (content != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.End,
                        content = content,
                    )
                } else {
                    Text(
                        text = value ?: placeholder.orEmpty(),
                        style = LedgerTextStyles.body,
                        color = if (value == null && placeholder != null) {
                            BlueLedgerTokens.TextSecondary.copy(alpha = 0.7f)
                        } else {
                            valueColor
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = BlueLedgerTokens.SpaceS),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
            if (trailing != null) trailing()
        }
        if (showDivider) LedgerDivider()
    }
}
