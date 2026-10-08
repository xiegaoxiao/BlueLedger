package com.blueledger.app.feature.management

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.money.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 期初余额解析结果（可正、可零、可负）。 */
sealed interface OpeningBalanceParse {
    data class Success(val cent: Long) : OpeningBalanceParse
    data class Failure(val message: String) : OpeningBalanceParse
}

/**
 * 账户期初余额的十进制解析。
 *
 * - 允许前导 `-`（负期初）与前导 `+`；允许最多两位小数；绝对值上限
 *   [Limits.MAX_OPENING_BALANCE_CENT]（9,999,999.99 元）。
 * - **不使用 Double**：整数部分与小数部分分别按十进制字符串解析。
 * - 空白输入按 0 处理（新建账户可以不填期初余额）。
 * - 个位精度不足时拒绝而不是静默四舍五入（第三位小数必须报错）。
 */
object OpeningBalance {

    fun parse(raw: String): OpeningBalanceParse {
        val text = raw.trim()
        if (text.isEmpty()) return OpeningBalanceParse.Success(0L)

        var body = text
        var negative = false
        if (body.startsWith("-")) {
            negative = true
            body = body.substring(1)
        } else if (body.startsWith("+")) {
            body = body.substring(1)
        }
        if (body.isEmpty()) return OpeningBalanceParse.Failure("请输入正确的金额")

        if (body.count { it == '.' } > 1) return OpeningBalanceParse.Failure("金额只能有一个小数点")
        if (!body.all { it in '0'..'9' || it == '.' }) {
            return OpeningBalanceParse.Failure("金额只能包含数字、小数点和负号")
        }

        val parts = body.split('.')
        val integerPart = parts[0]
        val fractionPart = if (parts.size == 2) parts[1] else ""
        if (integerPart.isEmpty() && fractionPart.isEmpty()) {
            return OpeningBalanceParse.Failure("请输入正确的金额")
        }
        if (fractionPart.length > Limits.MAX_AMOUNT_DECIMALS) {
            return OpeningBalanceParse.Failure("金额最多两位小数")
        }
        if (integerPart.length > 9) return OpeningBalanceParse.Failure(OUT_OF_RANGE_MESSAGE)

        val yuan = if (integerPart.isEmpty()) 0L else integerPart.toLongOrNull()
            ?: return OpeningBalanceParse.Failure("请输入正确的金额")
        val fen = when (fractionPart.length) {
            0 -> 0L
            1 -> fractionPart.toLong() * 10L
            else -> fractionPart.toLong()
        }
        val cent = yuan * Limits.CENT_PER_YUAN + fen
        if (cent > Limits.MAX_OPENING_BALANCE_CENT) {
            return OpeningBalanceParse.Failure(OUT_OF_RANGE_MESSAGE)
        }
        return OpeningBalanceParse.Success(if (negative) -cent else cent)
    }

    fun format(cent: Long): String = Money.format(cent)

    const val OUT_OF_RANGE_MESSAGE: String = "初始余额不能超过 9,999,999.99 元"
}

/** 账户编辑弹层状态。[accountId] 为 null 表示新增。 */
data class AccountEditorState(
    val accountId: String? = null,
    val name: String = "",
    val kind: AccountKind = AccountKind.CASH,
    val openingText: String = "0.00",
    val saving: Boolean = false,
    val errorMessage: String? = null,
    /** 编辑模式：修改前余额（全历史派生值），用于「修改前后」对比。 */
    val balanceBeforeCent: Long? = null,
    /** 编辑模式：按当前输入预计得到的新余额。 */
    val balanceAfterCent: Long? = null,
) {
    val isNew: Boolean get() = accountId == null
}

/** S09 账户管理 UI 状态。 */
data class AccountsUiState(
    val loading: Boolean = true,
    /** 全部账户（含归档），余额按全历史有效记录派生。 */
    val accounts: List<AccountWithBalance> = emptyList(),
    val defaultAccountId: String? = null,
    val editor: AccountEditorState? = null,
    val pendingArchiveId: String? = null,
    val pendingReplacementId: String? = null,
    val banner: ManagementBanner? = null,
) {
    val activeAccounts: List<AccountWithBalance> get() = accounts.filter { !it.account.isArchived }
    val archivedAccounts: List<AccountWithBalance> get() = accounts.filter { it.account.isArchived }

    val totalActiveBalanceCent: Long get() = sumActiveAccountBalance(accounts)

    fun account(id: String): AccountWithBalance? = accounts.firstOrNull { it.account.id == id }

    /** 待归档账户。 */
    val pendingArchive: AccountWithBalance? get() = pendingArchiveId?.let { account(it) }

    val isPendingDefault: Boolean
        get() = pendingArchive?.let { it.account.id == defaultAccountId } == true

    /** 最后一个未归档账户不能归档。 */
    val archiveBlockedByLastActive: Boolean
        get() = pendingArchive != null && activeAccounts.size <= 1

    /** 归档默认账户前必须先选新的默认账户。 */
    val archiveNeedsReplacement: Boolean
        get() = isPendingDefault && pendingReplacementId == null

    val canConfirmArchive: Boolean
        get() = pendingArchive != null && !archiveBlockedByLastActive && !archiveNeedsReplacement
}

/**
 * S09 账户管理的状态与规则。
 *
 * - 余额**永远来自仓库**（`期初 + 全部有效收入 − 全部有效支出`），与月份筛选无关，
 *   不在 UI 里另算一套口径。
 * - 期初余额只是账户基础数据：不生成收入账单，不影响月/年收支汇总。
 * - 名称唯一性（未归档范围内）、最后账户保护、归档默认账户需新默认
 *   由数据层裁决；这里只做即时提示并原样展示失败原因。
 */
class AccountsViewModel(
    private val repository: LedgerRepository,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AccountsUiState())
    val state: StateFlow<AccountsUiState> = _state.asStateFlow()

    init {
        scope.launch {
            repository.observeAccounts(includeArchived = true).collect { list ->
                _state.update { it.copy(loading = false, accounts = list) }
            }
        }
        scope.launch {
            repository.observeSettings().collect { settings ->
                _state.update { it.copy(defaultAccountId = settings.defaultAccountId) }
            }
        }
    }

    // ───────────────────────── 新增 / 编辑 ─────────────────────────

    fun onAddAccount() {
        _state.update {
            it.copy(
                banner = null,
                editor = AccountEditorState(
                    accountId = null,
                    name = "",
                    kind = AccountKind.CASH,
                    openingText = "0.00",
                ),
            )
        }
    }

    fun onEditAccount(id: String) {
        val current = _state.value.account(id) ?: return
        _state.update {
            it.copy(
                banner = null,
                editor = AccountEditorState(
                    accountId = current.account.id,
                    name = current.account.name,
                    kind = current.account.kind,
                    openingText = OpeningBalance.format(current.account.openingBalanceCent),
                    balanceBeforeCent = current.balanceCent,
                    balanceAfterCent = current.balanceCent,
                ),
            )
        }
    }

    fun onNameChanged(text: String) {
        // 不做静默截断：超出 1—20 字符时给出明确原因。
        _state.update { current ->
            current.editor?.let { current.copy(editor = it.copy(name = text, errorMessage = null)) } ?: current
        }
    }

    fun onKindSelected(kind: AccountKind) {
        _state.update { current ->
            current.editor?.let { current.copy(editor = it.copy(kind = kind, errorMessage = null)) } ?: current
        }
    }

    fun onOpeningChanged(text: String) {
        val limited = text.take(OPENING_INPUT_LIMIT)
        _state.update { current ->
            val editor = current.editor ?: return@update current
            val after = when (val parsed = OpeningBalance.parse(limited)) {
                is OpeningBalanceParse.Success ->
                    editor.balanceBeforeCent?.let { before ->
                        val change = current.account(editor.accountId.orEmpty())
                            ?.let { it.balanceCent - it.account.openingBalanceCent } ?: 0L
                        parsed.cent + change
                    }

                is OpeningBalanceParse.Failure -> null
            }
            // 「修改前后余额」：期初变化只改变派生余额，不会新增收入账单。
            val base = editor.balanceBeforeCent
            current.copy(
                editor = editor.copy(
                    openingText = limited,
                    errorMessage = null,
                    balanceAfterCent = if (after != null) after else base,
                ),
            )
        }
    }

    fun onEditorDismissed() {
        _state.update { if (it.editor?.saving == true) it else it.copy(editor = null) }
    }

    fun onEditorSave() {
        val editor = _state.value.editor ?: return
        if (editor.saving) return

        val name = editor.name.trim()
        validateName(name, editor.accountId)?.let { message ->
            _state.update { it.copy(editor = it.editor?.copy(errorMessage = message)) }
            return
        }
        val opening = when (val parsed = OpeningBalance.parse(editor.openingText)) {
            is OpeningBalanceParse.Success -> parsed.cent
            is OpeningBalanceParse.Failure -> {
                _state.update { it.copy(editor = it.editor?.copy(errorMessage = parsed.message)) }
                return
            }
        }

        _state.update { it.copy(editor = it.editor?.copy(saving = true, errorMessage = null)) }
        scope.launch {
            val result = repository.upsertAccount(
                AccountCommand(
                    id = editor.accountId,
                    name = name,
                    kind = editor.kind,
                    openingBalanceCent = opening,
                ),
            )
            when (result) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        editor = null,
                        banner = ManagementBanner.success(
                            if (editor.isNew) "已新增账户「$name」" else "已保存「$name」",
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(editor = it.editor?.copy(saving = false, errorMessage = result.error.readableMessage()))
                }
            }
        }
    }

    // ───────────────────────── 归档 / 恢复 / 默认账户 ─────────────────────────

    fun onRequestArchive(id: String) {
        val active = _state.value.activeAccounts
        if (active.size <= 1 && active.any { it.account.id == id }) {
            _state.update { it.copy(banner = ManagementBanner.error("至少保留一个未归档账户")) }
            return
        }
        _state.update {
            it.copy(
                pendingArchiveId = id,
                pendingReplacementId = if (it.defaultAccountId == id) {
                    it.activeAccounts.firstOrNull { account -> account.account.id != id }?.account?.id
                } else {
                    null
                },
                banner = null,
            )
        }
    }

    fun onCancelArchive() {
        _state.update { it.copy(pendingArchiveId = null, pendingReplacementId = null) }
    }

    fun onReplacementSelected(id: String) {
        _state.update { it.copy(pendingReplacementId = id) }
    }

    fun onConfirmArchive() {
        val current = _state.value
        val target = current.pendingArchive ?: return
        if (!current.canConfirmArchive) {
            // 不静默失败：关闭弹层并明确说明为什么不能归档。
            val reason = when {
                current.archiveBlockedByLastActive -> "至少保留一个未归档账户"
                current.archiveNeedsReplacement -> "归档默认账户前请先选择新的默认账户"
                else -> "当前无法归档该账户"
            }
            _state.update {
                it.copy(
                    pendingArchiveId = null,
                    pendingReplacementId = null,
                    banner = ManagementBanner.error(reason),
                )
            }
            return
        }
        val replacement = if (current.isPendingDefault) current.pendingReplacementId else null
        _state.update { it.copy(pendingArchiveId = null, pendingReplacementId = null) }
        scope.launch {
            when (val result = repository.setAccountArchived(target.account.id, true, replacement)) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        banner = ManagementBanner.success(
                            "已归档「${target.account.name}」，历史账单与统计不受影响",
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onRestoreAccount(id: String) {
        val target = _state.value.account(id) ?: return
        scope.launch {
            when (val result = repository.setAccountArchived(target.account.id, false)) {
                is MutationResult.Success -> _state.update {
                    it.copy(banner = ManagementBanner.success("已恢复「${target.account.name}」"))
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onSetDefaultAccount(id: String) {
        scope.launch {
            when (val result = repository.setDefaultAccount(id)) {
                is MutationResult.Success -> _state.update {
                    val name = it.account(id)?.account?.name.orEmpty()
                    it.copy(banner = ManagementBanner.success("已将「$name」设为默认账户"))
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onBannerShown() {
        _state.update { it.copy(banner = null) }
    }

    private fun validateName(name: String, selfId: String?): String? {
        if (name.length < Limits.MIN_ACCOUNT_NAME_LENGTH) return "请输入账户名称"
        if (name.length > Limits.MAX_ACCOUNT_NAME_LENGTH) {
            return "账户名称最多 ${Limits.MAX_ACCOUNT_NAME_LENGTH} 个字符"
        }
        val duplicate = _state.value.activeAccounts.any {
            it.account.id != selfId && it.account.name == name
        }
        return if (duplicate) "已存在名为「$name」的账户" else null
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        const val NAME_INPUT_LIMIT: Int = Limits.MAX_ACCOUNT_NAME_LENGTH
        const val OPENING_INPUT_LIMIT: Int = 16
    }
}

/**
 * 全部未归档账户余额之和（仅展示用）。
 *
 * 溢出时返回 [Long.MAX_VALUE] 而不是回绕成负数——账户余额本身仍是精确的整数分，
 * 这里只是聚合视图。
 */
internal fun sumActiveAccountBalance(accounts: List<AccountWithBalance>): Long {
    var total = 0L
    for (entry in accounts) {
        if (entry.account.isArchived) continue
        total = runCatching { Math.addExact(total, entry.balanceCent) }.getOrElse { return Long.MAX_VALUE }
    }
    return total
}
