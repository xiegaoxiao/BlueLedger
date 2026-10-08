package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.model.AccountKind

/**
 * 主按钮：主蓝实心、白色文字、圆角 16dp、高度 ≥52dp。
 * [loading] 时显示进度并禁用点击（保存防重复点击）。
 */
@Composable
fun LedgerPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    testTag: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val clickable = enabled && !loading
    val background = if (clickable) BlueLedgerTokens.Primary else LedgerTints.disabledSurface()
    val contentColor = if (clickable) BlueLedgerTokens.Surface else BlueLedgerTokens.TextSecondary
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusButton)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BlueLedgerTokens.ButtonHeight)
            .shadow(
                elevation = if (clickable) BlueLedgerTokens.CardElevationRaised else 0.dp,
                shape = shape,
                clip = false,
            )
            .clip(shape)
            .background(background)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = clickable, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = BlueLedgerTokens.SpaceL, vertical = BlueLedgerTokens.SpaceM),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(LedgerComponentSizes.iconMedium),
                color = contentColor,
                strokeWidth = 2.dp,
            )
            Box(modifier = Modifier.size(BlueLedgerTokens.SpaceS))
        } else if (leadingIcon != null) {
            leadingIcon()
            Box(modifier = Modifier.size(BlueLedgerTokens.SpaceS))
        }
        Text(
            text = text,
            style = LedgerTextStyles.button,
            color = contentColor,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** 次要按钮：白底、主蓝描边与文字（S02「保存并再记」）。 */
@Composable
fun LedgerSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    testTag: String? = null,
) {
    val clickable = enabled && !loading
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusButton)
    val contentColor = if (clickable) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BlueLedgerTokens.ButtonHeight)
            .clip(shape)
            .background(BlueLedgerTokens.Surface)
            .border(
                width = LedgerComponentSizes.dividerThickness,
                color = if (clickable) BlueLedgerTokens.Border else LedgerTints.disabledSurface(),
                shape = shape,
            )
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = clickable, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = BlueLedgerTokens.SpaceM, vertical = BlueLedgerTokens.SpaceM),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(LedgerComponentSizes.iconSmall),
                color = contentColor,
                strokeWidth = 2.dp,
            )
            Box(modifier = Modifier.size(BlueLedgerTokens.SpaceXs))
        }
        Text(
            text = text,
            style = LedgerTextStyles.button,
            color = contentColor,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** 文本按钮：无底色，主蓝文字，触控区 ≥48dp。 */
@Composable
fun LedgerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String? = null,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = BlueLedgerTokens.SpaceM, vertical = BlueLedgerTokens.SpaceS),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = LedgerTextStyles.bodyStrong,
            color = if (enabled) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary,
            maxLines = 1,
        )
    }
}

/**
 * 分段控件（支出/收入、月度/年度、全部/支出/收入）。
 *
 * 选中段使用白色底 + 强调色文字 + 轻微阴影；未选中段文字为次文字色。
 * 每一段的触控高度 ≥48dp（高于原型的视觉高度，满足无障碍硬性要求）。
 */
@Composable
fun <T> LedgerSegmentedToggle(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    accent: (T) -> Color = { BlueLedgerTokens.Primary },
    testTags: (T) -> String? = { null },
    segmentedSemantics: String? = null,
) {
    val trackShape = RoundedCornerShape(BlueLedgerTokens.RadiusButton)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(trackShape)
            .background(BlueLedgerTokens.SegmentTrack)
            .padding(BlueLedgerTokens.SpaceXs)
            .then(
                if (segmentedSemantics != null) {
                    Modifier.semantics { contentDescription = segmentedSemantics }
                } else {
                    Modifier
                },
            ),
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val segmentColor = accent(option)
            val tag = testTags(option)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip + BlueLedgerTokens.SpaceXs))
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(BlueLedgerTokens.CardElevation, RoundedCornerShape(BlueLedgerTokens.RadiusChip + BlueLedgerTokens.SpaceXs), clip = false)
                                .background(BlueLedgerTokens.Surface)
                        } else {
                            Modifier.background(Color.Transparent)
                        },
                    )
                    .then(if (tag != null) Modifier.testTag(tag) else Modifier)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    )
                    .padding(horizontal = BlueLedgerTokens.SpaceS, vertical = BlueLedgerTokens.SpaceXs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = if (isSelected) LedgerTextStyles.button else LedgerTextStyles.bodyStrong,
                    color = if (isSelected) segmentColor else BlueLedgerTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 分类图标圆底。选中态为主蓝实心 + 白色图形；未选中为浅蓝底 + 主蓝图形。 */
@Composable
fun LedgerCategoryIcon(
    iconKey: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    diameter: Dp = LedgerComponentSizes.categoryIconCircle,
    archived: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(
                when {
                    archived -> BlueLedgerTokens.Border
                    selected -> BlueLedgerTokens.Primary
                    else -> BlueLedgerTokens.Background
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LedgerIcons.category(iconKey),
            contentDescription = null,
            tint = when {
                archived -> BlueLedgerTokens.TextSecondary
                selected -> BlueLedgerTokens.Surface
                else -> BlueLedgerTokens.TextPrimary
            },
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

/**
 * 分类项：图标 + 名称，整项触控区 ≥48dp。
 * 选中态同时使用实心圆底、蓝色边框、勾选角标与加粗文字，不只依赖色深。
 */
@Composable
fun LedgerCategoryCell(
    name: String,
    iconKey: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    archived: Boolean = false,
    testTag: String? = null,
) {
    val cellShape = RoundedCornerShape(BlueLedgerTokens.RadiusInput)
    Column(
        modifier = modifier
            .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
            .clip(cellShape)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = BlueLedgerTokens.SpaceS, horizontal = BlueLedgerTokens.SpaceXs)
            .semantics {
                stateDescription = when {
                    archived -> "已归档"
                    selected -> "已选中"
                    else -> "未选中"
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Box {
            LedgerCategoryIcon(iconKey = iconKey, selected = selected, archived = archived, diameter = 50.dp)
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(BlueLedgerTokens.Primary)
                        .border(1.5.dp, BlueLedgerTokens.Surface, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = BlueLedgerTokens.Surface,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        Text(
            text = name,
            style = LedgerTextStyles.categoryLabel,
            color = when {
                archived -> BlueLedgerTokens.TextSecondary
                selected -> BlueLedgerTokens.Primary
                else -> BlueLedgerTokens.TextPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 账户类型图标圆底（S09：浅蓝圆底 + 主蓝图形）。 */
@Composable
fun LedgerAccountIcon(
    kind: AccountKind,
    modifier: Modifier = Modifier,
    diameter: Dp = LedgerComponentSizes.categoryIconCircle,
    selected: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(diameter)
            .clip(CircleShape)
            .background(if (selected) BlueLedgerTokens.Primary else BlueLedgerTokens.PrimarySoft),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = LedgerIcons.accountKindIcon(kind),
            contentDescription = LedgerIcons.accountKindLabel(kind),
            tint = if (selected) BlueLedgerTokens.Surface else BlueLedgerTokens.Primary,
            modifier = Modifier.size(diameter * 0.5f),
        )
    }
}

/**
 * 进度条：宽度最多 100%，超支不溢出；[fraction] 为 null 表示未设置（不绘制）。
 *
 * [testTag] 打在轨道节点、[fillTestTag] 打在填充条节点，便于验收「125% 时满而不溢出」
 * 这类判据（S08）。
 */
@Composable
fun LedgerProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    color: Color = BlueLedgerTokens.Primary,
    trackColor: Color = BlueLedgerTokens.Border,
    height: Dp = LedgerComponentSizes.progressTrackHeight,
    testTag: String? = null,
    fillTestTag: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .clip(CircleShape)
            .background(trackColor)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
    ) {
        if (fraction != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxSize()
                    .clip(CircleShape),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(color)
                        .then(if (fillTestTag != null) Modifier.testTag(fillTestTag) else Modifier),
                )
            }
        }
    }
}
