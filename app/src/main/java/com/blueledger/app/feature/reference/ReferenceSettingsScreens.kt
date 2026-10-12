package com.blueledger.app.feature.reference

import com.blueledger.app.core.designsystem.LedgerChoiceRow
import com.blueledger.app.core.designsystem.LedgerCenteredTopBar
import com.blueledger.app.core.designsystem.LedgerFittedAmountText
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerMenuDivider
import com.blueledger.app.core.designsystem.LedgerMenuRow
import com.blueledger.app.core.designsystem.LedgerNavigationRow
import com.blueledger.app.core.designsystem.LedgerSectionLabel
import com.blueledger.app.core.designsystem.LedgerSwitchRow
import com.blueledger.app.core.designsystem.LedgerUnderlineTabs
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
import com.blueledger.app.feature.management.AppVersionResolver
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

@Composable
fun ReferenceMineRoute(repository: LedgerRepository, clock: Clock, onSettings: () -> Unit, onAccounts: () -> Unit, onData: () -> Unit) {
    val (state, _) = ledgerState(repository, "reference.mine", TransactionFilter(limit = Int.MAX_VALUE))
    val appVersion = AppVersionResolver.resolve(LocalContext.current)
    var help by remember { mutableStateOf<String?>(null) }
    val first = state.earliestDate
    val days = first?.let { (java.time.temporal.ChronoUnit.DAYS.between(it, clock.today()) + 1).toInt() } ?: 0
    LazyColumn(Modifier.fillMaxSize().background(T.Background).testTag("mine_screen"), contentPadding = PaddingValues(bottom = T.SpaceL)) {
        item {
            Column(Modifier.fillMaxWidth().background(T.Primary)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = T.SpaceHuge).padding(top = T.SpaceXl), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(T.ProfileAvatar).clip(CircleShape).background(T.Surface), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = T.Primary, modifier = Modifier.size(T.ProfileAvatar * .6f)) }
                    Column(Modifier.weight(1f).padding(horizontal = T.SpaceM)) { Text("蓝记", fontSize = T.PageTitle, color = T.OnBrand); Text(if (androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.4f) "免费记账\n无广告" else "免费记账 · 无广告", color = T.OnBrandSecondary, fontSize = T.Caption) }

                }
                Row(Modifier.fillMaxWidth().padding(top = T.SpaceXl, bottom = T.SpaceM), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("记账天数" to days, "记账总笔数" to state.totalCount).forEach { (title, count) ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { Text(count.toString(), fontSize = T.ProfileCount, color = T.OnBrand); Text(title, fontSize = T.Caption, color = T.OnBrandSecondary, modifier = Modifier.padding(top = T.SpaceXs)) }
                    }
                }
            }
        }
        item {
            LedgerSectionCard(
                modifier = Modifier.padding(horizontal = T.SpaceL).padding(top = T.SpaceL),
                contentPadding = PaddingValues(0.dp), elevation = 0.dp,
            ) {
                LedgerMenuRow("我的账本", Icons.Outlined.ReceiptLong, "默认账本") { help = "账本数据仅保存在本机，可在数据管理中备份和恢复。" }
                LedgerMenuDivider()
                LedgerMenuRow("资产管家", Icons.Outlined.AccountBalanceWallet, onClick = onAccounts)
                LedgerMenuDivider()
                LedgerMenuRow("设置", Icons.Outlined.Settings, tag = "mine_settings", onClick = onSettings)
            }
        }
        item {
            LedgerSectionCard(
                modifier = Modifier.padding(horizontal = T.SpaceL).padding(top = T.SpaceL),
                contentPadding = PaddingValues(0.dp), elevation = 0.dp,
            ) {
                LedgerMenuRow("数据管理", Icons.Outlined.Backup, tag = "mine_data", onClick = onData)
                LedgerMenuDivider()
                LedgerMenuRow("使用帮助", Icons.Outlined.HelpOutline) { help = "点击底部＋，选择分类后输入金额。键盘右侧可改日期、加减计算；点击完成保存。明细顶部选择月份，图表支持周、月、年及分类钻取。资产余额可手动调整，关联账户后随账单更新。备份可在数据管理中导出。" }
                LedgerMenuDivider()
                LedgerMenuRow("关于蓝记", Icons.Outlined.Info, appVersion) { help = "蓝记 $appVersion\n免费离线记账 · 无广告\n所有金额以整数分保存。" }
            }
        }
    }
    help?.let { text -> AlertDialog(onDismissRequest = { help = null }, title = { Text("蓝记") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { help = null }) { Text("知道了") } }) }
}

@Composable
fun ReferenceSettingsRoute(repository: LedgerRepository, clock: Clock, onBack: () -> Unit, onCategories: (TransactionType) -> Unit, onAccounts: () -> Unit, onData: () -> Unit, onMigrate: () -> Unit, onAdvanced: () -> Unit = {}) {
    val prefs = rememberReferencePreferences()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf("设置") }
    var selection by rememberSaveable { mutableStateOf<String?>(null) }
    // 账户关联开关用显式 Compose 状态驱动：下方三行的可用性随它即时变化。
    var accountsLinked by remember { mutableStateOf(prefs.accountAssociation) }
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
        LedgerCenteredTopBar(if (page == "账户设置") "收支账户设置" else page, ::goBack, blue = true)
        LazyColumn(Modifier.weight(1f)) {
            when (page) {
                "设置" -> {
                    item { LedgerSectionLabel("功能设置"); LedgerNavigationRow("类别设置", tag = "mine_categories", onClick = { onCategories(TransactionType.EXPENSE) }) }
                    item { LedgerNavigationRow("收支账户", subtitle = "开启后，主账本记账时可选收支账户", onClick = { page = "账户设置" }) }
                    item { LedgerNavigationRow("默认记账类型", if (prefs.defaultType.isExpense) "支出" else "收入") { selection = "entry" } }
                    item { LedgerNavigationRow("图表页设置") { page = "图表页设置" } }
                    item { LedgerNavigationRow("高级功能", subtitle = "分类预算、自动记账、标签、日历、月起始日与回收站", tag = "mine_advanced", onClick = onAdvanced) }
                    item { LedgerSectionLabel("个性化设置"); LedgerNavigationRow("声音与触感") { page = "声音与触感" } }
                    item { LedgerNavigationRow("记账提醒", if (prefs.remindersEnabled) "已开启" else "未开启") { page = "记账提醒" } }
                    item { LedgerSectionLabel("数据设置"); LedgerNavigationRow("数据导出与备份", tag = "mine_data", onClick = onData) }
                    item { val hidden = LocalHideAmounts.current; LedgerSwitchRow("隐藏总金额", hidden, "hide_amounts_switch") { scope.launch { repository.setHideAmounts(it) } } }
                    item { LedgerNavigationRow("数据转移", onClick = onMigrate) }
                    item { LedgerSectionLabel("其他设置"); LedgerSwitchRow("快捷编辑", prefs.quickEdit) { prefs.quickEdit = it } }
                }
                "账户设置" -> {
                    item { LedgerSwitchRow("收支账户", accountsLinked, tag = "account_association_switch", subtitle = "开启后，主账本记账时可选收支账户") { accountsLinked = it; prefs.accountAssociation = it } }
                    item { LedgerNavigationRow("账户展示设置", subtitle = "设置记账时可选择的账户", enabled = accountsLinked, onClick = onAccounts) }
                    item {
                        val name = accounts.firstOrNull { it.account.id == prefs.defaultAccount(TransactionType.EXPENSE) }?.account?.name ?: "不关联账户"
                        LedgerNavigationRow("默认支出账户", value = name, subtitle = "记支出时系统默认选中的账户", enabled = accountsLinked) { selection = "expenseAccount" }
                    }
                    item {
                        val name = accounts.firstOrNull { it.account.id == prefs.defaultAccount(TransactionType.INCOME) }?.account?.name ?: "不关联账户"
                        LedgerNavigationRow("默认收入账户", value = name, subtitle = "记收入时系统默认选中的账户", enabled = accountsLinked) { selection = "incomeAccount" }
                    }
                }
                "图表页设置" -> {
                    item { Spacer(Modifier.height(10.dp)); LedgerNavigationRow("默认收支类型", if (prefs.chartType.isExpense) "支出" else "收入") { selection = "chartType" } }
                    item { LedgerNavigationRow("默认收支周期", when (prefs.chartPeriod) { ChartPeriod.WEEK -> "周"; ChartPeriod.MONTH -> "月"; ChartPeriod.YEAR -> "年" }) { selection = "chartPeriod" } }
                }
                "声音与触感" -> {
                    item { LedgerSwitchRow("按键声音", prefs.sound) { prefs.sound = it } }
                    item { LedgerSwitchRow("触感反馈", prefs.haptic) { prefs.haptic = it } }
                }
                "记账提醒" -> {
                    item { LedgerSwitchRow("记账提醒", prefs.remindersEnabled, onChange = ::changeReminder) }
                    items(prefs.reminderTimes.sorted(), key = { it }) { time ->
                        Row(Modifier.fillMaxWidth().background(T.Surface), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { LedgerNavigationRow(time) { pickTime(time) } }
                            TextButton(onClick = { prefs.reminderTimes = prefs.reminderTimes - time; LedgerReminders.reschedule(context) }) { Text("删除") }
                        }
                    }
                    item { LedgerNavigationRow("＋ 添加提醒") { pickTime() } }
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
        LedgerCenteredTopBar(title, onBack, blue = true)
        Spacer(Modifier.height(10.dp))
        LazyColumn {
            items(options.size) { index ->
                LedgerChoiceRow(options[index], index == selected, tag = "settings_choice_$index") {
                    onSelect(index)
                }
            }
        }
    }
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
        LedgerCenteredTopBar("选择转出类别", onBack)
        LedgerUnderlineTabs(listOf(TransactionType.EXPENSE, TransactionType.INCOME), type, { if (it.isExpense) "支出" else "收入" }, { typeName = it.name })
        message?.let { Text(it, Modifier.padding(20.dp), color = T.Primary) }
        LazyColumn { items(categories, key = { it.id }) { category ->
            LedgerNavigationRow(category.name + if (category.isArchived) "（已归档）" else "", value = "${state.rows.count { it.transaction.categoryId == category.id }}笔") { source = category }
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
