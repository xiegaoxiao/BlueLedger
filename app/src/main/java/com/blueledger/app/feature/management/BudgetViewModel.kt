package com.blueledger.app.feature.management

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.money.AmountParseResult
import com.blueledger.app.core.money.Money
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MutationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.YearMonth

/** S08 月度预算 UI 状态。 */
data class BudgetUiState(
    val month: YearMonth,
    val today: java.time.LocalDate,
    val loading: Boolean = true,
    /** null 只在加载中出现；[BudgetState.budgetCent] 为 null 才是「未设置」。 */
    val budget: BudgetState? = null,
    val amountText: String = "",
    val editing: Boolean = false,
    val saving: Boolean = false,
    val removing: Boolean = false,
    val showRemoveConfirm: Boolean = false,
    val fieldError: String? = null,
    val banner: ManagementBanner? = null,
    /** 上个月已设置的预算金额，供用户**明确触发**「沿用上月」时使用。 */
    val previousMonthBudgetCent: Long? = null,
) {
    val isSet: Boolean get() = budget?.isSet == true
    val status: BudgetStatus get() = budget?.status ?: BudgetStatus.UNSET
}

/**
 * S08 月度预算的状态与规则。
 *
 * - 预算按 `YearMonth` 独立保存；未设置用 `budgetCent == null` 表达，**绝不把 0 当分母**。
 * - 使用率 = 该月有效支出 ÷ 月预算；收入不参与，「收入不会降低已支出」。
 * - 视觉进度条用 [BudgetState.progressFraction]（已 coerceIn(0,1)，超支满格不溢出），
 *   文字用真实比例（可 > 100%）。
 * - 预算不阻止记账；也不自动继承上月，只有用户点击「沿用上月」才读取上月金额。
 */
class BudgetViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
    initialYearMonth: YearMonth?,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(
        BudgetUiState(
            month = initialYearMonth ?: clock.currentYearMonth(),
            today = clock.today(),
        ),
    )
    val state: StateFlow<BudgetUiState> = _state.asStateFlow()

    init {
        scope.launch {
            _state
                .map { it.month }
                .distinctUntilChanged()
                .collectLatest { month ->
                    repository.observeBudget(month).collect { budget ->
                        _state.update { current ->
                            current.copy(
                                loading = false,
                                budget = budget,
                                amountText = if (current.editing) {
                                    current.amountText
                                } else {
                                    budget.budgetCent?.let { Money.format(it) }.orEmpty()
                                },
                            )
                        }
                    }
                }
        }
        scope.launch {
            _state
                .map { it.month }
                .distinctUntilChanged()
                .collectLatest { month ->
                    val previous = repository.observeBudget(month.minusMonths(1)).first()
                    _state.update { it.copy(previousMonthBudgetCent = previous.budgetCent) }
                }
        }
    }

    // ───────────────────────── 月份 ─────────────────────────

    fun onPreviousMonth() = changeMonth(-1)

    fun onNextMonth() = changeMonth(1)

    private fun changeMonth(deltaMonths: Long) {
        if (_state.value.saving || _state.value.removing) return
        _state.update {
            it.copy(
                month = it.month.plusMonths(deltaMonths),
                editing = false,
                amountText = "",
                fieldError = null,
                showRemoveConfirm = false,
                banner = null,
            )
        }
    }

    // ───────────────────────── 编辑 ─────────────────────────

    fun onStartEdit() {
        _state.update { current ->
            current.copy(
                editing = true,
                amountText = current.budget?.budgetCent?.let { Money.format(it) }.orEmpty(),
                fieldError = null,
                banner = null,
            )
        }
    }

    fun onCancelEdit() {
        _state.update { it.copy(editing = false, fieldError = null, amountText = "") }
    }

    fun onAmountChanged(text: String) {
        _state.update { it.copy(amountText = text.take(AMOUNT_INPUT_LIMIT), fieldError = null, banner = null) }
    }

    /** 「沿用上月金额」：必须由用户明确触发，不自动继承。 */
    fun onUsePreviousMonth() {
        val previous = _state.value.previousMonthBudgetCent
        if (previous == null || previous <= 0L) {
            _state.update {
                it.copy(
                    banner = ManagementBanner.error(
                        "上月（${_state.value.month.minusMonths(1).chineseLabel()}）没有设置预算",
                    ),
                )
            }
            return
        }
        _state.update {
            it.copy(
                editing = true,
                amountText = Money.format(previous),
                fieldError = null,
                banner = ManagementBanner.success(
                    "已填入上月金额 ¥${Money.format(previous)}，保存后生效",
                ),
            )
        }
    }

    fun onSaveBudget() {
        val current = _state.value
        if (current.saving || current.removing) return

        val cents = when (val parsed = Money.parse(current.amountText)) {
            is AmountParseResult.Success -> parsed.amountCent
            is AmountParseResult.Failure -> {
                _state.update { it.copy(fieldError = budgetMessageFor(parsed)) }
                return
            }
        }
        if (cents > Limits.MAX_BUDGET_CENT) {
            _state.update { it.copy(fieldError = "预算不能超过 9,999,999.99 元") }
            return
        }

        _state.update { it.copy(saving = true, fieldError = null, banner = null) }
        scope.launch {
            when (val result = repository.setBudget(current.month, cents)) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        saving = false,
                        editing = false,
                        banner = ManagementBanner.success(
                            "已保存 ${current.month.chineseLabel()}预算 ¥${Money.format(cents)}",
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(saving = false, fieldError = result.error.readableMessage())
                }
            }
        }
    }

    // ───────────────────────── 移除 ─────────────────────────

    fun onRequestRemove() {
        if (!_state.value.isSet) return
        _state.update { it.copy(showRemoveConfirm = true) }
    }

    fun onCancelRemove() {
        _state.update { it.copy(showRemoveConfirm = false) }
    }

    /** 仅移除预算数据；账单与支出完全不受影响。 */
    fun onConfirmRemove() {
        val current = _state.value
        if (current.removing || !current.isSet) {
            _state.update { it.copy(showRemoveConfirm = false) }
            return
        }
        _state.update { it.copy(showRemoveConfirm = false, removing = true) }
        scope.launch {
            when (val result = repository.removeBudget(current.month)) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        removing = false,
                        editing = false,
                        amountText = "",
                        banner = ManagementBanner.success(
                            "已移除 ${current.month.chineseLabel()}预算，账单与支出不受影响",
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(removing = false, banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onBannerShown() {
        _state.update { it.copy(banner = null) }
    }

    private fun budgetMessageFor(failure: AmountParseResult.Failure): String = when (failure.code) {
        com.blueledger.app.core.model.ValidationCode.AMOUNT_EMPTY -> "请输入预算金额"
        com.blueledger.app.core.model.ValidationCode.AMOUNT_ZERO -> "预算必须大于 0 元"
        com.blueledger.app.core.model.ValidationCode.AMOUNT_NEGATIVE -> "预算必须大于 0 元"
        com.blueledger.app.core.model.ValidationCode.AMOUNT_OUT_OF_RANGE -> "预算不能超过 9,999,999.99 元"
        else -> failure.message
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        private const val AMOUNT_INPUT_LIMIT: Int = 16
    }
}

/**
 * S08 的展示文案（纯函数，便于精确断言）。
 *
 * 五态文案：
 * - `UNSET`：不计算比例，也不显示假进度条；
 * - `NORMAL`（< 80%）：无警告文字；
 * - `NEAR_LIMIT`（80% ≤ x < 100%）：「接近预算」；
 * - `EXHAUSTED`（恰好 100%）：「预算已用完」；
 * - `EXCEEDED`（> 100%）：「已超支 ¥500.00」。
 */
object BudgetPresentation {

    const val UNSET_TITLE: String = "为这个月设一个预算"

    /** 使用率文字，一位小数；未设置返回 null（不做除法）。 */
    fun ratioText(state: BudgetState): String? = state.ratioBasisPoint?.let { Money.formatBasisPoint(it) }

    /** 状态提醒文字；正常与未设置没有提醒。 */
    fun statusText(state: BudgetState): String? = when (state.status) {
        BudgetStatus.UNSET, BudgetStatus.NORMAL -> null
        BudgetStatus.NEAR_LIMIT -> "接近预算"
        BudgetStatus.EXHAUSTED -> "预算已用完"
        BudgetStatus.EXCEEDED -> "已超支 ¥${Money.format(state.exceededCent ?: 0L)}"
    }

    /** 可用余额 / 超支文案。 */
    fun remainingText(state: BudgetState): String? = state.remainingCent?.let { remaining ->
        if (remaining >= 0L) "本月还可支出 ¥${Money.format(remaining)}" else "已超支 ¥${Money.format(-remaining)}"
    }

    fun budgetText(state: BudgetState): String? = state.budgetCent?.let { "月预算 ¥${Money.format(it)}" }

    fun usedText(state: BudgetState): String = "已支出 ¥${Money.format(state.usedCent)}"
}
