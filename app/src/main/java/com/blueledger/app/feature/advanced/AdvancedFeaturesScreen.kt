package com.blueledger.app.feature.advanced

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.*
import com.blueledger.app.core.money.Money
import com.blueledger.app.feature.reference.ReferenceTitle
import com.blueledger.app.feature.reference.ReferenceRow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

private fun amount(cent: Long, hidden: Boolean) = if (hidden) "••••" else Money.format(cent)
private fun frequencyName(value: RecurrenceFrequency) = when (value) {
    RecurrenceFrequency.DAILY -> "天"; RecurrenceFrequency.WEEKLY -> "周"; RecurrenceFrequency.MONTHLY -> "月"; RecurrenceFrequency.YEARLY -> "年"
}

@Composable
fun AdvancedFeaturesRoute(repository: LedgerRepository, clock: Clock, onBack: () -> Unit, onDetail: (String) -> Unit) {
    val advancedFlow = remember(repository) { repository.observeAdvancedSettings() }
    val advanced by advancedFlow.collectAsStateWithLifecycle(AdvancedLedgerSettings())
    val expenseFlow = remember(repository) { repository.observeCategories(TransactionType.EXPENSE, true) }
    val expenses by expenseFlow.collectAsStateWithLifecycle(emptyList())
    val incomeFlow = remember(repository) { repository.observeCategories(TransactionType.INCOME, true) }
    val incomes by incomeFlow.collectAsStateWithLifecycle(emptyList())
    val accountFlow = remember(repository) { repository.observeAccounts(true) }
    val accounts by accountFlow.collectAsStateWithLifecycle(emptyList())
    val trashFlow = remember(repository) { repository.observeRecycleBin() }
    val trash by trashFlow.collectAsStateWithLifecycle(emptyList())
    var page by rememberSaveable { mutableStateOf("高级功能") }
    var monthText by rememberSaveable { mutableStateOf(clock.currentYearMonth().toString()) }
    var dateText by rememberSaveable { mutableStateOf(clock.today().toString()) }
    var selectedTag by rememberSaveable { mutableStateOf<String?>(null) }
    var assignTags by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val period = if (page == "记账日历") month.atDay(1)..month.atEndOfMonth() else LedgerPeriods.range(month, advanced.monthStartDay)
    val filter = if (page == "记账标签") TransactionFilter(limit = 100_000) else TransactionFilter(from = period.start, to = period.endInclusive, limit = 100_000)
    val rowsFlow = remember(repository, filter) { repository.observeTransactions(filter) }
    val rows by rowsFlow.collectAsStateWithLifecycle(TransactionPageState())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val hidden = LocalHideAmounts.current
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var budgetCategory by remember { mutableStateOf<LedgerCategory?>(null) }
    var newTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<LedgerTag?>(null) }
    var recurringEditor by remember { mutableStateOf<RecurringLedgerRule?>(null) }
    var newRecurring by remember { mutableStateOf(false) }
    var deleteTransaction by remember { mutableStateOf<LedgerTransaction?>(null) }
    var deleteTag by remember { mutableStateOf<LedgerTag?>(null) }
    var deleteRule by remember { mutableStateOf<RecurringLedgerRule?>(null) }
    fun mutate(change: (AdvancedLedgerSettings) -> AdvancedLedgerSettings) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = repository.updateAdvancedSettings(change)
                message = if (result is MutationResult.Failure) result.error.message else null
                if (result is MutationResult.Success) {
                    val posted = repository.processRecurringTransactions()
                    if (posted is MutationResult.Failure) message = posted.error.message
                    RecurringScheduler.reschedule(context, repository, clock)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "自动调度暂未完成，下次打开应用会继续补记。"
            } finally { busy = false }
        }
    }
    fun back() { if (selectedTag != null) { selectedTag = null; assignTags = false } else if (page != "高级功能") page = "高级功能" else onBack() }
    BackHandler(page != "高级功能" || selectedTag != null) { back() }
    LaunchedEffect(advanced.monthStartDay) { if (page != "记账日历") monthText = LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay).toString() }
    budgetCategory?.let { category ->
        AmountEditor("${category.name}每月预算", advanced.categoryBudgets.firstOrNull { it.categoryId == category.id }?.amountCent, onDismiss = { budgetCategory = null }, onRemove = { mutate { it.copy(categoryBudgets = it.categoryBudgets.filterNot { it.categoryId == category.id }) }; budgetCategory = null }) { cent ->
            mutate { it.copy(categoryBudgets = it.categoryBudgets.filterNot { it.categoryId == category.id } + CategoryBudgetRule(category.id, cent)) }; budgetCategory = null
        }
        return
    }
    if (newTag || editingTag != null) {
        NameEditor("标签名称", editingTag?.name.orEmpty(), onDismiss = { newTag = false; editingTag = null }) { name ->
            val tag = LedgerTag(editingTag?.id ?: UUID.randomUUID().toString(), name.trim())
            mutate { it.copy(tags = it.tags.filterNot { old -> old.id == tag.id } + tag) }; newTag = false; editingTag = null
        }
        return
    }
    if (newRecurring || recurringEditor != null) {
        RecurringRuleEditor(recurringEditor, clock, expenses + incomes, accounts.map { it.account }, onDismiss = { newRecurring = false; recurringEditor = null }) { rule ->
            mutate { it.copy(recurringRules = it.recurringRules.filterNot { old -> old.id == rule.id } + rule) }; newRecurring = false; recurringEditor = null
        }
        return
    }
    Column(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding().testTag("advanced_screen")) {
        ReferenceTitle(selectedTag?.let { id -> advanced.tags.firstOrNull { it.id == id }?.name } ?: page, ::back, blue = true)
        if (message != null) Text(message!!, color = T.Risk, modifier = Modifier.padding(16.dp).testTag("advanced_error"))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("advanced_list")) {
            when (page) {
                "高级功能" -> {
                    item { Text("功能免费使用，数据保存在本机。", color = T.TextSecondary, modifier = Modifier.padding(20.dp)) }
                    items(listOf("分类预算", "自动记账", "记账标签", "记账日历", "月起始日", "回收站")) { title ->
                        ReferenceRow(title, tag = "advanced_$title") { page = title; selectedTag = null; monthText = (if (title == "记账日历") clock.currentYearMonth() else LedgerPeriods.monthOf(clock.today(), advanced.monthStartDay)).toString() }
                    }
                }
                "分类预算" -> {
                    item { MonthNavigation(month, { monthText = it.toString() }) }
                    item { Text("${period.start} 至 ${period.endInclusive}\n预算每月沿用；收入不计入支出预算。", Modifier.padding(16.dp), color = T.TextSecondary) }
                    items(expenses, key = { it.id }) { category ->
                        val used = rows.items.filter { it.type.isExpense && it.transaction.categoryId == category.id }.sumOf { it.amountCent }
                        val budget = advanced.categoryBudgets.firstOrNull { it.categoryId == category.id }
                        ReferenceRow(category.name, budget?.let { "已用 ${amount(used, hidden)} / ${amount(it.amountCent, hidden)}" } ?: "未设置", subtitle = budget?.let { "${if (used > it.amountCent) "超出" else "剩余"} ${amount(kotlin.math.abs(it.amountCent - used), hidden)}" } ?: "点击设置每月分类预算", tag = "category_budget_${category.id}", enabled = !busy) { budgetCategory = category }
                    }
                }
                "月起始日" -> {
                    item { Text("选择每个记账月的开始日期。短月自动使用月末；只改变统计区间，不修改账单发生日期。", Modifier.padding(16.dp), color = T.TextSecondary) }
                    items((1..31).toList()) { day ->
                        ReferenceRow("每月${day}日", if (day == advanced.monthStartDay) "✓" else "", tag = "month_start_$day", enabled = !busy) { mutate { it.copy(monthStartDay = day) } }
                    }
                }
                "自动记账" -> {
                    item { ReferenceRow("＋ 添加周期规则", tag = "recurring_add", enabled = !busy) { newRecurring = true } }
                    item { Text("到期自动生成账单，应用启动时补记；省电限制可能延后后台执行。规则暂停后不再生成。", Modifier.padding(16.dp), color = T.TextSecondary) }
                    items(advanced.recurringRules, key = { it.id }) { rule ->
                        Column(Modifier.fillMaxWidth().background(T.Surface).padding(16.dp)) {
                            Text(rule.name, style = MaterialTheme.typography.titleMedium)
                            Text("${if (rule.type == "INCOME") "收入" else "支出"} ${amount(rule.amountCent, hidden)} · 每${rule.interval}${frequencyName(rule.frequency)}")
                            Text("下次：${rule.nextDate}", color = T.TextSecondary)
                            rule.lastError?.let { Text(it, color = T.Risk) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(rule.enabled, enabled = !busy, onCheckedChange = { enabled -> mutate { current -> current.copy(recurringRules = current.recurringRules.map { if (it.id == rule.id) it.copy(enabled = enabled, lastError = null) else it }) } }, modifier = Modifier.semantics { contentDescription = "启用周期规则${rule.name}" }.testTag("recurring_enabled_${rule.id}"))
                                TextButton(enabled = !busy, onClick = { recurringEditor = rule }) { Text("编辑") }
                                TextButton(enabled = !busy, onClick = { deleteRule = rule }) { Text("删除规则") }
                            }
                        }
                    }
                    if (advanced.recurringRules.isEmpty()) item { Text("暂无周期规则", Modifier.padding(20.dp)) }
                }
                "记账标签" -> {
                    if (selectedTag == null) {
                        item { ReferenceRow("＋ 添加标签", tag = "tag_add", enabled = !busy) { newTag = true } }
                        items(advanced.tags, key = { it.id }) { tag ->
                            Row(Modifier.fillMaxWidth().background(T.Surface), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.weight(1f)) { ReferenceRow(tag.name, "${rows.items.count { tag.id in advanced.transactionTags[it.id].orEmpty() }} 笔", tag = "tag_${tag.id}") { selectedTag = tag.id } }
                                TextButton(enabled = !busy, onClick = { editingTag = tag }) { Text("改名") }
                                TextButton(enabled = !busy, onClick = { deleteTag = tag }) { Text("删除") }
                            }
                        }
                        if (advanced.tags.isEmpty()) item { Text("暂无标签", Modifier.padding(20.dp)) }
                    } else {
                        item { ReferenceRow(if (assignTags) "完成关联" else "关联账单", tag = "tag_assign") { assignTags = !assignTags } }
                        val visible = rows.items.filter { assignTags || selectedTag in advanced.transactionTags[it.id].orEmpty() }
                        item { val income = visible.filter { it.type.isIncome }.sumOf { it.amountCent }; val expense = visible.filter { it.type.isExpense }.sumOf { it.amountCent }
                            Text("${visible.size} 笔 · 收入 ${amount(income, hidden)} · 支出 ${amount(expense, hidden)}", Modifier.padding(16.dp)) }
                        items(visible, key = { it.id }) { row ->
                            Row(Modifier.fillMaxWidth().background(T.Surface), verticalAlignment = Alignment.CenterVertically) {
                                if (assignTags) Checkbox(selectedTag in advanced.transactionTags[row.id].orEmpty(), enabled = !busy, onCheckedChange = { checked ->
                                    val id = selectedTag!!
                                    mutate { current -> val old = current.transactionTags[row.id].orEmpty(); val changed = if (checked) (old + id).distinct() else old - id; current.copy(transactionTags = if (changed.isEmpty()) current.transactionTags - row.id else current.transactionTags + (row.id to changed)) }
                                }, modifier = Modifier.semantics { contentDescription = "关联${row.occurredOn}${row.categoryName}" }.testTag("tag_transaction_${row.id}"))
                                Box(Modifier.weight(1f)) { ReferenceRow(row.categoryName, amount(row.amountCent, hidden), subtitle = "${row.occurredOn} ${row.note}") { if (!assignTags) onDetail(row.id) } }
                            }
                        }
                        if (visible.isEmpty()) item { Text("没有关联账单，点击关联账单添加。", Modifier.padding(20.dp)) }
                    }
                }
                "记账日历" -> {
                    item { MonthNavigation(month, { monthText = it.toString(); dateText = it.atDay(1).toString() }) }
                    item { CalendarGrid(month, LocalDate.parse(dateText), rows.items, hidden) { dateText = it.toString() } }
                    item { Text(dateText, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium) }
                    val dayRows = rows.items.filter { it.occurredOn.toString() == dateText }
                    item { Text("收入 ${amount(dayRows.filter { it.type.isIncome }.sumOf { it.amountCent }, hidden)} · 支出 ${amount(dayRows.filter { it.type.isExpense }.sumOf { it.amountCent }, hidden)}", Modifier.padding(horizontal = 16.dp)) }
                    items(dayRows, key = { it.id }) { row -> ReferenceRow(row.categoryName, amount(row.amountCent, hidden), subtitle = row.note) { onDetail(row.id) } }
                    if (dayRows.isEmpty()) item { Text("当日无记录", Modifier.padding(20.dp)) }
                }
                "回收站" -> {
                    item { Text("已删除记录不参与统计和余额。恢复会保留原 ID、日期、金额与标签；永久删除需要再次确认。", Modifier.padding(16.dp), color = T.TextSecondary) }
                    items(trash, key = { it.id }) { row ->
                        Column(Modifier.fillMaxWidth().background(T.Surface).padding(16.dp)) {
                            Text("${row.occurredOn} · ${expenses.plus(incomes).firstOrNull { it.id == row.categoryId }?.name.orEmpty()} · ${amount(row.amountCent, hidden)}")
                            if (row.note.isNotEmpty()) Text(row.note)
                            Row {
                                TextButton(enabled = !busy, modifier = Modifier.testTag("trash_restore_${row.id}"), onClick = { busy = true; scope.launch { try { val result = repository.restoreFromRecycleBin(row.id); message = (result as? MutationResult.Failure)?.error?.message } finally { busy = false } } }) { Text("恢复") }
                                TextButton(enabled = !busy, onClick = { deleteTransaction = row }) { Text("永久删除", color = T.Risk) }
                            }
                        }
                    }
                    if (trash.isEmpty()) item { Text("回收站为空", Modifier.padding(20.dp)) }
                }
            }
        }
    }
    deleteTag?.let { tag -> ConfirmRemoval("删除标签「${tag.name}」？", "只移除标签及关联，不删除账单。", { deleteTag = null }) {
        mutate { it.copy(tags = it.tags.filterNot { t -> t.id == tag.id }, transactionTags = it.transactionTags.mapValues { (_, ids) -> ids - tag.id }.filterValues { ids -> ids.isNotEmpty() }) }; deleteTag = null
    } }
    deleteRule?.let { rule -> ConfirmRemoval("删除周期规则？", "已经生成的账单会保留。", { deleteRule = null }) { mutate { it.copy(recurringRules = it.recurringRules.filterNot { r -> r.id == rule.id }) }; deleteRule = null } }
    deleteTransaction?.let { row -> ConfirmRemoval("永久删除这笔记录？", "永久删除后无法恢复。", { deleteTransaction = null }) { busy = true; scope.launch { try { val result = repository.permanentlyDeleteTransaction(row.id); message = (result as? MutationResult.Failure)?.error?.message } finally { busy = false; deleteTransaction = null } } } }
}

@Composable private fun MonthNavigation(month: YearMonth, change: (YearMonth) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { change(month.minusMonths(1)) }) { Text("上月") }; Text(month.toString()); TextButton(onClick = { change(month.plusMonths(1)) }) { Text("下月") }
    }
}

@Composable private fun CalendarGrid(month: YearMonth, selected: LocalDate, rows: List<TransactionWithRefs>, hidden: Boolean, select: (LocalDate) -> Unit) {
    val offset = month.atDay(1).dayOfWeek.value - 1
    val dates = List(offset + month.lengthOfMonth()) { index -> if (index < offset) null else month.atDay(index - offset + 1) }
    val byDate = remember(rows) { rows.groupBy { it.occurredOn } }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f).padding(8.dp)) } }
        dates.chunked(7).forEach { week -> Row(Modifier.fillMaxWidth()) {
            week.forEach { date ->
                Column(Modifier.weight(1f).heightIn(min = 64.dp).background(if (date == selected) T.PrimarySoft else T.Surface).then(if (date != null) Modifier.clickable { select(date) }.testTag("calendar_$date") else Modifier).padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date?.dayOfMonth?.toString().orEmpty())
                    if (date != null && byDate[date].orEmpty().isNotEmpty()) Text(if (hidden) "••" else "${byDate[date]!!.size}笔", color = T.Primary, style = MaterialTheme.typography.labelSmall)
                }
            }; repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
        } }
    }
}

@Composable private fun AdvancedEditor(title: String, dismiss: () -> Unit, enabled: Boolean, save: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = dismiss)
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding().imePadding().testTag("advanced_editor")) {
        ReferenceTitle(title, dismiss, blue = true)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = dismiss) { Text("取消") }
            Button(enabled = enabled, onClick = save, modifier = Modifier.testTag("advanced_save")) { Text("保存") }
        }
    }
}

@Composable private fun AmountEditor(title: String, initial: Long?, onDismiss: () -> Unit, onRemove: () -> Unit, save: (Long) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial?.let(Money::format).orEmpty().replace(",", "")) }
    val cent = Money.parseOrNull(text)
    AdvancedEditor(title, onDismiss, cent != null, { save(cent!!) }) {
        OutlinedTextField(text, { text = it }, label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("advanced_amount"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        Text("预算每月沿用，收入不计入分类支出。", color = T.TextSecondary)
        if (initial != null) TextButton(onClick = onRemove) { Text("清除预算") }
    }
}

@Composable private fun NameEditor(title: String, initial: String, onDismiss: () -> Unit, save: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AdvancedEditor(title, onDismiss, text.trim().length in 1..20, { save(text) }) {
        OutlinedTextField(text, { text = it }, singleLine = true, label = { Text("1—20 字符") }, modifier = Modifier.fillMaxWidth().testTag("advanced_name"))
    }
}

@Composable private fun ConfirmRemoval(title: String, text: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = confirm) { Text("确认删除", color = T.Risk) } }, dismissButton = { TextButton(onClick = dismiss) { Text("取消") } })
}

@Composable private fun RecurringRuleEditor(initial: RecurringLedgerRule?, clock: Clock, categories: List<LedgerCategory>, accounts: List<LedgerAccount>, onDismiss: () -> Unit, save: (RecurringLedgerRule) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var amountText by rememberSaveable { mutableStateOf(initial?.amountCent?.let(Money::format).orEmpty().replace(",", "")) }
    var type by rememberSaveable { mutableStateOf(initial?.type ?: "EXPENSE") }
    var categoryId by rememberSaveable { mutableStateOf(initial?.categoryId ?: categories.firstOrNull { it.type.name == type && !it.isArchived }?.id.orEmpty()) }
    var accountId by rememberSaveable { mutableStateOf(initial?.accountId ?: accounts.firstOrNull { !it.isArchived }?.id.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(initial?.startDate ?: clock.today().toString()) }
    var frequency by rememberSaveable { mutableStateOf(initial?.frequency?.name ?: RecurrenceFrequency.MONTHLY.name) }
    var interval by rememberSaveable { mutableStateOf(initial?.interval?.toString() ?: "1") }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    val cent = Money.parseOrNull(amountText)
    val start = runCatching { LocalDate.parse(date) }.getOrNull()
    val intervalValue = interval.toIntOrNull()
    val valid = name.trim().length in 1..40 && cent != null && start != null && start.year in 1900..9998 && intervalValue != null && intervalValue in 1..365 && categories.any { it.id == categoryId && it.type.name == type && !it.isArchived } && accounts.any { it.id == accountId && !it.isArchived } && note.length <= 200
    AdvancedEditor(if (initial == null) "添加周期规则" else "编辑周期规则", onDismiss, valid, {
        save(RecurringLedgerRule(initial?.id ?: UUID.randomUUID().toString(), name.trim(), type, cent!!, categoryId, accountId, note, date, if (initial != null && date == initial.startDate && frequency == initial.frequency.name && intervalValue == initial.interval) initial.nextDate else date, RecurrenceFrequency.valueOf(frequency), intervalValue!!, initial?.enabled ?: true))
    }) {
            OutlinedTextField(name, { name = it }, label = { Text("规则名称（1—40字符）") }, singleLine = true, modifier = Modifier.testTag("recurring_name"))
            OutlinedTextField(amountText, { amountText = it }, label = { Text("金额（元）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("recurring_amount"), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Row { listOf("EXPENSE" to "支出", "INCOME" to "收入").forEach { (value, label) -> TextButton(onClick = { type = value; categoryId = categories.firstOrNull { it.type.name == value && !it.isArchived }?.id.orEmpty() }) { Text(if (type == value) "✓ $label" else label) } } }
            Text("分类"); Row(Modifier.horizontalScroll(rememberScrollState())) { categories.filter { it.type.name == type && !it.isArchived }.forEach { category -> TextButton(onClick = { categoryId = category.id }) { Text((if (categoryId == category.id) "✓ " else "") + category.name) } } }
            Text("账户"); Row(Modifier.horizontalScroll(rememberScrollState())) { accounts.filterNot { it.isArchived }.forEach { account -> TextButton(onClick = { accountId = account.id }) { Text((if (accountId == account.id) "✓ " else "") + account.name) } } }
            Row(Modifier.horizontalScroll(rememberScrollState())) { RecurrenceFrequency.entries.forEach { value -> TextButton(onClick = { frequency = value.name }) { Text((if (frequency == value.name) "✓ " else "") + "每${frequencyName(value)}") } } }
            OutlinedTextField(interval, { interval = it }, label = { Text("间隔（1—365）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(date, { date = it }, label = { Text("开始日期 YYYY-MM-DD") }, singleLine = true, modifier = Modifier.testTag("recurring_date"))
            OutlinedTextField(note, { note = it }, label = { Text("备注") })
            Text("开始日期已到的规则将自动补记；创建前请核对金额、分类和账户。", color = T.TextSecondary)
    }
}
