package com.blueledger.app.core.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionType

/** 金额隐藏时的占位符号（不得再用透明文字隐藏后仍被 TalkBack 读出）。 */
const val LEDGER_HIDDEN_AMOUNT_MASK: String = "••••"

/**
 * 金额文本：等宽数字、统一两位小数、收入带「+」、支出带「−」。
 *
 * [hidden] 为 null 时读取 [LocalHideAmounts]（总控提供的统一金额隐藏开关）；
 * 隐藏时同时替换显示文本与语义描述，避免屏幕阅读器泄露金额。
 */
@Composable
fun LedgerAmountText(
    cents: Long,
    modifier: Modifier = Modifier,
    isIncome: Boolean = false,
    showSign: Boolean = false,
    hidden: Boolean? = null,
    style: TextStyle = LedgerTextStyles.rowAmount,
    color: Color? = null,
    testTag: String? = null,
    prefix: String? = null,
) {
    val hide = hidden ?: LocalHideAmounts.current
    val text = when {
        hide -> LEDGER_HIDDEN_AMOUNT_MASK
        showSign -> LedgerMoney.formatSigned(isIncome, cents)
        prefix != null -> prefix + LedgerMoney.format(cents)
        else -> LedgerMoney.format(cents)
    }
    val tint = color ?: if (isIncome) BlueLedgerTokens.Income else BlueLedgerTokens.Primary
    Text(
        text = text,
        style = style,
        color = tint,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = modifier
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .semantics {
                contentDescription = if (hide) {
                    "金额已隐藏"
                } else {
                    (if (isIncome) "收入 " else "支出 ") + LedgerMoney.format(cents) + " 元"
                }
            },
    )
}

/**
 * 深蓝摘要卡：结余主视觉 + 收入/支出并列。
 * 只负责展示，数值必须来自仓库（禁止在 UI 里另算一套口径）。
 */
@Composable
fun LedgerSummaryCard(
    label: String,
    summary: MoneySummary,
    modifier: Modifier = Modifier,
    incomeLabel: String = "本期收入",
    expenseLabel: String = "本期支出",
    hidden: Boolean? = null,
    testTag: String? = null,
    compact: Boolean = false,
) {
    val hide = hidden ?: LocalHideAmounts.current
    if (compact) {
        Row(
            modifier = modifier.fillMaxWidth()
                .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
                .semantics(mergeDescendants = true) {
                    contentDescription = if (hide) "$label 金额已隐藏" else
                        "$label ${LedgerMoney.format(summary.balanceCent)} 元，" +
                            "$incomeLabel ${LedgerMoney.format(summary.incomeCent)} 元，" +
                            "$expenseLabel ${LedgerMoney.format(summary.expenseCent)} 元"
                }.padding(vertical = BlueLedgerTokens.SpaceL),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        ) {
            listOf(
                Triple(incomeLabel, summary.incomeCent, BlueLedgerTokens.TextPrimary),
                Triple(expenseLabel, summary.expenseCent, BlueLedgerTokens.TextPrimary),
                Triple(label, summary.balanceCent, if (summary.balanceCent < 0) BlueLedgerTokens.Risk else BlueLedgerTokens.Primary),
            ).forEach { (title, cents, color) ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS)) {
                    Text(title, style = LedgerTextStyles.caption, color = BlueLedgerTokens.TextSecondary)
                    Text(if (hide) LEDGER_HIDDEN_AMOUNT_MASK else LedgerMoney.format(cents),
                        style = LedgerTextStyles.rowAmount, color = color, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
        return
    }
    val cardShape = RoundedCornerShape(BlueLedgerTokens.RadiusCard)
    val balanceColor = when {
        hide -> BlueLedgerTokens.Surface
        summary.balanceCent < 0 -> BlueLedgerTokens.Risk
        else -> BlueLedgerTokens.Surface
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(BlueLedgerTokens.Primary, BlueLedgerTokens.PrimaryDeep),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            )
            .drawBehind {
                drawCircle(
                    color = BlueLedgerTokens.Surface.copy(alpha = 0.07f),
                    radius = size.width * 0.58f,
                    center = Offset(size.width * 1.07f, -size.height * 0.12f),
                )
            }
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            // 整张卡作为一个语义单元：TalkBack 一次读出「结余 / 收入 / 支出」，
            // 而不是把每个数字拆成互不相关的片段。金额隐藏时读「金额已隐藏」。
            .semantics(mergeDescendants = true) {
                contentDescription = if (hide) {
                    "$label 金额已隐藏"
                } else {
                    "$label ${LedgerMoney.format(summary.balanceCent)} 元，" +
                        "$incomeLabel ${LedgerMoney.format(summary.incomeCent)} 元，" +
                        "$expenseLabel ${LedgerMoney.format(summary.expenseCent)} 元"
                }
            }
            .padding(horizontal = BlueLedgerTokens.CardPadding, vertical = BlueLedgerTokens.SpaceXxl),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
    ) {
        Text(
            text = label,
            style = LedgerTextStyles.summaryLabel,
            color = BlueLedgerTokens.Surface.copy(alpha = 0.82f),
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "¥",
                style = LedgerTextStyles.amountPrefix,
                color = balanceColor.copy(alpha = 0.9f),
                modifier = Modifier.padding(end = BlueLedgerTokens.SpaceXs, bottom = BlueLedgerTokens.SpaceXs),
            )
            Text(
                text = if (hide) LEDGER_HIDDEN_AMOUNT_MASK else LedgerMoney.format(summary.balanceCent),
                style = LedgerTextStyles.heroAmount,
                color = balanceColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 1.dp)
                .background(BlueLedgerTokens.Surface.copy(alpha = 0.18f)),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXl),
        ) {
            SummarySideValue(label = incomeLabel, isIncome = true, cents = summary.incomeCent, hidden = hide, modifier = Modifier.weight(1f))
            SummarySideValue(label = expenseLabel, isIncome = false, cents = summary.expenseCent, hidden = hide, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummarySideValue(
    label: String,
    isIncome: Boolean,
    cents: Long,
    hidden: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
        Text(
            text = (if (isIncome) "＋ " else "－ ") + label,
            style = LedgerTextStyles.summaryLabel,
            color = BlueLedgerTokens.Surface.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (hidden) LEDGER_HIDDEN_AMOUNT_MASK else LedgerMoney.format(cents),
            style = LedgerTextStyles.summaryValue,
            color = if (hidden) BlueLedgerTokens.Surface else if (isIncome) Color(0xFF7FE0C6) else BlueLedgerTokens.Surface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 账单行：左侧分类图标；中间分类/备注与辅助信息；右侧带符号两位小数金额。
 * 整行可点击（≥64dp），TalkBack 描述包含类型、分类、金额与日期。
 */
@Composable
fun LedgerTransactionRow(
    categoryName: String,
    iconKey: String,
    type: TransactionType,
    amountCent: Long,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    hideAmount: Boolean? = null,
    archived: Boolean = false,
    onClick: (() -> Unit)? = null,
    testTag: String? = null,
) {
    val hide = hideAmount ?: LocalHideAmounts.current
    val rowShape = RoundedCornerShape(BlueLedgerTokens.RadiusInput)
    val typeLabel = if (type.isIncome) "收入" else "支出"
    val rowModifier = modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = BlueLedgerTokens.ListRowMinHeight)
        .clip(rowShape)
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
        .then(
            if (onClick != null) {
                Modifier.clickable(
                    role = Role.Button,
                    onClickLabel = "$typeLabel $categoryName " +
                        (if (hide) "金额已隐藏" else LedgerMoney.format(amountCent) + " 元"),
                    onClick = onClick,
                )
            } else {
                Modifier
            },
        )
        .padding(horizontal = BlueLedgerTokens.SpaceS, vertical = BlueLedgerTokens.SpaceM)
        .semantics(mergeDescendants = true) {
            contentDescription = listOf(
                categoryName,
                typeLabel,
                if (hide) "金额已隐藏" else LedgerMoney.format(amountCent) + " 元",
                subtitle,
            ).filter { it.isNotBlank() }.joinToString("，")
        }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        LedgerCategoryIcon(iconKey = iconKey, selected = false, archived = archived)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = title,
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LedgerAmountText(
            cents = amountCent,
            isIncome = type.isIncome,
            showSign = true,
            hidden = hide,
            style = LedgerTextStyles.rowAmount,
        )
    }
}

/** 「标签 / 值」两栏行，用于详情与设置页。 */
@Composable
fun LedgerKeyValueRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = BlueLedgerTokens.TextPrimary,
    testTag: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = BlueLedgerTokens.MinTouchTarget)
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .padding(vertical = BlueLedgerTokens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = LedgerTextStyles.bodyStrong,
            color = valueColor,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** 空占位，保持接口稳定。 */
@Composable
internal fun LedgerSpacer(heightDp: androidx.compose.ui.unit.Dp) {
    Box(modifier = Modifier.size(heightDp))
}

/** 卡片投影辅助（供其他页面复用同一阴影规范）。 */
internal fun Modifier.ledgerCardShadow(shape: RoundedCornerShape): Modifier =
    this.shadow(BlueLedgerTokens.CardElevationRaised, shape, clip = false)
