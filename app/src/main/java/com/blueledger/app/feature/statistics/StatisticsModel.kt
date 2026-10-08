package com.blueledger.app.feature.statistics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.model.CategorySlice
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.YearAnalysis
import com.blueledger.app.core.money.Money
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * A4（S05/S06）的纯展示模型与坐标转换规则。
 *
 * 纪律：
 * - **不重新聚合**：金额总量、日趋势、分类切片全部来自仓库返回的
 *   `MonthAnalysis` / `YearAnalysis`；本文件只做「数据 → 展示/坐标」的转换。
 * - 图表角度/柱高允许用 Float，但 Float 结果绝不回写业务数据、也不作为统计口径。
 * - 占比直接用仓库的 `ratioPermille`（百分比 × 10），界面只做除以 10 的格式化，
 *   不在 UI 里另算一遍百分比（否则会与数据层四舍五入结果不一致）。
 */
object StatisticsText {

    /** 合并尾部分类的固定名称；与真实分类名「其他」必须区分（PRD S05）。 */
    const val MERGED_SLICE_NAME: String = "其余分类"

    /** 环图/图例中最多展示的真实分类数；超过时第 6 项固定为「其余分类」。 */
    const val MAX_RING_CATEGORIES: Int = 5

    fun money(cents: Long): String = Money.format(cents)

    /** 千分比（1000 = 100%）→ 一位小数百分比：`974` → `97.4%`。 */
    fun ratio(permille: Int): String = Money.formatPermille(permille)

    // ── 年月与日期 ──

    fun month(month: YearMonth): String = "${month.year} 年 ${month.monthValue} 月"

    fun year(year: Int): String = "$year 年"

    fun day(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日"

    fun fullDate(date: LocalDate): String = "${date.year} 年 ${date.monthValue} 月 ${date.dayOfMonth} 日"

    fun typeLabel(type: TransactionType): String = if (type == TransactionType.INCOME) "收入" else "支出"

    /** 年度统计的时间说明：当年「截至 YYYY 年 M 月 D 日」，完整历史年为「全年」。 */
    fun cutoffText(analysis: YearAnalysis): String = cutoffText(analysis.cutoff)

    /** 同上，直接接 `YearAnalysis.cutoff`（未到年份为 null → 「全年」）。 */
    fun cutoffText(cutoff: LocalDate?): String = cutoff?.let { "截至 ${fullDate(it)}" } ?: "全年"

    // ── 读数文案（含金额隐藏） ──

    fun amountText(cents: Long, hidden: Boolean, mask: String = MASK): String =
        if (hidden) mask else money(cents)

    fun ratioText(permille: Int, hidden: Boolean, mask: String = MASK): String =
        if (hidden) mask else ratio(permille)

    const val MASK: String = "••••"

    // ── 图表坐标轴 ──

    /** 坐标轴单位。大金额用「万元」缩写，摘要与明细仍保留准确金额（UI 说明 §S06）。 */
    enum class AxisUnit { YUAN, WAN }

    /**
     * 坐标轴刻度。`ticks` 从 0 到 [topCent]，[AxisTick.text] 是可直接渲染的轴标签。
     * 当 [hidden] 为 true 时标签全部替换为掩码——**网格线结构保留，数值不泄露**。
     */
    data class AxisScale(
        val topCent: Long,
        val ticks: List<Long>,
        val unit: AxisUnit,
    ) {
        val unitLabel: String get() = if (unit == AxisUnit.WAN) "万元" else "元"

        fun label(cent: Long, hidden: Boolean): String =
            if (hidden) MASK else StatisticsText.tickLabel(cent, unit)
    }

    /**
     * 由数据最大值推导「好看」的坐标轴：步长取 1/2/2.5/5 × 10^n，
     * 上限向上取整到步长整数倍。`maxCent <= 0` 时返回一条 0 基线，不产生 NaN。
     */
    fun axisScale(maxCent: Long, desiredTicks: Int = 4): AxisScale {
        val safeMax = maxCent.coerceAtLeast(0L)
        if (safeMax <= 0L) {
            return AxisScale(topCent = 0L, ticks = listOf(0L), unit = AxisUnit.YUAN)
        }
        val step = niceStep(safeMax, desiredTicks)
        val intervals = ((safeMax + step - 1L) / step).coerceAtLeast(1L)
        val top = intervals * step
        val ticks = (0..intervals).map { it * step }
        val unit = if (top >= WAN_THRESHOLD_CENT) AxisUnit.WAN else AxisUnit.YUAN
        return AxisScale(topCent = top, ticks = ticks, unit = unit)
    }

    /** 单个刻度标签：`0` / `1,000` / `1.5万`。 */
    fun tickLabel(cent: Long, unit: AxisUnit): String = when (unit) {
        AxisUnit.WAN -> if (cent == 0L) "0" else String.format(Locale.ROOT, "%.1f万", cent / 1_000_000.0)
        AxisUnit.YUAN -> yuanTickLabel(cent)
    }

    private fun yuanTickLabel(cent: Long): String =
        if (cent == 0L) "0"
        else if (cent >= SMALL_YUAN_THRESHOLD_CENT) Money.format(cent).removeSuffix(".00")
        else Money.format(cent)

    /** ≥ 10,000.00 元 使用「万元」轴。 */
    private const val WAN_THRESHOLD_CENT: Long = 1_000_000L

    /** ≥ 1,000.00 元的元轴用整数刻度，小金额保留两位小数（0.30 元不能被写成 0）。 */
    private const val SMALL_YUAN_THRESHOLD_CENT: Long = 100_000L

    private fun niceStep(maxCent: Long, desiredTicks: Int): Long {
        val tickCount = desiredTicks.coerceAtLeast(1)
        val target = (maxCent + tickCount - 1L) / tickCount
        var magnitude = 1L
        while (magnitude <= target / 10L) magnitude *= 10L
        for (multiplier in NICE_MULTIPLIERS) {
            val candidate = magnitude * multiplier
            if (candidate >= target) return candidate
        }
        return magnitude * 10L
    }

    private val NICE_MULTIPLIERS = listOf(1L, 2L, 5L, 10L)
}

/**
 * 分类配色：**固定蓝色深浅**，不为每个分类随机取色（AI 提示词 §9、UI 说明 S05）。
 * 「其余分类」是合并项而不是真实分类，因此用中性石板色，与任何真实分类（含名为「其他」的）都不同色。
 */
object CategoryPalette {

    private val shades: List<Color> = listOf(
        BlueLedgerTokens.Primary,
        BlueLedgerTokens.PrimaryDeep,
        lerp(BlueLedgerTokens.Primary, Color.White, 0.40f),
        lerp(BlueLedgerTokens.PrimaryDeep, Color.White, 0.52f),
        lerp(BlueLedgerTokens.Primary, Color.White, 0.22f),
        lerp(BlueLedgerTokens.PrimaryDeep, Color.White, 0.74f),
    )

    /** 合并项「其余分类」的固定色：与蓝色深浅序列区分开。 */
    val merged: Color = BlueLedgerTokens.TextSecondary

    val shadeCount: Int get() = shades.size

    fun shade(index: Int): Color = shades[Math.floorMod(index, shades.size)]

    fun colorOf(slice: RingSlice): Color = if (slice.merged) merged else shade(slice.shadeIndex)
}

/**
 * 环图/图例的一个切片。[merged] = true 表示这是合并出来的「其余分类」，
 * 它没有单一 categoryId，因此不可钻取。
 */
data class RingSlice(
    val categoryId: String?,
    val name: String,
    val amountCent: Long,
    val ratioPermille: Int,
    val merged: Boolean,
    val shadeIndex: Int,
) {
    val drillable: Boolean get() = categoryId != null && !merged
}

/**
 * 把仓库返回的**完整**分类切片合并成环图序列：前 [maxSlices] 类 + 「其余分类」。
 *
 * 完整排行**不**经过本函数（必须保留全部真实分类）。
 * 合并项金额是尾部各项金额之和，占比按同一口径重新四舍五入（不篡改真实分类金额来凑 100%）。
 */
fun buildRingSlices(
    slices: List<CategorySlice>,
    totalCent: Long,
    maxSlices: Int = StatisticsText.MAX_RING_CATEGORIES,
): List<RingSlice> {
    if (slices.isEmpty()) return emptyList()
    val limit = maxSlices.coerceAtLeast(1)
    if (slices.size <= limit) {
        return slices.mapIndexed { index, slice ->
            RingSlice(
                categoryId = slice.categoryId,
                name = slice.name,
                amountCent = slice.amountCent,
                ratioPermille = slice.ratioPermille,
                merged = false,
                shadeIndex = index,
            )
        }
    }
    val head = slices.take(limit).mapIndexed { index, slice ->
        RingSlice(
            categoryId = slice.categoryId,
            name = slice.name,
            amountCent = slice.amountCent,
            ratioPermille = slice.ratioPermille,
            merged = false,
            shadeIndex = index,
        )
    }
    val tail = slices.drop(limit)
    val tailAmount = tail.sumOf { it.amountCent }
    val mergedSlice = RingSlice(
        categoryId = null,
        name = StatisticsText.MERGED_SLICE_NAME,
        amountCent = tailAmount,
        ratioPermille = permilleOf(tailAmount, totalCent),
        merged = true,
        shadeIndex = -1,
    )
    return head + mergedSlice
}

/** 千分比四舍五入；总额 ≤ 0 时返回 0（绝不除零、不出 NaN）。 */
fun permilleOf(amountCent: Long, totalCent: Long): Int {
    if (totalCent <= 0L) return 0
    val scaled = amountCent * 1000L
    return ((scaled + totalCent / 2L) / totalCent).coerceIn(0L, 1000L).toInt()
}

/**
 * 年图的一个月份列。
 *
 * [reached] = false 表示「未到月份」：不是 0，不计入月均分母，**不可钻取**
 * （与 `YearMonthAmount.reached` 完全同义，不做任何本地推算）。
 */
data class YearMonthColumn(
    val month: Int,
    val incomeCent: Long,
    val expenseCent: Long,
    val reached: Boolean,
    val hasRecords: Boolean,
) {
    val balanceCent: Long get() = incomeCent - expenseCent
    val drillable: Boolean get() = reached
    val maxCent: Long get() = maxOf(incomeCent, expenseCent)
}

/** 由 [YearAnalysis.months] 生成恒 12 项、1—12 月顺序的列（缺失项按「未到」处理，不伪造 0 记录月）。 */
fun buildYearColumns(analysis: YearAnalysis): List<YearMonthColumn> =
    (1..12).map { month ->
        val item = analysis.months.firstOrNull { it.month == month }
        YearMonthColumn(
            month = month,
            incomeCent = item?.incomeCent ?: 0L,
            expenseCent = item?.expenseCent ?: 0L,
            reached = item?.reached ?: false,
            hasRecords = item?.hasRecords ?: false,
        )
    }

/** S05 月度面板的展示数据（全部字段派生自仓库返回的 [MonthAnalysis]）。 */
data class MonthPanel(
    val month: YearMonth,
    val type: TransactionType,
    val summary: MoneySummary,
    val typeTotalCent: Long,
    val daily: List<DailyAmount>,
    val slices: List<CategorySlice>,
    val ringSlices: List<RingSlice>,
    val periodEnd: LocalDate?,
) {
    val incomeCent: Long get() = summary.incomeCent
    val expenseCent: Long get() = summary.expenseCent
    val count: Int get() = summary.count
    val balanceCent: Long get() = summary.balanceCent

    /** 当前类型总额为 0：显示空心占位与「本月暂无…」，绝不绘制假 100% 环。 */
    val hasTypeData: Boolean get() = typeTotalCent > 0L

    /** 统计区间已到的天数（当月到今日、历史月整月、未来月 0）。 */
    val daysInPeriod: Int get() = periodEnd?.dayOfMonth ?: 0

    /** 日均（当前类型）；只在区间内有天数时给出，避免除零。 */
    val averageDailyCent: Long? get() = if (daysInPeriod <= 0) null else typeTotalCent / daysInPeriod

    val maxDailyCent: Long get() = daily.maxOfOrNull { it.amountCent } ?: 0L

    fun emptyText(): String =
        if (type == TransactionType.INCOME) "本月暂无收入" else "本月暂无支出"
}

/** S06 年度面板的展示数据（全部字段派生自仓库返回的 [YearAnalysis]）。 */
data class YearPanel(
    val year: Int,
    val summary: MoneySummary,
    val columns: List<YearMonthColumn>,
    val cutoff: LocalDate?,
    val isCurrentYear: Boolean,
    val reachedMonthCount: Int,
) {
    val incomeCent: Long get() = summary.incomeCent
    val expenseCent: Long get() = summary.expenseCent
    val count: Int get() = summary.count
    val balanceCent: Long get() = summary.balanceCent

    /** 已到月份的月均支出；分母用 [reachedMonthCount]（含已到但无记录的月份），分母为 0 时为 null。 */
    val averageMonthlyExpenseCent: Long?
        get() = if (reachedMonthCount <= 0) null else expenseCent / reachedMonthCount

    val maxMonthlyCent: Long get() = columns.maxOfOrNull { it.maxCent } ?: 0L

    val hasAnyRecords: Boolean get() = columns.any { it.reached && it.hasRecords }
}

/** 稳定测试标识：只新增不修改，供 A7 与 Compose 测试定位。 */
object StatisticsTags {

    const val SCREEN = "statistics_screen"
    const val TAB_MONTH = "stats_tab_month"
    const val TAB_YEAR = "stats_tab_year"

    const val MONTH_LABEL = "stats_month_label"
    const val PREV_MONTH = "btn_stats_prev_month"
    const val NEXT_MONTH = "btn_stats_next_month"
    const val MONTH_PICKER = "dialog_month_picker"
    fun monthOption(month: YearMonth): String = "month_option_${month.year}_${month.monthValue}"

    const val YEAR_LABEL = "stats_year_label"
    const val PREV_YEAR = "btn_stats_prev_year"
    const val NEXT_YEAR = "btn_stats_next_year"
    const val YEAR_PICKER = "dialog_year_picker"
    fun yearOption(year: Int): String = "year_option_$year"

    const val TYPE_EXPENSE = "stats_type_expense"
    const val TYPE_INCOME = "stats_type_income"

    const val SUMMARY_CARD = "stats_summary_card"
    const val SUMMARY_COUNT = "stats_summary_count"
    const val MONTH_AVERAGE = "stats_month_average"

    const val RING_CHART = "chart_ring"
    const val RING_EMPTY = "chart_ring_empty"
    const val RING_CENTER_TOTAL = "ring_center_total"
    fun legendRow(categoryId: String): String = "ring_legend_$categoryId"
    const val LEGEND_MERGED = "ring_legend_merged"

    const val TREND_CHART = "chart_daily_trend"
    const val TREND_READOUT = "chart_daily_readout"
    const val TREND_HINT = "chart_daily_hint"
    fun dayDetailRow(day: Int): String = "stats_day_detail_$day"

    const val RANKING_TITLE = "stats_ranking_title"
    fun rankingRow(categoryId: String): String = "stats_rank_$categoryId"

    const val YEAR_SUMMARY_CARD = "stats_year_summary"
    const val YEAR_CUTOFF = "stats_year_cutoff"
    const val YEAR_AVERAGE = "stats_year_average"
    const val YEAR_CHART = "chart_year_bars"
    const val YEAR_CHART_HINT = "chart_year_hint"
    const val YEAR_LEGEND_EXPENSE = "year_legend_expense"
    const val YEAR_LEGEND_INCOME = "year_legend_income"
    fun yearColumn(month: Int): String = "year_column_$month"
    fun yearMonthRow(month: Int): String = "year_month_row_$month"
    fun yearMonthBills(month: Int): String = "year_month_bills_$month"

    const val EMPTY_STATE = "stats_empty"
    const val SELECTED_MONTH_CARD = "stats_selected_month_card"
    const val SELECTED_MONTH_BILLS = "btn_selected_month_bills"

    const val LOADING = "stats_loading"
    const val ERROR = "stats_error"
    const val ERROR_RETRY = "stats_error_retry"

    const val ENTRY_ACTION = "stats_entry_action"
}
