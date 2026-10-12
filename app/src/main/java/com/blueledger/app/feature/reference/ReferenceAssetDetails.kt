package com.blueledger.app.feature.reference

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.*
import com.blueledger.app.core.designsystem.*
import com.blueledger.app.core.model.*
import com.blueledger.app.core.money.Money
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import java.time.YearMonth

private data class AssetChange(val date: java.time.LocalDate, val before: Long, val after: Long)

@Composable
internal fun ReferenceAssetDetail(repository: LedgerRepository, clock: Clock, item: AccountWithBalance,
    accounts: List<AccountWithBalance>, onBack: () -> Unit, onEdit: (Boolean) -> Unit, onBills: () -> Unit, onAddAccount: () -> Unit) {
    var monthText by rememberSaveable(item.account.id) { mutableStateOf(clock.currentYearMonth().toString()) }
    val month = YearMonth.parse(monthText)
    var monthPicker by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var transfer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val (state, vm) = ledgerState(repository, "asset.detail.${item.account.id}", TransactionFilter(accountId = item.account.id,
        from = month.atDay(1), to = month.atEndOfMonth(), limit = Int.MAX_VALUE))
    val history = remember(item.account.openingHistory, month) {
        var previous = 0L
        AssetHistory.parse(item.account.openingHistory).map { (date, balance) ->
            AssetChange(date, previous, balance).also { previous = balance }
        }.filter { YearMonth.from(it.date) == month && it.before != it.after }
    }
    val dates = (state.rows.map { it.occurredOn } + history.map { it.date }).distinct().sortedDescending()
    val hidden = LocalHideAmounts.current
    fun amount(cent: Long) = if (hidden) "••••" else Money.format(cent).replace(",", "")
    if (transfer) {
        AssetTransferScreen(repository, item, accounts, { transfer = false }, onAddAccount)
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding().testTag("asset_detail")) {
        val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
        Box(Modifier.fillMaxWidth().height((131 * scale).dp).background(T.Primary))
        Column {
            LedgerCenteredTopBar("资产详情", onBack, brand = true, height = (50 * scale).dp, fontSizeSp = 18 * scale)
            LazyColumn(Modifier.weight(1f).testTag("asset_detail_list")) {
                item { Box(Modifier.padding(horizontal = (12 * scale).dp).padding(top = (9 * scale).dp)) {
                    LedgerAssetDetailCard(item.account.name, item.balanceCent, assetIcon(item.account.kind, item.account.location), { onEdit(true) }, scale)
                } }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = (23 * scale).dp).padding(top = (20 * scale).dp, bottom = (8 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("收支明细", Modifier.weight(1f), fontWeight = FontWeight.Bold, fontSize = (16 * scale).sp, color = T.TextPrimary)
                        LedgerDropdownButton("${month.year}年${month.monthValue}月", { monthPicker = true }, Modifier.testTag("asset_detail_month"),
                            height = (28 * scale).dp, textStyle = LedgerTextStyles.body.copy(fontSize = (12 * scale).sp, lineHeight = (18 * scale).sp))
                    }
                }
                state.error?.let { item { LedgerNavigationRow(it, "重试", onClick = vm::retry) } }
                if (!state.loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                dates.forEach { date ->
                    item("date_$date") { Text("${date.monthValue}月${date.dayOfMonth}日 ${date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.CHINA)}",
                        Modifier.fillMaxWidth().padding(horizontal = (15 * scale).dp, vertical = (4 * scale).dp), fontSize = (12 * scale).sp, lineHeight = (16 * scale).sp, color = T.TextSecondary)
                        HorizontalDivider(color = T.Border.copy(alpha = .5f)) }
                    history.filter { it.date == date }.forEach { change ->
                        item("change_$date") { LedgerTransactionRow("余额调整", "other", if (change.after >= change.before) TransactionType.INCOME else TransactionType.EXPENSE,
                            kotlin.math.abs(change.after - change.before), "余额调整", "从 ${amount(change.before)} 调整为 ${amount(change.after)}",
                            modifier = Modifier.padding(horizontal = (7 * scale).dp).testTag("asset_change_$date"), onClick = { onEdit(true) }, compactScale = scale,
                            leadingIcon = { LedgerAssetIconTile(Icons.Outlined.Settings, scale, circular = true) }) }
                    }
                    state.rows.filter { it.occurredOn == date }.forEach { row -> item(row.id) { ReferenceTransaction(row, onBills) } }
                }
                if (state.loaded && dates.isEmpty()) item { LedgerEmptyState("暂无收支记录", subtle = true, illustration = { LedgerBudgetEmptyIllustration(scale) }) }
            }
            HorizontalDivider(color = T.Border)
            Row(Modifier.fillMaxWidth().height((48 * scale).dp)) {
                LedgerActionButton("转账", { transfer = true }, Modifier.weight(1f), style = LedgerActionStyle.NEUTRAL, testTag = "asset_transfer",
                    textStyle = LedgerTextStyles.body.copy(fontSize = (14 * scale).sp), contentPadding = PaddingValues(horizontal = 2.dp),
                    leadingIcon = { Icon(Icons.Outlined.SwapHoriz, null, Modifier.size(18.dp)) })
                LedgerActionButton("设置", { onEdit(false) }, Modifier.weight(1f), style = LedgerActionStyle.NEUTRAL, testTag = "asset_edit",
                    textStyle = LedgerTextStyles.body.copy(fontSize = (14 * scale).sp), contentPadding = PaddingValues(horizontal = 2.dp),
                    leadingIcon = { Icon(Icons.Outlined.Settings, null, Modifier.size(18.dp)) })
                LedgerActionButton("删除资产", { error = null; remove = true }, Modifier.weight(1f), style = LedgerActionStyle.DESTRUCTIVE_OUTLINED, testTag = "asset_remove",
                    textStyle = LedgerTextStyles.body.copy(fontSize = (14 * scale).sp), contentPadding = PaddingValues(horizontal = 2.dp))
            }
        }
    }
    if (monthPicker) ReferenceMonthPicker(month, clock.currentYearMonth(), { monthPicker = false }) { monthText = it.toString(); monthPicker = false }
    if (remove) LedgerConfirmDialog("确认删除该资产？", "将此账户移出资产列表，保留历史收支。可在账户管理中恢复。",
        "确认删除", onConfirm = {
            if (!busy) { busy = true; scope.launch {
                try {
                    when (val result = repository.setAccountArchived(item.account.id, true, accounts.firstOrNull { it.account.id != item.account.id }?.account?.id)) {
                        is MutationResult.Success -> { remove = false; onBack() }
                        is MutationResult.Failure -> error = result.error.message
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "操作失败，请重试" }
                finally { busy = false }
            } }
        }, onDismiss = { remove = false }, destructive = true, enabled = !busy, testTag = "asset_remove_dialog",
        testTagConfirm = "asset_remove_confirm", testTagDismiss = "asset_remove_cancel") {
        Text(item.account.name, style = LedgerTextStyles.bodyStrong)
        LedgerFittedAmountText(item.balanceCent)
        error?.let { LedgerErrorBanner(it) }
    }
}

@Composable
private fun AssetTransferScreen(repository: LedgerRepository, source: AccountWithBalance, accounts: List<AccountWithBalance>, dismiss: () -> Unit, onAddAccount: () -> Unit) {
    var target by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val targets = visibleAssetAccounts(accounts).filter { it.account.id != source.account.id && !it.account.isArchived }
    fun save() {
        val cent = parseAssetBalance(text, AccountKind.CASH)
        val id = target
        if (id == null || targets.none { it.account.id == id } || cent == null || cent <= 0) error = "请选择到账账户并输入有效金额"
        else if (!busy) { busy = true; scope.launch {
            try { when (val result = repository.transferAssets(source.account.id, id, cent)) {
                is MutationResult.Success -> dismiss()
                is MutationResult.Failure -> error = result.error.message
            } } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = "转账失败，请重试" }
            finally { busy = false }
        } }
    }
    androidx.activity.compose.BackHandler { if (!busy) dismiss() }
    Column(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding().imePadding().testTag("asset_transfer_dialog")) {
        LedgerCenteredTopBar("账户转账", { if (!busy) dismiss() }, brand = true, height = 50.dp, fontSizeSp = 18f, backTag = "asset_transfer_cancel")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(10.dp))
            LedgerKeyValueRow("转出账户", source.account.name, Modifier.background(T.Surface).padding(horizontal = 15.dp))
            Text("到账账户", Modifier.padding(horizontal = 15.dp, vertical = 12.dp), color = T.TextSecondary, style = LedgerTextStyles.caption)
            if (targets.isEmpty()) {
                Text("暂无可转入的账户", Modifier.padding(horizontal = 15.dp), color = T.TextPrimary, style = LedgerTextStyles.body)
                LedgerPrimaryButton("添加到账账户", onAddAccount, Modifier.fillMaxWidth().padding(29.dp), testTag = "asset_transfer_add_account")
            } else {
            targets.forEach { account -> LedgerChoiceRow(account.account.name, target == account.account.id, tag = "asset_transfer_to_${account.account.id}") { target = account.account.id } }
            LedgerInlineField("转账金额", text, { text = it; error = null }, "asset_transfer_amount", enabled = !busy, keyboardType = KeyboardType.Decimal)
            error?.let { LedgerErrorBanner(it) }
            LedgerPrimaryButton("确认转账", ::save, Modifier.fillMaxWidth().padding(29.dp), enabled = !busy && targets.any { it.account.id == target } && (parseAssetBalance(text, AccountKind.CASH) ?: 0) > 0,
                loading = busy, testTag = "asset_transfer_confirm", height = 48.dp, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            }
        }
    }
}
