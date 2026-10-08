package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * JSON 备份往返测试：证明正式备份格式能无损表达实体与设置，
 * 并且**不包含**软删除账单、**包含**全部归档配置（含无引用项）。
 */
class JsonBackupCodecRoundTripTest {

    private val clock = BackupTestClock()
    private val codec = JsonBackupCodec(clock)

    @Test
    fun `往返后账单 分类 账户 预算 设置一致`() {
        val source = BackupFx.snapshot()
        val decoded = codec.decode(codec.encode(source)).validOrNull()
        requireNotNull(decoded) { "合法备份必须解码成功" }
        val restored = decoded.validated.snapshot

        assertEquals(source.transactions.filter { it.deletedAt == null }, restored.transactions)
        assertEquals(source.categories, restored.categories)
        assertEquals(source.accounts, restored.accounts)
        assertEquals(source.budgets.sortedBy { it.yearMonth }, restored.budgets)
        assertEquals(
            source.settings.copy(lastBackupAt = null, lastBackupFileName = null),
            restored.settings,
        )
        assertEquals(source.exportedAt, restored.exportedAt)
        assertEquals(BackupLimits.BACKUP_SCHEMA_VERSION, decoded.summary.schemaVersion)
    }

    @Test
    fun `软删除账单不进入备份文件`() {
        val text = codec.encode(BackupFx.snapshot())
        assertFalse("备份文本不应出现软删除账单标题", text.contains(BackupFx.TX_DELETED))
        assertFalse(text.contains("已删除"))

        val restored = codec.decode(text).validOrNull()!!.validated.snapshot
        assertTrue(restored.transactions.none { it.id == BackupFx.TX_DELETED })
        assertTrue(restored.transactions.all { it.deletedAt == null })
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, restored.transactions.size)
    }

    @Test
    fun `归档分类与账户即使没有任何有效账单引用也会导出`() {
        val source = BackupFx.snapshot()
        val restored = codec.decode(codec.encode(source)).validOrNull()!!.validated.snapshot

        val categoryIds = restored.categories.map { it.id }.toSet()
        assertTrue("被历史账单引用的归档分类必须保留", BackupFx.CAT_ARCHIVED in categoryIds)
        assertTrue("无任何引用的归档分类也必须保留配置", BackupFx.CAT_UNUSED in categoryIds)

        val accountIds = restored.accounts.map { it.id }.toSet()
        assertTrue("被历史账单引用的归档账户必须保留", BackupFx.ACCOUNT_ARCHIVED in accountIds)
        assertTrue("无任何引用的归档账户也必须保留配置", BackupFx.ACCOUNT_UNUSED in accountIds)

        // 归档历史引用本身保持不变。
        val archivedRef = restored.transactions.first { it.id == BackupFx.TX_ARCHIVED_REF }
        assertEquals(BackupFx.CAT_ARCHIVED, archivedRef.categoryId)
        assertEquals(BackupFx.ACCOUNT_ARCHIVED, archivedRef.accountId)
        assertEquals(LocalDate.of(2026, 9, 30), archivedRef.occurredOn)
    }

    @Test
    fun `设置不写入最近备份时间，恢复后保留设备当前值`() {
        val text = codec.encode(BackupFx.snapshot())
        assertFalse(text.contains("lastBackupAt"))
        assertFalse(text.contains("lastBackupFileName"))
        assertFalse(text.contains("旧备份"))

        val restored = codec.decode(text).validOrNull()!!.validated.snapshot
        assertNull(restored.settings.lastBackupAt)
        assertNull(restored.settings.lastBackupFileName)
        assertEquals(BackupFx.ACCOUNT_WALLET, restored.settings.lastUsedAccountId)
        assertEquals(BackupFx.ACCOUNT_MAIN, restored.settings.defaultAccountId)
    }

    @Test
    fun `线格式符合冻结契约：顶层字段 金额分 ISO 日期 UTC 时间戳`() {
        val root = BackupJson.parse(codec.encode(BackupFx.snapshot()))

        assertEquals(BackupLimits.PRODUCT_ID, root.getValue("product").jsonPrimitive.content)
        assertEquals(
            BackupLimits.BACKUP_SCHEMA_VERSION,
            root.getValue("schemaVersion").jsonPrimitive.content.toInt(),
        )
        assertEquals("CNY", root.getValue("currency").jsonPrimitive.content)
        assertTrue(root.getValue("exportedAt").jsonPrimitive.content.endsWith("Z"))

        val transaction = root.transactionObjects()
            .first { it.idValue() == BackupFx.TX_FOOD }
        assertEquals("2850", transaction.getValue("amountCent").jsonPrimitive.content)
        assertEquals("2026-10-07", transaction.getValue("occurredOn").jsonPrimitive.content)
        assertTrue(transaction.getValue("createdAt").jsonPrimitive.content.endsWith("Z"))
        assertTrue(transaction.getValue("updatedAt").jsonPrimitive.content.endsWith("Z"))
        assertEquals("EXPENSE", transaction.getValue("type").jsonPrimitive.content)

        // 时间戳必须带 UTC 偏移，不能是含义不明的本地时间。
        assertEquals("2026-10-07T12:00:00Z", transaction.getValue("createdAt").jsonPrimitive.content)
    }

    @Test
    fun `解码摘要提供恢复确认所需的备份时间 记录数与覆盖范围`() {
        val decoded = codec.decode(codec.encode(BackupFx.snapshot())).validOrNull()!!
        val summary = decoded.summary

        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, summary.transactionCount)
        assertEquals(BackupFx.categories().size, summary.categoryCount)
        assertEquals(BackupFx.accounts().size, summary.accountCount)
        assertEquals(1, summary.budgetCount)
        assertEquals(LocalDate.of(2026, 9, 30), summary.earliestOccurredOn)
        assertEquals(BackupFx.TODAY, summary.latestOccurredOn)
        assertEquals(listOf(BackupFx.OCT_2026), summary.budgetMonths)
        assertEquals(clock.now(), summary.exportedAt)
        assertNull(summary.fileName)
    }

    @Test
    fun `仓库一致快照导出并回读，有效账单与归档配置一致`() = runTest {
        val repository = BackupFx.repository(clock)
        val snapshot = repository.exportConsistentSnapshot()

        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, snapshot.transactions.size)
        assertEquals(BackupFx.categories().size, snapshot.categories.size)
        assertEquals(BackupFx.accounts().size, snapshot.accounts.size)
        assertEquals(clock.now(), snapshot.exportedAt)

        val restored = codec.decode(codec.encode(snapshot)).validOrNull()!!.validated.snapshot
        assertEquals(snapshot.transactions, restored.transactions)
        assertEquals(snapshot.categories, restored.categories)
        assertEquals(snapshot.accounts, restored.accounts)
        assertEquals(snapshot.budgets, restored.budgets)
    }

    @Test
    fun `日期边界：闰年 2024-02-29 与跨年 2025-12-31 都能往返`() {
        val leap = BackupFx.snapshot().let { snapshot ->
            snapshot.copy(
                transactions = listOf(
                    snapshot.transactions.first { it.id == BackupFx.TX_FOOD }.copy(
                        id = "tx_leap",
                        occurredOn = LocalDate.of(2024, 2, 29),
                    ),
                    snapshot.transactions.first { it.id == BackupFx.TX_SALARY }.copy(
                        id = "tx_boundary",
                        occurredOn = LocalDate.of(2025, 12, 31),
                    ),
                ),
            )
        }
        val restored = codec.decode(codec.encode(leap)).validOrNull()!!.validated.snapshot
        assertEquals(listOf(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 12, 31)), restored.transactions.map { it.occurredOn })
    }
}
