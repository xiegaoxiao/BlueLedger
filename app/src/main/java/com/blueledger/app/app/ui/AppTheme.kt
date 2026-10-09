package com.blueledger.app.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 蓝记设计令牌 —— **全项目唯一的色值/尺寸来源**。
 *
 * 所有权：总控维护本文件（主题入口与令牌）。A2 的 core/designsystem 公共组件
 * 从这里读取颜色与尺寸，不得再定义第二套色值；其他功能代理同理。
 * 取值来源：docs/UI设计说明.md §3.1 与 docs/AI开发提示词.md §9。
 */
object BlueLedgerTokens {
    // ── 颜色（docs §3.1 十个 token，一个不多一个不少）──
    val Primary = Color(0xFF2563EB)
    val PrimaryDeep = Color(0xFF123B8D)
    val PrimarySoft = Color(0xFFEFF5FF)
    val Background = Color(0xFFF6F8FC)
    val Surface = Color(0xFFFFFFFF)
    /** 饱和品牌色块上的文字、控件和装饰，统一从白色表面派生。 */
    val OnBrand = Surface
    val OnBrandSecondary = OnBrand.copy(alpha = .75f)
    val OnBrandSubtle = OnBrand.copy(alpha = .12f)
    val OnBrandOutline = OnBrand.copy(alpha = .6f)
    val TextPrimary = Color(0xFF142542)
    val TextSecondary = Color(0xFF62738D)
    val Border = Color(0xFFE2EAF4)
    val Income = Color(0xFF16856B)
    val Risk = Color(0xFFC34646)

    /** 结余为负时使用风险色；结余为正时使用主蓝/深蓝，不用收入色（结余不是收入）。 */
    val BalanceNegative = Risk

    /** 分段控件未选中态的底色与文字。 */
    val SegmentTrack = PrimarySoft

    // ── 圆角（docs §3.2）──
    val RadiusCard: Dp = 20.dp
    val RadiusInput: Dp = 14.dp
    val RadiusButton: Dp = 16.dp
    val RadiusChip: Dp = 12.dp

    // ── 间距（4dp 基础系统：8/12/16/20/24/32）──
    val SpaceXs: Dp = 4.dp
    val SpaceS: Dp = 8.dp
    val SpaceM: Dp = 12.dp
    val SpaceL: Dp = 16.dp
    val SpaceXl: Dp = 20.dp
    val SpaceXxl: Dp = 24.dp
    val SpaceHuge: Dp = 32.dp

    /** 页面左右留白 20dp，小屏可缩至 16dp。 */
    val PageHorizontal: Dp = 20.dp
    val PageHorizontalCompact: Dp = 16.dp

    /** 页面区块与卡片内部使用不同留白，避免把所有信息压成连续列表。 */
    val SectionGap: Dp = 24.dp
    val CardPadding: Dp = 20.dp
    val PageVertical: Dp = 20.dp

    /** 主按钮高度 52—56dp。 */
    val ButtonHeight: Dp = 54.dp
    val ButtonMinHeight: Dp = 52.dp

    /** 最小触控区域：48×48dp（硬性无障碍要求）。 */
    val MinTouchTarget: Dp = 48.dp

    /** 双行账单预留独立阅读空间，字号放大时仍允许自然增高。 */
    val ListRowMinHeight: Dp = 80.dp

    // ── 字号（docs §3.2）──
    val AmountHero: TextUnit = 38.sp
    val AmountLarge: TextUnit = 28.sp
    val AmountMedium: TextUnit = 20.sp
    val PageTitle: TextUnit = 23.sp
    val CardTitle: TextUnit = 17.sp
    val Body: TextUnit = 15.sp
    val BodySmall: TextUnit = 14.sp
    val Caption: TextUnit = 12.sp

    /** 卡片阴影：轻微、柔和，不用重阴影。 */
    val CardElevation: Dp = 1.dp
    val CardElevationRaised: Dp = 3.dp

    // 页面品牌头部与图标尺寸。
    val BrandShortcutOverlap: Dp = 20.dp
    val ShortcutIconContainer: Dp = 32.dp
    val ProfileAvatar: Dp = 54.dp
    val IconMedium: Dp = 24.dp
    val IconSmall: Dp = 20.dp
    val MenuRowMinHeight: Dp = 56.dp
    val SegmentHeight: Dp = 28.dp
    val RadiusSegment: Dp = 3.dp
    /** 按参考图 1240px 宽归一到 354dp，排除系统栏后校准的结构尺寸。 */
    val BrandTitleHeight: Dp = 44.dp
    val ReferenceEntryTitleHeight: Dp = 48.dp
    val ReferencePeriodHeight: Dp = 36.dp
    val ReferenceChartHeight: Dp = 108.dp
    const val ReferenceCategoryInsetFraction: Float = .071f
    val ProfileCheckHeight: Dp = 32.dp
    val ProfileCheckIcon: Dp = 18.dp
    val ProfileCount: TextUnit = 24.sp
    val ReferenceListIcon: Dp = 34.dp
    val ReferenceRankingVertical: Dp = 10.dp
    val ReferenceBillToolbarHeight: Dp = 50.dp
    val ReferenceBottomBarHeight: Dp = 50.dp
    val ReferenceRecordOverlap: Dp = 30.dp
    val ReferenceRecordOuter: Dp = 60.dp
    val ReferenceRecordInner: Dp = 48.dp
}

private val BlueLedgerLightColorScheme = lightColorScheme(
    primary = BlueLedgerTokens.Primary,
    onPrimary = Color.White,
    primaryContainer = BlueLedgerTokens.PrimarySoft,
    onPrimaryContainer = BlueLedgerTokens.PrimaryDeep,
    secondary = BlueLedgerTokens.PrimaryDeep,
    onSecondary = Color.White,
    secondaryContainer = BlueLedgerTokens.PrimarySoft,
    onSecondaryContainer = BlueLedgerTokens.PrimaryDeep,
    tertiary = BlueLedgerTokens.Income,
    onTertiary = Color.White,
    background = BlueLedgerTokens.Background,
    onBackground = BlueLedgerTokens.TextPrimary,
    surface = BlueLedgerTokens.Surface,
    onSurface = BlueLedgerTokens.TextPrimary,
    surfaceVariant = BlueLedgerTokens.PrimarySoft,
    onSurfaceVariant = BlueLedgerTokens.TextSecondary,
    outline = BlueLedgerTokens.Border,
    outlineVariant = BlueLedgerTokens.Border,
    error = BlueLedgerTokens.Risk,
    onError = Color.White,
    errorContainer = Color(0xFFFCEBEB),
    onErrorContainer = BlueLedgerTokens.Risk,
    scrim = Color(0x66000000),
)

/** 当前是否为金额隐藏状态。列表/统计/账户等敏感区域统一读取它，避免各处自行判断。 */
val LocalHideAmounts = staticCompositionLocalOf { false }

/**
 * 蓝记主题入口。本版只有蓝色浅色主题（深色主题明确不在范围内），
 * 因此刻意忽略 [isSystemInDarkTheme]：不因为系统开了深色就渲染未设计的界面。
 */
@Composable
fun BlueLedgerTheme(
    hideAmounts: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 明确不使用深色主题；形参保留是为了让调用点显式表达“本版恒为浅色”。
    @Suppress("UNUSED_EXPRESSION")
    isSystemInDarkTheme()

    CompositionLocalProvider(LocalHideAmounts provides hideAmounts) {
        MaterialTheme(
            colorScheme = BlueLedgerLightColorScheme,
            content = content,
        )
    }
}
