package com.blueledger.app.core.time

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/**
 * 日期范围与 epochDay 换算的唯一实现。
 *
 * 全项目的 occurredOn 都按“本地日历日期”处理：数据库中存 epochDay（Long），
 * 月份/年份归属直接由日期决定，与设备时区变化无关。
 *
 * 统计截止规则（PRD §6.3、AI 提示词 §4.3）：
 * - 当前月：1 日 → 今日；历史月：1 日 → 月末；未来月：无有效区间。
 * - 当前年：1 月 1 日 → 今日；历史年：1 月 1 日 → 12 月 31 日；未来年：无有效区间。
 */
object DateRanges {

    /** 允许的最早发生日期，早于它的日期视为非法（防御明显错误的数据）。 */
    val MIN_SUPPORTED_DATE: LocalDate = LocalDate.of(1900, 1, 1)

    /** 允许的最晚发生日期。 */
    val MAX_SUPPORTED_DATE: LocalDate = LocalDate.of(9999, 12, 31)

    // ───────────────────────── epochDay 换算 ─────────────────────────

    /** LocalDate → epochDay（LocalDate 本身就以 epochDay 为中心，这里统一出口）。 */
    fun epochDay(date: LocalDate): Long = date.toEpochDay()

    /** epochDay → LocalDate。 */
    fun dateOf(epochDay: Long): LocalDate = LocalDate.ofEpochDay(epochDay)

    /** 日期是否落在可支持的日历范围内。 */
    fun isSupportedDate(date: LocalDate): Boolean =
        !date.isBefore(MIN_SUPPORTED_DATE) && !date.isAfter(MAX_SUPPORTED_DATE)

    // ───────────────────────── 月区间 ─────────────────────────

    fun monthStart(month: YearMonth): LocalDate = month.atDay(1)

    fun monthEnd(month: YearMonth): LocalDate = month.atEndOfMonth()

    fun daysInMonth(month: YearMonth): Int = month.lengthOfMonth()

    /** 某月是否晚于 today 所在月。 */
    fun isFutureMonth(month: YearMonth, today: LocalDate): Boolean =
        month.isAfter(YearMonth.from(today))

    /**
     * 某月统计的截止日：
     * 未来月 → null；当前月 → today；历史月 → 月末。
     */
    fun periodEnd(month: YearMonth, today: LocalDate): LocalDate? {
        val currentMonth = YearMonth.from(today)
        return when {
            month.isAfter(currentMonth) -> null
            month == currentMonth -> today
            else -> monthEnd(month)
        }
    }

    /** [month] 的有效闭区间；未来月返回 null。 */
    fun monthRange(month: YearMonth, today: LocalDate): ClosedRange<LocalDate>? =
        periodEnd(month, today)?.let { monthStart(month)..it }

    /** [month] 的有效 epochDay 闭区间；未来月返回 null。 */
    fun monthEpochDayRange(month: YearMonth, today: LocalDate): LongRange? =
        monthRange(month, today)?.let { it.start.toEpochDay()..it.endInclusive.toEpochDay() }

    // ───────────────────────── 年区间 ─────────────────────────

    /**
     * 某年统计的截止日：未来年 → null；当前年 → today；历史年 → 12 月 31 日。
     */
    fun yearPeriodEnd(year: Int, today: LocalDate): LocalDate? = when {
        year > today.year -> null
        year == today.year -> today
        else -> LocalDate.of(year, 12, 31)
    }

    /** [year] 的有效 epochDay 闭区间；未来年返回 null。 */
    fun yearEpochDayRange(year: Int, today: LocalDate): LongRange? =
        yearPeriodEnd(year, today)?.let { LocalDate.of(year, 1, 1).toEpochDay()..it.toEpochDay() }

    /**
     * 月均分母：已经进入的月份数。
     * 当前年为 today 的月份序号（未到月份不计入）；历史年为 12；未来年为 0。
     */
    fun reachedMonthCount(year: Int, today: LocalDate): Int = when {
        year > today.year -> 0
        year == today.year -> today.monthValue
        else -> 12
    }

    /** 该年某月是否已经进入（reached）。 */
    fun isMonthReached(year: Int, month: Int, today: LocalDate): Boolean = when {
        year > today.year -> false
        year < today.year -> true
        else -> month <= today.monthValue
    }

    /** 包含首尾的完整日期序列（用于补零日趋势）。 */
    fun daysBetween(from: LocalDate, to: LocalDate): List<LocalDate> {
        if (to.isBefore(from)) return emptyList()
        val count = ChronoUnit.DAYS.between(from, to).toInt()
        return (0..count).map { from.plusDays(it.toLong()) }
    }
}

/**
 * 账单筛选使用的 epochDay 闭区间；任一端为 null 表示该方向不限制。
 *
 * 用语义化类型而不是 `Pair`/`LongRange`：筛选区间允许“只有下界”或“只有上界”，
 * 且读起来是 `fromInclusive / toInclusive`，不会误用 List 的 `last` 之类成员。
 */
data class EpochDayBounds(
    val fromInclusive: Long? = null,
    val toInclusive: Long? = null,
)
