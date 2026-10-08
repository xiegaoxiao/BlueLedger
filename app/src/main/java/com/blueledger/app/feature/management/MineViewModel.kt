package com.blueledger.app.feature.management

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.lifecycle.ScreenSubscriptionOwner
import com.blueledger.app.core.lifecycle.ScreenSubscriptions
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.BackupRecord
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

/** S10 我的与设置 UI 状态。 */
data class MineUiState(
    val loading: Boolean = true,
    val hideAmounts: Boolean = false,
    val defaultAccountId: String? = null,
    /** 未归档账户（默认账户候选）。 */
    val activeAccounts: List<AccountWithBalance> = emptyList(),
    val currentMonth: YearMonth,
    val currentMonthBudget: BudgetState? = null,
    val transactionCount: Int = 0,
    val expenseCategoryCount: Int = 0,
    val incomeCategoryCount: Int = 0,
    val backupRecord: BackupRecord = BackupRecord(succeededAt = null, fileName = null),
    val showAccountPicker: Boolean = false,
    val savingSettings: Boolean = false,
    val banner: ManagementBanner? = null,
) {
    val defaultAccountName: String?
        get() = activeAccounts.firstOrNull { it.account.id == defaultAccountId }?.account?.name

    val categoryCount: Int get() = expenseCategoryCount + incomeCategoryCount

    val currentMonthBudgetCent: Long? get() = currentMonthBudget?.budgetCent
}

/**
 * S10「我的与设置」的状态与规则。
 *
 * - 金额隐藏是**全局设置**：状态只从 `observeSettings()` 得来，写入走
 *   `setHideAmounts`，因此首页 / 账单 / 统计 / 账户会一起变化，不存在「只在当前页面本地切换」。
 * - 默认账户同样来自设置；只能从未归档账户中选择。
 * - 备份状态来自 `observeBackupRecord()`：没有成功备份记录时明确显示「尚未备份」，
 *   不无依据显示「已备份」。
 * - 版本号由路由层从真实构建产物解析后传入，不在这里写死。
 */
class MineViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
) : ViewModel(), ScreenSubscriptionOwner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val subscriptions = ScreenSubscriptions(scope)

    private val _state = MutableStateFlow(MineUiState(currentMonth = clock.currentYearMonth()))
    val state: StateFlow<MineUiState> = _state.asStateFlow()

    init {
        setSubscriptionsActive(true)
    }

    override fun setSubscriptionsActive(active: Boolean) {
        subscriptions.setActive(active, ::observe)
    }

    private fun observe(readScope: CoroutineScope) {
        val month = clock.currentYearMonth()
        _state.update { it.copy(currentMonth = month) }
        readScope.launch {
            repository.observeSettings().collect { settings ->
                _state.update {
                    it.copy(
                        loading = false,
                        hideAmounts = settings.hideAmounts,
                        defaultAccountId = settings.defaultAccountId,
                    )
                }
            }
        }
        readScope.launch {
            repository.observeAccounts(includeArchived = false).collect { list ->
                _state.update { it.copy(activeAccounts = list) }
            }
        }
        readScope.launch {
            repository.observeBudget(month).collect { budget ->
                _state.update { it.copy(currentMonthBudget = budget) }
            }
        }
        readScope.launch {
            // 累计账单数：totalCount 不受分页影响，这里只取 1 条用于计数。
            repository.observeTransactions(TransactionFilter(limit = 1)).collect { page ->
                _state.update { it.copy(transactionCount = page.totalCount) }
            }
        }
        readScope.launch {
            repository.observeCategories(TransactionType.EXPENSE, includeArchived = false).collect { list ->
                _state.update { it.copy(expenseCategoryCount = list.size) }
            }
        }
        readScope.launch {
            repository.observeCategories(TransactionType.INCOME, includeArchived = false).collect { list ->
                _state.update { it.copy(incomeCategoryCount = list.size) }
            }
        }
        readScope.launch {
            repository.observeBackupRecord().collect { record ->
                _state.update { it.copy(backupRecord = record) }
            }
        }
    }

    /** 全局金额隐藏开关（写入仓库设置，而不是页面本地状态）。 */
    fun onHideAmountsToggled(hidden: Boolean) {
        val current = _state.value
        if (current.savingSettings || current.hideAmounts == hidden) return
        _state.update { it.copy(savingSettings = true, banner = null) }
        scope.launch {
            when (val result = repository.setHideAmounts(hidden)) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        savingSettings = false,
                        banner = ManagementBanner.success(
                            if (hidden) {
                                "已开启金额隐藏：首页、账单、统计、账户金额显示为 ••••"
                            } else {
                                "已关闭金额隐藏，金额恢复显示"
                            },
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    // 写入失败时设置保持原值，并给出原因（不假装已经切换）。
                    it.copy(savingSettings = false, banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onRequestAccountPicker() {
        _state.update { it.copy(showAccountPicker = true) }
    }

    fun onDismissAccountPicker() {
        _state.update { it.copy(showAccountPicker = false) }
    }

    fun onDefaultAccountSelected(id: String) {
        val name = _state.value.activeAccounts.firstOrNull { it.account.id == id }?.account?.name.orEmpty()
        _state.update { it.copy(showAccountPicker = false, savingSettings = true, banner = null) }
        scope.launch {
            when (val result = repository.setDefaultAccount(id)) {
                is MutationResult.Success -> _state.update {
                    it.copy(savingSettings = false, banner = ManagementBanner.success("已将「$name」设为默认账户"))
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(savingSettings = false, banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onBannerShown() {
        _state.update { it.copy(banner = null) }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }
}
