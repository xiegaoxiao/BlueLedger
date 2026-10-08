package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.CsvScope
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 备份/导出的建议文件名与时间文案。
 *
 * 文件名规则：
 * - 完整备份：`蓝记备份-2026-10-07.blueledger.json`（后缀取自冻结契约 [BackupLimits.FILE_SUFFIX]）。
 * - CSV：`蓝记账单-2026-10.csv` / `蓝记账单-全部-2026-10-07.csv`。
 *   使用 `.csv` 后缀与 `text/csv` MIME，避免表格软件把账单文件当成 JSON。
 */
object BackupFileNames {

    const val CSV_MIME_TYPE: String = "text/csv"

    fun backupFileName(today: LocalDate): String =
        "蓝记备份-$today" + BackupLimits.FILE_SUFFIX

    fun csvFileName(scope: CsvScope, month: YearMonth?, today: LocalDate): String = when (scope) {
        CsvScope.CURRENT_MONTH -> "蓝记账单-${month ?: today}.csv"
        CsvScope.ALL -> "蓝记账单-全部-$today.csv"
    }

    /** 界面展示的本地时间文案：`2026-10-07 09:41`。 */
    fun formatTimestamp(instant: Instant, zoneId: ZoneId): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zoneId).format(instant)

    fun uniqueExportFileName(suggested: String, instant: Instant, zoneId: ZoneId, token: String = UUID.randomUUID().toString().replace("-", "").take(12)): String {
        require(token.matches(Regex("[a-zA-Z0-9]{8,32}")))
        val suffix = if (suggested.endsWith(BackupLimits.FILE_SUFFIX)) BackupLimits.FILE_SUFFIX else ".csv"
        require(suggested.endsWith(suffix))
        val time = DateTimeFormatter.ofPattern("HHmmss").withZone(zoneId).format(instant)
        return suggested.removeSuffix(suffix) + "-$time-$token" + suffix
    }
}
