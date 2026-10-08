package com.blueledger.app.core.time

import com.blueledger.app.core.contract.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 生产时钟：读取设备当前时间与**当前**系统时区。
 *
 * 每次 [zoneId] 都重新读取 [ZoneId.systemDefault]，不缓存首次启动的时区；
 * 设备在运行中切换时区时，新的“今天”立即生效，但已经保存的 occurredOn 不会被改写。
 */
class SystemClock : Clock {

    override fun now(): Instant = Instant.now()

    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

/**
 * 固定时钟：测试、预览与夹具使用。
 *
 * 默认冻结在验收夹具时间 2026-10-07T00:00:00+08:00[Asia/Shanghai]。
 * 生产逻辑不得引用本类。
 */
class FixedClock(
    private var instant: Instant = DEFAULT_INSTANT,
    private var zone: ZoneId = DEFAULT_ZONE,
) : Clock {

    override fun now(): Instant = instant

    override fun zoneId(): ZoneId = zone

    /** 修改当前瞬时（测试推进时间）。 */
    fun setInstant(value: Instant) {
        instant = value
    }

    /** 向前推进若干毫秒（例如验证 5 秒撤销窗口过期）。 */
    fun advanceMillis(millis: Long) {
        instant = instant.plusMillis(millis)
    }

    /** 修改当前时区（例如验证时区变化不影响已存 occurredOn）。 */
    fun setZone(value: ZoneId) {
        zone = value
    }

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 2026-10-07 00:00 Asia/Shanghai。 */
        val DEFAULT_INSTANT: Instant =
            LocalDate.of(2026, 10, 7).atStartOfDay(DEFAULT_ZONE).toInstant()

        /** 夹具当天。 */
        val FIXTURE_TODAY: LocalDate = LocalDate.of(2026, 10, 7)

        /** 夹具当月。 */
        val FIXTURE_MONTH: YearMonth = YearMonth.of(2026, 10)

        /** 按“本地日期 + 时区”构造固定时钟（当天 00:00）。 */
        fun atDate(date: LocalDate, zone: ZoneId = DEFAULT_ZONE): FixedClock =
            FixedClock(date.atStartOfDay(zone).toInstant(), zone)
    }
}
