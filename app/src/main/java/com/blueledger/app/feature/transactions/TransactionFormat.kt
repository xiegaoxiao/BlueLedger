package com.blueledger.app.feature.transactions

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * 账单相关的中文展示格式（**只做展示，不参与任何金额计算**）。
 *
 * 归属说明：本文件位于 A3 自己的 feature/transactions 目录，被 feature/overview 复用，
 * 避免首页与账单页各写一套「月份 / 日期 / 日期时间」文案导致格式漂移。
 * 不定义任何色值与尺寸（那属于 app/ui/AppTheme.kt 的 BlueLedgerTokens）。
 *
 * 时间戳一律按**传入时区**渲染（调用方从注入的 Clock 取 zoneId），
 * 不使用系统默认时区，保证测试与生产一致。
 */
object TransactionFormat {

    /** 「2026 年 10 月」。 */
    fun monthLabel(month: YearMonth): String = "${month.year} 年 ${month.monthValue} 月"

    /** 「10 月 7 日」。 */
    fun monthDay(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日"

    /** 「星期三」。 */
    fun weekday(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.CHINA)

    /** 「10 月 7 日 星期三」——首页顶部的设备今日说明。 */
    fun dateWithWeekday(date: LocalDate): String = monthDay(date) + " " + weekday(date)

    /**
     * 日期分组标题：「10 月 7 日 · 今天」/「10 月 6 日 · 昨天」/「10 月 3 日 · 星期六」。
     * 只对设备今日与昨天使用相对文案，其余显示星期，避免出现需要实时刷新的「N 天前」。
     */
    fun dayGroupTitle(date: LocalDate, today: LocalDate): String {
        val suffix = when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> weekday(date)
        }
        return monthDay(date) + " · " + suffix
    }

    /** 「2026 / 10 / 07」——账单详情的发生日期。 */
    fun fullDate(date: LocalDate): String =
        "%04d / %02d / %02d".format(Locale.ROOT, date.year, date.monthValue, date.dayOfMonth)

    /**
     * 「2026 / 10 / 07 14:20」——创建时间/最近修改时间。
     * 绝不把创建时间当作发生日期展示（S04 明确要求两者分开）。
     */
    fun dateTime(instant: Instant, zone: ZoneId): String {
        val local = instant.atZone(zone)
        return fullDate(local.toLocalDate()) +
            " " +
            "%02d:%02d".format(Locale.ROOT, local.hour, local.minute)
    }

    /**
     * 账单行的副标题：「餐饮 · 默认账户 · 10 月 6 日」。
     * 备注为空时标题已经显示分类名，副标题不再重复分类名。
     */
    fun rowSubtitle(
        categoryName: String,
        accountName: String,
        date: LocalDate,
        noteIsBlank: Boolean,
    ): String {
        val parts = buildList {
            if (!noteIsBlank) add(categoryName)
            add(accountName)
            add(monthDay(date))
        }
        return parts.joinToString(" · ")
    }
}
