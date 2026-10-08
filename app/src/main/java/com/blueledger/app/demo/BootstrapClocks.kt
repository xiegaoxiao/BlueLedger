package com.blueledger.app.demo

import com.blueledger.app.core.contract.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * G0 引导用时钟实现。
 *
 * 生产运行必须使用 A1 在 `core/time` 提供的 SystemClock；本类只用于 G0 阶段
 * 让工程骨架能编译、能跑通空界面。集成阶段 AppContainer 会切换到 A1 的实现，
 * 之后本文件删除。
 *
 * 关键点：每次读时区都重新调用 [ZoneId.systemDefault]，**不缓存**首次启动的时区，
 * 这样设备在运行中改时区不会被永久冻结。
 */
class BootstrapSystemClock : Clock {
    override fun now(): Instant = Instant.now()

    override fun zoneId(): ZoneId = ZoneId.systemDefault()
}

/**
 * 可推进的固定时钟，供演示/预览与不依赖 A1 的测试使用。
 * 默认冻结在验收夹具时间：2026-10-07T00:00:00+08:00[Asia/Shanghai]。
 */
class BootstrapFixedClock(
    private var instant: Instant = DEFAULT_INSTANT,
    private var zone: ZoneId = DEFAULT_ZONE,
) : Clock {
    override fun now(): Instant = instant

    override fun zoneId(): ZoneId = zone

    fun setInstant(value: Instant) {
        instant = value
    }

    fun setZone(value: ZoneId) {
        zone = value
    }

    override fun today(): LocalDate = instant.atZone(zone).toLocalDate()

    override fun currentYearMonth(): YearMonth = YearMonth.from(today())

    companion object {
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val DEFAULT_INSTANT: Instant = LocalDate.of(2026, 10, 7).atStartOfDay(DEFAULT_ZONE).toInstant()
    }
}
