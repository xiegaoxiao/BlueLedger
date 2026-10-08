package com.blueledger.app.feature.backup

import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.model.BackupRecord
import com.blueledger.app.core.model.BackupSummary
import com.blueledger.app.core.model.LedgerError
import java.time.YearMonth

/** 导出的三种范围。MIME 决定系统保存对话框给出的默认类型与后缀。 */
enum class ExportKind {
    BACKUP,
    CSV_CURRENT_MONTH,
    CSV_ALL;

    val mimeType: String
        get() = when (this) {
            BACKUP -> BackupLimits.MIME_TYPE
            CSV_CURRENT_MONTH, CSV_ALL -> BackupFileNames.CSV_MIME_TYPE
        }
}

/** 正在进行的操作；非 null 时界面禁用全部入口，防止重复操作。 */
enum class BackupOperation { PREPARING_EXPORT, EXPORTING, RESTORING, SELECTING_FOLDER }

enum class BackupNoticeLevel { SUCCESS, ERROR }

/** 失败后「重试」重放的动作。 */
enum class BackupRetry { BACKUP_EXPORT, CSV_EXPORT, IMPORT, RESTORE_CONFIRM, FOLDER_SELECTION }

/** 一次性提示。[id] 单调递增，供界面区分新提示与重建后的旧状态。 */
data class BackupNotice(
    val level: BackupNoticeLevel,
    val message: String,
    val retry: BackupRetry? = null,
    val id: Long = 0L,
)

/** 等待系统文件保存对话框的导出请求（准备就绪的文本留在 ViewModel 内部）。 */
data class ExportLaunchRequest(
    val requestId: Long,
    val fileName: String,
    val mimeType: String,
)

/** 等待系统文件选择对话框的导入请求。 */
data class ImportLaunchRequest(val requestId: Long)

data class FolderLaunchRequest(val requestId: Long, val initialReference: String?)

/** 本页唯一的对话框。同一时刻只会有一个。 */
sealed interface DataManagementDialog {

    /** 导出前确认：明确展示范围与记录数（PRD S11）。 */
    data class ExportConfirm(
        val kind: ExportKind,
        val fileName: String,
        val lines: List<String>,
    ) : DataManagementDialog

    /** CSV 范围选择：当前月 / 全部，各自显示记录数。 */
    data class CsvScopeChoice(
        val monthLabel: String,
        val currentMonthCount: Int,
        val allCount: Int,
    ) : DataManagementDialog

    /** 恢复确认：备份时间、记录数与覆盖范围；确认前不得修改任何数据。 */
    data class RestoreConfirm(
        val summary: BackupSummary,
        val lines: List<String>,
    ) : DataManagementDialog
}

/**
 * S11 数据管理页状态。
 *
 * 金额字段只有「本月支出」，并且必须走金额隐藏（[hideAmounts] / `LocalHideAmounts`），
 * 金额隐藏**不影响**用户主动发起的导出内容。
 */
data class DataManagementUiState(
    val currentMonth: YearMonth,
    val loading: Boolean = true,
    val loadError: LedgerError? = null,
    val hideAmounts: Boolean = false,
    /** 全部有效账单数（不受月份筛选影响）。 */
    val totalTransactionCount: Int = 0,
    /** 当前月有效账单数。 */
    val currentMonthTransactionCount: Int = 0,
    val monthIncomeCent: Long = 0L,
    val monthExpenseCent: Long = 0L,
    /** 分类总数（含归档）。 */
    val categoryCount: Int = 0,
    /** 账户总数（含归档）与其中可用（未归档）的数量。 */
    val accountCount: Int = 0,
    val activeAccountCount: Int = 0,
    val backupRecord: BackupRecord = BackupRecord(succeededAt = null, fileName = null),
    val busy: BackupOperation? = null,
    val exportLaunch: ExportLaunchRequest? = null,
    val importLaunch: ImportLaunchRequest? = null,
    val dialog: DataManagementDialog? = null,
    val notice: BackupNotice? = null,
    val folderSavingEnabled: Boolean = false,
    val savedFolder: BackupFolder? = null,
    val folderLaunch: FolderLaunchRequest? = null,
) {
    /** 数据就绪（非加载中、无读取错误）。 */
    val isReady: Boolean get() = !loading && loadError == null

    /** 有操作在进行：全部入口禁用。 */
    val isBusy: Boolean get() = busy != null
}
