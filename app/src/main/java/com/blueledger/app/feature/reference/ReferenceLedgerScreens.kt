package com.blueledger.app.feature.reference

import com.blueledger.app.core.designsystem.LedgerMonthlyReportHeader
import com.blueledger.app.core.designsystem.LedgerTransactionRow
import com.blueledger.app.core.designsystem.LedgerMonthlyBalanceCard
import com.blueledger.app.core.designsystem.LedgerMetricsCard
import com.blueledger.app.core.designsystem.LedgerMetric
import com.blueledger.app.core.designsystem.LedgerReportSection
import com.blueledger.app.core.designsystem.LedgerActionButton
import com.blueledger.app.core.designsystem.LedgerActionStyle
import com.blueledger.app.core.designsystem.LedgerBillToolbar
import com.blueledger.app.core.designsystem.LedgerBillTableRow
import com.blueledger.app.core.designsystem.LedgerOverviewCard
import com.blueledger.app.core.designsystem.LedgerCenteredTopBar
import com.blueledger.app.core.designsystem.LedgerDropdownArrow
import com.blueledger.app.core.designsystem.LedgerFittedAmountText
import com.blueledger.app.core.designsystem.LedgerNavigationRow
import com.blueledger.app.core.designsystem.LedgerSegmentedControl
import com.blueledger.app.core.designsystem.LedgerLineChart
import com.blueledger.app.core.designsystem.LedgerEmptyState
import com.blueledger.app.core.designsystem.LedgerBudgetEmptyIllustration
import com.blueledger.app.core.designsystem.LedgerTopDropdown
import com.blueledger.app.core.designsystem.LedgerChoiceRow
import com.blueledger.app.core.designsystem.LedgerFlowDirectionIcon
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.LedgerCategoryIcon
import com.blueledger.app.core.designsystem.LedgerRankingRow
import com.blueledger.app.core.designsystem.LedgerBarComparisonRow
import com.blueledger.app.core.model.*
import com.blueledger.app.core.money.Money
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.WeekFields

@Composable
internal fun ledgerState(repository: LedgerRepository, key: String, filter: TransactionFilter): Pair<ReferenceLedgerState, ReferenceLedgerViewModel> {
    val vm: ReferenceLedgerViewModel = viewModel(key = key, factory = viewModelFactory {
        initializer { ReferenceLedgerViewModel(repository, filter) }
    })
    LaunchedEffect(filter) { vm.select(filter) }
    val state by vm.state.collectAsStateWithLifecycle()
    return state to vm
}

@Composable
fun ReferenceHomeRoute(repository: LedgerRepository, clock: Clock, onDetail: (String) -> Unit,
    onReport: () -> Unit, onBudget: (YearMonth) -> Unit, onAssets: () -> Unit, onMore: () -> Unit,
) {
    val advancedFlow = remember(repository) { repository.observeAdvancedSettings() }
    val advanced by advancedFlow.collectAsStateWithLifecycle(AdvancedLedgerSettings())
    val currentLedgerMonth = LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay)
    var selected by rememberSaveable { mutableStateOf(clock.currentYearMonth().toString()) }
    var observedMonthStartDay by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(advanced.monthStartDay) { if (observedMonthStartDay != advanced.monthStartDay) { selected = LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay).toString(); observedMonthStartDay = advanced.monthStartDay } }
    val month = YearMonth.parse(selected)
    var picker by remember { mutableStateOf(false) }
    var limit by rememberSaveable(selected) { mutableIntStateOf(50) }
    val (state, vm) = ledgerState(repository, "reference.home", TransactionFilter(yearMonth = month, limit = limit))
    val scope = rememberCoroutineScope()
    val prefs = rememberReferencePreferences()
    val hidden = LocalHideAmounts.current
    var quickEdit by remember { mutableStateOf<TransactionWithRefs?>(null) }
    val largeFont = LocalDensity.current.fontScale > 1.4f
    LazyColumn(Modifier.fillMaxSize().background(T.Surface).testTag("overview_screen")) {
        item("header") {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().background(T.Primary)) {
                    LedgerCenteredTopBar("蓝记", brand = true) {
                        IconButton(onClick = { scope.launch { repository.setHideAmounts(!hidden) } }, modifier = Modifier.testTag("overview_hide_toggle")) {
                            Icon(if (LocalHideAmounts.current) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏金额", tint = T.OnBrand)
                        }
                    }
                    // 隐藏金额开关直接使用根节点的统一状态。
                    Row(Modifier.fillMaxWidth().padding(horizontal = T.SpaceL, vertical = T.SpaceS), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(if (largeFont) 1.6f else .9f).clickable { picker = true }.testTag("overview_month_label").semantics(mergeDescendants = true) {
                            customActions = listOf(
                                CustomAccessibilityAction("上个月") { selected = month.minusMonths(1).toString(); true },
                                CustomAccessibilityAction("下个月") { if (month < currentLedgerMonth) selected = month.plusMonths(1).toString(); true },
                            )
                        }) {
                            Text("${month.year}年", fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, color = T.OnBrandSecondary)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(month.monthValue.toString().padStart(2, '0'), fontSize = if (largeFont) 20.sp else 27.sp,
                                        lineHeight = if (largeFont) 26.sp else 32.sp, maxLines = 1, color = T.OnBrand, modifier = Modifier.alignByBaseline())
                                    Text("月", fontSize = 14.sp, maxLines = 1, color = T.OnBrand, modifier = Modifier.alignByBaseline())
                                }
                                LedgerDropdownArrow(Modifier.testTag("overview_month_arrow"), color = T.OnBrand)
                            }
                        }
                        Box(Modifier.width(.5.dp).height(34.dp).background(T.OnBrandOutline))
                        Column(Modifier.weight(1.25f).padding(start = 16.dp)) {
                            Text("收入", fontSize = 13.sp, color = T.OnBrandSecondary)
                            HomeSummaryMoney(state.summary.incomeCent, Modifier.padding(top = 7.dp))
                        }
                        Column(Modifier.weight(1.25f)) {
                            Text("支出", fontSize = 13.sp, color = T.OnBrandSecondary)
                            HomeSummaryMoney(state.summary.expenseCent, Modifier.padding(top = 7.dp))
                        }
                    }
                }
                // 色块延伸至卡片上方 20dp，视觉上交叠，布局与点击范围仍保持自然流。
                Box(Modifier.fillMaxWidth().drawBehind {
                    drawRect(T.Primary, size = Size(size.width, T.BrandShortcutOverlap.toPx()))
                }.padding(horizontal = T.SpaceL).padding(bottom = T.SpaceM)) {
                    Surface(Modifier.fillMaxWidth().testTag("home_shortcut_card"),
                        shape = RoundedCornerShape(T.RadiusCard), color = T.Surface, shadowElevation = T.CardElevationRaised) {
                        Row(Modifier.fillMaxWidth()) {
                            Shortcut("账单", Icons.Outlined.ReceiptLong, Modifier.weight(1f), onReport)
                            Shortcut("预算", Icons.Outlined.PieChartOutline, Modifier.weight(1f)) { onBudget(month) }
                            Shortcut("资产管家", Icons.Outlined.AccountBalanceWallet, Modifier.weight(1f), onAssets)
                            Shortcut("更多", Icons.Outlined.MoreHoriz, Modifier.weight(1f), onMore)
                        }
                    }
                }
            }
        }
        when {
            state.error != null -> item { LedgerNavigationRow(state.error, value = "重试", onClick = vm::retry) }
            !state.loaded -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            state.totalCount == 0 -> item {
                Column(Modifier.fillMaxWidth().padding(top = if (largeFont) 32.dp else 100.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.ReceiptLong, null, Modifier.size(72.dp), tint = T.Border)
                    Text("本月还没有记录", color = T.TextSecondary, modifier = Modifier.padding(top = 20.dp))
                    Text("点击下方＋，开始记账", color = T.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            else -> state.days.forEachIndexed { index, day ->
                item("date_${day.date}") {
                    val dateLabel = "${day.date.monthValue.toString().padStart(2, '0')}月${day.date.dayOfMonth.toString().padStart(2, '0')}日 星期${listOf("一", "二", "三", "四", "五", "六", "日")[day.date.dayOfWeek.value - 1]}"
                    val totals = if (LocalHideAmounts.current) "••••" else buildString {
                        if (index == state.days.lastIndex && state.hasMore) append("已加载 ")
                        if (day.income > 0) append("收入：${referenceAmountText(day.income)} ")
                        if (day.expense > 0) append("支出：${referenceAmountText(day.expense)}")
                    }
                    val modifier = Modifier.fillMaxWidth().background(T.Surface).padding(horizontal = 16.dp, vertical = 7.dp)
                    if (largeFont) Column(modifier) {
                        Text(dateLabel, fontSize = 11.sp, color = T.TextSecondary)
                        Text(totals, fontSize = 11.sp, color = T.TextSecondary)
                    } else Row(modifier, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(dateLabel, fontSize = 11.sp, color = T.TextSecondary)
                        Text(totals, fontSize = 11.sp, color = T.TextSecondary)
                    }
                }
                items(day.rows, key = { it.id }) { row ->
                    ReferenceTransaction(row, onClick = { onDetail(row.id) }, onNote = if (prefs.quickEdit) ({ quickEdit = row }) else null)
                }
            }
        }
        if (state.hasMore) item("more") {
            TextButton(onClick = { limit += 50 }, modifier = Modifier.fillMaxWidth().testTag("home_load_more")) { Text("加载更多") }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
    if (picker) ReferenceMonthPicker(month, currentLedgerMonth, { picker = false }) { selected = it.toString(); picker = false }
    quickEdit?.let { row -> QuickNoteDialog(repository, row, { quickEdit = null }) }
}

@Composable
private fun RowScope.Shortcut(label: String, icon: ImageVector, modifier: Modifier, click: () -> Unit) {
    Column(modifier.clickable(onClick = click).padding(vertical = T.SpaceXs).testTag("home_$label"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(T.ShortcutIconContainer).clip(CircleShape).background(T.PrimarySoft), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(T.IconMedium), tint = T.Primary)
        }
        Text(label, fontSize = T.Caption, lineHeight = 16.sp, color = T.TextPrimary, modifier = Modifier.padding(top = T.SpaceXs))
    }
}

@Composable
internal fun ReferenceTransaction(row: TransactionWithRefs, onClick: () -> Unit, onNote: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).testTag("overview_row_${row.id}")
        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        LedgerCategoryIcon(row.categoryIconKey, false, diameter = 34.dp)
        Text(row.note.ifBlank { row.categoryName }, fontSize = 14.sp, color = T.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 14.dp).then(if (onNote != null) Modifier.clickable(onClick = onNote) else Modifier))
        Text(if (LocalHideAmounts.current) "••••" else (if (row.type.isExpense) "−" else "") +
            referenceAmountText(row.amountCent), fontSize = 14.sp, color = T.TextPrimary, maxLines = 1)
    }
    HorizontalDivider(Modifier.padding(start = 72.dp), color = T.Border.copy(alpha = .5f), thickness = .5.dp)
}

@Composable
private fun QuickNoteDialog(repository: LedgerRepository, row: TransactionWithRefs, dismiss: () -> Unit) {
    var text by remember { mutableStateOf(row.note) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text("编辑备注") }, text = {
        Column {
            OutlinedTextField(text, { if (it.length <= Limits.MAX_NOTE_LENGTH) text = it }, label = { Text("备注") }, singleLine = true)
            error?.let { Text(it, color = T.Risk) }
        }
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            val tx = row.transaction
            when (val result = repository.updateTransaction(tx.id, TransactionDraft(tx.type, tx.amountCent, tx.categoryId, tx.accountId, tx.occurredOn, text))) {
                is SaveResult.Success -> dismiss()
                is SaveResult.Failure -> { error = result.error.message; saving = false }
            }
        }
    }) { Text("完成") } }, dismissButton = { TextButton(onClick = dismiss, enabled = !saving) { Text("取消") } })
}

@Composable
fun ReferenceChartRoute(repository: LedgerRepository, clock: Clock, initialMonth: YearMonth? = null, initialYear: Int? = null,
    onCategory: (ChartRange, TransactionType, String) -> Unit,
) {
    val prefs = rememberReferencePreferences()
    var periodName by rememberSaveable { mutableStateOf(if (initialYear != null) "YEAR" else if (initialMonth != null) "MONTH" else prefs.chartPeriod.name) }
    var typeName by rememberSaveable { mutableStateOf(prefs.chartType.name) }
    var fromText by rememberSaveable { mutableStateOf((initialMonth?.atDay(1) ?: initialYear?.let { LocalDate.of(it, 1, 1) }
        ?: ChartRange.forPeriod(ChartPeriod.valueOf(periodName), clock.today()).from).toString()) }
    val period = ChartPeriod.valueOf(periodName)
    val type = TransactionType.valueOf(typeName)
    val from = LocalDate.parse(fromText)
    val advancedFlow = remember(repository) { repository.observeAdvancedSettings() }
    val advanced by advancedFlow.collectAsStateWithLifecycle(AdvancedLedgerSettings())
    var observedMonthStartDay by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(advanced.monthStartDay) { if (observedMonthStartDay != advanced.monthStartDay) { if (period != ChartPeriod.WEEK) fromText = ChartRange.forPeriod(period, clock.today(), advanced.monthStartDay).from.toString(); observedMonthStartDay = advanced.monthStartDay } }
    val range = when (period) { ChartPeriod.WEEK -> ChartRange.week(from); ChartPeriod.MONTH -> ChartRange.month(YearMonth.from(from), advanced.monthStartDay); ChartPeriod.YEAR -> ChartRange.year(from.year, advanced.monthStartDay) }
    val (state, vm) = ledgerState(repository, "reference.chart", TransactionFilter(from = range.from, to = minOf(range.to, clock.today()), type = type, limit = Int.MAX_VALUE))
    var menu by remember { mutableStateOf(false) }
    val total = if (type.isExpense) state.summary.expenseCent else state.summary.incomeCent
    val values = remember(state, range) { chartValues(state, range) }
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Surface).testTag("statistics_screen")) {
    val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
    Column(Modifier.fillMaxSize()) {
        LedgerTopDropdown(menu, { menu = false }, tag = "stats_type_menu", anchor = {
        Column(Modifier.background(T.Primary)) {
            Box(Modifier.fillMaxWidth().height((43 * scale).dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { menu = !menu }, modifier = Modifier.testTag("stats_type_selector")) {
                    Text(if (type.isExpense) "支出" else "收入", color = T.OnBrand, fontSize = 18.sp)
                    LedgerDropdownArrow(Modifier.padding(start = 6.dp), color = T.OnBrand)
                }
            }
            Box(Modifier.fillMaxWidth().padding(horizontal = (10 * scale).dp).padding(bottom = (10 * scale).dp)) {
                LedgerSegmentedControl(ChartPeriod.entries, period, { when (it) { ChartPeriod.WEEK -> "周"; ChartPeriod.MONTH -> "月"; ChartPeriod.YEAR -> "年" } }, {
                    periodName = it.name; fromText = ChartRange.forPeriod(it, clock.today(), advanced.monthStartDay).from.toString()
                }, onBrand = true, tag = { "stats_tab_${it.name.lowercase()}" }, compactHeight = (28 * scale).dp)
            }
        }
        }) {
            listOf(TransactionType.EXPENSE, TransactionType.INCOME).forEach { choice ->
                LedgerChoiceRow(if (choice.isExpense) "支出" else "收入", choice == type,
                    tag = "stats_type_${choice.name.lowercase()}", height = (50 * scale).dp,
                    textSizeSp = 14 * scale, horizontalPadding = (28 * scale).dp,
                    dividerStart = (50 * scale).dp,
                    leadingIcon = { LedgerFlowDirectionIcon(!choice.isExpense, Modifier.size((16 * scale).dp)) }) {
                    typeName = choice.name; menu = false
                }
            }
        }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                val periodHeight = if (LocalDensity.current.fontScale > 1.4f) Modifier.heightIn(min = T.MinTouchTarget) else Modifier.height(T.ReferencePeriodHeight)
                (3 downTo 0).forEach { offset ->
                    var choice = range
                    repeat(offset) { choice = choice.previous() }
                    val label = when (period) {
                        ChartPeriod.WEEK -> when (choice.from) {
                            ChartRange.week(clock.today()).from -> "本周"
                            ChartRange.week(clock.today()).previous().from -> "上周"
                            else -> "${choice.from.get(WeekFields.ISO.weekOfWeekBasedYear())}周"
                        }
                        ChartPeriod.MONTH -> "${choice.from.monthValue}月"
                        ChartPeriod.YEAR -> "${choice.from.year}年"
                    }
                    TextButton(onClick = { fromText = choice.from.toString() }, contentPadding = PaddingValues(T.SpaceXs), modifier = Modifier.width(76.dp).then(periodHeight).semantics { selected = offset == 0 }.then(if (offset == 0) Modifier.testTag(if (period == ChartPeriod.YEAR) "stats_year_label" else "stats_month_label") else Modifier)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = if (offset == 0) T.TextPrimary else T.TextSecondary)
                            if (offset == 0) Box(Modifier.width(35.dp).height(1.dp).background(T.Primary))
                        }
                    }
                }
                if (range.next().from <= clock.today()) TextButton(onClick = { fromText = range.next().from.toString() }, contentPadding = PaddingValues(T.SpaceXs), modifier = periodHeight.testTag("stats_next_period")) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一周期", tint = T.Primary) }
            }
        LazyColumn(Modifier.weight(1f)) {
            if (state.error != null) item { LedgerNavigationRow(state.error, value = "重试", onClick = vm::retry) }
            if (!state.loaded || state.filter.from != range.from || state.filter.type != type) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item("trend") {
                val hidden = LocalHideAmounts.current
                Column(Modifier.fillMaxWidth().height((189 * scale).dp).padding(horizontal = (10 * scale).dp).padding(top = (10 * scale).dp)) {
                    Column(Modifier.fillMaxWidth().testTag("stats_summary_card").semantics(mergeDescendants = true) {}) {
                        Text("总${if (type.isExpense) "支出" else "收入"}：${if (hidden) "••••" else Money.format(total).replace(",", "")}", fontSize = (12 * scale).sp, lineHeight = (16 * scale).sp, color = T.TextSecondary)
                        Text("平均值：${if (hidden) "••••" else Money.format(averageDaily(total, range, clock.today()) ?: 0).replace(",", "")}",
                            fontSize = (12 * scale).sp, lineHeight = (16 * scale).sp, color = T.TextSecondary, modifier = Modifier.padding(top = (6 * scale).dp))
                    }
                    val labels = when (period) {
                        ChartPeriod.YEAR -> (1..12).map { "${it}月" }
                        ChartPeriod.MONTH -> values.indices.map { range.from.plusDays(it.toLong()).dayOfMonth.toString().padStart(2, '0') }
                        ChartPeriod.WEEK -> values.indices.map { range.from.plusDays(it.toLong()).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd")) }
                    }
                    val ledgerMonth = LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay)
                    val future = if (period == ChartPeriod.YEAR) if (range.from.year == ledgerMonth.year) ledgerMonth.monthValue else if (range.from.year > ledgerMonth.year) 0 else values.size
                        else (java.time.temporal.ChronoUnit.DAYS.between(range.from, clock.today()) + 1).toInt().coerceIn(0, values.size)
                    LedgerLineChart(values, labels, futureFrom = future, height = (114 * scale).dp, compact = true,
                        empty = state.loaded && state.rows.isEmpty(), maxVisibleLabels = if (period == ChartPeriod.YEAR) 12 else 7,
                        formatAmount = { Money.format(it).replace(",", "") })
                }
            }
            item { HorizontalDivider(color = T.Border, thickness = 1.dp); Text("${if (type.isExpense) "支出" else "收入"}排行榜", fontSize = (16 * scale).sp, fontWeight = FontWeight.Bold, color = T.TextPrimary, modifier = Modifier.testTag("stats_ranking_title").padding(horizontal = (16 * scale).dp, vertical = (12 * scale).dp)) }
            items(state.categories, key = { it.id }) { category ->
                ReferenceRanking(category, state.categories.firstOrNull()?.cent ?: 0) { onCategory(range, type, category.id) }
            }
            if (state.loaded && state.categories.isEmpty()) item {
                LedgerEmptyState("暂无数据", contentPadding = (18 * scale).dp, subtle = true,
                    testTag = "stats_empty", contentScale = scale,
                    illustration = { LedgerBudgetEmptyIllustration(scale) })
            }
        }
    }
    }
}

@Composable
internal fun ReferenceRanking(category: ReferenceCategory, largest: Long, click: () -> Unit) {
    LedgerRankingRow(category.name, category.cent, category.permille / 1000.0,
        modifier = Modifier.testTag("stats_rank_${category.id}").padding(horizontal = T.SpaceL, vertical = T.ReferenceRankingVertical), onClick = click) {
        LedgerCategoryIcon(category.icon, false, diameter = T.ReferenceListIcon)
    }
}
@Composable
fun ReferenceCategoryBillsRoute(repository: LedgerRepository, from: LocalDate, to: LocalDate, type: TransactionType, categoryId: String, onBack: () -> Unit, onDetail: (String) -> Unit) {
    val (state, vm) = ledgerState(repository, "reference.categoryBills", TransactionFilter(from = from, to = to, type = type, categoryId = categoryId, limit = Int.MAX_VALUE))
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding()) {
        LedgerCenteredTopBar(state.rows.firstOrNull()?.categoryName ?: "分类明细", onBack, blue = true)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("${state.summary.count}笔"); LedgerFittedAmountText(state.summary.incomeCent + state.summary.expenseCent, large = true) }
        LazyColumn { if (state.error != null) item { LedgerNavigationRow(state.error, value = "重试", onClick = vm::retry) }
            items(state.rows, key = { it.id }) { row -> ReferenceTransaction(row, { onDetail(row.id) }) } }
    }
}

@Composable
fun ReferenceBillsRoute(repository: LedgerRepository, clock: Clock, onBack: (() -> Unit)? = null, onMonth: (YearMonth) -> Unit) {
    val advancedFlow = remember(repository) { repository.observeAdvancedSettings() }
    val advanced by advancedFlow.collectAsStateWithLifecycle(AdvancedLedgerSettings())
    val currentLedgerMonth = LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay)
    var years by rememberSaveable { mutableStateOf(false) }
    var selectedYear by rememberSaveable { mutableIntStateOf(clock.today().year) }
    var observedMonthStartDay by rememberSaveable { mutableIntStateOf(1) }
    LaunchedEffect(advanced.monthStartDay) { if (observedMonthStartDay != advanced.monthStartDay) { selectedYear = currentLedgerMonth.year; observedMonthStartDay = advanced.monthStartDay } }
    var yearPicker by remember { mutableStateOf(false) }
    val (state, vm) = ledgerState(repository, "reference.bills", TransactionFilter(limit = Int.MAX_VALUE))
    val yearRows = remember(state.months, selectedYear, currentLedgerMonth) {
        val recorded = state.months.filter { it.month.year == selectedYear }
        (recorded + if (selectedYear == currentLedgerMonth.year && recorded.none { it.month == currentLedgerMonth }) listOf(ReferenceMonth(currentLedgerMonth, 0, 0)) else emptyList()).sortedByDescending { it.month }
    }
    val sum = remember(state, selectedYear, years) { if (years) state.summary else MoneySummary(yearRows.sumOf { it.income }, yearRows.sumOf { it.expense }, 0) }
    Column(Modifier.fillMaxSize().background(T.Surface).then(if (onBack != null) Modifier.navigationBarsPadding() else Modifier).testTag("transactions_screen")) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
        val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
        Column(Modifier.fillMaxSize()) {
        LedgerBillToolbar(if (years) "全部年份" else "${selectedYear}年", years, { yearPicker = true }, { years = it }, scale, onBack)
        Box(Modifier.padding(horizontal = (15 * scale).dp).padding(top = (19 * scale).dp)) {
            LedgerOverviewCard(sum.balanceCent, sum.incomeCent, sum.expenseCent, scale, if (years) "总结余" else "年结余", if (years) "总收入" else "年收入", if (years) "总支出" else "年支出", "bills_summary_card", "bills_balance", "bills_income")
        }
        Spacer(Modifier.height((30 * scale).dp))
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tableWidth = maxOf(maxWidth, 320.dp * fontScale)
            Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(tableWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().height((30 * scale).dp).padding(horizontal = (24 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (years) "年份" else "月份", Modifier.weight(if (years) .96f else 1f), fontSize = (12 * scale).sp, color = T.TextSecondary)
                        listOf(if (years) "年收入" else "月收入", if (years) "年支出" else "月支出", if (years) "年结余" else "月结余").forEachIndexed { index, label ->
                            Text(label, Modifier.weight(if (years) if (index == 2) .82f else 1.11f else 1f), fontSize = (12 * scale).sp, color = T.TextSecondary, textAlign = TextAlign.Start)
                        }
                        if (!years) Spacer(Modifier.width((16 * scale).dp))
                    }
                    HorizontalDivider(color = T.Border.copy(alpha = .5f), thickness = .5.dp)
                    LazyColumn(Modifier.weight(1f).testTag("bills_table")) {
                        if (state.error != null) item { LedgerNavigationRow(state.error, value = "重试", onClick = vm::retry) }
                        if (years) {
                            val minimum = state.rows.minOfOrNull { it.occurredOn.year } ?: clock.today().year
                            items((clock.today().year downTo minimum).toList(), key = { it }) { year ->
                                val rows = state.rows.filter { it.occurredOn.year == year }
                                val income = rows.filter { it.type.isIncome }.sumOf { it.amountCent }
                                val expense = rows.filter { it.type.isExpense }.sumOf { it.amountCent }
                                LedgerBillTableRow("${year}年", listOf(income, expense, income - expense), scale, annual = true) { selectedYear = year; years = false }
                            }
                        } else items(yearRows.filter { it.month <= currentLedgerMonth }, key = { it.month.toString() }) { row ->
                            LedgerBillTableRow("${row.month.monthValue}月", listOf(row.income, row.expense, row.income - row.expense), scale) { onMonth(row.month) }
                        }
                    }
                }
            }
        }
    }
    }
    }
    if (yearPicker) AlertDialog(onDismissRequest = { yearPicker = false }, title = { Text("选择年份") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            (currentLedgerMonth.year downTo maxOf(1970, state.months.minOfOrNull { it.month.year } ?: currentLedgerMonth.year - 5)).forEach { year ->
                TextButton(onClick = { selectedYear = year; yearPicker = false }, modifier = Modifier.fillMaxWidth()) { Text("${year}年") }
            }
        }
    }, confirmButton = { TextButton(onClick = { yearPicker = false }) { Text("取消") } })
}

@Composable
private fun billAmount(cent: Long): String = if (LocalHideAmounts.current) "••••" else Money.format(cent).replace(",", "")

@Composable
private fun HomeSummaryMoney(cent: Long, modifier: Modifier) {
    val largeFont = LocalDensity.current.fontScale > 1.4f
    Text(if (LocalHideAmounts.current) "••••" else Money.format(cent), modifier,
        fontSize = if (largeFont) 12.sp else 16.sp, lineHeight = if (largeFont) 18.sp else 22.sp,
        maxLines = 1, color = T.OnBrand)
}

@Composable
fun ReferenceMonthReportRoute(repository: LedgerRepository, clock: Clock, month: YearMonth, onBack: () -> Unit, onDetail: (String) -> Unit, onAll: (YearMonth) -> Unit, onCategory: (YearMonth, String) -> Unit = { selected, _ -> onAll(selected) }) {
    val (state, vm) = ledgerState(repository, "reference.monthReport", TransactionFilter(yearMonth = month, limit = Int.MAX_VALUE))
    val (previous, previousVm) = ledgerState(repository, "reference.previousReport", TransactionFilter(yearMonth = month.minusMonths(1), limit = Int.MAX_VALUE))
    val longest = remember(state.days) { monthlyLongestStreak(state.days.map { it.date }) }
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding().testTag("month_report_screen")) {
        val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
        LazyColumn(Modifier.fillMaxSize().testTag("month_report_list"), contentPadding = PaddingValues(bottom = (20 * scale).dp)) {
            item { LedgerMonthlyReportHeader(month.year, month.monthValue, scale, onBack) }
            if (!state.loaded || !previous.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            val error = state.error ?: previous.error
            if (error != null) item { LedgerNavigationRow(error, "重试", onClick = { vm.retry(); previousVm.retry() }) }
            if (state.loaded && previous.loaded) {
                item { Box(Modifier.padding(horizontal = (10 * scale).dp)) {
                    LedgerMonthlyBalanceCard(state.summary.balanceCent, previous.summary.balanceCent, state.summary.incomeCent, state.summary.expenseCent, scale)
                } }
                item { Box(Modifier.padding(horizontal = (10 * scale).dp).padding(top = (10 * scale).dp)) {
                    LedgerMetricsCard("记账成就", listOf(
                        LedgerMetric("最长连续", longest, "天", "month_report_streak"),
                        LedgerMetric("记账天数", state.days.size, "天", "month_report_days"),
                        LedgerMetric("账单笔数", state.totalCount, "笔", "month_report_count")
                    ), scale)
                } }
                item { Box(Modifier.padding(horizontal = (10 * scale).dp).padding(top = (10 * scale).dp)) {
                    LedgerReportSection("支出分类", scale) {
                        if (state.expenseCategories.isEmpty()) Text("暂无支出记录", Modifier.fillMaxWidth().padding(vertical = (24 * scale).dp),
                            color = T.TextSecondary, fontSize = (13 * scale).sp, textAlign = TextAlign.Center)
                        else {
                            ReferenceCategoryRing(state.expenseCategories)
                            state.expenseCategories.forEach { category -> ReferenceRanking(category, state.expenseCategories.firstOrNull()?.cent ?: 0) { onCategory(month, category.id) } }
                        }
                    }
                } }
                item { Box(Modifier.padding(horizontal = (10 * scale).dp).padding(top = (10 * scale).dp)) {
                    LedgerReportSection("收支明细", scale) {
                        if (state.rows.isEmpty()) Text("暂无账单", Modifier.fillMaxWidth().padding(vertical = (24 * scale).dp),
                            color = T.TextSecondary, fontSize = (13 * scale).sp, textAlign = TextAlign.Center)
                        else {
                            state.rows.take(3).forEach { row ->
                                LedgerTransactionRow(row.categoryName, row.categoryIconKey, row.type, row.amountCent,
                                    row.note.ifBlank { row.categoryName }, "${row.occurredOn.monthValue}月${row.occurredOn.dayOfMonth}日",
                                    onClick = { onDetail(row.id) }, compactScale = scale, testTag = "month_report_row_${row.id}")
                            }
                            LedgerActionButton("查看全部账单", { onAll(month) }, Modifier.fillMaxWidth().testTag("month_report_all"), style = LedgerActionStyle.SECONDARY)
                        }
                    }
                } }
            }
        }
    }
}

internal fun monthlyLongestStreak(dates: List<LocalDate>): Int {
    var previous: LocalDate? = null
    var current = 0
    var longest = 0
    dates.distinct().sorted().forEach { date ->
        current = if (previous?.plusDays(1) == date) current + 1 else 1
        longest = maxOf(longest, current)
        previous = date
    }
    return longest
}
