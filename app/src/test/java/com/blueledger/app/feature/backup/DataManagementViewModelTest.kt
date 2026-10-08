package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.BackupDecodeResult
import com.blueledger.app.core.model.CsvScope
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.demo.InMemoryLedgerRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * S11 状态机测试：导出成功/失败/取消、恢复预览与确认、取消不报错、
 * 失败保留原库、操作中禁用重复操作。
 *
 * 全部走替身 [FakeBackupFileGateway]，不接触 Android 文件 API。
 */
class DataManagementViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = BackupTestClock()
    private val codec = JsonBackupCodec(clock)
    private lateinit var repository: InMemoryLedgerRepository
    private lateinit var files: FakeBackupFileGateway

    @Before
    fun setUp() {
        repository = BackupFx.repository(clock)
        files = FakeBackupFileGateway()
    }

    private fun viewModel(): DataManagementViewModel =
        DataManagementViewModel(repository, clock, codec, files)

    /** 准备导出 → 确认 → 消费系统对话框请求，返回建议文件名。 */
    private fun DataManagementViewModel.startExport(): String {
        onExportBackupRequested()
        onDialogConfirmed()
        val launch = requireNotNull(state.value.exportLaunch) { "应当请求系统文件保存对话框" }
        onExportPickerLaunched(launch.requestId)
        return launch.fileName
    }

    // ───────────────────────── 数据摘要 ─────────────────────────

    @Test
    fun `加载后展示本地数据摘要`() {
        val vm = viewModel()
        val state = vm.state.value

        assertFalse(state.loading)
        assertNull(state.loadError)
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, state.totalTransactionCount)
        assertEquals(BackupFx.CURRENT_MONTH_TRANSACTION_COUNT, state.currentMonthTransactionCount)
        assertEquals(1_000_000L, state.monthIncomeCent)
        assertEquals(2_850L, state.monthExpenseCent)
        assertEquals(BackupFx.categories().size, state.categoryCount)
        assertEquals(BackupFx.accounts().size, state.accountCount)
        assertEquals(2, state.activeAccountCount)
        // 夹具里本来就带着上一次成功备份时间（用于证明它不会被写进备份文件）。
        assertEquals(BackupFx.settings().lastBackupAt, state.backupRecord.succeededAt)
    }

    @Test
    fun `备份记录来自仓库：成功导出后刷新`() {
        val vm = viewModel()
        vm.startExport()
        files.displayNameValue = "蓝记备份-2026-10-07.blueledger.json"
        vm.onExportPickerResult("content://downloads/backup.json")

        val record = vm.state.value.backupRecord
        assertEquals(clock.now(), record.succeededAt)
        assertEquals("蓝记备份-2026-10-07.blueledger.json", record.fileName)
    }

    // ───────────────────────── 导出 ─────────────────────────

    @Test
    fun `完整备份导出前展示范围与记录数`() {
        val vm = viewModel()
        vm.onExportBackupRequested()

        val dialog = vm.state.value.dialog as DataManagementDialog.ExportConfirm
        assertEquals(ExportKind.BACKUP, dialog.kind)
        assertEquals(BackupFileNames.backupFileName(BackupFx.TODAY), dialog.fileName)
        assertTrue(dialog.lines.any { it.contains("有效账单 ${BackupFx.EFFECTIVE_TRANSACTION_COUNT} 笔") })
        assertTrue(dialog.lines.any { it.contains("分类 ${BackupFx.categories().size} 个") })
        assertTrue(dialog.lines.any { it.contains("归档") })
        // 确认前不产生任何写入。
        assertTrue(files.writes.isEmpty())
    }

    @Test
    fun `导出成功才写入文件并记录最近成功备份`() {
        val vm = viewModel()
        val suggested = vm.startExport()
        assertEquals(BackupFileNames.backupFileName(BackupFx.TODAY), suggested)

        files.displayNameValue = "我的账本.blueledger.json"
        vm.onExportPickerResult("content://downloads/my.json")

        val write = files.writes.single()
        assertEquals("content://downloads/my.json", write.reference)
        assertEquals(BackupLimits.MIME_TYPE, write.mimeType)
        // 真正写出去的内容是合法备份，且不含软删除账单。
        val decoded = codec.decode(write.text)
        assertTrue("写出的内容必须是合法备份", decoded is BackupDecodeResult.Valid)
        assertFalse(write.text.contains(BackupFx.TX_DELETED))

        assertEquals(clock.now(), vm.state.value.backupRecord.succeededAt)
        assertEquals("我的账本.blueledger.json", vm.state.value.backupRecord.fileName)
        assertEquals(BackupNoticeLevel.SUCCESS, vm.state.value.notice?.level)
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `写入失败绝不提示成功，也不更新成功备份时间`() {
        val vm = viewModel()
        val backupTimeBefore = vm.state.value.backupRecord.succeededAt
        files.writeFailure = LedgerError.Backup(
            code = ValidationCode.BACKUP_WRITE_FAILED,
            message = "写入文件失败，导出未完成：磁盘已满",
        )
        vm.startExport()
        vm.onExportPickerResult("content://downloads/full.json")

        val notice = requireNotNull(vm.state.value.notice)
        assertEquals(BackupNoticeLevel.ERROR, notice.level)
        assertTrue(notice.message.contains("磁盘已满"))
        assertEquals(BackupRetry.BACKUP_EXPORT, notice.retry)
        assertTrue("写入失败时不应有任何成功写入记录", files.writes.isEmpty())
        assertEquals(
            "写入失败不得更新最近成功备份时间",
            backupTimeBefore,
            vm.state.value.backupRecord.succeededAt,
        )
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `取消文件选择是正常取消：不显示失败也不更新成功时间`() {
        val vm = viewModel()
        val backupTimeBefore = vm.state.value.backupRecord.succeededAt
        vm.startExport()
        // 系统对话框返回 null = 用户取消。
        vm.onExportPickerResult(null)

        assertNull("取消不应显示任何失败提示", vm.state.value.notice)
        assertEquals("取消不得更新最近成功备份时间", backupTimeBefore, vm.state.value.backupRecord.succeededAt)
        assertTrue(files.writes.isEmpty())
        assertNull(vm.state.value.busy)
    }

    @Test
    fun `取消导出确认不写文件`() {
        val vm = viewModel()
        vm.onExportBackupRequested()
        vm.onDialogDismissed()

        assertNull(vm.state.value.dialog)
        assertNull(vm.state.value.exportLaunch)
        assertTrue(files.writes.isEmpty())
        assertTrue(vm.state.value.notice == null)
    }

    @Test
    fun `CSV 导出先选范围，两个范围各自显示记录数`() {
        val vm = viewModel()
        vm.onCsvExportRequested()

        val scopeDialog = vm.state.value.dialog as DataManagementDialog.CsvScopeChoice
        assertEquals("2026 年 10 月", scopeDialog.monthLabel)
        assertEquals(BackupFx.CURRENT_MONTH_TRANSACTION_COUNT, scopeDialog.currentMonthCount)
        assertEquals(BackupFx.EFFECTIVE_TRANSACTION_COUNT, scopeDialog.allCount)

        vm.onCsvScopeChosen(CsvScope.CURRENT_MONTH)
        val confirm = vm.state.value.dialog as DataManagementDialog.ExportConfirm
        assertEquals(ExportKind.CSV_CURRENT_MONTH, confirm.kind)
        assertTrue(confirm.lines.any { it.contains("当前月（2026 年 10 月）") })
        assertTrue(confirm.lines.any { it.contains("记录数：${BackupFx.CURRENT_MONTH_TRANSACTION_COUNT} 笔") })
    }

    @Test
    fun `CSV 导出按所选范围写文件并记录成功`() {
        val vm = viewModel()
        vm.onCsvExportRequested()
        vm.onCsvScopeChosen(CsvScope.CURRENT_MONTH)
        vm.onDialogConfirmed()

        val launch = requireNotNull(vm.state.value.exportLaunch)
        assertEquals(BackupFileNames.CSV_MIME_TYPE, launch.mimeType)
        assertTrue(launch.fileName.endsWith(".csv"))
        assertEquals("蓝记账单-2026-10.csv", launch.fileName)

        vm.onExportPickerLaunched(launch.requestId)
        vm.onExportPickerResult("content://downloads/csv")

        val write = files.writes.single()
        assertEquals(BackupFileNames.CSV_MIME_TYPE, write.mimeType)
        val rows = CsvParser.parse(write.text)
        assertEquals(BackupFx.CURRENT_MONTH_TRANSACTION_COUNT + 1, rows.size)
        assertEquals("蓝记账单-2026-10.csv", vm.state.value.backupRecord.fileName)
    }

    // ───────────────────────── 恢复 ─────────────────────────

    @Test
    fun `取消选择文件不显示失败且业务数据完全不变`() {
        val before = runBlocking { repository.exportConsistentSnapshot() }
        val vm = viewModel()
        vm.onRestoreRequested()
        val launch = requireNotNull(vm.state.value.importLaunch)
        vm.onImportPickerLaunched(launch.requestId)

        vm.onImportPickerResult(null)

        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.dialog)
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() })
    }

    @Test
    fun `损坏备份被拒绝，原数据完全不变并给出原因`() {
        val before = runBlocking { repository.exportConsistentSnapshot() }
        val vm = viewModel()
        files.readResult = BackupTextReadResult.Success("{ 这不是 JSON")

        vm.onRestoreRequested()
        vm.onImportPickerLaunched(requireNotNull(vm.state.value.importLaunch).requestId)
        vm.onImportPickerResult("content://downloads/broken.json")

        val notice = requireNotNull(vm.state.value.notice)
        assertEquals(BackupNoticeLevel.ERROR, notice.level)
        assertTrue(notice.message.contains("当前数据未改变"))
        assertEquals(BackupRetry.IMPORT, notice.retry)
        assertNull(vm.state.value.dialog)
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() })
    }

    @Test
    fun `超限备份被拒绝且原数据不变`() {
        val before = runBlocking { repository.exportConsistentSnapshot() }
        val vm = viewModel()
        files.readResult = BackupTextReadResult.Failure(
            LedgerError.Backup(
                code = ValidationCode.BACKUP_TOO_LARGE,
                message = "备份文件超过 32 MiB 上限，已拒绝（读取过程中检测）",
            ),
        )

        vm.onRestoreRequested()
        vm.onImportPickerLaunched(requireNotNull(vm.state.value.importLaunch).requestId)
        vm.onImportPickerResult("content://downloads/huge.json")

        val notice = requireNotNull(vm.state.value.notice)
        assertTrue(notice.message.contains("32 MiB"))
        assertTrue(notice.message.contains("当前数据未改变"))
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() })
    }

    @Test
    fun `合法备份先预览，确认前数据不变，确认后整库替换`() {
        val vm = viewModel()
        val target = BackupFx.snapshot().let { snapshot ->
            snapshot.copy(
                transactions = listOf(snapshot.transactions.first { it.id == BackupFx.TX_SALARY }),
                budgets = emptyList(),
            )
        }
        files.readResult = BackupTextReadResult.Success(codec.encode(target))
        files.displayNameValue = "目标备份.blueledger.json"

        val before = runBlocking { repository.exportConsistentSnapshot() }
        vm.onRestoreRequested()
        vm.onImportPickerLaunched(requireNotNull(vm.state.value.importLaunch).requestId)
        vm.onImportPickerResult("content://downloads/target.json")

        val dialog = vm.state.value.dialog as DataManagementDialog.RestoreConfirm
        assertEquals("目标备份.blueledger.json", dialog.summary.fileName)
        assertEquals(1, dialog.summary.transactionCount)
        assertEquals(clock.now(), dialog.summary.exportedAt)
        assertTrue(dialog.lines.any { it.contains("备份时间") })
        assertTrue(dialog.lines.any { it.contains("账单：1 笔") })
        assertTrue(dialog.lines.any { it.contains("预算：无") })
        // 确认前：数据完全不变。
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() })

        vm.onDialogConfirmed()

        assertEquals(1, runBlocking { repository.observeTransactions(TransactionFilter()).first().totalCount })
        assertEquals(1, vm.state.value.totalTransactionCount)
        assertEquals(BackupNoticeLevel.SUCCESS, vm.state.value.notice?.level)
        assertNull(vm.state.value.dialog)
        // 恢复后保留**设备当前**的最近成功备份时间：备份文件里没有这个字段，不能被回滚成 null。
        assertEquals(
            BackupFx.settings().lastBackupAt,
            runBlocking { repository.observeBackupRecord().first().succeededAt },
        )
    }

    @Test
    fun `取消恢复确认不修改数据也不显示失败`() {
        val vm = viewModel()
        files.readResult = BackupTextReadResult.Success(codec.encode(BackupFx.snapshot()))
        val before = runBlocking { repository.exportConsistentSnapshot() }

        vm.onRestoreRequested()
        vm.onImportPickerLaunched(requireNotNull(vm.state.value.importLaunch).requestId)
        vm.onImportPickerResult("content://downloads/valid.json")
        assertTrue(vm.state.value.dialog is DataManagementDialog.RestoreConfirm)

        vm.onDialogDismissed()

        assertNull(vm.state.value.dialog)
        assertNull(vm.state.value.notice)
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() })
    }

    @Test
    fun `恢复事务失败保留原库并给出具体原因，可重试`() {
        val vm = viewModel()
        files.readResult = BackupTextReadResult.Success(codec.encode(BackupFx.snapshot()))
        val before = runBlocking { repository.exportConsistentSnapshot() }
        repository.failNextWrite = LedgerError.Storage("事务失败：磁盘错误")

        vm.onRestoreRequested()
        vm.onImportPickerLaunched(requireNotNull(vm.state.value.importLaunch).requestId)
        vm.onImportPickerResult("content://downloads/valid.json")
        vm.onDialogConfirmed()

        val notice = requireNotNull(vm.state.value.notice)
        assertEquals(BackupNoticeLevel.ERROR, notice.level)
        assertTrue(notice.message.contains("事务失败"))
        assertTrue(notice.message.contains("当前数据未改变"))
        assertEquals(BackupRetry.RESTORE_CONFIRM, notice.retry)
        assertEquals(before, runBlocking { repository.exportConsistentSnapshot() })

        // 重试会重新打开确认对话框，而不是直接覆盖。
        vm.onNoticeRetryRequested()
        assertTrue(vm.state.value.dialog is DataManagementDialog.RestoreConfirm)
    }

    // ───────────────────────── 操作中禁用重复操作 ─────────────────────────

    @Test
    fun `操作进行中禁用全部入口，完成后恢复`() {
        val vm = viewModel()
        files.writeGate = CompletableDeferred()

        vm.startExport()
        vm.onExportPickerResult("content://downloads/slow.json")

        assertEquals(BackupOperation.EXPORTING, vm.state.value.busy)
        // 忙碌期间的其它入口全部无效。
        vm.onExportBackupRequested()
        assertNull(vm.state.value.dialog)
        vm.onRestoreRequested()
        assertNull(vm.state.value.importLaunch)

        files.writeGate!!.complete(Unit)

        assertNull(vm.state.value.busy)
        assertEquals(1, files.writes.size)
        assertEquals(BackupNoticeLevel.SUCCESS, vm.state.value.notice?.level)
    }

    @Test
    fun `恢复进行中再次发起恢复会被忽略`() {
        val vm = viewModel()
        files.readResult = BackupTextReadResult.Success(codec.encode(BackupFx.snapshot()))

        vm.onRestoreRequested()
        val first = requireNotNull(vm.state.value.importLaunch)
        vm.onImportPickerLaunched(first.requestId)
        vm.onImportPickerResult("content://downloads/valid.json")

        assertTrue(vm.state.value.dialog is DataManagementDialog.RestoreConfirm)
        // 已有确认对话框时，重复发起恢复不应该再排队第二个请求。
        vm.onRestoreRequested()
        assertNull(vm.state.value.importLaunch)
    }
}
