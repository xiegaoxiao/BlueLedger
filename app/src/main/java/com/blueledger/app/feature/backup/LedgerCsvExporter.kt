package com.blueledger.app.feature.backup

import com.blueledger.app.core.model.CsvScope
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.TransactionType
import java.time.YearMonth

/**
 * 账单 CSV 导出（S11「导出账单 CSV」）。
 *
 * 规范来源：PRD §5 S11「CSV 规范」与 docs/AI开发提示词.md §13「文件与恢复」。
 *
 * - 列为 日期、收支类型、金额（元）、分类、账户、备注；**每笔一行**。
 * - 金额使用**正数**、两位小数、不带千分位；收支方向由「收支类型」独立列表达。
 *   不带千分位是有意的：`1,234.56` 在 CSV 里必须加引号才不裂列，而金额列是程序生成的，
 *   保持纯数字最不容易被表格软件误判。
 * - UTF-8 带 BOM，行分隔符 CRLF（RFC 4180 与 Excel 惯例）。
 * - 只导出**有效账单**（deletedAt == null）；金额隐藏设置不影响用户主动发起的导出。
 * - 转义与公式防护是**两个独立步骤**：
 *   1. 公式防护：单元格若以 `= + - @` 开头（允许前置空白/Tab/CR 之后再出现）则前缀 `'`，
 *      使其不再被表格软件当作公式求值；
 *   2. RFC 4180 转义：内容含逗号、双引号或 CR/LF 时用双引号包裹，内部双引号加倍。
 *   金额列由程序生成为纯数字，永远不会触发公式防护。
 */
object LedgerCsvExporter {

    /** UTF-8 BOM：让 Excel/WPS 等直接识别为 UTF-8，避免中文乱码。 */
    const val BOM: String = "\uFEFF"

    /** RFC 4180 行分隔符。 */
    const val ROW_SEPARATOR: String = "\r\n"

    val HEADERS: List<String> = listOf("日期", "收支类型", "金额（元）", "分类", "账户", "备注")

    /** 一次导出的结果。[rowCount] 不含表头。 */
    data class CsvExport(
        val text: String,
        val rowCount: Int,
        val scope: CsvScope,
        val month: YearMonth?,
    ) {
        val scopeLabel: String get() = scopeLabel(scope, month)
    }

    /**
     * 生成 CSV 文本。[scope] 为 [CsvScope.CURRENT_MONTH] 时必须提供 [month]。
     *
     * 行顺序与账单列表一致：occurredOn 降序 → createdAt 降序 → id 升序。
     */
    fun build(snapshot: LedgerSnapshot, scope: CsvScope, month: YearMonth? = null): CsvExport {
        require(scope != CsvScope.CURRENT_MONTH || month != null) { "当前月导出必须提供月份" }

        val rows = snapshot.transactions
            .asSequence()
            .filter { it.deletedAt == null }
            .filter { scope == CsvScope.ALL || com.blueledger.app.core.model.LedgerPeriods.monthOf(it.occurredOn, snapshot.advanced.monthStartDay) == month }
            .sortedWith(
                compareByDescending<LedgerTransaction> { it.occurredOn }
                    .thenByDescending { it.createdAt }
                    .thenBy { it.id },
            )
            .toList()

        val categoryNames = snapshot.categories.associate { it.id to it.name }
        val accountNames = snapshot.accounts.associate { it.id to it.name }

        val builder = StringBuilder(64 + rows.size * 64)
        builder.append(BOM)
        builder.append(renderRow(HEADERS))
        for (transaction in rows) {
            builder.append(
                renderRow(
                    listOf(
                        transaction.occurredOn.toString(),
                        typeLabel(transaction.type),
                        formatAmount(transaction.amountCent),
                        categoryNames[transaction.categoryId].orEmpty(),
                        accountNames[transaction.accountId].orEmpty(),
                        transaction.note,
                    ),
                ),
            )
        }
        return CsvExport(
            text = builder.toString(),
            rowCount = rows.size,
            scope = scope,
            month = month,
        )
    }

    fun scopeLabel(scope: CsvScope, month: YearMonth?): String = when (scope) {
        CsvScope.CURRENT_MONTH -> "当前月（${month?.let(::monthLabel) ?: "—"}）"
        CsvScope.ALL -> "全部有效账单"
    }

    fun typeLabel(type: TransactionType): String = if (type.isIncome) "收入" else "支出"

    /** 分 → 元（两位小数、正数、无千分位）。只做整数拆分，不经过浮点。 */
    fun formatAmount(cent: Long): String {
        val negative = cent < 0L
        val abs = when {
            cent == Long.MIN_VALUE -> Long.MAX_VALUE
            negative -> -cent
            else -> cent
        }
        val yuan = abs / 100L
        val fen = abs % 100L
        return buildString(20) {
            if (negative) append('-')
            append(yuan)
            append('.')
            if (fen < 10L) append('0')
            append(fen)
        }
    }

    /** 一整行（含行分隔符）。 */
    fun renderRow(values: List<String>): String =
        values.joinToString(separator = ",", postfix = ROW_SEPARATOR) { escape(it) }

    /**
     * 公式防护 + RFC 4180 转义。两步互相独立，顺序固定：先防护，再按转义规则包裹。
     *
     * 金额列由程序生成为 `1234.56` 这类纯数字串，[needsFormulaGuard] 恒为 false，
     * 因此永远不会被改写。
     */
    fun escape(raw: String): String {
        val guarded = if (needsFormulaGuard(raw)) "'$raw" else raw
        if (!requiresQuoting(guarded)) return guarded
        val builder = StringBuilder(guarded.length + 8)
        builder.append('"')
        for (ch in guarded) {
            if (ch == '"') builder.append('"')
            builder.append(ch)
        }
        builder.append('"')
        return builder.toString()
    }

    /**
     * 是否会把该单元格当作公式：跳过前置空白（含空格、Tab、CR、LF、NBSP、BOM）后，
     * 首个可见字符是 `=`、`+`、`-`、`@` 之一。
     *
     * 纯空白单元格不算公式（没有任何可执行内容）。
     */
    fun needsFormulaGuard(raw: String): Boolean {
        val first = raw.firstOrNull { !it.isFormulaLeadingWhitespace() } ?: return false
        return first == '=' || first == '+' || first == '-' || first == '@'
    }

    fun requiresQuoting(value: String): Boolean =
        value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }

    private fun Char.isFormulaLeadingWhitespace(): Boolean =
        isWhitespace() || this == '\u00A0' || this == '\uFEFF'

    /** 展示用月份文案：`2026 年 10 月`。 */
    fun monthLabel(month: YearMonth): String = "${month.year} 年 ${month.monthValue} 月"
}
