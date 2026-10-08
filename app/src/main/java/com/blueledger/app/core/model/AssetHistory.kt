package com.blueledger.app.core.model

import java.time.LocalDate

/** 手动余额调整只改变资产基准，不伪造收支；历史图按调整的生效日期取值。 */
object AssetHistory {
    fun parse(raw: String): List<Pair<LocalDate, Long>> {
        if (raw.isEmpty()) return emptyList()
        require(raw.length <= 100_000)
        return raw.split(';').map {
            val pieces = it.split(':')
            require(pieces.size == 2)
            LocalDate.ofEpochDay(pieces[0].toLong()) to pieces[1].toLong().also { cent ->
                require(cent in -Limits.MAX_OPENING_BALANCE_CENT..Limits.MAX_OPENING_BALANCE_CENT)
            }
        }.also { points -> require(points.zipWithNext().all { (a, b) -> a.first < b.first }) }
    }

    fun record(raw: String, date: LocalDate, cent: Long): String =
        (parse(raw).filter { it.first != date } + (date to cent)).sortedBy { it.first }
            .joinToString(";") { "${it.first.toEpochDay()}:${it.second}" }

    fun openingAt(account: LedgerAccount, date: LocalDate): Long {
        val history = parse(account.openingHistory)
        return if (history.isEmpty()) account.openingBalanceCent
        else history.lastOrNull { !it.first.isAfter(date) }?.second ?: 0L
    }
}
