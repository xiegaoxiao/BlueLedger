package com.blueledger.app.feature.backup

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.BackupLimits
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerBackupCodec
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.LEDGER_HIDDEN_AMOUNT_MASK
import com.blueledger.app.core.designsystem.LedgerConfirmDialog
import com.blueledger.app.core.designsystem.LedgerDivider
import com.blueledger.app.core.designsystem.LedgerErrorBanner
import com.blueledger.app.core.designsystem.LedgerErrorState
import com.blueledger.app.core.designsystem.LedgerKeyValueRow
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerPrimaryButton
import com.blueledger.app.core.designsystem.LedgerSecondaryButton
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSectionHeader
import com.blueledger.app.core.designsystem.LedgerSuccessBanner
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTopBar
import com.blueledger.app.core.model.CsvScope
import com.blueledger.app.core.money.Money
import java.time.ZoneId

/**
 * S11 数据管理入口（冻结签名）。
 *
 * `backupCodec` 由总控的 AppContainer 注入；文件访问用系统 SAF（CreateDocument / OpenDocument），
 * **不申请任何存储权限**，也不使用网页下载方式。
 */
@Composable
fun DataManagementRoute(
    repository: LedgerRepository,
    clock: Clock,
    backupCodec: LedgerBackupCodec,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val files = remember(context) { ContentResolverBackupFileGateway(context.applicationContext) }
    val folders = remember(context, files) { ContentResolverBackupFolderGateway(context.applicationContext, files) }
    val viewModel: DataManagementViewModel = viewModel(
        key = "data-management",
        factory = viewModelFactory {
            initializer {
                DataManagementViewModel(
                    repository = repository,
                    clock = clock,
                    codec = backupCodec,
                    files = files,
                    folders = folders,
                )
            }
        },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    // 两种导出用不同 MIME 的 CreateDocument：系统保存对话框据此给出默认类型与后缀。
    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupLimits.MIME_TYPE),
    ) { uri ->
        viewModel.onExportPickerResult(uri?.toString())
    }
    val csvExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupFileNames.CSV_MIME_TYPE),
    ) { uri ->
        viewModel.onExportPickerResult(uri?.toString())
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        viewModel.onImportPickerResult(uri?.toString())
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        viewModel.onFolderPickerResult(uri?.toString())
    }

    val exportRequest = state.exportLaunch
    LaunchedEffect(exportRequest?.requestId) {
        val request = exportRequest ?: return@LaunchedEffect
        viewModel.onExportPickerLaunched(request.requestId)
        if (request.mimeType == BackupFileNames.CSV_MIME_TYPE) {
            csvExportLauncher.launch(request.fileName)
        } else {
            backupExportLauncher.launch(request.fileName)
        }
    }

    val importRequest = state.importLaunch
    LaunchedEffect(importRequest?.requestId) {
        val request = importRequest ?: return@LaunchedEffect
        viewModel.onImportPickerLaunched(request.requestId)
        // 备份文件的 MIME 元数据由第三方 provider 决定（json / plain / octet-stream 都可能），
        // 收窄类型会让用户在部分文件管理器里选不到自己的备份；真正的门槛是内容完整校验。
        importLauncher.launch(arrayOf("*/*"))
    }

    val folderRequest = state.folderLaunch
    LaunchedEffect(folderRequest?.requestId) {
        val request = folderRequest ?: return@LaunchedEffect
        viewModel.onFolderPickerLaunched(request.requestId)
        folderLauncher.launch(request.initialReference?.let(Uri::parse))
    }

    DataManagementScreen(
        state = state,
        zoneId = clock.zoneId(),
        onBack = onBack,
        onExportBackup = viewModel::onExportBackupRequested,
        onExportCsv = viewModel::onCsvExportRequested,
        onRestore = viewModel::onRestoreRequested,
        onCsvScopeChosen = viewModel::onCsvScopeChosen,
        onDialogConfirmed = viewModel::onDialogConfirmed,
        onDialogDismissed = viewModel::onDialogDismissed,
        onNoticeDismissed = viewModel::onNoticeDismissed,
        onNoticeRetry = viewModel::onNoticeRetryRequested,
        onRetryLoad = viewModel::onRetryLoad,
        onChooseFolder = viewModel::onChooseFolderRequested,
    )
}

/**
 * S11 页面本体（无状态，便于用固定状态做 Compose 测试）。
 *
 * 不含任何“清空全部账本”入口（docs/实现决策.md §11 已裁决本版不提供）。
 */
@Composable
fun DataManagementScreen(
    state: DataManagementUiState,
    zoneId: ZoneId,
    onBack: () -> Unit,
    onExportBackup: () -> Unit,
    onExportCsv: () -> Unit,
    onRestore: () -> Unit,
    onCsvScopeChosen: (CsvScope) -> Unit,
    onDialogConfirmed: () -> Unit,
    onDialogDismissed: () -> Unit,
    onNoticeDismissed: () -> Unit,
    onNoticeRetry: () -> Unit,
    onRetryLoad: () -> Unit,
    modifier: Modifier = Modifier,
    onChooseFolder: () -> Unit = {},
) {
    val pagePadding = if (LocalConfiguration.current.screenWidthDp < 360) {
        BlueLedgerTokens.PageHorizontalCompact
    } else {
        BlueLedgerTokens.PageHorizontal
    }
    val hideAmounts = LocalHideAmounts.current || state.hideAmounts

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            LedgerTopBar(
                title = "数据管理",
                onBack = onBack,
                modifier = Modifier.statusBarsPadding(),
            )
            when {
                state.loading -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LedgerLoadingState(message = "正在读取本地数据…", testTag = "data_loading")
                    }
                }

                state.loadError != null -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LedgerErrorState(
                            title = "暂时无法读取数据",
                            message = state.loadError.message,
                            onRetry = onRetryLoad,
                            testTag = "data_load_error",
                        )
                    }
                }

                else -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = pagePadding, vertical = BlueLedgerTokens.SpaceL),
                        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
                    ) {
                        LocalOnlyCard()
                        state.notice?.let { notice ->
                            NoticeBanner(
                                notice = notice,
                                onDismiss = onNoticeDismissed,
                                onRetry = onNoticeRetry,
                            )
                        }
                        if (state.isBusy) {
                            BusyRow()
                        }
                        ActionsCard(
                            enabled = !state.isBusy,
                            onExportCsv = onExportCsv,
                            onExportBackup = onExportBackup,
                            onRestore = onRestore,
                            folderSavingEnabled = state.folderSavingEnabled,
                            folderLabel = state.savedFolder?.label,
                            onChooseFolder = onChooseFolder,
                        )
                        StatusCard(state = state, hideAmounts = hideAmounts, zoneId = zoneId)
                        TipsCard()
                        Box(modifier = Modifier.navigationBarsPadding())
                    }
                }
            }
        }

        when (val dialog = state.dialog) {
            is DataManagementDialog.CsvScopeChoice -> CsvScopeDialog(
                dialog = dialog,
                onChoose = onCsvScopeChosen,
                onDismiss = onDialogDismissed,
            )

            is DataManagementDialog.ExportConfirm -> LedgerConfirmDialog(
                title = if (dialog.kind == ExportKind.BACKUP) "导出完整备份？" else "导出账单 CSV？",
                message = if (state.folderSavingEnabled) "确认范围与记录数后，保存到指定文件夹。每次生成新文件，保留之前的备份。"
                    else "导出前请确认范围与记录数。文件保存位置由你选择，应用不会上传。",
                confirmText = if (!state.folderSavingEnabled) "选择保存位置" else if (state.savedFolder == null) "选择保存文件夹" else "保存",
                onConfirm = onDialogConfirmed,
                onDismiss = onDialogDismissed,
                testTag = "data_dialog_export",
                testTagConfirm = "data_dialog_export_confirm",
                testTagDismiss = "data_dialog_export_cancel",
            ) {
                DialogLines(lines = dialog.lines + "文件名：${dialog.fileName}" +
                    if (state.folderSavingEnabled) listOf("保存位置：${state.savedFolder?.label ?: "首次选择后自动记住"}") else emptyList())
            }

            is DataManagementDialog.RestoreConfirm -> LedgerConfirmDialog(
                title = "覆盖恢复？",
                message = "恢复后会替换当前账单、回收站、分类、账户、预算、标签、周期规则及设置。旧版备份没有的高级设置会重置。确认前不会修改任何数据。",
                confirmText = "确认恢复",
                dismissText = "取消",
                destructive = true,
                onConfirm = onDialogConfirmed,
                onDismiss = onDialogDismissed,
                testTag = "data_dialog_restore",
                testTagConfirm = "data_dialog_restore_confirm",
                testTagDismiss = "data_dialog_restore_cancel",
            ) {
                DialogLines(lines = dialog.lines)
            }

            null -> Unit
        }
    }
}

// ───────────────────────── 各区块 ─────────────────────────

@Composable
private fun LocalOnlyCard() {
    LedgerSectionCard(modifier = Modifier.testTag("data_local_only_card")) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(BlueLedgerTokens.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.VerifiedUser,
                    contentDescription = null,
                    tint = BlueLedgerTokens.Primary,
                    modifier = Modifier.size(30.dp),
                )
            }
            Text(
                text = "你的记录，留在你手里。",
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
            )
            Text(
                text = "账单存储在本机，备份文件由你自行保管。换设备前，记得保存一份账本。",
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ActionsCard(
    enabled: Boolean,
    onExportCsv: () -> Unit,
    onExportBackup: () -> Unit,
    onRestore: () -> Unit,
    folderSavingEnabled: Boolean,
    folderLabel: String?,
    onChooseFolder: () -> Unit,
) {
    LedgerSectionCard(
        modifier = Modifier.testTag("data_actions_card"),
        contentPadding = PaddingValues(
            horizontal = BlueLedgerTokens.SpaceL,
            vertical = BlueLedgerTokens.SpaceS,
        ),
    ) {
        DataActionRow(
            title = "导出账单 CSV",
            subtitle = "当前月或全部有效账单，适合在表格工具中查看",
            icon = Icons.Outlined.TableChart,
            enabled = enabled,
            testTag = "data_action_csv",
            onClick = onExportCsv,
        )
        LedgerDivider()
        DataActionRow(
            title = "备份完整账本",
            subtitle = "账单、分类、账户、预算与设置一并保存",
            icon = Icons.Outlined.FileDownload,
            enabled = enabled,
            testTag = "data_action_backup",
            onClick = onExportBackup,
        )
        LedgerDivider()
        DataActionRow(
            title = "从备份恢复",
            subtitle = "先校验并预览，确认后覆盖当前账本",
            icon = Icons.Outlined.FileUpload,
            enabled = enabled,
            testTag = "data_action_restore",
            onClick = onRestore,
        )
        if (folderSavingEnabled) {
            LedgerDivider()
            DataActionRow(
                title = "保存位置",
                subtitle = folderLabel ?: "首次选择后，备份和 CSV 都保存到这个文件夹",
                icon = Icons.Outlined.FolderOpen,
                enabled = enabled,
                testTag = "data_export_folder",
                onClick = onChooseFolder,
            )
        }
    }
}

@Composable
private fun DataActionRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = BlueLedgerTokens.ListRowMinHeight)
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
            .testTag(testTag)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = title, onClick = onClick)
            .padding(vertical = BlueLedgerTokens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                .background(BlueLedgerTokens.PrimarySoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary,
                modifier = Modifier.size(22.dp),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = LedgerTextStyles.bodyStrong,
                color = if (enabled) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
                maxLines = 1,
            )
            Text(
                text = subtitle,
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                maxLines = 2,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StatusCard(state: DataManagementUiState, hideAmounts: Boolean, zoneId: ZoneId) {
    val record = state.backupRecord
    val backupTimeText = record.succeededAt?.let { at ->
        val formatted = BackupFileNames.formatTimestamp(at, zoneId)
        if (record.fileName.isNullOrBlank()) formatted else "$formatted（${record.fileName}）"
    } ?: "尚未备份"

    val monthExpenseText = if (hideAmounts) {
        LEDGER_HIDDEN_AMOUNT_MASK
    } else {
        "¥" + Money.format(state.monthExpenseCent)
    }

    LedgerSectionCard(modifier = Modifier.testTag("data_status_card")) {
        LedgerSectionHeader(title = "本地数据", subtitle = "只读统计")
        LedgerKeyValueRow(
            label = "最近备份",
            value = backupTimeText,
            testTag = "data_last_backup",
        )
        LedgerKeyValueRow(
            label = "当前账本",
            value = "共有 ${state.totalTransactionCount} 笔有效记录",
            testTag = "data_total_count",
        )
        LedgerKeyValueRow(
            label = "本月（${LedgerCsvExporter.monthLabel(state.currentMonth)}）",
            value = "${state.currentMonthTransactionCount} 笔 · 支出 $monthExpenseText",
            testTag = "data_month_summary",
        )
        LedgerKeyValueRow(
            label = "分类 / 账户",
            value = "分类 ${state.categoryCount} 个 · 账户 ${state.accountCount} 个" +
                "（可用 ${state.activeAccountCount} 个）",
            testTag = "data_entity_counts",
        )
    }
}

@Composable
private fun TipsCard() {
    LedgerSectionCard(modifier = Modifier.testTag("data_tips_card")) {
        LedgerSectionHeader(title = "备份小提示")
        Text(
            text = "· 完整备份是本地 .blueledger.json 文件，包含全部有效账单、分类、账户、预算与设置；" +
                "回收站记录和高级功能配置也进入完整备份。",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
        Text(
            text = "· 恢复是整库替换：确认后当前数据会被备份内容覆盖；校验失败或事务失败都会保留原库。",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )
        Text(
            text = "· 单个备份上限 ${BackupLimits.MAX_BYTES / (1024 * 1024)} MiB、" +
                "${BackupLimits.MAX_TRANSACTIONS} 笔账单；超限文件会被拒绝，不做截断。",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )
    }
}

@Composable
private fun NoticeBanner(
    notice: BackupNotice,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
) {
    val actionText = if (notice.retry != null) "重试" else "知道了"
    val action = if (notice.retry != null) onRetry else onDismiss
    if (notice.level == BackupNoticeLevel.SUCCESS) {
        LedgerSuccessBanner(
            message = notice.message,
            actionText = actionText,
            onAction = action,
            testTag = "data_notice_success",
        )
    } else {
        LedgerErrorBanner(
            message = notice.message,
            actionText = actionText,
            onAction = action,
            testTag = "data_notice_error",
        )
    }
}

@Composable
private fun BusyRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("data_busy")
            .padding(vertical = BlueLedgerTokens.SpaceS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        CircularProgressIndicator(
            color = BlueLedgerTokens.Primary,
            strokeWidth = 2.dp,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "正在处理，请稍候…",
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
        )
    }
}

@Composable
private fun DialogLines(lines: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
        lines.forEach { line ->
            Text(
                text = line,
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextPrimary,
            )
        }
    }
}

@Composable
private fun CsvScopeDialog(
    dialog: DataManagementDialog.CsvScopeChoice,
    onChoose: (CsvScope) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("data_dialog_csv_scope"),
        shape = RoundedCornerShape(BlueLedgerTokens.RadiusCard),
        containerColor = BlueLedgerTokens.Surface,
        title = {
            Text(
                text = "导出账单 CSV",
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM)) {
                Text(
                    text = "金额为正数、两位小数，收支由独立列区分；备注按 CSV 规则转义并做公式防护。",
                    style = LedgerTextStyles.body,
                    color = BlueLedgerTokens.TextSecondary,
                )
                LedgerPrimaryButton(
                    text = "当前月：${dialog.monthLabel}（${dialog.currentMonthCount} 笔）",
                    onClick = { onChoose(CsvScope.CURRENT_MONTH) },
                    testTag = "data_csv_scope_current",
                )
                LedgerSecondaryButton(
                    text = "全部有效账单（${dialog.allCount} 笔）",
                    onClick = { onChoose(CsvScope.ALL) },
                    testTag = "data_csv_scope_all",
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier
                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                    .testTag("data_dialog_csv_scope_cancel"),
            ) {
                Text(
                    text = "取消",
                    style = LedgerTextStyles.button,
                    color = BlueLedgerTokens.TextSecondary,
                )
            }
        },
    )
}
