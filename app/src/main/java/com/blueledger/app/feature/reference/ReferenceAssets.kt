package com.blueledger.app.feature.reference

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.contract.*
import com.blueledger.app.core.designsystem.*
import com.blueledger.app.core.model.*

private data class AssetEditor(val kind: AccountKind, val location: AccountLocation,
    val original: AccountWithBalance? = null, val balanceOnly: Boolean = false, val initialName: String = "")

internal fun visibleAssetAccounts(accounts: List<AccountWithBalance>): List<AccountWithBalance> = accounts.filter {
    it.account.id != Defaults.DEFAULT_ACCOUNT_ID || it.account.name != Defaults.DEFAULT_ACCOUNT_NAME || it.account.openingBalanceCent != 0L
}
internal fun assetPlatformGroups(accounts: List<AccountWithBalance>) = AssetPlatform.entries.mapNotNull { platform ->
    accounts.filter { it.account.location.platform == platform }.takeIf { it.isNotEmpty() }?.let { platform to it }
}
internal fun assetTotals(balances: List<Long>): Pair<Long, Long> = balances.filter { it > 0 }.sum() to -balances.filter { it < 0 }.sum()

@Composable
fun ReferenceAssetsRoute(repository: LedgerRepository, clock: Clock, onBack: () -> Unit, onAccountBills: (String) -> Unit) {
    val accounts by remember(repository) { repository.observeAccounts(false) }.collectAsStateWithLifecycle(initialValue = null)
    val assets = remember(accounts) { visibleAssetAccounts(accounts.orEmpty()) }
    val totals = remember(assets) { assetTotals(assets.map { it.balanceCent }) }
    var charts by rememberSaveable { mutableStateOf(false) }
    var types by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<AssetEditor?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = types || editor != null || detailId != null) {
        when { editor != null -> editor = null; types -> types = false; else -> detailId = null }
    }
    if (types) { AssetTypesDialog({ types = false }) { kind, location, name ->
        editor = AssetEditor(kind, location, initialName = name); types = false
    }; return }
    editor?.let { request ->
        AssetEditDialog(repository, request.kind, request.location, request.original, request.balanceOnly, request.initialName) { editor = null }
        return
    }
    assets.firstOrNull { it.account.id == detailId }?.let { item ->
        ReferenceAssetDetail(repository, clock, item, accounts.orEmpty(), { detailId = null },
            { balanceOnly -> editor = AssetEditor(item.account.kind, item.account.location, item, balanceOnly) },
            { onAccountBills(item.account.id) }, { types = true })
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Surface).navigationBarsPadding().testTag("assets_screen")) {
        val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
        Column {
            LedgerCenteredTopBar(if (charts) "资产统计" else "资产管家", onBack, height = (50 * scale).dp, fontSizeSp = 22 * scale)
            if (charts) ReferenceAssetCharts(repository, clock, assets, Modifier.weight(1f)) else LazyColumn(Modifier.weight(1f).testTag("asset_list"),
                contentPadding = PaddingValues(horizontal = (15 * scale).dp)) {
                item { Spacer(Modifier.height((19 * scale).dp)); LedgerOverviewCard(totals.first - totals.second, totals.first, totals.second, scale) }
                if (accounts == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                val groups = AccountKind.entries.mapNotNull { kind ->
                    val group = assets.filter { it.account.kind == kind }
                    if (group.isEmpty()) null else Triple(kind.name, when (kind) {
                        AccountKind.CASH -> "现金"; AccountKind.BANK_CARD -> "银行卡"; AccountKind.E_WALLET -> "虚拟账户"
                        else -> LedgerIcons.accountKindLabel(kind)
                    }, group)
                }
                groups.forEach { (key, label, group) ->
                    item(key) {
                        Spacer(Modifier.height((20 * scale).dp))
                        Row(Modifier.fillMaxWidth().height((24 * scale).dp).padding(horizontal = (12 * scale).dp).testTag("asset_group_$key"), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), fontSize = (12 * scale).sp, fontWeight = FontWeight.Bold, color = T.TextPrimary)
                            Box(Modifier.width((145 * scale).dp)) { LedgerFittedAmountText(group.sumOf { it.balanceCent }, Modifier.testTag("asset_group_total_$key"),
                                fontSizeSp = 12 * scale, color = T.TextSecondary, groupThousands = false, alignEnd = true) }
                        }
                        group.forEach { item -> LedgerAssetListRow(item.account.name, item.balanceCent,
                            assetIcon(item.account.kind, item.account.location), scale, "asset_account_${item.account.id}", "asset_balance_${item.account.id}") { detailId = item.account.id } }
                    }
                }
                item { LedgerActionButton("添加资产", { types = true }, Modifier.fillMaxWidth().padding(top = (8 * scale).dp, bottom = 24.dp).height((46 * scale).dp),
                    style = LedgerActionStyle.NEUTRAL, testTag = "asset_add", containerColor = T.Background,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape((8 * scale).dp),
                    textStyle = LedgerTextStyles.body.copy(fontSize = (16 * scale).sp), leadingIcon = { Icon(Icons.Outlined.Add, null) }) }
                if (accounts != null && assets.isEmpty()) item { LedgerEmptyState("暂无账户", icon = Icons.Outlined.AccountBalanceWallet) }
            }
            LedgerUnderlineTabs(listOf(false, true), charts, { if (it) "图表" else "资产" }, { charts = it }, underline = false,
                tag = { if (it) "asset_tab_charts" else "asset_tab_accounts" },
                icon = { if (it) Icons.Outlined.ShowChart else Icons.Outlined.AccountBalanceWallet })
        }
    }
}
