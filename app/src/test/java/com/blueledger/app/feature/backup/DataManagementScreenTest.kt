package com.blueledger.app.feature.backup

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.app.ui.BlueLedgerTheme
import com.blueledger.app.core.model.BackupRecord
import com.blueledger.app.core.model.BackupSummary
import com.blueledger.app.core.model.CsvScope
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.YearMonth

/**
 * S11 Compose 界面测试（Robolectric 真实组合与布局）。
 *
 * 用固定 [DataManagementUiState] 渲染无状态页面，验证：
 * 三个入口可点击、状态卡文案、金额隐藏掩码、忙碌禁用、错误提示可重试、
 * 恢复确认对话框展示备份时间与记录数、CSV 范围对话框回调。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xhdpi")
class DataManagementScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun uiState(
        loading: Boolean = false,
        busy: BackupOperation? = null,
        dialog: DataManagementDialog? = null,
        notice: BackupNotice? = null,
        hideAmounts: Boolean = false,
        backupRecord: BackupRecord = BackupRecord(succeededAt = null, fileName = null),
    ): DataManagementUiState = DataManagementUiState(
        currentMonth = YearMonth.of(2026, 10),
        loading = loading,
        hideAmounts = hideAmounts,
        totalTransactionCount = BackupFx.EFFECTIVE_TRANSACTION_COUNT,
        currentMonthTransactionCount = BackupFx.CURRENT_MONTH_TRANSACTION_COUNT,
        monthIncomeCent = 1_000_000L,
        monthExpenseCent = 2_850L,
        categoryCount = BackupFx.categories().size,
        accountCount = BackupFx.accounts().size,
        activeAccountCount = 3,
        backupRecord = backupRecord,
        busy = busy,
        dialog = dialog,
        notice = notice,
    )

    private fun setContent(
        state: DataManagementUiState,
        hideAmounts: Boolean = false,
        onExportBackup: () -> Unit = {},
        onExportCsv: () -> Unit = {},
        onRestore: () -> Unit = {},
        onCsvScopeChosen: (CsvScope) -> Unit = {},
        onDialogConfirmed: () -> Unit = {},
        onDialogDismissed: () -> Unit = {},
        onNoticeDismissed: () -> Unit = {},
        onNoticeRetry: () -> Unit = {},
        onRetryLoad: () -> Unit = {},
    ) {
        composeRule.setContent {
            BlueLedgerTheme(hideAmounts = hideAmounts) {
                DataManagementScreen(
                    state = state,
                    zoneId = BackupTestClock.DEFAULT_ZONE,
                    onBack = {},
                    onExportBackup = onExportBackup,
                    onExportCsv = onExportCsv,
                    onRestore = onRestore,
                    onCsvScopeChosen = onCsvScopeChosen,
                    onDialogConfirmed = onDialogConfirmed,
                    onDialogDismissed = onDialogDismissed,
                    onNoticeDismissed = onNoticeDismissed,
                    onNoticeRetry = onNoticeRetry,
                    onRetryLoad = onRetryLoad,
                )
            }
        }
    }

    @Test
    fun `三个入口与本地数据卡真实渲染`() {
        setContent(uiState())

        composeRule.onNodeWithText("数据管理").assertIsDisplayed()
        composeRule.onNodeWithTag("data_action_csv").assertIsDisplayed()
        composeRule.onNodeWithTag("data_action_backup").assertIsDisplayed()
        composeRule.onNodeWithTag("data_action_restore").assertIsDisplayed()
        composeRule.onNodeWithText("导出账单 CSV").assertIsDisplayed()
        composeRule.onNodeWithText("备份完整账本").assertIsDisplayed()
        composeRule.onNodeWithText("从备份恢复").assertIsDisplayed()
    }

    @Test
    fun `状态卡展示最近备份 记录数与实体数量`() {
        setContent(
            uiState(
                backupRecord = BackupRecord(
                    succeededAt = BackupTestClock.DEFAULT_INSTANT,
                    fileName = "蓝记备份.blueledger.json",
                ),
            ),
        )

        composeRule.onNodeWithText("最近备份").assertIsDisplayed()
        // 固定时钟 2026-10-07T00:00+08:00[Asia/Shanghai] → 本地时间文案为 2026-10-07 00:00。
        composeRule.onNodeWithText("2026-10-07 00:00（蓝记备份.blueledger.json）").assertIsDisplayed()
        composeRule.onNodeWithText("共有 3 笔有效记录").assertIsDisplayed()
        composeRule.onNodeWithText("2 笔 · 支出 ¥28.50").assertIsDisplayed()
        composeRule.onNodeWithText("分类 ${BackupFx.categories().size} 个 · 账户 4 个（可用 3 个）").assertIsDisplayed()
    }

    @Test
    fun `尚未备份时显示占位文案`() {
        setContent(uiState())
        composeRule.onNodeWithText("尚未备份").assertIsDisplayed()
    }

    @Test
    fun `金额隐藏时本月支出显示掩码且不泄露金额`() {
        setContent(uiState(), hideAmounts = true)

        composeRule.onNodeWithText("2 笔 · 支出 $LEDGER_MASK").assertIsDisplayed()
        assertTrue(
            "隐藏时不得出现真实金额",
            composeRule.onAllNodesWithText("28.50", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `忙碌时入口禁用并显示处理中`() {
        var exportClicked = 0
        setContent(uiState(busy = BackupOperation.EXPORTING), onExportBackup = { exportClicked++ })

        composeRule.onNodeWithTag("data_busy").assertIsDisplayed()
        composeRule.onNodeWithTag("data_action_backup").assertIsNotEnabled()
        composeRule.onNodeWithTag("data_action_csv").assertIsNotEnabled()
        composeRule.onNodeWithTag("data_action_restore").assertIsNotEnabled()
        composeRule.onNodeWithTag("data_action_backup").performClick()
        composeRule.waitForIdle()
        assertEquals("禁用状态下的点击不应触发导出", 0, exportClicked)
    }

    @Test
    fun `入口点击回调正确`() {
        var backup = 0
        var csv = 0
        var restore = 0
        setContent(
            uiState(),
            onExportBackup = { backup++ },
            onExportCsv = { csv++ },
            onRestore = { restore++ },
        )

        composeRule.onNodeWithTag("data_action_backup").performClick()
        composeRule.onNodeWithTag("data_action_csv").performClick()
        composeRule.onNodeWithTag("data_action_restore").performClick()

        assertEquals(1, backup)
        assertEquals(1, csv)
        assertEquals(1, restore)
    }

    @Test
    fun `失败提示展示具体原因并可重试`() {
        var retried = 0
        setContent(
            uiState(
                notice = BackupNotice(
                    level = BackupNoticeLevel.ERROR,
                    message = "无法恢复：备份文件版本不受支持（文件为 v2，当前支持 v1），未做猜测式迁移。当前数据未改变。",
                    retry = BackupRetry.IMPORT,
                ),
            ),
            onNoticeRetry = { retried++ },
        )

        composeRule.onNodeWithTag("data_notice_error").assertIsDisplayed()
        composeRule.onNodeWithText("重试").assertIsDisplayed()
        composeRule.onNodeWithText("重试").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `成功提示不显示为错误`() {
        setContent(
            uiState(
                notice = BackupNotice(
                    level = BackupNoticeLevel.SUCCESS,
                    message = "已导出完整备份：3 条记录 → 蓝记备份-2026-10-07.blueledger.json",
                ),
            ),
        )
        composeRule.onNodeWithTag("data_notice_success").assertIsDisplayed()
        assertTrue(
            composeRule.onAllNodesWithText("已导出完整备份", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `恢复确认对话框展示备份时间 记录数与覆盖范围`() {
        val summary = BackupSummary(
            product = "blueledger",
            schemaVersion = 1,
            exportedAt = BackupTestClock.DEFAULT_INSTANT,
            currency = "CNY",
            transactionCount = 3,
            categoryCount = 16,
            accountCount = 4,
            budgetCount = 1,
            earliestOccurredOn = java.time.LocalDate.of(2026, 9, 30),
            latestOccurredOn = BackupFx.TODAY,
            budgetMonths = listOf(BackupFx.OCT_2026),
            fileName = "蓝记备份.blueledger.json",
        )
        var confirmed = 0
        var dismissed = 0
        setContent(
            uiState(
                dialog = DataManagementDialog.RestoreConfirm(
                    summary = summary,
                    lines = listOf(
                        "备份文件：蓝记备份.blueledger.json",
                        "备份时间：2026-10-07 00:00",
                        "账单：3 笔（2026-09-30 至 2026-10-07）",
                        "分类 16 个、账户 4 个",
                        "预算 1 个月：2026-10",
                    ),
                ),
            ),
            onDialogConfirmed = { confirmed++ },
            onDialogDismissed = { dismissed++ },
        )

        composeRule.onNodeWithTag("data_dialog_restore").assertIsDisplayed()
        composeRule.onNodeWithText("覆盖恢复？").assertIsDisplayed()
        composeRule.onNodeWithText("备份时间：2026-10-07 00:00").assertIsDisplayed()
        composeRule.onNodeWithText("账单：3 笔（2026-09-30 至 2026-10-07）").assertIsDisplayed()
        composeRule.onNodeWithText("预算 1 个月：2026-10").assertIsDisplayed()

        composeRule.onNodeWithTag("data_dialog_restore_cancel").performClick()
        assertEquals("取消必须先于确认生效", 1, dismissed)
        assertEquals(0, confirmed)
    }

    @Test
    fun `CSV 范围对话框两个范围都可选择`() {
        var chosen: CsvScope? = null
        setContent(
            uiState(
                dialog = DataManagementDialog.CsvScopeChoice(
                    monthLabel = "2026 年 10 月",
                    currentMonthCount = BackupFx.CURRENT_MONTH_TRANSACTION_COUNT,
                    allCount = BackupFx.EFFECTIVE_TRANSACTION_COUNT,
                ),
            ),
            onCsvScopeChosen = { chosen = it },
        )

        composeRule.onNodeWithTag("data_dialog_csv_scope").assertIsDisplayed()
        composeRule.onNodeWithText("当前月：2026 年 10 月（2 笔）").assertIsDisplayed()
        composeRule.onNodeWithText("全部有效账单（3 笔）").assertIsDisplayed()

        composeRule.onNodeWithTag("data_csv_scope_all").performClick()
        assertEquals(CsvScope.ALL, chosen)
    }

    @Test
    fun `导出确认对话框展示范围与记录数`() {
        setContent(
            uiState(
                dialog = DataManagementDialog.ExportConfirm(
                    kind = ExportKind.BACKUP,
                    fileName = "蓝记备份-2026-10-07.blueledger.json",
                    lines = listOf(
                        "范围：全部有效账单 + 全部分类、账户、预算与设置",
                        "有效账单 3 笔、分类 16 个、账户 4 个、预算 1 个月",
                    ),
                ),
            ),
        )
        composeRule.onNodeWithTag("data_dialog_export").assertIsDisplayed()
        composeRule.onNodeWithText("范围：全部有效账单 + 全部分类、账户、预算与设置").assertIsDisplayed()
        composeRule.onNodeWithText("文件名：蓝记备份-2026-10-07.blueledger.json").assertIsDisplayed()
    }

    @Test
    fun `本版不提供清空全部账本入口`() {
        setContent(uiState())
        assertFalse(
            "已裁决本版不提供清空账本入口",
            composeRule.onAllNodesWithText("清空", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `读取失败显示错误与重试，不伪装成空账本`() {
        var retried = 0
        setContent(
            uiState(loading = false).copy(
                loadError = com.blueledger.app.core.model.LedgerError.Storage("本地数据库暂时不可用，请重试"),
            ),
            onRetryLoad = { retried++ },
        )
        composeRule.onNodeWithTag("data_load_error").assertIsDisplayed()
        composeRule.onNodeWithText("本地数据库暂时不可用，请重试").assertIsDisplayed()
        composeRule.onNodeWithTag("data_load_error_retry").performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `加载中不显示空账本`() {
        setContent(uiState(loading = true))
        composeRule.onNodeWithTag("data_loading").assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithText("共有 3 笔有效记录").fetchSemanticsNodes().isEmpty())
    }

    private companion object {
        const val LEDGER_MASK = "••••"
    }
}
