package com.blueledger.app.feature.transactions

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.LedgerAccount
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.Outcome
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 详情页一次性事件：删除成功携带[DeleteReceipt]交回上层。 */
sealed interface DetailEvent {
    data class Deleted(val receipt: DeleteReceipt) : DetailEvent
}

/**
 * S04 账单详情的 ViewModel。
 *
 * 职责边界（总控裁决）：
 * - 删除成功后**只**发出 [DetailEvent.Deleted]（携带凭据）并通知 `onDeleted`；
 *   不自己 `popBackStack`，不自己弹撤销提示——撤销由 NavHost 统一展示。
 * - 编辑只走 `onEdit(id)` 导航事件，本页不复制一套编辑表单。
 */
class DetailViewModel(
    private val repository: LedgerRepository,
    private val transactionId: String,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val retryTrigger = MutableStateFlow(0)

    private val _state = MutableStateFlow(DetailUiState())
    val state: StateFlow<DetailUiState> = _state.asStateFlow()

    private val eventChannel = Channel<DetailEvent>(Channel.BUFFERED)
    val events: Flow<DetailEvent> = eventChannel.receiveAsFlow()

    private var latestCategories: List<LedgerCategory> = emptyList()
    private var latestAccounts: List<LedgerAccount> = emptyList()

    init {
        observeTransaction()
        observeReferences()
    }

    // ───────────────────────── 数据订阅 ─────────────────────────

    private fun observeTransaction() {
        scope.launch {
            retryTrigger
                .flatMapLatest { repository.observeTransaction(transactionId).onReadFailure() }
                .collect { transaction ->
                    _state.update { current ->
                        when {
                            // 自己刚删除成功：保留画面等待上层返回，不闪「记录不存在」。
                            transaction == null && current.deleted -> current.copy(loading = false)
                            transaction == null -> DetailUiState(loading = false, deleted = false)
                            else -> current.copy(
                                loading = false,
                                transaction = transaction,
                                showDeleteDialog = false,
                                deleteError = null,
                                readErrorMessage = null,
                            )
                        }
                    }
                    applyReferences()
                }
        }
    }

    private fun observeReferences() {
        scope.launch {
            retryTrigger
                .flatMapLatest {
                    combine(
                        repository.observeCategories(TransactionType.EXPENSE, includeArchived = true),
                        repository.observeCategories(TransactionType.INCOME, includeArchived = true),
                        repository.observeAccounts(includeArchived = true),
                    ) { expense, income, accounts -> Triple(expense, income, accounts) }
                        .onReadFailure()
                }
                .collect { (expense, income, accounts) ->
                    latestCategories = expense + income
                    latestAccounts = accounts.map { it.account }
                    applyReferences()
                }
        }
    }

    private fun applyReferences() {
        _state.update { current ->
            val transaction = current.transaction ?: return@update current
            val category = latestCategories.firstOrNull { it.id == transaction.categoryId }
            val account = latestAccounts.firstOrNull { it.id == transaction.accountId }
            current.copy(
                categoryName = category?.name ?: "未分类",
                categoryIconKey = category?.iconKey.orEmpty(),
                categoryArchived = category?.isArchived ?: false,
                accountName = account?.name ?: "未知账户",
            )
        }
    }

    // ───────────────────────── 删除 ─────────────────────────

    fun onDeleteRequested() {
        _state.update { it.copy(showDeleteDialog = true, deleteError = null) }
    }

    fun onDeleteDismissed() {
        if (_state.value.deleting) return
        _state.update { it.copy(showDeleteDialog = false, deleteError = null) }
    }

    /** 确认删除：软删除成功 → 把撤销凭据交回上层；失败 → 保留当前数据并给出原因。 */
    fun onDeleteConfirmed() {
        val current = _state.value
        // 正在删除、已经删除成功、或记录已不存在时，重复确认一律忽略（防双击重复提交）。
        if (current.deleting || current.deleted || current.transaction == null) return
        _state.update { it.copy(deleting = true, deleteError = null) }

        scope.launch {
            when (val outcome = repository.softDeleteTransaction(transactionId)) {
                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            deleting = false,
                            showDeleteDialog = false,
                            deleted = true,
                            deleteError = null,
                        )
                    }
                    eventChannel.send(DetailEvent.Deleted(outcome.value))
                }

                is Outcome.Failure -> {
                    _state.update { it.copy(deleting = false, deleteError = outcome.error.message) }
                }
            }
        }
    }

    fun onDeleteErrorShown() {
        _state.update { it.copy(deleteError = null) }
    }

    fun onRetry() {
        _state.update { it.copy(loading = true, readErrorMessage = null) }
        retryTrigger.update { it + 1 }
    }

    private fun <T> Flow<T>.onReadFailure(): Flow<T> = catch {
        _state.update { current ->
            current.copy(loading = false, readErrorMessage = TransactionsViewModel.READ_ERROR_MESSAGE)
        }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
