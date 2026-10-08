package com.blueledger.app.feature.reference

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.contract.*
import com.blueledger.app.core.designsystem.LedgerIcons
import com.blueledger.app.core.model.*
import com.blueledger.app.core.money.Money
import com.blueledger.app.feature.entry.AmountInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth
import kotlin.math.abs

@Composable
fun ReferenceAssetsRoute(repository: LedgerRepository, clock: Clock, onBack: () -> Unit, onAccountBills: (String) -> Unit) {
    val accountFlow = remember(repository) { repository.observeAccounts(false) }
    val accounts by accountFlow.collectAsStateWithLifecycle(initialValue = null)
    val assets = remember(accounts) { accounts.orEmpty().filter { it.account.id != Defaults.DEFAULT_ACCOUNT_ID || it.account.name != Defaults.DEFAULT_ACCOUNT_NAME } }
    var charts by rememberSaveable { mutableStateOf(false) }
    var types by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf<AccountKind?>(null) }
    var edit by remember { mutableStateOf<AccountWithBalance?>(null) }
    var detail by remember { mutableStateOf<AccountWithBalance?>(null) }
    val totals = remember(assets) { assetTotals(assets.map { it.balanceCent }) }
    androidx.activity.compose.BackHandler(enabled = types || selectedType != null || edit != null) {
        when { selectedType != null -> selectedType = null; edit != null -> edit = null; else -> types = false }
    }
    if (types) { AssetTypesDialog({ types = false }) { selectedType = it; types = false }; return }
    selectedType?.let { kind -> AssetEditDialog(repository, kind, null, { selectedType = null }); return }
    edit?.let { item -> AssetEditDialog(repository, item.account.kind, item, { edit = null }); return }
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding().testTag("assets_screen")) {
        ReferenceTitle("资产管家", onBack)
        if (charts) ReferenceAssetCharts(repository, clock, assets, Modifier.weight(1f)) else LazyColumn(Modifier.weight(1f)) {
            item {
                Column(Modifier.fillMaxWidth().padding(20.dp).clip(RoundedCornerShape(12.dp)).background(T.PrimarySoft).padding(20.dp)) {
                    Text("净资产", fontSize = 13.sp, color = T.TextSecondary)
                    ReferenceMoney(totals.first - totals.second, large = true, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column { Text("资产", color = T.TextSecondary, fontSize = 13.sp); ReferenceMoney(totals.first) }
                        Column { Text("负债", color = T.TextSecondary, fontSize = 13.sp); ReferenceMoney(totals.second) }
                    }
                }
            }
            if (accounts == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (accounts != null && assets.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.AccountBalanceWallet, null, Modifier.size(88.dp), tint = T.PrimarySoft)
                    Text("添加资产，清楚掌握余额", Modifier.padding(top = 20.dp), color = T.TextSecondary)
                }
            }
            items(assets, key = { it.account.id }) { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).clickable { detail = item }.padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(T.Background), contentAlignment = Alignment.Center) {
                        Icon(LedgerIcons.accountKindIcon(item.account.kind), null, tint = T.TextPrimary)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(item.account.name, color = T.TextPrimary, fontSize = 16.sp)
                        Text(item.account.note.ifBlank { LedgerIcons.accountKindLabel(item.account.kind) }, color = T.TextSecondary, fontSize = 12.sp, maxLines = 1)
                    }
                    ReferenceMoney(item.balanceCent)
                }
                HorizontalDivider(Modifier.padding(start = 76.dp), color = T.Border, thickness = .5.dp)
            }
            item { TextButton(onClick = { types = true }, modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("asset_add")) { Text("＋ 添加资产", fontSize = 16.sp) } }
        }
        Row(Modifier.fillMaxWidth().height(56.dp)) {
            listOf(false, true).forEach { isChart -> TextButton(onClick = { charts = isChart }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                Text(if (isChart) "图表" else "资产", color = if (isChart == charts) T.Primary else T.TextSecondary)
            } }
        }
    }
    detail?.let { current ->
        val item = assets.firstOrNull { it.account.id == current.account.id } ?: current
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(item.account.name) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (item.balanceCent < 0) "当前欠款" else "当前余额", color = T.TextSecondary)
                ReferenceMoney(abs(item.balanceCent), large = true)
                if (item.account.note.isNotBlank()) Text(item.account.note)
                TextButton(onClick = { onAccountBills(item.account.id); detail = null }) { Text("查看关联账单") }
                val history = remember(item) { AssetHistory.parse(item.account.openingHistory) }
                if (history.isNotEmpty()) {
                    Text("余额调整记录", fontSize = 14.sp)
                    Column(Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())) {
                        history.asReversed().forEach { (date, cent) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(date.toString(), fontSize = 12.sp); ReferenceMoney(cent) }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { edit = item; detail = null }) { Text("编辑") } }, dismissButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
}

internal fun assetTotals(balances: List<Long>): Pair<Long, Long> = balances.filter { it > 0 }.sum() to -balances.filter { it < 0 }.sum()

internal fun parseAssetBalance(raw: String, kind: AccountKind): Long? {
    val negative = raw.startsWith('-')
    if (negative && kind.isLiability) return null
    val cent = AmountInput(if (negative) raw.drop(1) else raw).centsOrNull() ?: return null
    if (cent > Limits.MAX_OPENING_BALANCE_CENT) return null
    return if (negative || kind.isLiability) -cent else cent
}

@Composable
private fun AssetTypesDialog(dismiss: () -> Unit, choose: (AccountKind) -> Unit) {
    val kinds = listOf(AccountKind.CASH, AccountKind.BANK_CARD, AccountKind.CREDIT_CARD, AccountKind.E_WALLET, AccountKind.INVESTMENT, AccountKind.LIABILITY, AccountKind.RECEIVABLE, AccountKind.OTHER)
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding()) {
        ReferenceTitle("添加资产", dismiss)
        Column(Modifier.verticalScroll(rememberScrollState())) { kinds.forEach { kind ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { choose(kind) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(LedgerIcons.accountKindIcon(kind), null, tint = T.TextPrimary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
                Text(LedgerIcons.accountKindLabel(kind), color = T.TextPrimary)
            }
            HorizontalDivider(color = T.Border, thickness = .5.dp)
        } }
    }
}

@Composable
private fun AssetEditDialog(repository: LedgerRepository, kind: AccountKind, original: AccountWithBalance?, dismiss: () -> Unit) {
    var name by remember { mutableStateOf(original?.account?.name ?: if (kind == AccountKind.CASH) "现金" else "") }
    var note by remember { mutableStateOf(original?.account?.note.orEmpty()) }
    var amount by remember { mutableStateOf(original?.balanceCent?.let { Money.format(if (kind.isLiability) abs(it) else it).replace(",", "") } ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save() {
        val desired = parseAssetBalance(amount, kind)
        if (desired == null) { error = "请输入有效金额，最多两位小数"; return }
        val flowDelta = original?.let { it.balanceCent - it.account.openingBalanceCent } ?: 0
        saving = true
        scope.launch {
            when (val result = repository.upsertAccount(AccountCommand(original?.account?.id, name, kind, desired - flowDelta, note))) {
                is MutationResult.Success -> dismiss()
                is MutationResult.Failure -> { error = result.error.message; saving = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding().imePadding()) {
        ReferenceTitle(if (original == null) LedgerIcons.accountKindLabel(kind) else "编辑资产", { if (!saving) dismiss() }) {
            TextButton(enabled = !saving, onClick = ::save, modifier = Modifier.testTag("asset_save")) { Text(if (saving) "保存中…" else "保存") }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("asset_name"))
            OutlinedTextField(note, { if (it.length <= Limits.MAX_NOTE_LENGTH) note = it }, label = { Text("备注（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(amount, { amount = it }, label = { Text(if (kind.isLiability) "欠款" else "余额") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("asset_balance"))
            error?.let { Text(it, color = T.Risk) }
        }
    }
}

@Composable
private fun ReferenceAssetCharts(repository: LedgerRepository, clock: Clock, assets: List<AccountWithBalance>, modifier: Modifier) {
    var type by rememberSaveable { mutableIntStateOf(0) }
    var year by rememberSaveable { mutableIntStateOf(clock.today().year) }
    val (state, _) = ledgerState(repository, "reference.assetHistory", TransactionFilter(limit = Int.MAX_VALUE))
    val series by produceState<List<Pair<Long, Long>>>(emptyList(), assets, state, year) {
        value = withContext(Dispatchers.Default) {
            assetMonthlyTotals(assets.map { it.account }, state.rows, year, clock.today())
        }
    }
    val values = remember(series, type) { series.map { totals -> when (type) { 0 -> totals.first; 1 -> totals.second; else -> totals.first - totals.second } } }
    val balances = assets.filter { if (type == 1) it.balanceCent < 0 else it.balanceCent > 0 }.sortedByDescending { abs(it.balanceCent) }
    val colors = listOf(T.Primary, Color(0xFF70A5F8), Color(0xFF8FCFC0), Color(0xFFA79BDF), Color(0xFFE5B36D))
    LazyColumn(modifier) {
        item {
            ReferenceTabs(listOf(0, 1, 2), type, { listOf("资产", "负债", "净资产")[it] }, { type = it })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { year-- }) { Text("‹") }; Text("${year}年")
                TextButton(enabled = year < clock.today().year, onClick = { year++ }) { Text("›") }
            }
            ReferenceLineChart(values, (1..12).map { "${it}月" }, Modifier.padding(20.dp), futureFrom = if (year == clock.today().year) clock.today().monthValue else 12)
        }
        item {
            HorizontalDivider(color = T.Border, thickness = 6.dp)
            Text("${if (type == 1) "负债" else "资产"}构成", fontSize = 18.sp, modifier = Modifier.padding(20.dp))
            val sum = balances.sumOf { abs(it.balanceCent) }
            Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(180.dp).padding(18.dp)) {
                    if (sum == 0L) drawCircle(T.Border, style = Stroke(30.dp.toPx())) else {
                        var start = -90f
                        balances.forEachIndexed { i, item ->
                            val sweep = (abs(item.balanceCent).toDouble() / sum * 360).toFloat()
                            drawArc(colors[i % colors.size], start, sweep, false, style = Stroke(30.dp.toPx())); start += sweep
                        }
                    }
                }
                if (sum == 0L) Text("暂无数据", color = T.TextSecondary)
            }
        }
        items(balances, key = { it.account.id }) { account ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(account.account.name, color = T.TextPrimary); ReferenceMoney(abs(account.balanceCent))
            }
        }
    }
}
