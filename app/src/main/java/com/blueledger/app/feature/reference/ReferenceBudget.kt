package com.blueledger.app.feature.reference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.*
import com.blueledger.app.core.model.*
import com.blueledger.app.feature.entry.AmountInput
import com.blueledger.app.feature.entry.rememberAmountKeyboardFeedback
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

private const val TOTAL_BUDGET = "__total__"
private data class BudgetUsage(val key: String, val total: Long, val categories: Map<String, Long>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceBudgetRoute(repository: LedgerRepository, clock: Clock, initialMonth: YearMonth?, onBack: () -> Unit) {
    val advancedFlow = remember(repository) { repository.observeAdvancedSettings() }
    val loadedSettings by advancedFlow.collectAsStateWithLifecycle<AdvancedLedgerSettings?>(null)
    val advanced = loadedSettings ?: AdvancedLedgerSettings()
    val currentMonth = LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay)
    var monthText by rememberSaveable { mutableStateOf((initialMonth ?: currentMonth).toString()) }
    var observedMonthStartDay by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(advanced.monthStartDay) {
        if (observedMonthStartDay != advanced.monthStartDay) {
            if (initialMonth == null) monthText = currentMonth.toString()
            observedMonthStartDay = advanced.monthStartDay
        }
    }
    val month = YearMonth.parse(monthText)
    var yearly by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableIntStateOf(month.year) }
    val rules = if (yearly) advanced.yearlyCategoryBudgets else advanced.categoryBudgets
    val budgetFlow = remember(repository, month) { repository.observeBudget(month) }
    val monthlyBudget by budgetFlow.collectAsStateWithLifecycle<BudgetState?>(null)
    val totalCent = if (yearly) advanced.yearlyBudgetCent else monthlyBudget?.takeIf { it.yearMonth == month }?.budgetCent
    val period = if (yearly) LocalDate.of(year, 1, 1)..LocalDate.of(year, 12, 31) else LedgerPeriods.range(month, advanced.monthStartDay)
    val key = "$yearly:${period.start}:${period.endInclusive}:${rules.map { it.categoryId }}"
    // Summary queries include every matching bill, regardless of transaction-list pagination.
    val usageFlow = remember(repository, key) {
        val ids = rules.map { it.categoryId }
        val filter = TransactionFilter(from = period.start, to = period.endInclusive, type = TransactionType.EXPENSE)
        combine(listOf(repository.observeFilteredSummary(filter)) + ids.map { repository.observeFilteredSummary(filter.copy(categoryId = it)) }) { values ->
            BudgetUsage(key, values[0].expenseCent, ids.mapIndexed { index, id -> id to values[index + 1].expenseCent }.toMap())
        }
    }
    val usage by usageFlow.collectAsStateWithLifecycle<BudgetUsage?>(null)
    val currentUsage = usage?.takeIf { it.key == key }
    val categoriesFlow = remember(repository) { repository.observeCategories(TransactionType.EXPENSE, true) }
    val categories by categoriesFlow.collectAsStateWithLifecycle(emptyList())
    var cycleMenu by remember { mutableStateOf(false) }
    var periodPicker by remember { mutableStateOf(false) }
    var categoryPicker by remember { mutableStateOf(false) }
    var editorTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var menuTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var clearTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var amountText by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val totalName = if (yearly) "年度总预算" else "月度总预算"
    fun targetName(target: String) = if (target == TOTAL_BUDGET) totalName else "${categories.firstOrNull { it.id == target }?.name ?: "分类"}${if (yearly) "年" else "月"}预算"
    fun targetAmount(target: String) = if (target == TOTAL_BUDGET) totalCent else rules.firstOrNull { it.categoryId == target }?.amountCent
    fun edit(target: String) {
        amountText = targetAmount(target)?.let { AmountInput.fromCents(it).text }.orEmpty()
        error = null; menuTarget = null; categoryPicker = false; editorTarget = target
    }
    suspend fun write(target: String, amount: Long?): MutationResult {
        if (target == TOTAL_BUDGET && !yearly) return repository.setMonthlyBudget(amount, month)
        return repository.updateAdvancedSettings { latest ->
            if (target == TOTAL_BUDGET) latest.copy(yearlyBudgetCent = amount)
            else {
                val existing = if (yearly) latest.yearlyCategoryBudgets else latest.categoryBudgets
                val updated = when {
                    amount == null -> existing.filterNot { it.categoryId == target }
                    existing.any { it.categoryId == target } -> existing.map { if (it.categoryId == target) it.copy(amountCent = amount) else it }
                    else -> existing + CategoryBudgetRule(target, amount)
                }
                if (yearly) latest.copy(yearlyCategoryBudgets = updated) else latest.copy(categoryBudgets = updated)
            }
        }
    }
    fun mutate(target: String, amount: Long?, success: () -> Unit) {
        if (busy) return
        busy = true; error = null
        scope.launch {
            try {
                when (val result = write(target, amount)) {
                    is MutationResult.Success -> success()
                    is MutationResult.Failure -> error = result.error.message
                }
            } finally { busy = false }
        }
    }
    BackHandler(cycleMenu) { cycleMenu = false }
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val visualScale = (maxWidth.value / 361f).coerceAtMost(1.5f)
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding().testTag("budget_screen")) {
        LedgerCenteredTopBar(if (yearly) "年预算" else "月预算", onBack, brand = true,
            onTitleClick = { cycleMenu = !cycleMenu }, titleTag = "budget_cycle", height = (50 * visualScale).dp, fontSizeSp = 19 * visualScale)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val ready = loadedSettings != null && currentUsage != null && (yearly || monthlyBudget?.yearMonth == month)
            if (!ready) LedgerLoadingState()
            else if (totalCent == null && rules.isEmpty()) BoxWithConstraints(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(bottom = maxHeight * .32f), contentAlignment = Alignment.Center) {
                    LedgerEmptyState("暂无预算", actionText = "添加预算", onAction = { edit(TOTAL_BUDGET) },
                        subtle = true, testTag = "budget_empty", contentScale = visualScale, illustration = { LedgerBudgetEmptyIllustration(visualScale) })
                }
            } else Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("budget_list")) {
                    item {
                        if (totalCent != null) {
                            val state = BudgetState(month, totalCent, currentUsage!!.total)
                            LedgerBudgetSummary(if (yearly) "${year}年总预算" else "${month.monthValue}月总预算",
                                state.remainingCent!!, totalCent, state.usedCent, 10000 - (state.ratioBasisPoint ?: 0),
                                onEdit = { menuTarget = TOTAL_BUDGET; error = null },
                                periodLabel = if (yearly) "本年" else "本月", onPeriodClick = { periodPicker = true })
                        } else LedgerActionButton("添加${if (yearly) "年" else "月"}总预算", { edit(TOTAL_BUDGET) },
                            Modifier.fillMaxWidth().padding(16.dp), style = LedgerActionStyle.SECONDARY, testTag = "budget_total_add")
                        HorizontalDivider(thickness = 10.dp, color = T.Background)
                    }
                    if (rules.isEmpty()) item {
                        Box(Modifier.fillParentMaxHeight(.68f), contentAlignment = Alignment.Center) {
                            LedgerEmptyState("未设置分类预算", subtle = true, testTag = "budget_categories_empty", contentScale = visualScale, illustration = { LedgerBudgetEmptyIllustration(visualScale) })
                        }
                    }
                    items(rules, key = { it.categoryId }) { rule ->
                        val category = categories.firstOrNull { it.id == rule.categoryId }
                        val state = BudgetState(month, rule.amountCent, currentUsage!!.categories[rule.categoryId] ?: 0)
                        Box(Modifier.testTag("category_budget_${rule.categoryId}")) {
                        LedgerBudgetSummary(category?.name ?: "已移除分类", state.remainingCent!!, rule.amountCent, state.usedCent,
                            10000 - (state.ratioBasisPoint ?: 0), { menuTarget = rule.categoryId; error = null },
                            compact = true, iconKey = category?.iconKey, tag = "category_budget_${rule.categoryId}")
                        }
                        HorizontalDivider(thickness = 8.dp, color = T.Background)
                    }
                }
                HorizontalDivider(thickness = .5.dp, color = T.Border.copy(alpha = .4f))
                Box(Modifier.fillMaxWidth().height((50 * visualScale).dp)) {
                    LedgerActionButton("添加分类预算", { categoryPicker = true }, Modifier.fillMaxSize(),
                        style = LedgerActionStyle.NEUTRAL, testTag = "budget_category_add",
                        textStyle = LedgerTextStyles.body.copy(fontSize = (14 * visualScale).sp, lineHeight = (20 * visualScale).sp),
                        leadingIcon = { Icon(Icons.Outlined.Add, null, Modifier.size((16 * visualScale).dp)) })
                }
            }
            if (cycleMenu) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .6f)).clickable { cycleMenu = false }.testTag("budget_cycle_scrim"))
                Column(Modifier.fillMaxWidth().background(T.Surface).testTag("budget_cycle_menu")) {
                    LedgerChoiceRow("月预算", !yearly, tag = "budget_cycle_month") { yearly = false; cycleMenu = false }
                    LedgerChoiceRow("年预算", yearly, tag = "budget_cycle_year") { yearly = true; cycleMenu = false }
                }
            }
        }
    }
    }
    if (periodPicker) {
        if (!yearly) ReferenceMonthPicker(month, currentMonth, { periodPicker = false }) { monthText = it.toString(); periodPicker = false }
        else {
            var draftYear by remember { mutableIntStateOf(year) }
            LedgerPickerSheet("选择年份", "year_picker", "year_dismiss", "year_confirm", { periodPicker = false }, { year = draftYear; periodPicker = false }) {
                LedgerWheelColumn(minOf(1970, year)..clock.today().year, draftYear, "年份", "budget_year", Modifier.width(180.dp)) { draftYear = it }
            }
        }
    }
    if (categoryPicker) ModalBottomSheet(onDismissRequest = { categoryPicker = false }, containerColor = T.Surface, dragHandle = null) {
        LedgerSheetHeader("选择预算分类", { categoryPicker = false }, closeTag = "budget_category_cancel")
        val available = categories.filter { !it.isArchived && rules.none { rule -> rule.categoryId == it.id } }
        if (available.isEmpty()) LedgerEmptyState("暂无可添加的分类", description = "所有可用支出分类均已设置预算")
        else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).testTag("budget_category_picker")) {
            items(available, key = { it.id }) { category ->
                LedgerNavigationRow(category.name, tag = "budget_choose_${category.id}") { edit(category.id) }
            }
        }
    }
    menuTarget?.let { target ->
        ModalBottomSheet(onDismissRequest = { menuTarget = null }, containerColor = T.Surface) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(T.SpaceL), verticalArrangement = Arrangement.spacedBy(T.SpaceS)) {
                LedgerActionButton("编辑${targetName(target)}", { edit(target) }, Modifier.fillMaxWidth(), style = LedgerActionStyle.SECONDARY, testTag = "budget_menu_edit")
                LedgerActionButton("${if (target == TOTAL_BUDGET) "清除" else "删除"}${targetName(target)}", {
                    clearTarget = target; menuTarget = null; error = null
                }, Modifier.fillMaxWidth(), style = LedgerActionStyle.DESTRUCTIVE_OUTLINED, testTag = "budget_menu_remove")
                LedgerActionButton("取消", { menuTarget = null }, Modifier.fillMaxWidth(), style = LedgerActionStyle.NEUTRAL)
            }
        }
    }
    clearTarget?.let { target ->
        LedgerConfirmDialog(title = "${if (target == TOTAL_BUDGET) "清除" else "删除"}${targetName(target)}？",
            message = if (target == TOTAL_BUDGET && !yearly) "清除每月默认预算及当前月份预算，保留分类预算、账单和其他月份单独设置的预算。" else "只移除此预算设置，账单与其他预算仍会保留。",
            confirmText = if (busy) "处理中…" else if (target == TOTAL_BUDGET) "清除" else "删除", destructive = true, enabled = !busy,
            testTag = "budget_clear_dialog", testTagConfirm = "budget_clear_confirm", testTagDismiss = "budget_clear_cancel",
            onDismiss = { clearTarget = null; error = null }, onConfirm = { mutate(target, null) { clearTarget = null } }) {
            Text("${if (yearly) "${year}年" else "${month.year}年${month.monthValue}月"} · ${targetName(target)}", style = LedgerTextStyles.bodyStrong)
            targetAmount(target)?.let { LedgerFittedAmountText(it) }
            error?.let { LedgerErrorBanner(it, testTag = "budget_clear_error") }
        }
    }
    editorTarget?.let { target ->
        BudgetAmountEditor(if (target == TOTAL_BUDGET) if (yearly) "每年总预算" else "每月总预算" else targetName(target),
            amountText, busy, error, onChange = { amountText = it; error = null },
            onCancel = { editorTarget = null; error = null },
            onSave = { cent -> mutate(target, cent) { editorTarget = null } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetAmountEditor(title: String, text: String, busy: Boolean, error: String?, onChange: (String) -> Unit, onCancel: () -> Unit, onSave: (Long) -> Unit) {
    val amount = AmountInput(text)
    ModalBottomSheet(onDismissRequest = { if (!busy) onCancel() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy }),
        containerColor = T.Surface, dragHandle = null) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = (maxWidth.value / 361f).coerceAtMost(1.5f)
            val expanded = maxHeight < 560.dp || LocalDensity.current.fontScale > 1.4f
            Column(Modifier.fillMaxHeight(if (expanded) 1f else .76f).testTag("budget_editor")) {
                LedgerSheetHeader(title, onCancel, enabled = !busy, closeTag = "budget_cancel",
                    titleStyle = LedgerTextStyles.body.copy(fontSize = (16 * scale).sp, lineHeight = (24 * scale).sp), closeDiameter = (22 * scale).dp)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = (28 * scale).dp, vertical = T.SpaceL), verticalArrangement = Arrangement.spacedBy(T.SpaceS)) {
                    Surface(color = T.Background, shape = RoundedCornerShape((4 * scale).dp)) {
                        Text(if (amount.isEmpty) "请输入预算金额" else amount.displayText,
                            Modifier.fillMaxWidth().padding(horizontal = (16 * scale).dp, vertical = ((if (amount.isEmpty) 12 else 7) * scale).dp).testTag("budget_draft"),
                            style = if (amount.isEmpty) LedgerTextStyles.body.copy(fontSize = (14 * scale).sp, lineHeight = (18 * scale).sp)
                                else LedgerTextStyles.body.copy(fontSize = (22 * scale).sp, lineHeight = (28 * scale).sp),
                            textAlign = if (amount.isEmpty) TextAlign.Start else TextAlign.End,
                            color = if (amount.isEmpty) T.TextSecondary else T.TextPrimary)
                    }
                    error?.let { LedgerErrorBanner(it, testTag = "budget_editor_error") }
                }
                LedgerPrimaryButton("确定", { amount.centsOrNull()?.takeIf { it > 0 }?.let(onSave) },
                    Modifier.widthIn(max = (206 * scale).dp).align(Alignment.CenterHorizontally).padding(vertical = T.SpaceXl),
                    enabled = (amount.centsOrNull() ?: 0) > 0, loading = busy, testTag = "budget_save", shape = RoundedCornerShape(50),
                    height = (46 * scale).dp, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    textStyle = LedgerTextStyles.button.copy(fontSize = (16 * scale).sp, lineHeight = (22 * scale).sp))
                LedgerAmountKeyboard(onDigit = { onChange(amount.appendDigit(it).text) },
                    onDecimalPoint = { onChange(amount.appendDecimalPoint().text) }, onDelete = { onChange(amount.deleteLast().text) },
                    actionsEnabled = !busy, keyMinHeight = (51 * scale).dp, deleteIconSize = (24 * scale).dp,
                    digitStyle = LedgerTextStyles.keypadDigit.copy(fontSize = (20 * scale).sp, lineHeight = (28 * scale).sp),
                    onKeyFeedback = rememberAmountKeyboardFeedback())
            }
        }
    }
}
