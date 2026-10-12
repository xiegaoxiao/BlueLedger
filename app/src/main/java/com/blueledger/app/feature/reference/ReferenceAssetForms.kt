package com.blueledger.app.feature.reference

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blueledger.app.app.ui.BlueLedgerTokens as T
import com.blueledger.app.core.contract.*
import com.blueledger.app.core.designsystem.*
import com.blueledger.app.core.model.*
import com.blueledger.app.core.money.Money
import com.blueledger.app.feature.entry.AmountInput
import kotlinx.coroutines.launch
import kotlin.math.abs

internal fun parseAssetBalance(raw: String, kind: AccountKind): Long? {
    val negative = raw.startsWith('-')
    if (negative && kind.isLiability) return null
    val cent = AmountInput(if (negative) raw.drop(1) else raw).centsOrNull() ?: return null
    if (cent > Limits.MAX_OPENING_BALANCE_CENT) return null
    return if (negative || kind.isLiability) -cent else cent
}

internal val assetBanks = listOf("工商银行", "建设银行", "农业银行", "中国银行", "招商银行", "交通银行", "中信银行", "浦发银行", "光大银行", "广发银行", "其他银行")
internal fun assetBankParts(name: String): Pair<String, String> {
    val match = Regex("^(.*) · ([0-9]{4})$").matchEntire(name)
    return if (match == null) name to "" else match.groupValues[1] to match.groupValues[2]
}
internal fun assetIcon(kind: AccountKind, location: AccountLocation) = when {
    kind == AccountKind.CASH -> Icons.Outlined.AttachMoney
    location == AccountLocation.WECHAT_BALANCE || location == AccountLocation.WECHAT_LINGQI -> Icons.Outlined.Chat
    location == AccountLocation.ALIPAY_POCKET -> Icons.Outlined.Savings
    kind == AccountKind.BANK_CARD || kind == AccountKind.CREDIT_CARD -> Icons.Outlined.CreditCard
    else -> Icons.Outlined.AccountBalanceWallet
}

@Composable
internal fun AssetTypesDialog(dismiss: () -> Unit, choose: (AccountKind, AccountLocation, String) -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    BackHandler(page != 0) { page = 0 }
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding()) {
        val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
        Column {
            LedgerCenteredTopBar(when (page) { 1 -> "添加银行卡"; 2 -> "添加虚拟账户"; else -> "添加账户" },
                { if (page == 0) dismiss() else page = 0 }, brand = true, height = (50 * scale).dp, fontSizeSp = 18 * scale)
            LazyColumn(Modifier.testTag("asset_type_list")) {
                when (page) {
                    0 -> {
                        item { LedgerAssetSelectionRow("现金", icon = Icons.Outlined.AttachMoney, scale = scale, tag = "asset_type_CASH") { choose(AccountKind.CASH, AccountLocation.OTHER, "现金") } }
                        item { LedgerAssetSelectionRow("银行卡", icon = Icons.Outlined.CreditCard, scale = scale, tag = "asset_type_BANK_CARD") { page = 1 } }
                        item { LedgerAssetSelectionRow("虚拟账户", "支付宝/微信/支付宝小荷包", Icons.Outlined.AccountBalanceWallet, scale, "asset_type_virtual") { page = 2 } }
                    }
                    1 -> itemsIndexed(assetBanks) { index, bank ->
                        LedgerAssetSelectionRow(bank, icon = Icons.Outlined.AccountBalance, scale = scale, tag = "asset_bank_$index",
                            iconContent = if (index == assetBanks.lastIndex) null else ({ LedgerBankMark(index, (32 * scale).dp) })) {
                            choose(AccountKind.BANK_CARD, AccountLocation.BANK_CARD, if (index == assetBanks.lastIndex) "" else bank)
                        }
                    }
                    2 -> {
                        listOf("支付宝" to AccountLocation.ALIPAY_BALANCE, "微信" to AccountLocation.WECHAT_BALANCE,
                            "支付宝小荷包" to AccountLocation.ALIPAY_POCKET).forEach { (title, location) ->
                            item { LedgerAssetSelectionRow(title, icon = assetIcon(AccountKind.E_WALLET, location), scale = scale,
                                tag = "asset_type_${location.name}") { choose(AccountKind.E_WALLET, location, title) } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AssetEditDialog(repository: LedgerRepository, kind: AccountKind, initialLocation: AccountLocation,
    original: AccountWithBalance?, balanceOnly: Boolean, initialName: String = "", dismiss: () -> Unit) {
    val bank = kind == AccountKind.BANK_CARD
    val originalParts = original?.account?.name?.let { if (bank) assetBankParts(it) else it to "" }
    var name by remember { mutableStateOf(originalParts?.first ?: initialName) }
    var lastFour by remember { mutableStateOf(if (bank) originalParts?.second.orEmpty() else "") }
    var note by remember { mutableStateOf(original?.account?.note.orEmpty()) }
    var amount by remember { mutableStateOf(original?.balanceCent?.let { Money.format(if (kind.isLiability) abs(it) else it).replace(",", "") }.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val desired = parseAssetBalance(amount, kind)
    val savedName = name.trim() + if (bank && lastFour.isNotEmpty()) " · $lastFour" else ""
    val valid = name.isNotBlank() && savedName.length <= Limits.MAX_ACCOUNT_NAME_LENGTH && desired != null &&
        (!bank || lastFour.isEmpty() || lastFour.matches(Regex("[0-9]{4}")))
    fun save() {
        if (saving || !valid) return
        saving = true; error = null
        scope.launch {
            try {
                val command = AccountCommand(original?.account?.id, savedName, kind,
                    original?.account?.openingBalanceCent ?: desired!!, note, initialLocation,
                    currentBalanceCent = if (original != null) desired else null)
                when (val result = repository.upsertAccount(command)) {
                    is MutationResult.Success -> dismiss()
                    is MutationResult.Failure -> error = result.error.message
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { error = "保存失败，请重试" }
            finally { saving = false }
        }
    }
    BackHandler { if (!saving) dismiss() }
    BoxWithConstraints(Modifier.fillMaxSize().background(T.Background).navigationBarsPadding().imePadding().testTag("asset_editor")) {
        val scale = (maxWidth.value / 361f).coerceAtMost(1.2f)
        Column {
            LedgerCenteredTopBar(if (balanceOnly) "调整余额" else if (original == null) "添加" else "设置",
                { if (!saving) dismiss() }, brand = true, height = (50 * scale).dp, fontSizeSp = 18 * scale, backTag = "asset_cancel")
            LazyColumn(Modifier.weight(1f)) {
                item { Spacer(Modifier.height((10 * scale).dp)) }
                if (!balanceOnly) {
                    item { LedgerInlineField(if (bank && original == null) "所在银行" else "名称", name, { name = it; error = null }, "asset_name", scale, enabled = !saving) }
                    if (bank) item { LedgerInlineField("卡号（后四位）", lastFour,
                        { if (it.length <= 4 && it.all(Char::isDigit)) { lastFour = it; error = null } },
                        "asset_card_last_four", scale, optional = true, enabled = !saving, keyboardType = KeyboardType.Number) }
                    item { LedgerInlineField("备注", note, { if (it.length <= Limits.MAX_NOTE_LENGTH) note = it }, "asset_note", scale, optional = true, enabled = !saving) }
                } else item { Text(original?.account?.name.orEmpty(), Modifier.padding((15 * scale).dp), color = T.TextPrimary, fontSize = (15 * scale).sp) }
                item { LedgerInlineField(if (kind.isLiability) "欠款" else "余额", amount, { amount = it; error = null }, "asset_balance", scale, enabled = !saving, keyboardType = KeyboardType.Decimal) }
                if (bank && lastFour.isNotEmpty() && lastFour.length != 4) item { LedgerErrorBanner("请输入完整的后四位卡号") }
                error?.let { item { LedgerErrorBanner(it, testTag = "asset_save_error") } }
                item { LedgerPrimaryButton("保存", ::save, Modifier.fillMaxWidth().padding(horizontal = (29 * scale).dp).padding(top = (29 * scale).dp, bottom = 24.dp),
                    enabled = valid && !saving, loading = saving, testTag = "asset_save", height = (48 * scale).dp,
                    textStyle = LedgerTextStyles.button.copy(fontSize = (14 * scale).sp, lineHeight = (20 * scale).sp),
                    contentPadding = PaddingValues(horizontal = (16 * scale).dp, vertical = (8 * scale).dp),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape((4 * scale).dp)) }
            }
        }
    }
}
