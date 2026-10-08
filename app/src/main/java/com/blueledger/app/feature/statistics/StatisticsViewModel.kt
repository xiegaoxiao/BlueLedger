package com.blueledger.app.feature.statistics

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.lifecycle.ScreenSubscriptionOwner
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.MonthAnalysis
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.YearAnalysis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/**
 * S05/S06 的界面状态。
 *
 * [MonthPanel] / [YearPanel] 的全部字段都来自仓库返回值；本状态类不含任何自算的聚合口径。
 * 「未到月份」只用 `reached = false` 表达，**绝不用 0 伪装**。
 */
data class StatisticsUiState(
    val tab: StatisticsTab,
    val month: YearMonth,
    val year: Int,
    val type: TransactionType,
    val today: LocalDate,
    val currentMonth: YearMonth,
    val loading: Boolean = true,
    val errorMessage: String? = null,
    val monthPanel: MonthPanel? = null,
    val yearPanel: YearPanel? = null,
    val selectedDayIndex: Int? = null,
    val selectedMonth: Int? = null,
) {
    val isMonthTab: Boolean get() = tab == StatisticsTab.MONTH

    /** 当前月之后没有真实月份可看，因此禁用「下个月」。 */
    val canGoNextMonth: Boolean get() = month.isBefore(currentMonth)

    val canGoNextYear: Boolean get() = year < currentMonth.year

    val selectedDay: DailyAmount?
        get() = selectedDayIndex?.let { index -> monthPanel?.daily?.getOrNull(index) }

    val selectedYearColumn: YearMonthColumn?
        get() = selectedMonth?.let { month -> yearPanel?.columns?.firstOrNull { it.month == month } }
}

/**
 * S05/S06 的状态持有者。
 *
 * 数据来源只有两个冻结契约方法：
 * `observeMonthAnalysis(month, type)` 与 `observeYearAnalysis(year)`。
 * 类型切换只改 [StatisticsUiState.type]，因此环图、日趋势、排行**同时**由同一份
 * `MonthAnalysis` 驱动（不会出现「环图是支出、趋势是收入」的混合状态）。
 *
 * 未到月份（晚于设备当前月）不可选择、不可钻取：`reachedMonthCount` 与
 * "未到月份不是 0" 的语义由数据层的 `reached` 决定，本类只做守卫。
 */
class StatisticsViewModel(
    private val repository: LedgerRepository,
    private val clock: Clock,
    initialTab: StatisticsTab? = null,
    initialYearMonth: YearMonth? = null,
    initialYear: Int? = null,
) : ViewModel(), ScreenSubscriptionOwner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val today: LocalDate = clock.today()

    /** 设备当前月，是「未到月份」判定的唯一依据（不写死任何演示日期）。 */
    val currentMonth: YearMonth = YearMonth.from(today)

    /** 年份选择器可回看的年份范围（当前年为上限）。 */
    val pickerYears: List<Int> = (today.year downTo today.year - YEAR_LOOKBACK).toList()

    /** 当前的订阅 Job；月份/类型/年份变化时先取消再重新订阅。 */
    private var monthJob: Job? = null
    private var yearJob: Job? = null
    private var subscriptionsActive = true

    private val _state = MutableStateFlow(
        StatisticsUiState(
            tab = initialTab ?: StatisticsTab.MONTH,
            month = initialYearMonth?.let(::clampMonth) ?: currentMonth,
            year = (initialYear ?: today.year).coerceAtMost(today.year),
            type = TransactionType.EXPENSE,
            today = today,
            currentMonth = currentMonth,
        ),
    )
    val state: StateFlow<StatisticsUiState> = _state.asStateFlow()

    init {
        reloadMonth()
        reloadYear()
    }

    override fun setSubscriptionsActive(active: Boolean) {
        if (subscriptionsActive == active) return
        subscriptionsActive = active
        if (active) {
            reloadMonth()
            reloadYear()
        } else {
            monthJob?.cancel()
            yearJob?.cancel()
            monthJob = null
            yearJob = null
        }
    }

    /**
     * 重新订阅当前（月份 + 类型）的月度分析。
     *
     * 先取消旧查询，避免旧周期的结果覆盖当前页面。
     * 仓库在后台线程聚合，界面收到结果后统一更新；离开页面时暂停查询，
     * 保留已有展示状态，返回后重新订阅获取最新数据。
     */
    private fun reloadMonth() {
        if (!subscriptionsActive) return
        monthJob?.cancel()
        val month = _state.value.month
        val type = _state.value.type
        monthJob = scope.launch {
            repository.observeMonthAnalysis(month, type)
                .catch { reportReadFailure() }
                .collect { analysis ->
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            errorMessage = null,
                            monthPanel = analysis.toPanel(),
                            selectedDayIndex = current.selectedDayIndex
                                ?.takeIf { it in analysis.daily.indices },
                        )
                    }
                }
        }
    }

    private fun reloadYear() {
        if (!subscriptionsActive) return
        yearJob?.cancel()
        val year = _state.value.year
        yearJob = scope.launch {
            repository.observeYearAnalysis(year)
                .catch { reportReadFailure() }
                .collect { analysis ->
                    _state.update { current ->
                        current.copy(
                            loading = false,
                            errorMessage = null,
                            yearPanel = analysis.toPanel(),
                            selectedMonth = current.selectedMonth
                                ?.takeIf { selected -> analysis.months.any { it.month == selected && it.reached } },
                        )
                    }
                }
        }
    }

    // ───────────────────────── 分段 / 类型 ─────────────────────────

    fun onTabSelected(tab: StatisticsTab) {
        _state.update { if (it.tab == tab) it else it.copy(tab = tab) }
    }

    /**
     * 支出/收入切换：环图、日趋势、排行共用同一份分析结果，因此三者必然同时切换。
     * **必须重新订阅**（`observeMonthAnalysis` 的 type 是查询参数），否则环图/趋势/排行
     * 会停留在上一个类型的口径上——这正是「类型切换同时控制三者」的硬性要求。
     */
    fun onTypeSelected(type: TransactionType) {
        val before = _state.value.type
        _state.update { if (it.type == type) it else it.copy(type = type, selectedDayIndex = null) }
        if (_state.value.type != before) reloadMonth()
    }

    // ───────────────────────── 月份 ─────────────────────────

    fun onPreviousMonth() {
        _state.update { it.copy(month = it.month.minusMonths(1), selectedDayIndex = null) }
        reloadMonth()
    }

    /** 下个月：只允许走到设备当前月，未来月没有可展示的有效日期区间。 */
    fun onNextMonth() {
        val before = _state.value.month
        _state.update {
            if (!it.canGoNextMonth) it else it.copy(month = it.month.plusMonths(1), selectedDayIndex = null)
        }
        if (_state.value.month != before) reloadMonth()
    }

    fun onPickMonth(month: YearMonth) {
        val target = clampMonth(month)
        val before = _state.value.month
        _state.update { it.copy(month = target, selectedDayIndex = null) }
        if (target != before) reloadMonth()
    }

    // ───────────────────────── 年份 ─────────────────────────

    fun onPreviousYear() {
        _state.update { it.copy(year = it.year - 1, selectedMonth = null) }
        reloadYear()
    }

    fun onNextYear() {
        val before = _state.value.year
        _state.update { if (!it.canGoNextYear) it else it.copy(year = it.year + 1, selectedMonth = null) }
        if (_state.value.year != before) reloadYear()
    }

    fun onPickYear(year: Int) {
        val target = year.coerceAtMost(today.year)
        val before = _state.value.year
        _state.update { it.copy(year = target, selectedMonth = null) }
        if (target != before) reloadYear()
    }

    // ───────────────────────── 图表选择 ─────────────────────────

    /** 点按日趋势：整个绘图区都命中最近的数据点（不是只有细线/圆点可点）。 */
    fun onSelectDay(index: Int) {
        _state.update { current ->
            val daily = current.monthPanel?.daily ?: return@update current
            if (index !in daily.indices) current else current.copy(selectedDayIndex = index)
        }
    }

    fun onClearDaySelection() {
        _state.update { it.copy(selectedDayIndex = null) }
    }

    /** 点选年图月份列：未到月份拒绝选中（`reached = false` 不是 0）。 */
    fun onSelectMonth(month: Int) {
        _state.update { current ->
            val column = current.yearPanel?.columns?.firstOrNull { it.month == month }
            if (column?.drillable != true) current else current.copy(selectedMonth = month)
        }
    }

    fun onClearMonthSelection() {
        _state.update { it.copy(selectedMonth = null) }
    }

    // ───────────────────────── 钻取 ─────────────────────────

    /**
     * 年度统计 → 该月月报（PRD §4.4：切换月度统计并保留年份、月份）。
     * 未到月份返回 false 且不改状态，绝不允许「点未来月看到 0 月报」。
     */
    fun onOpenMonthReport(month: YearMonth): Boolean {
        if (month.isAfter(currentMonth)) return false
        val before = _state.value.month
        _state.update { it.copy(tab = StatisticsTab.MONTH, month = month, selectedDayIndex = null) }
        if (month != before) reloadMonth()
        return true
    }

    /** 年图点选后的「查看该月账单」：进入月度页并保留月份，钻取参数由调用方生成。 */
    fun onOpenBillsOfMonth(month: Int): Boolean {
        val year = _state.value.year
        return onOpenMonthReport(YearMonth.of(year, month.coerceIn(1, 12)))
    }

    // ───────────────────────── 失败重试 ─────────────────────────

    fun onRetry() {
        _state.update { it.copy(loading = true, errorMessage = null) }
        reloadMonth()
        reloadYear()
    }

    private fun reportReadFailure() {
        _state.update { it.copy(loading = false, errorMessage = "暂时无法读取数据") }
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    private fun clampMonth(month: YearMonth): YearMonth = if (month.isAfter(currentMonth)) currentMonth else month

    private companion object {
        const val YEAR_LOOKBACK: Int = 20
    }
}

/** `MonthAnalysis` → 展示模型。不做任何二次聚合，只做字段搬运与环比无关的派生。 */
fun MonthAnalysis.toPanel(): MonthPanel = MonthPanel(
    month = month,
    type = type,
    summary = com.blueledger.app.core.model.MoneySummary(
        incomeCent = incomeCent,
        expenseCent = expenseCent,
        count = count,
    ),
    typeTotalCent = typeTotalCent,
    daily = daily,
    slices = slices,
    ringSlices = buildRingSlices(slices, typeTotalCent),
    periodEnd = periodEnd,
)

/** `YearAnalysis` → 展示模型。12 项 `reached` 语义原样保留。 */
fun YearAnalysis.toPanel(): YearPanel = YearPanel(
    year = year,
    summary = com.blueledger.app.core.model.MoneySummary(
        incomeCent = incomeCent,
        expenseCent = expenseCent,
        count = count,
    ),
    columns = buildYearColumns(this),
    cutoff = cutoff,
    isCurrentYear = isCurrentYear,
    reachedMonthCount = reachedMonthCount,
)
