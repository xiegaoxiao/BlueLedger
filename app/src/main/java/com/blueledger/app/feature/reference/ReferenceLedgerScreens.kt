package com.blueledger.app.feature.reference

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
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
    var selected by rememberSaveable { mutableStateOf(clock.currentYearMonth().toString()) }
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
            Column(Modifier.fillMaxWidth().background(T.PrimarySoft)) {
                ReferenceTitle("蓝记", blue = true) {
                    IconButton(onClick = { scope.launch { repository.setHideAmounts(!hidden) } }, modifier = Modifier.testTag("overview_hide_toggle")) {
                        Icon(if (LocalHideAmounts.current) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, "显示或隐藏金额", tint = T.TextPrimary)
                    }
                }
                // 隐藏金额开关直接使用根节点的统一状态。
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(if (largeFont) 1.6f else .9f).clickable { picker = true }.testTag("overview_month_label").semantics(mergeDescendants = true) {
                        customActions = listOf(
                            CustomAccessibilityAction("上个月") { selected = month.minusMonths(1).toString(); true },
                            CustomAccessibilityAction("下个月") { if (month < clock.currentYearMonth()) selected = month.plusMonths(1).toString(); true },
                        )
                    }) {
                        Text("${month.year}年", fontSize = 13.sp, lineHeight = 18.sp, maxLines = 1, color = T.TextSecondary)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(month.monthValue.toString().padStart(2, '0'), fontSize = if (largeFont) 20.sp else 27.sp,
                                    lineHeight = if (largeFont) 26.sp else 32.sp, maxLines = 1, color = T.TextPrimary, modifier = Modifier.alignByBaseline())
                                Text("月", fontSize = 14.sp, maxLines = 1, color = T.TextPrimary, modifier = Modifier.alignByBaseline())
                            }
                            ReferenceDropdownArrow(Modifier.testTag("overview_month_arrow"))
                        }
                    }
                    Box(Modifier.width(.5.dp).height(34.dp).background(T.TextSecondary.copy(alpha = .25f)))
                    Column(Modifier.weight(1.25f).padding(start = 16.dp)) {
                        Text("收入", fontSize = 13.sp, color = T.TextSecondary)
                        HomeSummaryMoney(state.summary.incomeCent, Modifier.padding(top = 7.dp))
                    }
                    Column(Modifier.weight(1.25f)) {
                        Text("支出", fontSize = 13.sp, color = T.TextSecondary)
                        HomeSummaryMoney(state.summary.expenseCent, Modifier.padding(top = 7.dp))
                    }
                }
                Surface(Modifier.padding(horizontal = 6.dp).fillMaxWidth().testTag("home_shortcut_card"),
                    shape = RoundedCornerShape(6.dp), color = T.Surface, shadowElevation = 1.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        Shortcut("账单", Icons.Outlined.ReceiptLong, Modifier.weight(1f), onReport)
                        Shortcut("预算", Icons.Outlined.PieChartOutline, Modifier.weight(1f)) { onBudget(month) }
                        Shortcut("资产管家", Icons.Outlined.AccountBalanceWallet, Modifier.weight(1f), onAssets)
                        Shortcut("更多", Icons.Outlined.MoreHoriz, Modifier.weight(1f), onMore)
                    }
                }
                Spacer(Modifier.height(2.dp).background(T.Surface))
            }
        }
        when {
            state.error != null -> item { ReferenceRow(state.error, "重试", onClick = vm::retry) }
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
                        if (day.income > 0) append("收入：${Money.format(day.income)} ")
                        if (day.expense > 0) append("支出：${Money.format(day.expense)}")
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
    if (picker) ReferenceMonthPicker(month, clock.currentYearMonth(), { picker = false }) { selected = it.toString(); picker = false }
    quickEdit?.let { row -> QuickNoteDialog(repository, row, { quickEdit = null }) }
}

@Composable
private fun RowScope.Shortcut(label: String, icon: ImageVector, modifier: Modifier, click: () -> Unit) {
    Column(modifier.clickable(onClick = click).padding(vertical = 9.dp).testTag("home_$label"), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(25.dp), tint = T.TextPrimary)
        Text(label, fontSize = 12.sp, lineHeight = 16.sp, color = T.TextPrimary, modifier = Modifier.padding(top = 6.dp))
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
            Money.format(row.amountCent).replace(",", "").trimEnd('0').trimEnd('.'), fontSize = 14.sp, color = T.TextPrimary, maxLines = 1)
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
    val range = when (period) { ChartPeriod.WEEK -> ChartRange.week(from); ChartPeriod.MONTH -> ChartRange.month(YearMonth.from(from)); ChartPeriod.YEAR -> ChartRange.year(from.year) }
    val (state, vm) = ledgerState(repository, "reference.chart", TransactionFilter(from = range.from, to = minOf(range.to, clock.today()), type = type, limit = Int.MAX_VALUE))
    var menu by remember { mutableStateOf(false) }
    val total = if (type.isExpense) state.summary.expenseCent else state.summary.incomeCent
    val values = remember(state, range) { chartValues(state, range) }
    Column(Modifier.fillMaxSize().background(T.Surface).testTag("statistics_screen")) {
        Column(Modifier.background(T.PrimarySoft)) {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { menu = true }) {
                    Text(if (type.isExpense) "支出" else "收入", color = T.TextPrimary, fontSize = 18.sp)
                    ReferenceDropdownArrow(Modifier.padding(start = 6.dp))
                }
                DropdownMenu(menu, { menu = false }) {
                    TransactionType.entries.forEach { choice -> DropdownMenuItem(text = { Text(if (choice.isExpense) "支出" else "收入") },
                        onClick = { typeName = choice.name; menu = false }, modifier = Modifier.testTag("stats_type_${choice.name.lowercase()}")) }
                }
            }
            ReferenceTabs(ChartPeriod.entries, period, { when (it) { ChartPeriod.WEEK -> "周"; ChartPeriod.MONTH -> "月"; ChartPeriod.YEAR -> "年" } }, {
                periodName = it.name; fromText = ChartRange.forPeriod(it, clock.today()).from.toString()
            }, { "stats_tab_${it.name.lowercase()}" })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                (3 downTo 0).forEach { offset ->
                    var choice = range
                    repeat(offset) { choice = choice.previous() }
                    val label = when (period) {
                        ChartPeriod.WEEK -> "${choice.from.get(WeekFields.ISO.weekOfWeekBasedYear())}周"
                        ChartPeriod.MONTH -> "${choice.from.monthValue}月"
                        ChartPeriod.YEAR -> "${choice.from.year}年"
                    }
                    TextButton(onClick = { fromText = choice.from.toString() }, modifier = Modifier.width(76.dp).then(if (offset == 0) Modifier.testTag(if (period == ChartPeriod.YEAR) "stats_year_label" else "stats_month_label") else Modifier)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(label, color = T.TextPrimary)
                            if (offset == 0) Box(Modifier.width(35.dp).height(1.dp).background(T.TextPrimary))
                        }
                    }
                }
                if (range.next().from <= clock.today()) TextButton(onClick = { fromText = range.next().from.toString() }) { Text("›") }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (state.error != null) item { ReferenceRow(state.error, "重试", onClick = vm::retry) }
            if (!state.loaded || state.filter.from != range.from || state.filter.type != type) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            item("trend") {
                Column(Modifier.padding(20.dp)) {
                    Row(Modifier.fillMaxWidth().testTag("stats_summary_card").semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("总${if (type.isExpense) "支出" else "收入"}", color = T.TextSecondary, fontSize = 13.sp); ReferenceMoney(total, large = true) }
                        Column(horizontalAlignment = Alignment.End) { Text("平均每天", color = T.TextSecondary, fontSize = 13.sp); ReferenceMoney(averageDaily(total, range, clock.today()) ?: 0, large = true) }
                    }
                    Text("${range.from} — ${minOf(range.to, clock.today())}", fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(vertical = 12.dp))
                    val labels = if (period == ChartPeriod.YEAR) (1..12).map { "${it}月" } else values.indices.map { "${range.from.plusDays(it.toLong()).dayOfMonth}日" }
                    val future = if (period == ChartPeriod.YEAR) if (range.from.year == clock.today().year) clock.today().monthValue else values.size
                        else (java.time.temporal.ChronoUnit.DAYS.between(range.from, clock.today()) + 1).toInt().coerceIn(0, values.size)
                    ReferenceLineChart(values, labels, futureFrom = future)
                }
            }
            item { HorizontalDivider(color = T.Border, thickness = 6.dp); Text("${if (type.isExpense) "支出" else "收入"}排行榜", fontSize = 18.sp, color = T.TextPrimary, modifier = Modifier.padding(20.dp)) }
            items(state.categories, key = { it.id }) { category ->
                ReferenceRanking(category, state.categories.firstOrNull()?.cent ?: 0) { onCategory(range, type, category.id) }
            }
            if (state.loaded && state.categories.isEmpty()) item { Text("暂无${if (type.isExpense) "支出" else "收入"}记录", color = T.TextSecondary, modifier = Modifier.padding(20.dp)) }
        }
    }
}

@Composable
internal fun ReferenceRanking(category: ReferenceCategory, largest: Long, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).testTag("stats_rank_${category.id}").padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LedgerCategoryIcon(category.icon, false, diameter = 38.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(category.name, fontSize = 15.sp, color = T.TextPrimary)
                Text("  ${Money.formatPermille(category.permille)}", color = T.TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                ReferenceMoney(category.cent)
            }
            Spacer(Modifier.height(9.dp))
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(T.Background)) {
                Box(Modifier.fillMaxWidth(if (largest <= 0) 0f else (category.cent.toDouble() / largest).toFloat().coerceIn(0f, 1f)).height(4.dp).background(T.Primary))
            }
        }
    }
}

@Composable
fun ReferenceCategoryBillsRoute(repository: LedgerRepository, from: LocalDate, to: LocalDate, type: TransactionType, categoryId: String, onBack: () -> Unit, onDetail: (String) -> Unit) {
    val (state, vm) = ledgerState(repository, "reference.categoryBills", TransactionFilter(from = from, to = to, type = type, categoryId = categoryId, limit = Int.MAX_VALUE))
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding()) {
        ReferenceTitle(state.rows.firstOrNull()?.categoryName ?: "分类明细", onBack, blue = true)
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("${state.summary.count}笔"); ReferenceMoney(state.summary.incomeCent + state.summary.expenseCent, large = true) }
        LazyColumn { if (state.error != null) item { ReferenceRow(state.error, "重试", onClick = vm::retry) }
            items(state.rows, key = { it.id }) { row -> ReferenceTransaction(row, { onDetail(row.id) }) } }
    }
}

@Composable
fun ReferenceBillsRoute(repository: LedgerRepository, clock: Clock, onBack: (() -> Unit)? = null, onMonth: (YearMonth) -> Unit) {
    var years by rememberSaveable { mutableStateOf(false) }
    var selectedYear by rememberSaveable { mutableIntStateOf(clock.today().year) }
    var yearPicker by remember { mutableStateOf(false) }
    val (state, vm) = ledgerState(repository, "reference.bills", TransactionFilter(limit = Int.MAX_VALUE))
    val yearRows = remember(state, selectedYear) { (12 downTo 1).map { n -> state.months.firstOrNull { it.month == YearMonth.of(selectedYear, n) } ?: ReferenceMonth(YearMonth.of(selectedYear, n), 0, 0) } }
    val sum = remember(state, selectedYear, years) { if (years) state.summary else MoneySummary(yearRows.sumOf { it.income }, yearRows.sumOf { it.expense }, 0) }
    Column(Modifier.fillMaxSize().background(T.Surface).then(if (onBack != null) Modifier.navigationBarsPadding() else Modifier).testTag("transactions_screen")) {
        BillToolbar(years, selectedYear, onBack, { yearPicker = true }, { years = it })
        BillSummaryCard(sum, years)
        Spacer(Modifier.height(24.dp))
        val fontScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val tableWidth = maxOf(maxWidth, 320.dp * fontScale)
            Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                Column(Modifier.width(tableWidth).fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 38.dp).padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (years) "年份" else "月份", Modifier.weight(.9f), fontSize = 12.sp, color = T.TextSecondary)
                        listOf(if (years) "年收入" else "月收入", if (years) "年支出" else "月支出", if (years) "年结余" else "月结余").forEach {
                            Text(it, Modifier.weight(1f), fontSize = 12.sp, color = T.TextSecondary, textAlign = TextAlign.Start)
                        }
                        Spacer(Modifier.width(16.dp))
                    }
                    HorizontalDivider(color = T.Border.copy(alpha = .5f), thickness = .5.dp)
                    LazyColumn(Modifier.weight(1f).testTag("bills_table")) {
                        if (state.error != null) item { ReferenceRow(state.error, "重试", onClick = vm::retry) }
                        if (years) {
                            val minimum = state.months.minOfOrNull { it.month.year } ?: clock.today().year
                            items((clock.today().year downTo minimum).toList(), key = { it }) { year ->
                                val rows = state.months.filter { it.month.year == year }
                                BillTableRow("${year}年", rows.sumOf { it.income }, rows.sumOf { it.expense }) { selectedYear = year; years = false }
                            }
                        } else items(yearRows.filter { it.month <= clock.currentYearMonth() }, key = { it.month.toString() }) { row ->
                            BillTableRow("${row.month.monthValue}月", row.income, row.expense) { onMonth(row.month) }
                        }
                    }
                }
            }
        }
    }
    if (yearPicker) AlertDialog(onDismissRequest = { yearPicker = false }, title = { Text("选择年份") }, text = {
        Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
            (clock.today().year downTo maxOf(1970, state.months.minOfOrNull { it.month.year } ?: clock.today().year - 5)).forEach { year ->
                TextButton(onClick = { selectedYear = year; yearPicker = false }, modifier = Modifier.fillMaxWidth()) { Text("${year}年") }
            }
        }
    }, confirmButton = { TextButton(onClick = { yearPicker = false }) { Text("取消") } })
}

@Composable
private fun BillTableRow(label: String, income: Long, expense: Long, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = click).padding(horizontal = 24.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(.9f), fontSize = 15.sp, color = T.TextPrimary)
        listOf(income, expense, income - expense).forEach { cent ->
            Text(billAmount(cent), Modifier.weight(1f), fontSize = 13.sp, color = T.TextPrimary, textAlign = TextAlign.Start, maxLines = 1)
        }
        Icon(Icons.Outlined.ChevronRight, "查看${label}账单", Modifier.size(16.dp), tint = T.TextSecondary.copy(alpha = .45f))
    }
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), thickness = .5.dp, color = T.Border.copy(alpha = .5f))
}

@Composable
private fun billAmount(cent: Long): String = if (LocalHideAmounts.current) "••••" else Money.format(cent).replace(",", "")

@Composable
private fun HomeSummaryMoney(cent: Long, modifier: Modifier) {
    val largeFont = LocalDensity.current.fontScale > 1.4f
    Text(if (LocalHideAmounts.current) "••••" else Money.format(cent), modifier,
        fontSize = if (largeFont) 12.sp else 16.sp, lineHeight = if (largeFont) 18.sp else 22.sp,
        maxLines = 1, color = T.TextPrimary)
}

@Composable
private fun BillToolbar(years: Boolean, selectedYear: Int, onBack: (() -> Unit)?, pickYear: () -> Unit, select: (Boolean) -> Unit) {
    val largeFont = LocalDensity.current.fontScale > 1.4f
    val yearSelector: @Composable () -> Unit = {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.size(40.dp).testTag("btn_back")) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = T.TextPrimary)
        }
        Row(Modifier.heightIn(min = 48.dp).clickable(enabled = !years, onClick = pickYear)
            .testTag("bills_year_selector").padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (years) "全部年份" else "${selectedYear}年", fontSize = 15.sp, maxLines = 1, color = T.TextPrimary)
            if (!years) ReferenceDropdownArrow(Modifier.testTag("bills_year_arrow"))
        }
    }
    if (largeFont) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { yearSelector() }
        BillPeriodSwitch(years, select, Modifier.fillMaxWidth().padding(bottom = 8.dp))
    } else Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        yearSelector()
        Spacer(Modifier.width(16.dp))
        BillPeriodSwitch(years, select, Modifier.width(148.dp))
    }
}

@Composable
private fun BillPeriodSwitch(years: Boolean, select: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, T.TextPrimary, RoundedCornerShape(8.dp))) {
        listOf(false, true).forEach { annual ->
            Box(Modifier.weight(1f).heightIn(min = 40.dp).background(if (annual == years) T.TextPrimary else T.Surface)
                .selectable(selected = annual == years, role = Role.Tab, onClick = { select(annual) })
                .testTag(if (annual) "bills_tab_year" else "bills_tab_month"), contentAlignment = Alignment.Center) {
                Text(if (annual) "年账单" else "月账单", fontSize = 13.sp, maxLines = 1, color = if (annual == years) Color.White else T.TextPrimary,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun BillSummaryCard(sum: MoneySummary, years: Boolean) {
    val largeFont = LocalDensity.current.fontScale > 1.4f
    Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(T.PrimarySoft).testTag("bills_summary_card")) {
        Box(Modifier.matchParentSize().padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) {
            Icon(Icons.Outlined.CurrencyYen, null, Modifier.size(116.dp), tint = T.Primary.copy(alpha = .08f))
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 18.dp)) {
            Text(if (years) "累计结余" else "年结余", fontSize = 12.sp, lineHeight = 18.sp, color = T.TextPrimary)
            Text(billAmount(sum.balanceCent), fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, modifier = Modifier.padding(top = 6.dp).testTag("bills_balance"), color = T.TextPrimary)
            if (largeFont) {
                Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    BillSummaryAmount(if (years) "总收入" else "年收入", sum.incomeCent)
                    BillSummaryAmount(if (years) "总支出" else "年支出", sum.expenseCent)
                }
            } else Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                BillSummaryAmount(if (years) "总收入" else "年收入", sum.incomeCent)
                BillSummaryAmount(if (years) "总支出" else "年支出", sum.expenseCent)
            }
        }
    }
}

@Composable
private fun BillSummaryAmount(label: String, cent: Long) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, color = T.TextPrimary, modifier = Modifier.alignByBaseline())
        Text(billAmount(cent), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = T.TextPrimary, maxLines = 1, modifier = Modifier.alignByBaseline())
    }
}

@Composable
fun ReferenceMonthReportRoute(repository: LedgerRepository, clock: Clock, month: YearMonth, onBack: () -> Unit, onDetail: (String) -> Unit, onAll: (YearMonth) -> Unit, onCategory: (YearMonth, String) -> Unit = { selected, _ -> onAll(selected) }) {
    val (state, vm) = ledgerState(repository, "reference.monthReport", TransactionFilter(yearMonth = month, limit = Int.MAX_VALUE))
    val (previous, _) = ledgerState(repository, "reference.previousReport", TransactionFilter(yearMonth = month.minusMonths(1), limit = Int.MAX_VALUE))
    val context = LocalContext.current
    val hidden = LocalHideAmounts.current
    val categories = state.expenseCategories
    val shareScope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { ReferenceTitle("${month.monthValue}月账单", onBack, blue = true) }
        item {
            Column(Modifier.fillMaxWidth().background(T.PrimarySoft).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${month.year}年${month.monthValue}月", color = T.TextSecondary, fontSize = 14.sp)
                Text("本月结余", Modifier.padding(top = 20.dp), fontSize = 14.sp)
                ReferenceMoney(state.summary.balanceCent, large = true)
                Text("上月结余 ${if (hidden) "••••" else Money.format(previous.summary.balanceCent)}", color = T.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Text("记录了 ${state.days.size} 天 · ${state.totalCount} 笔账单", Modifier.padding(top = 20.dp), fontSize = 13.sp)
            }
        }
        if (state.error != null) item { ReferenceRow(state.error, "重试", onClick = vm::retry) }
        item {
            Text("收支对比", fontSize = 18.sp, modifier = Modifier.padding(20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("收入", color = T.TextSecondary); ReferenceMoney(state.summary.incomeCent, large = true) }
                Column { Text("支出", color = T.TextSecondary); ReferenceMoney(state.summary.expenseCent, large = true) }
            }
            val largest = maxOf(state.summary.incomeCent, state.summary.expenseCent, previous.summary.incomeCent, previous.summary.expenseCent, 1L)
            listOf("本月收入" to state.summary.incomeCent, "上月收入" to previous.summary.incomeCent, "本月支出" to state.summary.expenseCent, "上月支出" to previous.summary.expenseCent).forEach { (label, amount) ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.width(64.dp))
                    Box(Modifier.weight(1f).height(10.dp).background(T.Background)) { Box(Modifier.fillMaxWidth((amount.toDouble() / largest).toFloat()).height(10.dp).background(if (label.startsWith("本月")) T.Primary else T.Border)) }
                }
            }
        }
        item { HorizontalDivider(color = T.Border, thickness = 6.dp); Text("支出分类", fontSize = 18.sp, modifier = Modifier.padding(20.dp)) }
        item { ReferenceCategoryRing(categories) }
        items(categories, key = { it.id }) { category -> ReferenceRanking(category, categories.firstOrNull()?.cent ?: 0) { onCategory(month, category.id) } }
        item { HorizontalDivider(color = T.Border, thickness = 6.dp); Text("支出排行榜", fontSize = 18.sp, modifier = Modifier.padding(20.dp)) }
        items(state.expenseRanking, key = { "ranking_${it.id}" }) { row -> ReferenceTransaction(row, { onDetail(row.id) }) }
        item { TextButton(onClick = { onAll(month) }, modifier = Modifier.fillMaxWidth()) { Text("查看更多") } }
        item { TextButton(enabled = state.loaded && !sharing, onClick = {
            sharing = true; shareError = null
            shareScope.launch {
                try {
                    val intent = monthlyReportShareIntent(context, month, state, previous, hidden)
                    context.startActivity(Intent.createChooser(intent, "分享账单"))
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { shareError = "生成分享图片失败，请重试" }
                finally { sharing = false }
            }
        }, modifier = Modifier.fillMaxWidth().testTag("report_share")) { Text(if (sharing) "生成图片中…" else "分享账单") }
            shareError?.let { Text(it, color = T.Risk, modifier = Modifier.padding(20.dp)) }
        }
    }
}
