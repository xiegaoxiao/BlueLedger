package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.lifecycle.ObserveScreenSubscriptions
import com.blueledger.app.core.designsystem.LEDGER_HIDDEN_AMOUNT_MASK
import com.blueledger.app.core.designsystem.LedgerDivider
import com.blueledger.app.core.designsystem.LedgerErrorBanner
import com.blueledger.app.core.designsystem.LedgerFormRow
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSuccessBanner
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.money.Money

/**
 * S10 我的与设置（冻结入口，签名由总控 NavHost 直接调用，不得改动）。
 *
 * 这里只有本地账本的入口与设置：**不放登录、云头像、会员、同步**。
 * 金额隐藏与默认账户写入仓库设置，因此是全局生效，不是页面本地开关。
 */
@Composable
fun MineRoute(
    repository: LedgerRepository,
    clock: Clock,
    onOpenCategories: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenData: () -> Unit,
) {
    val mineViewModel: MineViewModel = viewModel(
        key = "mine",
        factory = viewModelFactory {
            initializer { MineViewModel(repository = repository, clock = clock) }
        },
    )
    ObserveScreenSubscriptions(mineViewModel)
    val state by mineViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // 版本号必须来自真实构建产物（BuildConfig / PackageManager），不得写死。
    val appVersion = AppVersionResolver.resolve(context)

    MineScreen(
        state = state,
        appVersion = appVersion,
        onOpenCategories = onOpenCategories,
        onOpenBudget = onOpenBudget,
        onOpenAccounts = onOpenAccounts,
        onOpenData = onOpenData,
        onHideAmountsToggled = mineViewModel::onHideAmountsToggled,
        onRequestAccountPicker = mineViewModel::onRequestAccountPicker,
        onDismissAccountPicker = mineViewModel::onDismissAccountPicker,
        onDefaultAccountSelected = mineViewModel::onDefaultAccountSelected,
        onBannerShown = mineViewModel::onBannerShown,
    )
}

@Composable
fun MineScreen(
    state: MineUiState,
    appVersion: String,
    onOpenCategories: () -> Unit,
    onOpenBudget: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenData: () -> Unit,
    onHideAmountsToggled: (Boolean) -> Unit,
    onRequestAccountPicker: () -> Unit,
    onDismissAccountPicker: () -> Unit,
    onDefaultAccountSelected: (String) -> Unit,
    onBannerShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = BlueLedgerTokens.PageHorizontal)
            .testTag(ManagementTags.MINE_SCREEN),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SectionGap),
    ) {
        Column(
            modifier = Modifier.padding(top = BlueLedgerTokens.PageVertical),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(text = "我的", style = LedgerTextStyles.pageTitle, color = BlueLedgerTokens.TextPrimary)
            Text(text = "你的账本，你来掌握", style = LedgerTextStyles.caption, color = BlueLedgerTokens.TextSecondary)
        }

        state.banner?.let { banner ->
            if (banner.isError) {
                LedgerErrorBanner(
                    message = banner.message,
                    actionText = "知道了",
                    onAction = onBannerShown,
                    testTag = "mine_banner",
                )
            } else {
                LedgerSuccessBanner(
                    message = banner.message,
                    actionText = "知道了",
                    onAction = onBannerShown,
                    testTag = "mine_banner",
                )
            }
        }

        Column(Modifier.fillMaxWidth().background(BlueLedgerTokens.PrimarySoft,
            RoundedCornerShape(BlueLedgerTokens.RadiusCard)).padding(BlueLedgerTokens.SpaceL),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL)) {
            IdentityCard()
            StatsCard(state = state)
        }

        Text(
            text = "账本管理",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )
        LedgerSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = BlueLedgerTokens.CardPadding,
            vertical = BlueLedgerTokens.SpaceXs,
        )) {
            LedgerFormRow(
                label = "分类管理",
                icon = Icons.Outlined.Category,
                value = "${state.categoryCount} 个可用",
                onClick = onOpenCategories,
                showDivider = true,
                testTag = ManagementTags.MINE_ENTRY_CATEGORIES,
            )
            LedgerFormRow(
                label = "月度预算",
                icon = Icons.Outlined.PieChart,
                value = state.currentMonthBudgetCent?.let { "¥" + moneyOrMask(it) } ?: "未设置",
                onClick = onOpenBudget,
                showDivider = true,
                testTag = ManagementTags.MINE_ENTRY_BUDGET,
            )
            LedgerFormRow(
                label = "我的账户",
                icon = Icons.Outlined.AccountBalanceWallet,
                value = "${state.activeAccounts.size} 个",
                onClick = onOpenAccounts,
                showDivider = true,
                testTag = ManagementTags.MINE_ENTRY_ACCOUNTS,
            )
            LedgerFormRow(
                label = "数据管理",
                icon = Icons.Outlined.Download,
                value = "备份 · 恢复 · CSV",
                onClick = onOpenData,
                testTag = ManagementTags.MINE_ENTRY_DATA,
            )
        }

        Text(
            text = "偏好与隐私",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )
        LedgerSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = BlueLedgerTokens.CardPadding,
            vertical = BlueLedgerTokens.SpaceXs,
        )) {
            LedgerFormRow(
                label = "金额隐藏",
                icon = Icons.Outlined.VisibilityOff,
                value = if (state.hideAmounts) "已隐藏（显示 ••••）" else "未隐藏",
                onClick = { onHideAmountsToggled(!state.hideAmounts) },
                showDivider = true,
                testTag = ManagementTags.MINE_HIDE_AMOUNTS_SWITCH,
                trailing = {
                    Switch(
                        checked = state.hideAmounts,
                        onCheckedChange = { onHideAmountsToggled(it) },
                        enabled = !state.savingSettings,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = BlueLedgerTokens.Surface,
                            checkedTrackColor = BlueLedgerTokens.Primary,
                            uncheckedThumbColor = BlueLedgerTokens.Surface,
                            uncheckedTrackColor = BlueLedgerTokens.Border,
                            uncheckedBorderColor = BlueLedgerTokens.Border,
                        ),
                        modifier = Modifier.testTag(ManagementTags.MINE_HIDE_AMOUNTS_SWITCH + "_toggle"),
                    )
                },
            )
            LedgerFormRow(
                label = "默认账户",
                icon = Icons.Outlined.AccountBalanceWallet,
                value = state.defaultAccountName ?: "未设置",
                onClick = onRequestAccountPicker,
                showDivider = true,
                testTag = ManagementTags.MINE_DEFAULT_ACCOUNT_ROW,
            )
            LedgerFormRow(
                label = "主题",
                icon = Icons.Outlined.Palette,
                value = "蓝色浅色主题（本版不跟随系统深色）",
                showDivider = true,
                testTag = ManagementTags.MINE_THEME_ROW,
            )
            LedgerFormRow(
                label = "版本",
                icon = Icons.Outlined.Info,
                value = appVersion,
                testTag = ManagementTags.MINE_VERSION_ROW,
            )
        }

        Text(
            text = "本机数据与备份",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )
        LedgerSectionCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            LedgerFormRow(
                label = "数据保存位置",
                icon = Icons.Outlined.CloudDone,
                value = "仅本机保存 · 无需登录 · 不上传",
                showDivider = true,
            )
            LedgerFormRow(
                label = "最近成功备份",
                icon = Icons.Outlined.Download,
                value = state.backupRecord.succeededAt?.let { at ->
                    at.displayDateTime(java.time.ZoneId.systemDefault()) +
                        (state.backupRecord.fileName?.let { " · $it" } ?: "")
                } ?: "尚未备份",
                testTag = ManagementTags.MINE_BACKUP_STATUS,
            )
        }

        Text(
            text = "账本内容只保存在这台设备上；需要长期保存时请到「数据管理」导出备份文件。",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(bottom = BlueLedgerTokens.SpaceHuge),
        )
    }

    if (state.showAccountPicker) {
        AlertDialog(
            onDismissRequest = onDismissAccountPicker,
            shape = RoundedCornerShape(BlueLedgerTokens.RadiusCard),
            containerColor = BlueLedgerTokens.Surface,
            modifier = Modifier.testTag(ManagementTags.MINE_ACCOUNT_PICKER_DIALOG),
            title = {
                Text(
                    text = "选择默认账户",
                    style = LedgerTextStyles.cardTitle,
                    color = BlueLedgerTokens.TextPrimary,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
                    state.activeAccounts.forEach { entry ->
                        val selected = entry.account.id == state.defaultAccountId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                                .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
                                .background(
                                    if (selected) BlueLedgerTokens.PrimarySoft else BlueLedgerTokens.Surface,
                                )
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = { onDefaultAccountSelected(entry.account.id) },
                                )
                                .padding(horizontal = BlueLedgerTokens.SpaceM, vertical = BlueLedgerTokens.SpaceS)
                                .testTag("mine_account_option_${entry.account.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = entry.account.name,
                                style = LedgerTextStyles.body,
                                color = if (selected) BlueLedgerTokens.Primary else BlueLedgerTokens.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "当前默认",
                                    style = LedgerTextStyles.caption,
                                    color = BlueLedgerTokens.Primary,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onDismissAccountPicker,
                    modifier = Modifier
                        .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                        .testTag("mine_account_picker_dismiss"),
                ) {
                    Text(
                        text = "关闭",
                        style = LedgerTextStyles.button,
                        color = BlueLedgerTokens.TextSecondary,
                    )
                }
            },
        )
    }
}

// ───────────────────────── 卡片 ─────────────────────────

@Composable
private fun IdentityCard() {
    Column(modifier = Modifier.testTag(ManagementTags.MINE_IDENTITY_CARD)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusCard))
                    .background(BlueLedgerTokens.PrimarySoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "蓝",
                    style = LedgerTextStyles.heroAmount,
                    color = BlueLedgerTokens.Primary,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
            ) {
                Text(
                    text = "我的账本",
                    style = LedgerTextStyles.cardTitle,
                    color = BlueLedgerTokens.TextPrimary,
                )
                Text(
                    text = "记录当下，让生活更有底气。",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                )
                Text(
                    text = "本地账本 · 无需登录",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.Primary,
                )
            }
        }
    }
}

@Composable
private fun StatsCard(state: MineUiState) {
    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            StatCell(label = "累计账单", value = state.transactionCount.toString(), modifier = Modifier.weight(1f))
            StatCell(label = "可用账户", value = state.activeAccounts.size.toString(), modifier = Modifier.weight(1f))
            StatCell(label = "可用分类", value = state.categoryCount.toString(), modifier = Modifier.weight(1f))
        }

    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Text(
            text = value,
            style = LedgerTextStyles.largeAmount,
            color = BlueLedgerTokens.PrimaryDeep,
        )
        Text(
            text = label,
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
    }
}

/** 金额文案：金额隐藏开启时统一为 `••••`。 */
@Composable
private fun moneyOrMask(cents: Long): String =
    if (com.blueledger.app.app.ui.LocalHideAmounts.current) LEDGER_HIDDEN_AMOUNT_MASK else Money.format(cents)
