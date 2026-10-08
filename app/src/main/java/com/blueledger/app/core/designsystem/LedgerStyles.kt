package com.blueledger.app.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens

/**
 * 「蓝记」公共组件的文字样式与组件几何尺寸。
 *
 * 所有权说明（与总控约定）：
 * - **色值/间距/圆角/字号唯一来源是 `app/ui/AppTheme.kt` 的
 *   [BlueLedgerTokens]**，本文件不定义任何第二套色板；
 *   仅把字号组合成带字重与行高的 [TextStyle]，并按需派生透明底（不新增色值）。
 * - [LedgerComponentSizes] 只是组件内部几何（图标直径、键盘按键高度等），
 *   [BlueLedgerTokens] 未覆盖；若后续需要跨模块统一，再提交总控加入 AppTheme。
 */
object LedgerTextStyles {

    /** 摘要卡/详情主金额 36—40sp，tabular figures。 */
    val heroAmount: TextStyle = TextStyle(
        fontSize = BlueLedgerTokens.AmountHero,
        lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )

    /** S02 记账输入金额 36—40sp。 */
    val entryAmount: TextStyle = heroAmount

    /** 金额（28sp）。 */
    val largeAmount: TextStyle = TextStyle(
        fontSize = BlueLedgerTokens.AmountLarge,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )

    /** 列表行金额（17sp）。 */
    val rowAmount: TextStyle = TextStyle(
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )

    /** 页面标题 22—24sp。 */
    val pageTitle = TextStyle(
        fontSize = BlueLedgerTokens.PageTitle,
        lineHeight = 30.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 卡片标题 16—18sp。 */
    val cardTitle = TextStyle(
        fontSize = BlueLedgerTokens.CardTitle,
        lineHeight = 23.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 正文 14—16sp。 */
    val body = TextStyle(
        fontSize = BlueLedgerTokens.Body,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 强调正文。 */
    val bodyStrong = TextStyle(
        fontSize = BlueLedgerTokens.Body,
        lineHeight = 21.sp,
        fontWeight = FontWeight.Medium,
    )

    /** 表单标签。 */
    val fieldLabel = TextStyle(
        fontSize = BlueLedgerTokens.BodySmall,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
    )

    /** 按钮文字 16sp。 */
    val button = TextStyle(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 数字键盘按键文字。 */
    val keypadDigit = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )

    /** 辅助说明 12sp 起。 */
    val caption = TextStyle(
        fontSize = BlueLedgerTokens.Caption,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 分类名。 */
    val categoryLabel = TextStyle(
        fontSize = BlueLedgerTokens.Caption,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
    )

    /** 金额前缀「¥」。 */
    val amountPrefix = TextStyle(
        fontSize = BlueLedgerTokens.AmountLarge,
        lineHeight = 34.sp,
        fontWeight = FontWeight.SemiBold,
    )

    /** 深蓝摘要卡上的次级标签。 */
    val summaryLabel = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
    )

    /** 深蓝摘要卡上的次级金额。 */
    val summaryValue = TextStyle(
        fontSize = 16.sp,
        lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
    )
}

/** 组件内部几何尺寸（不涉及颜色，非全局 token）。 */
object LedgerComponentSizes {
    /** 图标按钮触控区。 */
    val iconButton = 44.dp

    /** 分类图标圆底直径（触控区仍为 48dp）。 */
    val categoryIconCircle = 44.dp

    /** 顶栏高度。 */
    val topBarHeight = 56.dp

    /** 数字键盘按键最小高度（≥48dp 触控要求）。 */
    val keypadKeyMinHeight = 56.dp

    val keypadGap = 8.dp
    val iconSmall = 18.dp
    val iconMedium = 22.dp
    val iconLarge = 26.dp
    val iconXLarge = 32.dp
    val dividerThickness = 1.dp
    val progressTrackHeight = 8.dp
    val emptyIllustration = 72.dp

    /** 金额最大长度（含千分位与小数点）——用于大字号下不裁切关键数字。 */
    val amountMaxLines = 1
}

/**
 * 带透明度的派生色。**不新增色值**：一律基于 [BlueLedgerTokens] 的
 * 十个 token 与 alpha 合成，避免出现第二套漂移的色板。
 */
object LedgerTints {
    /** 收入浅底（用于收入金额卡）。 */
    fun incomeSoft(): Color = BlueLedgerTokens.Income.copy(alpha = 0.10f)

    /** 风险浅底（错误横幅、删除确认底）。 */
    fun riskSoft(): Color = BlueLedgerTokens.Risk.copy(alpha = 0.10f)

    /** 数字键盘删除键底色。 */
    fun keyDeleteSurface(): Color = BlueLedgerTokens.Border

    /** 键盘区域底色。 */
    fun keypadBackground(): Color = BlueLedgerTokens.Background

    /** 未输入金额的占位色。 */
    fun amountPlaceholder(): Color = BlueLedgerTokens.TextSecondary.copy(alpha = 0.45f)

    /** 不可用按钮底色。 */
    fun disabledSurface(): Color = BlueLedgerTokens.Border

    /** 卡片轻阴影。 */
    fun cardShadow(): Color = BlueLedgerTokens.PrimaryDeep.copy(alpha = 0.06f)
}

/**
 * 金额展示格式化（**仅展示**，禁止用于存储、累加或统计）。
 *
 * 业务口径的唯一实现是 core/money（A1）。本函数只把已经算好的 Long
 * 整数分渲染成「1,234.50」，供设计系统组件与尚未接入 core/money 的页面使用。
 */
object LedgerMoney {
    fun format(cents: Long): String {
        val negative = cents < 0L
        val abs = if (cents == Long.MIN_VALUE) Long.MAX_VALUE else if (negative) -cents else cents
        val yuan = abs / 100L
        val fraction = abs % 100L
        val grouped = groupThousands(yuan.toString())
        return buildString {
            if (negative) append('-')
            append(grouped)
            append('.')
            if (fraction < 10L) append('0')
            append(fraction)
        }
    }

    /** 带收支符号：支出「−」、收入「+」。原始金额始终为正整数分。 */
    fun formatSigned(isIncome: Boolean, cents: Long): String =
        (if (isIncome) "+" else "−") + format(cents)

    private fun groupThousands(digits: String): String {
        if (digits.length <= 3) return digits
        val sb = StringBuilder()
        val firstGroup = digits.length % 3
        if (firstGroup > 0) sb.append(digits, 0, firstGroup)
        var index = firstGroup
        while (index < digits.length) {
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(digits, index, index + 3)
            index += 3
        }
        return sb.toString()
    }
}
