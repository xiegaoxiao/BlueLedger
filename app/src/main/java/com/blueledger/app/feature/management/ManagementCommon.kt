package com.blueledger.app.feature.management

import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.MutationResult
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A5（S07—S10）管理功能的公共小工具。
 *
 * 纪律：
 * - **绝不吞掉失败当成功**：仓库返回的 [LedgerError] 一律转成可读文案展示给用户，
 *   成功与失败使用两种明显不同的反馈（[LedgerSuccessBanner] / [LedgerErrorBanner]）。
 * - 本文件不定义任何色值/尺寸，颜色与间距全部来自 `app/ui/AppTheme.kt` 的令牌
 *   与 A2 的 `core/designsystem` 组件。
 */

/** 仓库错误 → 用户可读文案。数据层已经给出中文 message，这里只做空值兜底。 */
fun LedgerError.readableMessage(): String = message.ifBlank { "操作失败，请重试" }

/** 写操作失败时的展示文案；成功返回 null（调用方不得把 null 当作失败）。 */
fun MutationResult.failureMessage(): String? =
    (this as? MutationResult.Failure)?.error?.readableMessage()

/** 写操作成功时的稳定 id（新增分类/账户时用于后续选中）。 */
fun MutationResult.successId(): String? = (this as? MutationResult.Success)?.id

/** 「2026 年 10 月」。 */
fun YearMonth.chineseLabel(): String = "$year 年 $monthValue 月"

/** 「2026-10-07 15:30」，备份时间等审计时间戳的展示格式。 */
fun Instant.displayDateTime(zoneId: ZoneId): String =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zoneId).format(this)

/** 页面内的提示横幅内容。 */
data class ManagementBanner(
    val message: String,
    val isError: Boolean,
) {
    companion object {
        fun success(message: String): ManagementBanner = ManagementBanner(message, isError = false)
        fun error(message: String): ManagementBanner = ManagementBanner(message, isError = true)
    }
}

/**
 * A5 页面的稳定测试标识。
 *
 * 与 A7 的验收约定：新增 selector 只往里加，不改已有值；<id> 一律使用
 * `Defaults` / 仓库返回的稳定 ID（如 `cat_expense_food`、`acc_default`）。
 */
object ManagementTags {

    // ── S10 我的与设置 ──
    const val MINE_SCREEN = "mine_screen"
    const val MINE_ENTRY_CATEGORIES = "mine_entry_categories"
    const val MINE_ENTRY_BUDGET = "mine_entry_budget"
    const val MINE_ENTRY_ACCOUNTS = "mine_entry_accounts"
    const val MINE_ENTRY_DATA = "mine_entry_data"
    const val MINE_DEFAULT_ACCOUNT_ROW = "mine_default_account_row"
    const val MINE_HIDE_AMOUNTS_SWITCH = "mine_hide_amounts_switch"
    const val MINE_THEME_ROW = "mine_theme_row"
    const val MINE_VERSION_ROW = "mine_version_row"
    const val MINE_BACKUP_STATUS = "mine_backup_status"
    const val MINE_IDENTITY_CARD = "mine_identity_card"
    const val MINE_ACCOUNT_PICKER_DIALOG = "mine_account_picker_dialog"

    // ── S07 分类管理 ──
    const val CATEGORIES_SCREEN = "categories_screen"
    const val CATEGORIES_TYPE_EXPENSE = "categories_type_expense"
    const val CATEGORIES_TYPE_INCOME = "categories_type_income"
    const val CATEGORIES_ADD = "categories_add"
    const val CATEGORIES_ACTIVE_COUNT = "categories_active_count"
    const val CATEGORIES_EDITOR_DIALOG = "dialog_category_editor"
    const val CATEGORIES_NAME_FIELD = "field_category_name"
    const val CATEGORIES_SAVE = "btn_category_save"
    const val CATEGORIES_CANCEL = "btn_category_cancel"
    const val CATEGORIES_ARCHIVE_DIALOG = "dialog_archive_category"
    const val CATEGORIES_ARCHIVED_HEADER = "categories_archived_header"
    const val CATEGORIES_BANNER = "categories_banner"
    const val CATEGORIES_FALLBACK_HINT = "categories_fallback_hint"

    fun categoryRow(id: String) = "cat_row_$id"
    fun categoryMoveUp(id: String) = "btn_cat_up_$id"
    fun categoryMoveDown(id: String) = "btn_cat_down_$id"
    fun categoryEdit(id: String) = "btn_cat_edit_$id"
    fun categoryArchive(id: String) = "btn_cat_archive_$id"
    fun categoryRestore(id: String) = "btn_cat_restore_$id"
    fun categoryIconOption(key: String) = "icon_option_$key"
    fun categoryIconGroup(title: String) = "category_icon_group_$title"

    // ── S08 月度预算 ──
    const val BUDGET_SCREEN = "budget_screen"
    const val BUDGET_MONTH_LABEL = "budget_month_label"
    const val BUDGET_PREV_MONTH = "btn_budget_prev_month"
    const val BUDGET_NEXT_MONTH = "btn_budget_next_month"
    const val BUDGET_UNSET_HINT = "budget_unset_hint"
    const val BUDGET_PROGRESS_TRACK = "budget_progress_track"
    const val BUDGET_PROGRESS_FILL = "budget_progress_fill"
    const val BUDGET_STATUS_TEXT = "budget_status_text"
    const val BUDGET_RATIO_TEXT = "budget_ratio_text"
    const val BUDGET_REMAINING_TEXT = "budget_remaining_text"
    const val BUDGET_USED_TEXT = "budget_used_text"
    const val BUDGET_AMOUNT_TEXT = "budget_amount_text"
    const val BUDGET_EDIT = "btn_budget_edit"
    const val BUDGET_REUSE_PREVIOUS = "btn_budget_reuse_previous"
    const val BUDGET_AMOUNT_FIELD = "field_budget_amount"
    const val BUDGET_SAVE = "btn_budget_save"
    const val BUDGET_REMOVE = "btn_budget_remove"
    const val BUDGET_REMOVE_DIALOG = "dialog_remove_budget"
    const val BUDGET_ERROR = "budget_error"
    const val BUDGET_BANNER = "budget_banner"
    const val BUDGET_LOADING = "budget_loading"

    // ── S09 账户管理 ──
    const val ACCOUNTS_SCREEN = "accounts_screen"
    const val ACCOUNTS_TOTAL_BALANCE = "accounts_total_balance"
    const val ACCOUNTS_ADD = "accounts_add"
    const val ACCOUNTS_EDITOR_DIALOG = "dialog_account_editor"
    const val ACCOUNTS_NAME_FIELD = "field_account_name"
    const val ACCOUNTS_OPENING_FIELD = "field_account_opening"
    const val ACCOUNTS_BALANCE_PREVIEW = "account_balance_preview"
    const val ACCOUNTS_SAVE = "btn_account_save"
    const val ACCOUNTS_CANCEL = "btn_account_cancel"
    const val ACCOUNTS_ARCHIVE_DIALOG = "dialog_archive_account"
    const val ACCOUNTS_ARCHIVED_HEADER = "accounts_archived_header"
    const val ACCOUNTS_BANNER = "accounts_banner"
    const val ACCOUNTS_LOADING = "accounts_loading"

    fun accountRow(id: String) = "acct_row_$id"
    fun accountEdit(id: String) = "btn_account_edit_$id"
    fun accountArchive(id: String) = "btn_account_archive_$id"
    fun accountRestore(id: String) = "btn_account_restore_$id"
    fun accountSetDefault(id: String) = "btn_account_default_$id"
    fun accountTransactions(id: String) = "btn_account_txns_$id"
    fun accountReplacementOption(id: String) = "replacement_option_$id"
    fun accountKindOption(kind: String) = "kind_option_$kind"
}
