package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.CsvScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSV 导出测试：范围、有效账单过滤、BOM、两位小数正数金额、
 * RFC 4180 转义，以及**与转义相互独立**的公式防护。
 */
class LedgerCsvExporterTest {

    private val snapshot = BackupFx.snapshot()

    @Test
    fun `表头与 BOM 正确，行数等于有效账单数加表头`() {
        val csv = LedgerCsvExporter.build(snapshot, CsvScope.ALL, BackupFx.OCT_2026)
        assertTrue("CSV 必须以 UTF-8 BOM 开头", csv.text.startsWith("\uFEFF"))
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, csv.rowCount)

        val rows = CsvParser.parse(csv.text)
        assertEquals(listOf("日期", "收支类型", "金额（元）", "分类", "账户", "备注"), rows.first())
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT + 1, rows.size)
    }

    @Test
    fun `只导出有效账单，软删除账单在任何范围都不出现`() {
        val all = LedgerCsvExporter.build(snapshot, CsvScope.ALL, BackupFx.OCT_2026)
        assertFalse("软删除账单不得导出", all.text.contains("已删除"))
        assertFalse(all.text.contains(BackupFx.TX_DELETED))
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, all.rowCount)

        val current = LedgerCsvExporter.build(snapshot, CsvScope.CURRENT_MONTH, BackupFx.OCT_2026)
        assertFalse(current.text.contains("已删除"))
        assertFalse(current.text.contains(BackupFx.TX_DELETED))
    }

    @Test
    fun `当前月范围只含当前月，全部范围含全部有效账单`() {
        val current = LedgerCsvExporter.build(snapshot, CsvScope.CURRENT_MONTH, BackupFx.OCT_2026)
        assertEquals(BackupFx.CURRENT_MONTH_TRANSACTION_COUNT, current.rowCount)
        assertFalse("9 月账单不应出现在当前月导出", current.text.contains("2026-09-30"))
        assertEquals("当前月（2026 年 10 月）", current.scopeLabel)

        val all = LedgerCsvExporter.build(snapshot, CsvScope.ALL, BackupFx.OCT_2026)
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, all.rowCount)
        assertTrue(all.text.contains("2026-09-30"))
        assertEquals("全部有效账单", all.scopeLabel)
    }

    @Test
    fun `金额列始终为正数两位小数且不带千分位`() {
        val rows = CsvParser.parse(
            LedgerCsvExporter.build(snapshot, CsvScope.ALL, BackupFx.OCT_2026).text,
        ).drop(1)

        // 排序与账单列表一致：发生日期降序 → 10-07、10-01、09-30
        assertEquals(listOf("28.50", "10000.00", "1.00"), rows.map { it[2] })
        assertEquals(listOf("支出", "收入", "支出"), rows.map { it[1] })
        assertTrue(rows.all { !it[2].startsWith("-") })
        assertTrue(rows.all { it[2].matches(Regex("\\d+\\.\\d{2}")) })

        // 金额列是程序生成的纯数字：不加引号、不加公式前缀。
        val firstDataLine = LedgerCsvExporter.build(snapshot, CsvScope.ALL, BackupFx.OCT_2026)
            .text.removePrefix(LedgerCsvExporter.BOM)
            .split(LedgerCsvExporter.ROW_SEPARATOR)[1]
        assertTrue("金额列不能被转义或防护改写：$firstDataLine", firstDataLine.contains(",28.50,"))
    }

    @Test
    fun `金额格式化只做整数拆分`() {
        assertEquals("0.01", LedgerCsvExporter.formatAmount(1L))
        assertEquals("0.10", LedgerCsvExporter.formatAmount(10L))
        assertEquals("12.50", LedgerCsvExporter.formatAmount(1_250L))
        assertEquals("10000.00", LedgerCsvExporter.formatAmount(1_000_000L))
        assertEquals("9999999.99", LedgerCsvExporter.formatAmount(999_999_999L))
    }

    @Test
    fun `转义与公式防护相互独立`() {
        assertEquals("普通文本", LedgerCsvExporter.escape("普通文本"))
        assertEquals("\"a,b\"", LedgerCsvExporter.escape("a,b"))
        assertEquals("\"他说\"\"你好\"\"\"", LedgerCsvExporter.escape("他说\"你好\""))
        assertEquals("\"'=1,2\"", LedgerCsvExporter.escape("=1,2"))
        assertEquals("'=SUM(A1:A2)", LedgerCsvExporter.escape("=SUM(A1:A2)"))
        // 正常金额文本不受公式防护影响。
        assertEquals("1234.56", LedgerCsvExporter.escape("1234.56"))
        assertEquals("28.50", LedgerCsvExporter.escape("28.50"))

        assertFalse(LedgerCsvExporter.needsFormulaGuard("1234.56"))
        assertFalse(LedgerCsvExporter.needsFormulaGuard(" 12"))
        assertFalse(LedgerCsvExporter.needsFormulaGuard("   "))
        assertFalse(LedgerCsvExporter.needsFormulaGuard(""))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("=1"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("+1"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("-1"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("@x"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard(" =1"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("\t=1"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("\r=1"))
        assertTrue(LedgerCsvExporter.needsFormulaGuard("\u00A0=1"))
    }

    @Test
    fun `公式起始备注被安全化且原文保留`() {
        val notes = listOf(
            "=SUM(A1:A2)",
            "+1+1",
            "-2-3",
            "@echo",
            " =1+1",
            "\t=cmd|x",
            "正常备注,带逗号",
        )
        val csv = LedgerCsvExporter.build(BackupFx.snapshotWithNotes(notes), CsvScope.ALL, BackupFx.OCT_2026)
        val rows = CsvParser.parse(csv.text).drop(1)

        assertEquals(
            listOf(
                "'=SUM(A1:A2)",
                "'+1+1",
                "'-2-3",
                "'@echo",
                "' =1+1",
                "'\t=cmd|x",
                "正常备注,带逗号",
            ),
            rows.map { it[5] },
        )
        // 原始文本没有丢失，只是被加上安全前缀。
        assertTrue(rows[0][5].removePrefix("'") == "=SUM(A1:A2)")
        // 没有任何字段以公式起始字符直接开头。
        assertFalse(csv.text.contains(",=SUM"))
        assertFalse(csv.text.contains(",+1"))
        assertFalse(csv.text.contains(",@echo"))
        // 金额列不受影响。
        assertEquals("28.50", rows[0][2])
    }

    @Test
    fun `备注中的换行与引号按 RFC4180 转义且不裂行`() {
        val note = "第一行\r\n第二行 \"引号\" 与,逗号"
        val csv = LedgerCsvExporter.build(BackupFx.snapshotWithNotes(listOf(note)), CsvScope.ALL, BackupFx.OCT_2026)
        val rows = CsvParser.parse(csv.text)

        assertEquals("换行被包在引号内，不应产生额外行", 2, rows.size)
        assertEquals(note, rows[1][5])
        assertTrue(csv.text.contains("\"第一行\r\n第二行 \"\"引号\"\" 与,逗号\""))
    }

    @Test
    fun `分类与账户列解析为当前名称`() {
        val rows = CsvParser.parse(
            LedgerCsvExporter.build(snapshot, CsvScope.ALL, BackupFx.OCT_2026).text,
        ).drop(1)
        // 10-07 晚餐：餐饮 / 钱包；09-30 历史账单：旧分类 / 旧账户（归档项仍按名称导出）
        assertEquals(listOf("餐饮", "工资", "旧分类"), rows.map { it[3] })
        assertEquals(listOf("钱包", "主账户", "旧账户"), rows.map { it[4] })
    }

    @Test
    fun `文件后缀与 MIME 正确`() {
        assertEquals(
            "蓝记账单-2026-10.csv",
            BackupFileNames.csvFileName(CsvScope.CURRENT_MONTH, BackupFx.OCT_2026, BackupFx.TODAY),
        )
        assertEquals(
            "蓝记账单-全部-2026-10-07.csv",
            BackupFileNames.csvFileName(CsvScope.ALL, null, BackupFx.TODAY),
        )
        assertEquals("text/csv", BackupFileNames.CSV_MIME_TYPE)
        assertEquals(
            "蓝记备份-2026-10-07.blueledger.json",
            BackupFileNames.backupFileName(BackupFx.TODAY),
        )
        assertTrue(BackupFileNames.backupFileName(BackupFx.TODAY).endsWith(BackupLimits.FILE_SUFFIX))
    }
}
