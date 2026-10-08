package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.BackupDecodeResult
import com.blueledger.app.core.model.CsvScope
import com.blueledger.app.core.model.LedgerError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RememberedFolderExportTest {
    @get:Rule val mainDispatcherRule = MainDispatcherRule()
    private val clock = BackupTestClock()
    private val codec = JsonBackupCodec(clock)
    private val repository = BackupFx.repository(clock)
    private val files = FakeBackupFileGateway()
    private val folder = BackupFolder("content://storage/tree/backup", "内部存储 / Download/BlueLedger")

    private class FolderFake(initial: BackupFolder? = null) : BackupFolderGateway {
        override val selectedFolder = MutableStateFlow(initial)
        val writes = mutableListOf<Triple<String, String, String>>()
        var selectionFailure: LedgerError? = null
        var writeFailure: LedgerError? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun rememberFolder(reference: String): BackupFolderSelectionResult {
            selectionFailure?.let { return BackupFolderSelectionResult.Failure(it) }
            val chosen = BackupFolder(reference, "内部存储 / Download/BlueLedger")
            selectedFolder.value = chosen
            return BackupFolderSelectionResult.Success(chosen)
        }
        override suspend fun exportNewFile(fileName: String, mimeType: String, text: String): BackupFolderExportResult {
            gate?.await()
            writeFailure?.let { return BackupFolderExportResult.Failure(it) }
            writes += Triple(fileName, mimeType, text)
            return BackupFolderExportResult.Success(fileName, requireNotNull(selectedFolder.value).label)
        }
    }
    private fun vm(folders: FolderFake) = DataManagementViewModel(repository, clock, codec, files, folders)

    @Test fun `first export chooses a folder once and resumes the prepared backup`() {
        val gateway = FolderFake()
        val vm = vm(gateway)
        vm.onExportBackupRequested()
        vm.onDialogConfirmed()
        val request = requireNotNull(vm.state.value.folderLaunch)
        assertNull(request.initialReference)
        assertNull(vm.state.value.exportLaunch)
        assertTrue(gateway.writes.isEmpty())
        vm.onFolderPickerLaunched(request.requestId)
        vm.onFolderPickerResult(folder.reference)
        val written = gateway.writes.single()
        assertEquals(BackupLimits.MIME_TYPE, written.second)
        assertTrue(codec.decode(written.third) is BackupDecodeResult.Valid)
        assertFalse(written.third.contains(BackupFx.TX_DELETED))
        assertEquals(written.first, vm.state.value.backupRecord.fileName)
        assertEquals(BackupNoticeLevel.SUCCESS, vm.state.value.notice?.level)
        assertNull(vm.state.value.busy)
        assertEquals(folder, vm.state.value.savedFolder)
    }

    @Test fun `later exports and a recreated view model reuse folder with unique files`() {
        val gateway = FolderFake(folder)
        repeat(2) {
            val vm = vm(gateway)
            vm.onExportBackupRequested()
            vm.onDialogConfirmed()
            assertNull(vm.state.value.folderLaunch)
            assertNull(vm.state.value.exportLaunch)
        }
        assertEquals(2, gateway.writes.size)
        assertNotEquals(gateway.writes[0].first, gateway.writes[1].first)
        assertTrue(gateway.writes.all { it.first.endsWith(BackupLimits.FILE_SUFFIX) })
        assertTrue(files.writes.isEmpty())
    }

    @Test fun `both CSV scopes save directly to the remembered folder with correct rows`() {
        val gateway = FolderFake(folder)
        val vm = vm(gateway)
        for ((scope, count) in listOf(CsvScope.CURRENT_MONTH to 2, CsvScope.ALL to 3)) {
            vm.onCsvExportRequested()
            vm.onCsvScopeChosen(scope)
            vm.onDialogConfirmed()
            val written = gateway.writes.last()
            assertEquals(BackupFileNames.CSV_MIME_TYPE, written.second)
            assertTrue(written.first.endsWith(".csv"))
            assertEquals(count + 1, CsvParser.parse(written.third).size)
            assertNull(vm.state.value.folderLaunch)
            assertNull(vm.state.value.exportLaunch)
        }
    }

    @Test fun `cancel first folder selection writes nothing and does not record success`() {
        val gateway = FolderFake()
        val vm = vm(gateway)
        val before = vm.state.value.backupRecord
        vm.onExportBackupRequested()
        vm.onDialogConfirmed()
        vm.onFolderPickerResult(null)
        assertEquals(before, vm.state.value.backupRecord)
        assertNull(vm.state.value.notice)
        assertNull(vm.state.value.busy)
        assertTrue(gateway.writes.isEmpty())
        vm.onFolderPickerResult(folder.reference) // Late duplicate callback cannot resume a cancelled export.
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun `changing folder can be cancelled without losing the old folder`() {
        val gateway = FolderFake(folder)
        val vm = vm(gateway)
        vm.onChooseFolderRequested()
        assertEquals(folder.reference, vm.state.value.folderLaunch?.initialReference)
        vm.onFolderPickerResult(null)
        assertEquals(folder, vm.state.value.savedFolder)
        vm.onExportBackupRequested()
        vm.onDialogConfirmed()
        assertEquals(1, gateway.writes.size)
        assertNull(vm.state.value.folderLaunch)
    }

    @Test fun `grant failure keeps previous folder and backup metadata`() {
        val gateway = FolderFake(folder)
        gateway.selectionFailure = LedgerError.Storage("授权失败")
        val vm = vm(gateway)
        val before = vm.state.value.backupRecord
        vm.onChooseFolderRequested()
        vm.onFolderPickerResult("content://new/tree/folder")
        assertEquals(folder, vm.state.value.savedFolder)
        assertEquals(before, vm.state.value.backupRecord)
        assertEquals(BackupNoticeLevel.ERROR, vm.state.value.notice?.level)
        assertTrue(gateway.writes.isEmpty())
    }

    @Test fun `write failure does not claim success and offers folder reselection`() {
        val gateway = FolderFake(folder)
        gateway.writeFailure = LedgerError.Storage("磁盘已满")
        val vm = vm(gateway)
        val before = vm.state.value.backupRecord
        vm.onExportBackupRequested()
        vm.onDialogConfirmed()
        assertEquals(before, vm.state.value.backupRecord)
        assertEquals(BackupNoticeLevel.ERROR, vm.state.value.notice?.level)
        assertEquals(BackupRetry.FOLDER_SELECTION, vm.state.value.notice?.retry)
        assertTrue(gateway.writes.isEmpty())
        vm.onNoticeRetryRequested()
        assertNotNull(vm.state.value.folderLaunch)
    }

    @Test fun `double confirm and entry taps while writing cannot queue another export`() {
        val gateway = FolderFake(folder)
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val vm = vm(gateway)
        vm.onExportBackupRequested()
        vm.onDialogConfirmed()
        assertEquals(BackupOperation.EXPORTING, vm.state.value.busy)
        vm.onDialogConfirmed()
        vm.onChooseFolderRequested()
        vm.onExportBackupRequested()
        vm.onCsvExportRequested()
        assertNull(vm.state.value.folderLaunch)
        assertNull(vm.state.value.dialog)
        gate.complete(Unit)
        assertEquals(1, gateway.writes.size)
        assertNull(vm.state.value.busy)
    }

    @Test fun `filename keeps backup extension and local time even with fixed clock`() {
        val suggested = BackupFileNames.backupFileName(BackupFx.TODAY)
        val a = BackupFileNames.uniqueExportFileName(suggested, clock.now(), clock.zoneId(), "abcdefgh")
        val b = BackupFileNames.uniqueExportFileName(suggested, clock.now(), clock.zoneId(), "ijklmnop")
        assertEquals("蓝记备份-2026-10-07-000000-abcdefgh.blueledger.json", a)
        assertNotEquals(a, b)
    }
}
