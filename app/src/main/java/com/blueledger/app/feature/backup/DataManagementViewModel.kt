package com.blueledger.app.feature.backup

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerBackupCodec
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.BackupDecodeResult
import com.blueledger.app.core.model.BackupSummary
import com.blueledger.app.core.model.CsvScope
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.RestoreResult
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidatedLedgerSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * S11 数据管理页的状态机。
 *
 * 只依赖冻结契约（[LedgerRepository]、[Clock]、[LedgerBackupCodec]）与文件访问端口
 * [BackupFileGateway]，因此全部流程都能在纯 JVM 单测里用替身验证：
 *
 * - 导出：准备（一致快照 + 编码/CSV）→ 展示范围与记录数 → 用户确认 → 系统保存对话框 →
 *   **真正写入成功后**才 `recordBackupSuccess`。写入失败只报错，绝不提示成功。
 * - 导入：系统选择对话框 → 读取（读取过程中检查体积）→ 解码与完整校验 → 展示备份时间/
 *   记录数/覆盖范围 → 用户确认 → 事务替换。取消选择、取消确认都不改任何业务数据、不报失败。
 * - 恢复失败（含事务失败）保留原库，提示具体原因并给出重试。
 * - 任何操作进行中禁用全部入口。
 */
class DataManagementViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val codec: LedgerBackupCodec,
    private val files: BackupFileGateway,
    private val folders: BackupFolderGateway? = null,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(DataManagementUiState(currentMonth = clock.currentYearMonth(),
        folderSavingEnabled = folders != null, savedFolder = folders?.selectedFolder?.value))
    val state: StateFlow<DataManagementUiState> = _state.asStateFlow()

    private var observeJobs: List<Job> = emptyList()

    /** 已备好但还没写入文件的导出内容（只存在于 ViewModel 内部，不放进 UI 状态）。 */
    private var preparedExport: PreparedExport? = null

    /** 已通过完整校验、等待用户确认的恢复预览。 */
    private var previewedRestore: PreviewedRestore? = null

    private var requestSequence: Long = 0L
    private var noticeSequence: Long = 0L
    private var resumeExportAfterFolderSelection = false

    init {
        startObserving()
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    // ───────────────────────── 数据订阅 ─────────────────────────

    /** 读取失败后的重试入口（重新订阅全部数据源）。 */
    fun onRetryLoad() {
        startObserving()
    }

    private fun startObserving() {
        observeJobs.forEach { it.cancel() }
        _state.update { it.copy(loading = true, loadError = null) }
        val month = clock.currentYearMonth()
        observeJobs = listOf(
            launchObservation {
                repository.observeTransactions(TransactionFilter(limit = 1)).collect { page ->
                    _state.update { it.copy(loading = false, totalTransactionCount = page.totalCount) }
                }
            },
            launchObservation {
                repository.observeTransactions(TransactionFilter(yearMonth = month, limit = 1)).collect { page ->
                    _state.update { it.copy(currentMonthTransactionCount = page.totalCount) }
                }
            },
            launchObservation {
                repository.observeMonthSummary(month).collect { summary ->
                    _state.update {
                        it.copy(monthIncomeCent = summary.incomeCent, monthExpenseCent = summary.expenseCent)
                    }
                }
            },
            launchObservation {
                combine(
                    repository.observeCategories(TransactionType.INCOME, includeArchived = true),
                    repository.observeCategories(TransactionType.EXPENSE, includeArchived = true),
                ) { income, expense -> income.size + expense.size }
                    .collect { count -> _state.update { it.copy(categoryCount = count) } }
            },
            launchObservation {
                repository.observeAccounts(includeArchived = true).collect { accounts ->
                    _state.update {
                        it.copy(
                            accountCount = accounts.size,
                            activeAccountCount = accounts.count { account -> !account.account.isArchived },
                        )
                    }
                }
            },
            launchObservation {
                repository.observeSettings().collect { settings ->
                    _state.update { it.copy(hideAmounts = settings.hideAmounts) }
                }
            },
            launchObservation {
                repository.observeBackupRecord().collect { record ->
                    _state.update { it.copy(backupRecord = record) }
                }
            },
        ) + listOfNotNull(folders?.let { gateway -> launchObservation {
            gateway.selectedFolder.collect { folder -> _state.update { it.copy(savedFolder = folder) } }
        } })
    }

    /** 读取失败不能伪装成空账本：标记 loadError，界面显示失败与重试。 */
    private fun launchObservation(block: suspend CoroutineScope.() -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    loading = false,
                    loadError = LedgerError.Storage(
                        "暂时无法读取数据：${e.readableReason() ?: e.javaClass.simpleName}",
                    ),
                )
            }
        }
    }

    // ───────────────────────── 导出 ─────────────────────────

    /** 「备份完整账本」：准备内容并弹出范围确认。 */
    fun onExportBackupRequested() {
        beginExport(ExportKind.BACKUP)
    }

    /** 「导出账单 CSV」：先让用户选择当前月 / 全部（两个范围都显示记录数）。 */
    fun onCsvExportRequested() {
        if (_state.value.isBusy) return
        _state.update {
            it.copy(
                notice = null,
                dialog = DataManagementDialog.CsvScopeChoice(
                    monthLabel = LedgerCsvExporter.monthLabel(it.currentMonth),
                    currentMonthCount = it.currentMonthTransactionCount,
                    allCount = it.totalTransactionCount,
                ),
            )
        }
    }

    /** CSV 范围选定后进入导出确认。 */
    fun onCsvScopeChosen(scope: CsvScope) {
        _state.update { it.copy(dialog = null) }
        beginExport(
            if (scope == CsvScope.CURRENT_MONTH) ExportKind.CSV_CURRENT_MONTH else ExportKind.CSV_ALL,
        )
    }

    private fun beginExport(kind: ExportKind) {
        if (_state.value.isBusy) return
        scope.launch {
            _state.update {
                it.copy(busy = BackupOperation.PREPARING_EXPORT, dialog = null, notice = null)
            }
            val prepared = try {
                prepareExport(kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        busy = null,
                        notice = errorNotice(
                            "导出准备失败：${e.readableReason() ?: e.javaClass.simpleName}",
                            retryOf(kind),
                        ),
                    )
                }
                return@launch
            }
            preparedExport = prepared
            _state.update {
                it.copy(
                    busy = null,
                    dialog = DataManagementDialog.ExportConfirm(
                        kind = prepared.kind,
                        fileName = prepared.fileName,
                        lines = prepared.dialogLines,
                    ),
                )
            }
        }
    }

    private suspend fun prepareExport(kind: ExportKind): PreparedExport {
        val snapshot = repository.exportConsistentSnapshot()
        val today = clock.today()
        val prepared = when (kind) {
            ExportKind.BACKUP -> {
                val text = codec.encode(snapshot)
                val count = snapshot.transactions.count { it.deletedAt == null }
                PreparedExport(
                    kind = kind,
                    text = text,
                    fileName = BackupFileNames.backupFileName(today),
                    dialogLines = listOf(
                        "范围：全部有效账单 + 全部分类、账户、预算与设置",
                        "有效账单 $count 笔、分类 ${snapshot.categories.size} 个、" +
                            "账户 ${snapshot.accounts.size} 个、预算 ${snapshot.budgets.size} 个月",
                        "不含软删除账单与页面草稿；归档的分类与账户仍会保留",
                        "文件只保存在你选择的位置，应用不会上传",
                    ),
                    recordCount = count,
                    scopeLabel = "完整备份",
                )
            }

            ExportKind.CSV_CURRENT_MONTH, ExportKind.CSV_ALL -> {
                val csvScope =
                    if (kind == ExportKind.CSV_CURRENT_MONTH) CsvScope.CURRENT_MONTH else CsvScope.ALL
                val month = clock.currentYearMonth()
                val csv = LedgerCsvExporter.build(snapshot, csvScope, month)
                PreparedExport(
                    kind = kind,
                    text = csv.text,
                    fileName = BackupFileNames.csvFileName(csvScope, month, today),
                    dialogLines = listOf(
                        "范围：${csv.scopeLabel}",
                        "记录数：${csv.rowCount} 笔（只含有效账单）",
                        "金额为正数、两位小数；备注按 CSV 规则转义并做公式防护",
                    ),
                    recordCount = csv.rowCount,
                    scopeLabel = csv.scopeLabel,
                )
            }
        }
        return if (folders == null) prepared else prepared.copy(
            fileName = BackupFileNames.uniqueExportFileName(prepared.fileName, clock.now(), clock.zoneId()),
        )
    }

    /** 导出确认对话框的「确认」：请求界面拉起系统文件保存对话框。 */
    fun onDialogConfirmed() {
        if (_state.value.isBusy) return
        when (val dialog = _state.value.dialog) {
            is DataManagementDialog.ExportConfirm -> {
                val prepared = preparedExport ?: return
                if (folders != null) {
                    _state.update { it.copy(dialog = null) }
                    if (folders.selectedFolder.value == null) startFolderSelection(resumeExport = true)
                    else {
                        preparedExport = null
                        exportToSavedFolder(prepared)
                    }
                    return
                }
                _state.update {
                    it.copy(
                        dialog = null,
                        exportLaunch = ExportLaunchRequest(
                            requestId = ++requestSequence,
                            fileName = prepared.fileName,
                            mimeType = prepared.kind.mimeType,
                        ),
                    )
                }
            }

            is DataManagementDialog.RestoreConfirm -> confirmRestore()

            // CSV 范围选择用专门的回调（onCsvScopeChosen）。
            is DataManagementDialog.CsvScopeChoice, null -> Unit
        }
    }

    /** 对话框取消：导出取消只是放弃本次准备；恢复取消绝不触碰数据。 */
    fun onDialogDismissed() {
        when (_state.value.dialog) {
            is DataManagementDialog.RestoreConfirm -> {
                previewedRestore = null
                _state.update { it.copy(dialog = null) }
            }

            else -> {
                preparedExport = null
                _state.update { it.copy(dialog = null) }
            }
        }
    }

    /** 界面已拉起系统保存对话框（消费一次性请求，避免重组重复拉起）。 */
    fun onExportPickerLaunched(requestId: Long) {
        _state.update { if (it.exportLaunch?.requestId == requestId) it.copy(exportLaunch = null) else it }
    }

    /**
     * 系统保存对话框返回。
     *
     * [reference] 为 null 表示用户取消选择：正常取消，不显示失败、不更新成功时间、不动数据。
     * 只有写入真正成功后才记录「最近成功备份」——写失败绝不提示成功。
     */
    fun onExportPickerResult(reference: String?) {
        val prepared = preparedExport
        preparedExport = null
        if (reference == null) {
            _state.update { it.copy(busy = null) }
            return
        }
        if (prepared == null) return
        scope.launch {
            _state.update { it.copy(busy = BackupOperation.EXPORTING) }
            when (val written = files.writeText(reference, prepared.kind.mimeType, prepared.text)) {
                is BackupFileWriteResult.Failure -> _state.update {
                    it.copy(
                        busy = null,
                        notice = errorNotice(
                            "导出失败：${written.error.message}",
                            retryOf(prepared.kind),
                        ),
                    )
                }

                is BackupFileWriteResult.Success -> {
                    val fileName = files.displayName(reference)?.takeIf { it.isNotBlank() } ?: prepared.fileName
                    completeExport(prepared, fileName)
                }
            }
        }
    }

    fun onChooseFolderRequested() {
        if (folders == null || _state.value.isBusy || _state.value.dialog != null) return
        startFolderSelection(resumeExport = false)
    }

    private fun startFolderSelection(resumeExport: Boolean) {
        val gateway = folders ?: return
        resumeExportAfterFolderSelection = resumeExport
        _state.update { it.copy(busy = BackupOperation.SELECTING_FOLDER, notice = null,
            folderLaunch = FolderLaunchRequest(++requestSequence, gateway.selectedFolder.value?.reference)) }
    }

    fun onFolderPickerLaunched(requestId: Long) {
        _state.update { if (it.folderLaunch?.requestId == requestId) it.copy(folderLaunch = null) else it }
    }

    fun onFolderPickerResult(reference: String?) {
        val gateway = folders ?: return
        if (_state.value.busy != BackupOperation.SELECTING_FOLDER) return
        val resume = resumeExportAfterFolderSelection
        resumeExportAfterFolderSelection = false
        if (reference == null) {
            if (resume) preparedExport = null
            _state.update { it.copy(busy = null, folderLaunch = null) }
            return
        }
        scope.launch {
            val selected = try { gateway.rememberFolder(reference) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { BackupFolderSelectionResult.Failure(LedgerError.Storage("无法保存所选文件夹，请重新选择")) }
            when (selected) {
                is BackupFolderSelectionResult.Failure -> {
                    if (resume) preparedExport = null
                    _state.update { it.copy(busy = null, folderLaunch = null,
                        notice = errorNotice(selected.error.message, BackupRetry.FOLDER_SELECTION)) }
                }
                is BackupFolderSelectionResult.Success -> {
                    _state.update { it.copy(savedFolder = selected.folder, busy = null, folderLaunch = null) }
                    if (resume) {
                        val prepared = preparedExport
                        preparedExport = null
                        if (prepared != null) exportToSavedFolder(prepared)
                    } else _state.update { it.copy(notice = successNotice("已设置保存位置：${selected.folder.label}")) }
                }
            }
        }
    }

    private fun exportToSavedFolder(prepared: PreparedExport) {
        val gateway = folders ?: return
        _state.update { it.copy(busy = BackupOperation.EXPORTING, notice = null) }
        scope.launch {
            val written = try { gateway.exportNewFile(prepared.fileName, prepared.kind.mimeType, prepared.text) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { BackupFolderExportResult.Failure(LedgerError.Storage("保存失败，请检查保存位置后重试")) }
            when (written) {
                is BackupFolderExportResult.Success -> completeExport(prepared, written.fileName, written.folderLabel)
                is BackupFolderExportResult.Failure -> _state.update { it.copy(busy = null,
                    notice = errorNotice("导出失败：${written.error.message}", BackupRetry.FOLDER_SELECTION)) }
            }
        }
    }

    private suspend fun completeExport(prepared: PreparedExport, fileName: String, folderLabel: String? = null) {
        val recorded = try { repository.recordBackupSuccess(clock.now(), fileName) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { MutationResult.Failure(LedgerError.Storage(e.readableReason() ?: e.javaClass.simpleName)) }
        when (recorded) {
            is MutationResult.Success -> _state.update { it.copy(busy = null,
                notice = successNotice("已导出${prepared.scopeLabel}：${prepared.recordCount} 条记录 → $fileName" +
                    (folderLabel?.let { label -> "\n保存位置：$label" } ?: ""))) }
            is MutationResult.Failure -> _state.update { it.copy(busy = null,
                notice = errorNotice("文件已写入 $fileName，但“最近备份时间”记录失败：${recorded.error.message}", retryOf(prepared.kind))) }
        }
    }

    // ───────────────────────── 恢复 ─────────────────────────

    /** 「从备份恢复」：请求界面拉起系统文件选择对话框。 */
    fun onRestoreRequested() {
        if (_state.value.isBusy) return
        // 已有待确认的恢复预览时不再排队第二个选择请求（确认或取消后才能重新选择）。
        if (previewedRestore != null) return
        _state.update { it.copy(notice = null, importLaunch = ImportLaunchRequest(++requestSequence)) }
    }

    fun onImportPickerLaunched(requestId: Long) {
        _state.update { if (it.importLaunch?.requestId == requestId) it.copy(importLaunch = null) else it }
    }

    /**
     * 系统文件选择返回。
     *
     * [reference] 为 null = 用户取消选择：正常取消，不显示失败、不修改任何业务数据。
     * 选择后先读取（读取过程中检查体积）再完整校验，只有全部通过才展示确认对话框。
     */
    fun onImportPickerResult(reference: String?) {
        if (reference == null) return
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(busy = BackupOperation.RESTORING, notice = null, dialog = null) }
            val read = try {
                files.readText(reference)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BackupTextReadResult.Failure(
                    LedgerError.Storage("读取文件失败：${e.readableReason() ?: e.javaClass.simpleName}"),
                )
            }
            when (read) {
                is BackupTextReadResult.Failure -> _state.update {
                    it.copy(
                        busy = null,
                        notice = errorNotice("无法恢复：${read.error.message}。当前数据未改变。", BackupRetry.IMPORT),
                    )
                }

                is BackupTextReadResult.Success -> when (val decoded = codec.decode(read.text)) {
                    is BackupDecodeResult.Invalid -> _state.update {
                        it.copy(
                            busy = null,
                            notice = errorNotice(
                                "无法恢复：${decoded.error.message}。当前数据未改变。",
                                BackupRetry.IMPORT,
                            ),
                        )
                    }

                    is BackupDecodeResult.Valid -> {
                        val summary = decoded.summary.copy(fileName = files.displayName(reference))
                        previewedRestore = PreviewedRestore(decoded.validated, summary)
                        _state.update {
                            it.copy(
                                busy = null,
                                dialog = DataManagementDialog.RestoreConfirm(summary, restoreLines(summary)),
                            )
                        }
                    }
                }
            }
        }
    }

    /** 恢复确认：调用事务替换。失败保留原库并给出具体原因，可重试。 */
    private fun confirmRestore() {
        val preview = previewedRestore ?: return
        if (_state.value.isBusy) return
        scope.launch {
            _state.update { it.copy(dialog = null, busy = BackupOperation.RESTORING) }
            val result = try {
                repository.restoreValidatedSnapshot(preview.validated)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                RestoreResult.Failure(
                    LedgerError.Storage(
                        "恢复失败，当前数据未改变：${e.readableReason() ?: e.javaClass.simpleName}",
                    ),
                )
            }
            when (result) {
                is RestoreResult.Success -> {
                    // 恢复成功后清空本页选中的文件与预览：旧草稿/旧选择不得再写回新账本。
                    previewedRestore = null
                    preparedExport = null
                    _state.update {
                        it.copy(
                            busy = null,
                            dialog = null,
                            exportLaunch = null,
                            importLaunch = null,
                            notice = successNotice(
                                "已恢复备份：账单 ${result.transactionCount} 笔、分类 ${result.categoryCount} 个、" +
                                    "账户 ${result.accountCount} 个、预算 ${result.budgetCount} 个月。",
                            ),
                        )
                    }
                }

                is RestoreResult.Failure -> _state.update {
                    it.copy(
                        busy = null,
                        notice = errorNotice(
                            "恢复失败：${result.error.message}。当前数据未改变。",
                            BackupRetry.RESTORE_CONFIRM,
                        ),
                    )
                }
            }
        }
    }

    // ───────────────────────── 提示与重试 ─────────────────────────

    fun onNoticeDismissed() {
        _state.update { it.copy(notice = null) }
    }

    fun onNoticeRetryRequested() {
        val retry = _state.value.notice?.retry ?: return
        _state.update { it.copy(notice = null) }
        when (retry) {
            BackupRetry.FOLDER_SELECTION -> onChooseFolderRequested()
            BackupRetry.BACKUP_EXPORT -> beginExport(ExportKind.BACKUP)
            BackupRetry.CSV_EXPORT -> onCsvExportRequested()
            BackupRetry.IMPORT -> onRestoreRequested()
            BackupRetry.RESTORE_CONFIRM -> {
                val preview = previewedRestore ?: return
                _state.update {
                    it.copy(
                        dialog = DataManagementDialog.RestoreConfirm(
                            summary = preview.summary,
                            lines = restoreLines(preview.summary),
                        ),
                    )
                }
            }
        }
    }

    // ───────────────────────── 内部 ─────────────────────────

    private fun restoreLines(summary: BackupSummary): List<String> = buildList {
        add("备份文件：${summary.fileName ?: "已选择文件"}")
        add("备份时间：${BackupFileNames.formatTimestamp(summary.exportedAt, clock.zoneId())}")
        add(
            if (summary.transactionCount == 0 || summary.earliestOccurredOn == null || summary.latestOccurredOn == null) {
                "账单：${summary.transactionCount} 笔"
            } else {
                "账单：${summary.transactionCount} 笔（${summary.earliestOccurredOn} 至 ${summary.latestOccurredOn}）"
            },
        )
        add("分类 ${summary.categoryCount} 个、账户 ${summary.accountCount} 个")
        add(
            if (summary.budgetCount == 0) {
                "预算：无"
            } else {
                "预算 ${summary.budgetCount} 个月：" + summary.budgetMonths.joinToString("、")
            },
        )
    }

    private fun successNotice(message: String): BackupNotice =
        BackupNotice(BackupNoticeLevel.SUCCESS, message, retry = null, id = ++noticeSequence)

    private fun errorNotice(message: String, retry: BackupRetry?): BackupNotice =
        BackupNotice(BackupNoticeLevel.ERROR, message, retry = retry, id = ++noticeSequence)

    private fun retryOf(kind: ExportKind): BackupRetry = when (kind) {
        ExportKind.BACKUP -> BackupRetry.BACKUP_EXPORT
        ExportKind.CSV_CURRENT_MONTH, ExportKind.CSV_ALL -> BackupRetry.CSV_EXPORT
    }

    private data class PreparedExport(
        val kind: ExportKind,
        val text: String,
        val fileName: String,
        val dialogLines: List<String>,
        val recordCount: Int,
        val scopeLabel: String,
    )

    private data class PreviewedRestore(
        val validated: ValidatedLedgerSnapshot,
        val summary: BackupSummary,
    )
}
