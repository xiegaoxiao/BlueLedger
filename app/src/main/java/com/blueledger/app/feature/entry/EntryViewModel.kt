package com.blueledger.app.feature.entry

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.LedgerMoney
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

/**
 * S02 新增/编辑记账的 ViewModel。
 *
 * 只依赖冻结的 [LedgerRepository] 契约与可注入 [Clock]：
 * - 新增与编辑共用同一状态模型；编辑保留原 id 与 createdAt（由数据层 updateTransaction 保证）。
 * - 金额解析全部走 [AmountInput]（Long 整数分，无浮点）。
 * - 保存使用稳定的 requestId：同一用户提交的失败重试与双击不会重复入账。
 * - 分类/账户列表始终来自仓库，归档的历史关联在编辑模式下保留为可选项。
 */
class EntryViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
    private val editTransactionId: String?,
    initialType: TransactionType?,
    initialCategoryId: String?,
    private val preferredAccounts: Map<TransactionType, String> = emptyMap(),
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        EntryUiState(
            mode = if (editTransactionId == null) EntryMode.CREATE else EntryMode.EDIT,
            loading = editTransactionId != null,
            type = initialType ?: TransactionType.EXPENSE,
            occurredOn = clock.today(),
            today = clock.today(),
            // 首页快捷分类预选仅在新增模式生效；编辑模式以原账单为准。
            selectedCategoryId = if (editTransactionId == null) initialCategoryId else null,
        ),
    )
    val state: StateFlow<EntryUiState> = _state.asStateFlow()

    private val exitChannel = Channel<EntryEvent>(Channel.BUFFERED)
    val events = exitChannel.receiveAsFlow()

    /** 最近一次拉到的完整分类/账户列表（含归档），用于编辑模式保留原归档关联。 */
    private var latestCategories: List<LedgerCategory> = emptyList()
    private var latestAccounts: List<AccountWithBalance> = emptyList()
    private var latestSettings: LedgerSettings? = null

    /** 用户改动基线，用于「未保存修改」判断。 */
    private var baseline: FormSnapshot? = null

    /** 用户是否手动选过账户（选过之后不再被「最近使用/默认账户」覆盖）。 */
    private var userPickedAccount = false

    /** 一次提交内保持稳定的 requestId：失败重试不会重复新增。 */
    private var pendingRequestId: String? = null

    init {
        if (editTransactionId == null) {
            // 新增模式的基线：金额/备注为空，日期为今天，账户在解析出默认账户后补上。
            baseline = FormSnapshot(
                type = initialType ?: TransactionType.EXPENSE,
                amountText = "",
                categoryId = initialCategoryId,
                occurredOn = clock.today(),
                accountId = null,
                note = "",
            )
        }
        scope.launch {
            if (editTransactionId == null) {
                _state.update { it.copy(loading = false) }
            } else {
                val tx = repository.observeTransaction(editTransactionId).first()
                if (tx == null) {
                    _state.update { it.copy(loading = false, missingTransaction = true) }
                    return@launch
                }
                applyLoadedTransaction(tx)
            }
            observeFormData()
        }
    }

    // ───────────────────────── 数据订阅 ─────────────────────────

    private fun observeFormData() {
        scope.launch {
            _state
                .map { it.type }
                .distinctUntilChanged()
                .collectLatest { type ->
                    repository.observeCategories(type, includeArchived = true).collect { list ->
                        latestCategories = list
                        applyCategories()
                    }
                }
        }
        scope.launch {
            repository.observeAccounts(includeArchived = true).collect { list ->
                latestAccounts = list
                applyAccounts()
            }
        }
        scope.launch {
            repository.observeSettings().collect { settings ->
                latestSettings = settings
                applyAccounts()
            }
        }
    }

    private fun applyLoadedTransaction(tx: LedgerTransaction) {
        _state.update {
            it.copy(
                loading = false,
                transactionId = tx.id,
                type = tx.type,
                amount = AmountInput.fromCents(tx.amountCent),
                selectedCategoryId = tx.categoryId,
                occurredOn = tx.occurredOn,
                selectedAccountId = tx.accountId,
                note = tx.note,
            )
        }
        baseline = FormSnapshot(
            type = tx.type,
            amountText = AmountInput.fromCents(tx.amountCent).text,
            categoryId = tx.categoryId,
            occurredOn = tx.occurredOn,
            accountId = tx.accountId,
            note = tx.note,
        )
        // 原关联可能是已归档项，必须在列表中保留为当前唯一可选项。
        applyCategories()
        applyAccounts()
    }

    private fun applyCategories() {
        val current = _state.value
        val selectedId = current.selectedCategoryId
        val visible = latestCategories
            .filter { !it.isArchived || it.id == selectedId }
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        val keep = keepExistingSelection(selectedId, visible.map { it.id })
        _state.update { it.copy(categories = visible, selectedCategoryId = keep) }
    }

    /**
     * 列表尚未加载（空列表）时保留原选择；已加载但不含该 id 时才清空，
     * 避免编辑模式下因为一次空发射丢失原账户/分类关联。
     */
    private fun keepExistingSelection(selectedId: String?, availableIds: List<String>): String? = when {
        selectedId == null -> null
        availableIds.isEmpty() -> selectedId
        availableIds.contains(selectedId) -> selectedId
        else -> null
    }

    private fun applyAccounts() {
        val current = _state.value
        val selectedId = current.selectedAccountId
        val visible = latestAccounts
            .filter { !it.account.isArchived || it.account.id == selectedId }
            .sortedWith(compareBy({ it.account.isArchived }, { it.account.name }, { it.account.id }))
        val keep = keepExistingSelection(selectedId, visible.map { it.account.id })
        val resolved = when {
            keep != null -> keep
            current.mode == EntryMode.CREATE && selectedId == null && !userPickedAccount ->
                defaultAccountId(visible, latestSettings)

            else -> null
        }
        _state.update { it.copy(accounts = visible, selectedAccountId = resolved) }

        // 新增模式的基线补上自动选中的账户，避免刚进页面就被判为「已修改」。
        val base = baseline
        if (base != null && base.accountId == null && resolved != null) {
            baseline = base.copy(accountId = resolved)
        }
    }

    /** 账户默认：最近有效使用的账户 → 默认账户 → 第一个可用账户（PRD S02）。 */
    private fun defaultAccountId(
        visible: List<AccountWithBalance>,
        settings: LedgerSettings?,
    ): String? {
        val active = visible.filter { !it.account.isArchived }
        preferredAccounts[_state.value.type]?.let { preferred ->
            if (active.any { it.account.id == preferred }) return preferred
        }
        val lastUsed = settings?.lastUsedAccountId
        if (lastUsed != null && active.any { it.account.id == lastUsed }) return lastUsed
        val defaultId = settings?.defaultAccountId
        if (defaultId != null && active.any { it.account.id == defaultId }) return defaultId
        return active.firstOrNull()?.account?.id
    }

    // ───────────────────────── 金额键盘 ─────────────────────────

    fun onDigit(digit: Char) {
        _state.update {
            it.copy(amount = it.amount.appendDigit(digit), amountError = null, savedMessage = null)
        }
    }

    fun onDecimalPoint() {
        _state.update {
            it.copy(amount = it.amount.appendDecimalPoint(), amountError = null, savedMessage = null)
        }
    }

    fun onDelete() {
        _state.update {
            if (it.amount.isEmpty && it.calculation != null) {
                it.copy(amount = AmountInput.fromCents(it.calculation.leftCent), calculation = null, amountError = null)
            } else it.copy(amount = it.amount.deleteLast(), amountError = null, savedMessage = null)
        }
    }

    fun onClearAmount() {
        _state.update {
            it.copy(amount = it.amount.clear(), calculation = null, amountError = null, savedMessage = null)
        }
    }

    fun onOperator(operator: Char) {
        require(operator == '+' || operator == '-')
        _state.update { current ->
            val value = if (current.calculation != null && !current.amount.isEmpty) {
                current.calculation.result(current.amount)
            } else current.calculation?.leftCent ?: current.amount.centsOrNull()
            if (value == null) current.copy(amountError = "计算结果须在 0 至 9,999,999.99 元之间")
            else current.copy(calculation = AmountCalculation(value, operator), amount = AmountInput(), amountError = null)
        }
    }

    fun onCalculate(): Boolean {
        val current = _state.value
        val calculation = current.calculation ?: return true
        val value = calculation.result(current.amount)
        if (value == null) {
            _state.update { it.copy(amountError = "请完成运算，结果须在 0 至 9,999,999.99 元之间") }
            return false
        }
        _state.update { it.copy(amount = if (value == 0L) AmountInput("0") else AmountInput.fromCents(value), calculation = null, amountError = null) }
        return true
    }

    // ───────────────────────── 表单 ─────────────────────────

    fun onTypeSelected(type: TransactionType) {
        if (_state.value.type == type) return
        // 切换类型：更新分类集合并清空已选分类，保留金额/日期/账户/备注。
        _state.update {
            it.copy(type = type, selectedCategoryId = null, categoryError = null, savedMessage = null,
                selectedAccountId = if (!userPickedAccount && !it.isEditMode) null else it.selectedAccountId)
        }
        applyCategories()
        applyAccounts()
    }

    fun onCategorySelected(categoryId: String) {
        _state.update { it.copy(selectedCategoryId = categoryId, categoryError = null) }
    }

    fun onDateSelected(date: LocalDate) {
        if (date.isAfter(clock.today())) {
            _state.update { it.copy(dateError = "暂不支持记录未来日期") }
            return
        }
        _state.update { it.copy(occurredOn = date, dateError = null) }
    }

    fun onAccountSelected(accountId: String) {
        userPickedAccount = true
        _state.update { it.copy(selectedAccountId = accountId, accountError = null) }
    }

    fun onNoteChanged(text: String) {
        _state.update { it.copy(note = text.take(Limits.MAX_NOTE_LENGTH), saveError = null) }
    }

    /** 备注获得/失去焦点：系统 IME 与自定义数字键盘绝不叠加。 */
    fun onNoteFocusChanged(focused: Boolean) {
        _state.update { it.copy(numericKeyboardVisible = !focused) }
    }

    fun onSavedMessageShown() {
        _state.update { it.copy(savedMessage = null) }
    }

    fun onSaveErrorShown() {
        _state.update { it.copy(saveError = null) }
    }

    // ───────────────────────── 退出 ─────────────────────────

    /** 返回/关闭：有未保存修改时先确认，否则直接退出。 */
    fun onBackPressed() {
        val current = _state.value
        if (current.saving) return
        if (hasUnsavedChanges()) {
            _state.update { it.copy(showDiscardDialog = true) }
        } else {
            requestExit()
        }
    }

    fun onDiscardConfirmed() {
        _state.update { it.copy(showDiscardDialog = false) }
        requestExit()
    }

    fun onDiscardDismissed() {
        _state.update { it.copy(showDiscardDialog = false) }
    }

    private fun requestExit() {
        scope.launch { exitChannel.send(EntryEvent.Exit) }
    }

    fun hasUnsavedChanges(): Boolean {
        val base = baseline ?: return _state.value.amount.text.isNotEmpty()
        return _state.value.calculation != null || _state.value.toSnapshot() != base
    }

    // ───────────────────────── 保存 ─────────────────────────

    fun onSave() = save(andNew = false)

    fun onSaveAndNew() {
        if (_state.value.isEditMode) return
        save(andNew = true)
    }

    private fun save(andNew: Boolean) {
        if (!onCalculate()) return
        val current = _state.value
        if (current.saving || current.loading || current.missingTransaction) return

        validate(current)?.let { applyError ->
            applyError()
            return
        }
        val cents = current.amountCents ?: return
        val categoryId = current.selectedCategoryId ?: return
        val accountId = current.selectedAccountId ?: return

        // 先同步置位，避免同一帧内的第二次点击重复提交。
        _state.update { it.copy(saving = true, saveError = null, savedMessage = null) }

        val requestId = pendingRequestId ?: UUID.randomUUID().toString().also { pendingRequestId = it }
        val draft = TransactionDraft(
            type = current.type,
            amountCent = cents,
            categoryId = categoryId,
            accountId = accountId,
            occurredOn = current.occurredOn,
            note = current.note,
        )

        scope.launch {
            val transactionId = current.transactionId
            val result = if (current.isEditMode && transactionId != null) {
                repository.updateTransaction(transactionId, draft)
            } else {
                repository.createTransaction(draft, requestId)
            }
            when (result) {
                is SaveResult.Success -> {
                    pendingRequestId = null
                    if (current.isEditMode || !andNew) {
                        requestExit()
                    } else {
                        // 连续记账：保留类型/日期/账户，清空金额/分类/备注，键盘继续可用。
                        _state.update {
                            it.copy(
                                saving = false,
                                amount = AmountInput(),
                                selectedCategoryId = null,
                                note = "",
                                amountError = null,
                                categoryError = null,
                                accountError = null,
                                saveError = null,
                                numericKeyboardVisible = true,
                                savedMessage = "已记下 ¥" + LedgerMoney.format(cents),
                            )
                        }
                        baseline = _state.value.toSnapshot()
                    }
                }

                is SaveResult.Failure -> {
                    // 失败保留全部输入，并给出可重试的错误提示。
                    _state.update { it.copy(saving = false, saveError = result.error.message) }
                }
            }
        }
    }

    /** 返回非空 lambda 表示校验失败，调用方执行它以更新错误态。 */
    private fun validate(current: EntryUiState): (() -> Unit)? {
        when (val amount = current.amount.validate()) {
            is AmountValidation.Invalid -> return {
                _state.update { it.copy(amountError = amount.message, saving = false) }
            }

            is AmountValidation.Valid -> Unit
        }
        if (current.selectedCategoryId == null) {
            return { _state.update { it.copy(categoryError = "请选择分类", saving = false) } }
        }
        if (current.categories.none { it.id == current.selectedCategoryId }) {
            return { _state.update { it.copy(categoryError = "请选择可用的分类", saving = false) } }
        }
        if (current.selectedAccountId == null) {
            return { _state.update { it.copy(accountError = "请选择账户", saving = false) } }
        }
        if (current.occurredOn.isAfter(clock.today())) {
            return { _state.update { it.copy(dateError = "暂不支持记录未来日期", saving = false) } }
        }
        if (current.note.length > Limits.MAX_NOTE_LENGTH) {
            return {
                _state.update { it.copy(saveError = "备注最多 ${Limits.MAX_NOTE_LENGTH} 个字符", saving = false) }
            }
        }
        return null
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}

/** 用于「未保存修改」判断的规范化快照。 */
private data class FormSnapshot(
    val type: TransactionType,
    val amountText: String,
    val categoryId: String?,
    val occurredOn: LocalDate,
    val accountId: String?,
    val note: String,
)

private fun EntryUiState.toSnapshot(): FormSnapshot = FormSnapshot(
    type = type,
    amountText = amount.text,
    categoryId = selectedCategoryId,
    occurredOn = occurredOn,
    accountId = selectedAccountId,
    note = note,
)
