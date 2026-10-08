package com.blueledger.app.feature.reference

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Check
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.*
import com.blueledger.app.core.model.*
import com.blueledger.app.core.money.Money
import com.blueledger.app.feature.entry.AmountInput
import com.blueledger.app.feature.entry.EntryAmountKeyboard
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Composable
fun ReferenceMineRoute(repository: LedgerRepository, clock: Clock, onSettings: () -> Unit, onAccounts: () -> Unit, onData: () -> Unit) {
    val (state, _) = ledgerState(repository, "reference.mine", TransactionFilter(limit = Int.MAX_VALUE))
    val prefs = rememberReferencePreferences()
    var help by remember { mutableStateOf<String?>(null) }
    val checked = prefs.storage.getString("check_in", "") == clock.today().toString()
    val streak = prefs.storage.getInt("check_streak", 0)
    val first = state.earliestDate
    val days = first?.let { (java.time.temporal.ChronoUnit.DAYS.between(it, clock.today()) + 1).toInt() } ?: 0
    LazyColumn(Modifier.fillMaxSize().background(T.Background).testTag("mine_screen")) {
        item {
            Column(Modifier.fillMaxWidth().background(T.PrimarySoft).padding(horizontal = 24.dp, vertical = 26.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(60.dp).clip(CircleShape).background(T.Surface), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = T.TextSecondary, modifier = Modifier.size(38.dp)) }
                    Column(Modifier.weight(1f).padding(start = 16.dp)) { Text("蓝记", fontSize = 21.sp, color = T.TextPrimary); Text("我的账本", color = T.TextSecondary, fontSize = 13.sp) }
                    TextButton(enabled = !checked, onClick = {
                        val last = runCatching { LocalDate.parse(prefs.storage.getString("check_in", "")) }.getOrNull()
                        prefs.storage.edit().putString("check_in", clock.today().toString())
                            .putInt("check_streak", if (last == clock.today().minusDays(1)) streak + 1 else 1).apply()
                    }) { Text(if (checked) "已打卡" else "打卡") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("连续打卡" to streak, "记账天数" to days, "记账总笔数" to state.totalCount).forEach { (title, count) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text(count.toString(), fontSize = 24.sp, color = T.TextPrimary); Text(title, fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(10.dp)); ReferenceRow("我的账本", "默认账本") { help = "账本数据仅保存在本机，可在数据管理中备份和恢复。" } }
        item { ReferenceRow("资产管家", onClick = onAccounts) }
        item { ReferenceRow("设置", tag = "mine_settings", onClick = onSettings) }
        item { Spacer(Modifier.height(10.dp)); ReferenceRow("数据管理", tag = "mine_data", onClick = onData) }
        item { ReferenceRow("使用帮助") { help = "点击底部＋，选择分类后输入金额。键盘右侧可改日期、加减计算；点击完成保存。明细顶部选择月份，图表支持周、月、年及分类钻取。资产余额可手动调整，关联账户后随账单更新。备份可在数据管理中导出。" } }
        item { ReferenceRow("关于蓝记", "1.1.3") { help = "蓝记 1.1.3\n本地离线记账\n所有金额以整数分保存。" } }
    }
    help?.let { text -> AlertDialog(onDismissRequest = { help = null }, title = { Text("蓝记") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { help = null }) { Text("知道了") } }) }
}

@Composable
fun ReferenceSettingsRoute(repository: LedgerRepository, clock: Clock, onBack: () -> Unit, onCategories: (TransactionType) -> Unit, onAccounts: () -> Unit, onData: () -> Unit, onMigrate: () -> Unit) {
    val prefs = rememberReferencePreferences()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("设置") }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    fun goBack() { if (selection != null) selection = null else if (page != "设置") page = "设置" else onBack() }
    androidx.activity.compose.BackHandler(enabled = page != "设置" || selection != null) { goBack() }
    var error by remember { mutableStateOf<String?>(null) }
    val accountFlow = remember(repository) { repository.observeAccounts(false) }
    val accounts by accountFlow.collectAsStateWithLifecycle(emptyList())
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.remindersEnabled = granted
        LedgerReminders.reschedule(context)
        if (!granted) error = "通知权限未开启，提醒尚未启用"
    }
    fun changeReminder(enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else { prefs.remindersEnabled = enabled; LedgerReminders.reschedule(context) }
    }
    fun pickTime(existing: String? = null) {
        val time = existing?.let { LocalTime.parse(it) } ?: LocalTime.of(17, 30)
        TimePickerDialog(context, { _, hour, minute ->
            val newTime = LocalTime.of(hour, minute).toString()
            prefs.reminderTimes = (prefs.reminderTimes - setOfNotNull(existing) + newTime)
            LedgerReminders.reschedule(context)
        }, time.hour, time.minute, true).show()
    }
    val selected = selection
    if (selected != null) {
        val title = when (selected) {
            "entry" -> "默认记账类型"
            "chartType" -> "默认收支类型"
            "chartPeriod" -> "默认收支周期"
            "expenseAccount" -> "默认支出账户"
            else -> "默认收入账户"
        }
        val options = when (selected) {
            "entry", "chartType" -> listOf("支出", "收入")
            "chartPeriod" -> listOf("周", "月", "年")
            else -> listOf("不关联") + accounts.map { it.account.name }
        }
        val index = when (selected) {
            "entry" -> if (prefs.defaultType.isExpense) 0 else 1
            "chartType" -> if (prefs.chartType.isExpense) 0 else 1
            "chartPeriod" -> prefs.chartPeriod.ordinal
            else -> accounts.indexOfFirst { it.account.id == prefs.defaultAccount(if (selected == "expenseAccount") TransactionType.EXPENSE else TransactionType.INCOME) } + 1
        }
        ReferenceChoicePage(title, options, index, when (selected) {
            "entry" -> "可设置记账时默认选中收入或支出"
            "chartType" -> "可设置图表默认查看收入或支出"
            "chartPeriod" -> "可设置图表默认查看周、月或年"
            else -> "新建账单时使用选中的账户"
        }, onBack = ::goBack) { chosen ->
            when (selected) {
                "entry" -> prefs.defaultType = if (chosen == 0) TransactionType.EXPENSE else TransactionType.INCOME
                "chartType" -> prefs.chartType = if (chosen == 0) TransactionType.EXPENSE else TransactionType.INCOME
                "chartPeriod" -> prefs.chartPeriod = ChartPeriod.entries[chosen]
                else -> prefs.setDefaultAccount(if (selected == "expenseAccount") TransactionType.EXPENSE else TransactionType.INCOME, if (chosen == 0) null else accounts[chosen - 1].account.id)
            }
            selection = null
        }
        return
    }
    Column(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding()) {
        ReferenceTitle(page, ::goBack, blue = true)
        LazyColumn(Modifier.weight(1f)) {
            when (page) {
                "设置" -> {
                    item { SectionLabel("功能设置"); ReferenceRow("类别设置", tag = "mine_categories", onClick = { onCategories(TransactionType.EXPENSE) }) }
                    item { ReferenceRow("收支账户", onClick = { page = "账户设置" }) }
                    item { ReferenceRow("默认记账类型", if (prefs.defaultType.isExpense) "支出" else "收入") { selection = "entry" } }
                    item { ReferenceRow("图表页设置") { page = "图表页设置" } }
                    item { SectionLabel("个性化设置"); ReferenceRow("声音与触感") { page = "声音与触感" } }
                    item { ReferenceRow("记账提醒", if (prefs.remindersEnabled) "已开启" else "未开启") { page = "记账提醒" } }
                    item { SectionLabel("数据设置"); ReferenceRow("数据导出与备份", tag = "mine_data", onClick = onData) }
                    item { val hidden = LocalHideAmounts.current; SwitchRow("隐藏总金额", hidden, "hide_amounts_switch") { scope.launch { repository.setHideAmounts(it) } } }
                    item { ReferenceRow("数据转移", onClick = onMigrate) }
                    item { SectionLabel("其他设置"); SwitchRow("快捷编辑", prefs.quickEdit) { prefs.quickEdit = it } }
                }
                "账户设置" -> {
                    item { SwitchRow("账户关联", prefs.accountAssociation) { prefs.accountAssociation = it } }
                    item { ReferenceRow("账户显示与管理", onClick = onAccounts) }
                    item { ReferenceRow("默认支出账户", accounts.firstOrNull { it.account.id == prefs.defaultAccount(TransactionType.EXPENSE) }?.account?.name ?: "不关联") { selection = "expenseAccount" } }
                    item { ReferenceRow("默认收入账户", accounts.firstOrNull { it.account.id == prefs.defaultAccount(TransactionType.INCOME) }?.account?.name ?: "不关联") { selection = "incomeAccount" } }
                }
                "图表页设置" -> {
                    item { Spacer(Modifier.height(10.dp)); ReferenceRow("默认收支类型", if (prefs.chartType.isExpense) "支出" else "收入") { selection = "chartType" } }
                    item { ReferenceRow("默认收支周期", when (prefs.chartPeriod) { ChartPeriod.WEEK -> "周"; ChartPeriod.MONTH -> "月"; ChartPeriod.YEAR -> "年" }) { selection = "chartPeriod" } }
                }
                "声音与触感" -> {
                    item { SwitchRow("按键声音", prefs.sound) { prefs.sound = it } }
                    item { SwitchRow("触感反馈", prefs.haptic) { prefs.haptic = it } }
                }
                "记账提醒" -> {
                    item { SwitchRow("记账提醒", prefs.remindersEnabled, onChange = ::changeReminder) }
                    items(prefs.reminderTimes.sorted(), key = { it }) { time ->
                        Row(Modifier.fillMaxWidth().background(T.Surface), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ReferenceRow(time) { pickTime(time) } }
                            TextButton(onClick = { prefs.reminderTimes = prefs.reminderTimes - time; LedgerReminders.reschedule(context) }) { Text("删除") }
                        }
                    }
                    item { ReferenceRow("＋ 添加提醒") { pickTime() }; Text("提醒时间受系统省电调度影响。", fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(20.dp)) }
                }
            }
            error?.let { item { Text(it, color = T.Risk, modifier = Modifier.padding(20.dp)) } }
        }
    }
}

@Composable
internal fun ReferenceChoicePage(title: String, options: List<String>, selected: Int, hint: String,
    onBack: () -> Unit, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding().testTag("settings_choice_page")) {
        ReferenceTitle(title, onBack, blue = true)
        Spacer(Modifier.height(10.dp))
        LazyColumn {
            items(options.size) { index ->
                Row(Modifier.fillMaxWidth().background(T.Surface)
                    .selectable(index == selected, role = Role.RadioButton, onClick = { onSelect(index) })
                    .heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 12.dp)
                    .testTag("settings_choice_$index"), verticalAlignment = Alignment.CenterVertically) {
                    Text(options[index], fontSize = 16.sp, color = T.TextPrimary, modifier = Modifier.weight(1f))
                    if (index == selected) Icon(Icons.Outlined.Check, "已选择", tint = T.TextPrimary, modifier = Modifier.size(24.dp))
                }
                HorizontalDivider(color = T.Border.copy(alpha = .45f), thickness = .5.dp)
            }
            item { Text(hint, color = T.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(title: String) { Text(title, fontSize = 12.sp, color = T.TextSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) }

@Composable
private fun SwitchRow(title: String, selected: Boolean, tag: String = "", onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(T.Surface).heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 16.sp, color = T.TextPrimary, modifier = Modifier.weight(1f))
        Switch(selected, onChange, Modifier.testTag(tag))
    }
    HorizontalDivider(color = T.Border, thickness = .5.dp)
}

@Composable
fun ReferenceMigrateRoute(repository: LedgerRepository, onBack: () -> Unit) {
    var typeName by rememberSaveable { mutableStateOf("EXPENSE") }
    val type = TransactionType.valueOf(typeName)
    val flow = remember(repository, type) { repository.observeCategories(type, true) }
    val categories by flow.collectAsStateWithLifecycle(emptyList())
    val (state, _) = ledgerState(repository, "reference.migrate", TransactionFilter(type = type, limit = Int.MAX_VALUE))
    var source by remember { mutableStateOf<LedgerCategory?>(null) }
    var target by remember { mutableStateOf<LedgerCategory?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding()) {
        ReferenceTitle("选择转出类别", onBack)
        ReferenceTabs(listOf(TransactionType.EXPENSE, TransactionType.INCOME), type, { if (it.isExpense) "支出" else "收入" }, { typeName = it.name })
        Text("将选中类别的全部有效账单转移到另一同类型类别。", color = T.TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(20.dp))
        message?.let { Text(it, Modifier.padding(20.dp), color = T.Primary) }
        LazyColumn { items(categories, key = { it.id }) { category ->
            ReferenceRow(category.name + if (category.isArchived) "（已归档）" else "", "${state.rows.count { it.transaction.categoryId == category.id }}笔") { source = category }
        } }
    }
    source?.let { from ->
        if (target == null) AlertDialog(onDismissRequest = { if (!busy) source = null }, title = { Text("转入类别") }, text = {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                categories.filter { it.id != from.id && !it.isArchived }.forEach { category ->
                    TextButton(onClick = { target = category }, modifier = Modifier.fillMaxWidth()) { Text(category.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { source = null }) { Text("取消") } })
        else AlertDialog(onDismissRequest = { if (!busy) target = null }, title = { Text("确认转移") }, text = {
            Text("将「${from.name}」的 ${state.rows.count { it.transaction.categoryId == from.id }} 笔账单转入「${target!!.name}」。金额和日期保持不变。")
        }, confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true; scope.launch {
                val result = repository.migrateCategory(from.id, target!!.id)
                message = if (result is MutationResult.Failure) result.error.message else "转移完成"
                busy = false; target = null; source = null
            }
        }) { Text(if (busy) "转移中…" else "转移") } }, dismissButton = { TextButton(enabled = !busy, onClick = { target = null; source = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReferenceBudgetRoute(repository: LedgerRepository, clock: Clock, initialMonth: YearMonth?, onBack: () -> Unit) {
    var monthText by rememberSaveable { mutableStateOf((initialMonth ?: clock.currentYearMonth()).toString()) }
    val month = YearMonth.parse(monthText)
    val flow = remember(repository, month) { repository.observeBudget(month) }
    val budget by flow.collectAsStateWithLifecycle(initialValue = null)
    var menu by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf(AmountInput()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val current = budget
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding()) {
        ReferenceTitle("月预算⌄", onBack) { TextButton(onClick = { picker = true }) { Text("${month.monthValue}月") } }
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("月度总预算", fontSize = 17.sp); TextButton(onClick = { menu = true }) { Text("编辑") } }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val progress = (current?.progressFraction ?: 0f).coerceIn(0f, 1f)
            val primary = T.Primary; val border = T.Border
            Box(Modifier.size(110.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    drawArc(border, 0f, 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx()))
                    if (current?.isSet == true) drawArc(primary, -90f, (1f - progress) * 360f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(10.dp.toPx()))
                }
                Text(if (current?.isSet == true) "剩余${(100 * (1 - progress)).toInt()}%" else "未设置", fontSize = 13.sp, color = T.TextPrimary)
            }
            Column(Modifier.padding(start = 20.dp)) {
                Text("剩余预算", fontSize = 13.sp, color = T.TextSecondary)
                if (current?.budgetCent != null) ReferenceMoney(current.budgetCent - current.usedCent, large = true) else Text("未设置", fontSize = 26.sp)
                Text("本月预算", color = T.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 16.dp)); ReferenceMoney(current?.budgetCent ?: 0)
                Text("本月支出", color = T.TextSecondary, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp)); ReferenceMoney(current?.usedCent ?: 0)
            }
        }
        error?.let { Text(it, color = T.Risk, modifier = Modifier.padding(20.dp)) }
    }
    if (picker) ReferenceMonthPicker(month, clock.currentYearMonth(), { picker = false }) { monthText = it.toString(); picker = false }
    if (menu) ModalBottomSheet(onDismissRequest = { menu = false }, containerColor = T.Surface) {
        ReferenceRow("编辑月度总预算") { amount = current?.budgetCent?.let { AmountInput.fromCents(it) } ?: AmountInput(); editing = true; menu = false }
        if (current?.isSet == true) ReferenceRow("清除月度总预算") { clearing = true; menu = false }
        TextButton(onClick = { menu = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
    }
    if (clearing) AlertDialog(onDismissRequest = { if (!busy) clearing = false }, title = { Text("清除每月预算？") }, text = { Text("账单和其他历史月份单独设置的预算仍会保留。") }, confirmButton = {
        TextButton(enabled = !busy, onClick = { busy = true; scope.launch { val result = repository.setMonthlyBudget(null, month); if (result is MutationResult.Failure) error = result.error.message; busy = false; clearing = false } }) { Text("清除") }
    }, dismissButton = { TextButton(onClick = { clearing = false }, enabled = !busy) { Text("取消") } })
    if (editing) ModalBottomSheet(onDismissRequest = { if (!busy) editing = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = T.Surface, dragHandle = null) {
        ReferenceTitle("每月总预算") { TextButton(enabled = !busy, onClick = { editing = false }) { Text("关闭") } }
        Text(amount.displayText, fontSize = 30.sp, modifier = Modifier.fillMaxWidth().padding(20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        error?.let { Text(it, color = T.Risk, modifier = Modifier.padding(horizontal = 20.dp)) }
        TextButton(onClick = {
            val cent = amount.centsOrNull()
            if (cent == null || cent <= 0) { error = "请输入大于0的预算"; return@TextButton }
            busy = true; scope.launch {
                when (val result = repository.setMonthlyBudget(cent, month)) {
                    is MutationResult.Success -> { editing = false; error = null }
                    is MutationResult.Failure -> error = result.error.message
                }; busy = false
            }
        }, enabled = !busy && (amount.centsOrNull() ?: 0) > 0, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "保存中…" else "确定") }
        EntryAmountKeyboard(onDigit = { amount = amount.appendDigit(it) }, onDecimalPoint = { amount = amount.appendDecimalPoint() }, onDelete = { amount = amount.deleteLast() }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), actionsEnabled = !busy)
    }
}
