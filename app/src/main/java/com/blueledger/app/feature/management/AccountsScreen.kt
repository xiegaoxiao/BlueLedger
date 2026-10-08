package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.LEDGER_HIDDEN_AMOUNT_MASK
import com.blueledger.app.core.designsystem.LedgerAccountIcon
import com.blueledger.app.core.designsystem.LedgerAmountText
import com.blueledger.app.core.designsystem.LedgerConfirmDialog
import com.blueledger.app.core.designsystem.LedgerDivider
import com.blueledger.app.core.designsystem.LedgerErrorBanner
import com.blueledger.app.core.designsystem.LedgerIcons
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerSecondaryButton
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSuccessBanner
import com.blueledger.app.core.designsystem.LedgerTextButton
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTopBar
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.money.Money

/**
 * S09 账户管理（冻结入口，签名由总控 NavHost 直接调用，不得改动）。
 *
 * 余额来自仓库的 `AccountWithBalance`（全历史有效记录派生），
 * 期初余额只是账户基础数据，不生成收入账单、不影响月/年收支统计。
 */
@Composable
fun AccountsRoute(
    repository: LedgerRepository,
    clock: Clock,
    onBack: () -> Unit,
    onOpenTransactions: (TransactionFilterSeed) -> Unit,
) {
    val accountsViewModel: AccountsViewModel = viewModel(
        key = "accounts",
        factory = viewModelFactory {
            initializer { AccountsViewModel(repository = repository) }
        },
    )
    val state by accountsViewModel.state.collectAsStateWithLifecycle()
    AccountsScreen(
        state = state,
        onBack = onBack,
        onAddAccount = accountsViewModel::onAddAccount,
        onEditAccount = accountsViewModel::onEditAccount,
        onNameChanged = accountsViewModel::onNameChanged,
        onKindSelected = accountsViewModel::onKindSelected,
        onOpeningChanged = accountsViewModel::onOpeningChanged,
        onEditorSave = accountsViewModel::onEditorSave,
        onEditorDismiss = accountsViewModel::onEditorDismissed,
        onRequestArchive = accountsViewModel::onRequestArchive,
        onCancelArchive = accountsViewModel::onCancelArchive,
        onReplacementSelected = accountsViewModel::onReplacementSelected,
        onConfirmArchive = accountsViewModel::onConfirmArchive,
        onRestoreAccount = accountsViewModel::onRestoreAccount,
        onSetDefaultAccount = accountsViewModel::onSetDefaultAccount,
        onBannerShown = accountsViewModel::onBannerShown,
        onOpenTransactions = { accountId ->
            // 仍按发生日期筛选（不引入创建时间口径），只带账户条件。
            onOpenTransactions(TransactionFilterSeed(accountId = accountId))
        },
    )
}

@Composable
fun AccountsScreen(
    state: AccountsUiState,
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    onEditAccount: (String) -> Unit,
    onNameChanged: (String) -> Unit,
    onKindSelected: (AccountKind) -> Unit,
    onOpeningChanged: (String) -> Unit,
    onEditorSave: () -> Unit,
    onEditorDismiss: () -> Unit,
    onRequestArchive: (String) -> Unit,
    onCancelArchive: () -> Unit,
    onReplacementSelected: (String) -> Unit,
    onConfirmArchive: () -> Unit,
    onRestoreAccount: (String) -> Unit,
    onSetDefaultAccount: (String) -> Unit,
    onBannerShown: () -> Unit,
    onOpenTransactions: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Background)
            .navigationBarsPadding()
            .testTag(ManagementTags.ACCOUNTS_SCREEN),
    ) {
        LedgerTopBar(
            title = "我的账户",
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = onAddAccount,
                    modifier = Modifier
                        .size(BlueLedgerTokens.MinTouchTarget)
                        .testTag(ManagementTags.ACCOUNTS_ADD),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "新增账户",
                        tint = BlueLedgerTokens.Primary,
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BlueLedgerTokens.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        ) {
            state.banner?.let { banner ->
                if (banner.isError) {
                    LedgerErrorBanner(
                        message = banner.message,
                        actionText = "知道了",
                        onAction = onBannerShown,
                        testTag = ManagementTags.ACCOUNTS_BANNER,
                    )
                } else {
                    LedgerSuccessBanner(
                        message = banner.message,
                        actionText = "知道了",
                        onAction = onBannerShown,
                        testTag = ManagementTags.ACCOUNTS_BANNER,
                    )
                }
            }

            if (state.loading) {
                LedgerLoadingState(message = "正在读取账户…", testTag = ManagementTags.ACCOUNTS_LOADING)
            } else {
                TotalBalanceCard(
                    totalCent = state.totalActiveBalanceCent,
                    accountCount = state.activeAccounts.size,
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = BlueLedgerTokens.SpaceS),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "常用账户",
                        style = LedgerTextStyles.cardTitle,
                        color = BlueLedgerTokens.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${state.activeAccounts.size} 个账户",
                        style = LedgerTextStyles.caption,
                        color = BlueLedgerTokens.TextSecondary,
                    )
                }

                state.activeAccounts.forEach { entry ->
                    ActiveAccountCard(
                        entry = entry,
                        isDefault = entry.account.id == state.defaultAccountId,
                        onEdit = { onEditAccount(entry.account.id) },
                        onArchive = { onRequestArchive(entry.account.id) },
                        onSetDefault = { onSetDefaultAccount(entry.account.id) },
                        onOpenTransactions = { onOpenTransactions(entry.account.id) },
                    )
                }

                if (state.archivedAccounts.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ManagementTags.ACCOUNTS_ARCHIVED_HEADER),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已归档 · ${state.archivedAccounts.size} 个",
                            style = LedgerTextStyles.bodyStrong,
                            color = BlueLedgerTokens.TextSecondary,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "历史账单仍保留",
                            style = LedgerTextStyles.caption,
                            color = BlueLedgerTokens.TextSecondary,
                        )
                    }
                    state.archivedAccounts.forEach { entry ->
                        ArchivedAccountRow(
                            entry = entry,
                            onRestore = { onRestoreAccount(entry.account.id) },
                            onOpenTransactions = { onOpenTransactions(entry.account.id) },
                        )
                    }
                }

                Text(
                    text = "余额按全历史有效账单计算：期初余额 ＋ 全部收入 − 全部支出；" +
                        "期初余额不生成账单，也不参与月/年收支统计。",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                    modifier = Modifier.padding(bottom = BlueLedgerTokens.SpaceL),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = BlueLedgerTokens.PageHorizontal,
                    vertical = BlueLedgerTokens.SpaceM,
                ),
        ) {
            LedgerSecondaryButton(
                text = "＋ 新增账户",
                onClick = onAddAccount,
                testTag = ManagementTags.ACCOUNTS_ADD + "_bottom",
            )
        }
    }

    state.editor?.let { editor ->
        AccountEditorDialog(
            editor = editor,
            onNameChanged = onNameChanged,
            onKindSelected = onKindSelected,
            onOpeningChanged = onOpeningChanged,
            onSave = onEditorSave,
            onDismiss = onEditorDismiss,
        )
    }

    state.pendingArchive?.let { target ->
        ArchiveAccountDialog(
            state = state,
            target = target,
            onReplacementSelected = onReplacementSelected,
            onConfirm = onConfirmArchive,
            onDismiss = onCancelArchive,
        )
    }
}

// ───────────────────────── 汇总卡 ─────────────────────────

@Composable
private fun TotalBalanceCard(totalCent: Long, accountCount: Int) {
    LedgerSectionCard {
        Text(
            text = "全部账户余额",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
        LedgerAmountText(
            cents = totalCent,
            prefix = "¥",
            style = LedgerTextStyles.heroAmount,
            color = BlueLedgerTokens.PrimaryDeep,
            testTag = ManagementTags.ACCOUNTS_TOTAL_BALANCE,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceXs),
        )
        Text(
            text = "期初余额 ＋ 全部收入 − 全部支出 · 共 $accountCount 个可用账户",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )
    }
}

// ───────────────────────── 账户行 ─────────────────────────

@Composable
private fun ActiveAccountCard(
    entry: AccountWithBalance,
    isDefault: Boolean,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onSetDefault: () -> Unit,
    onOpenTransactions: () -> Unit,
) {
    val account = entry.account
    LedgerSectionCard(
        modifier = Modifier.testTag(ManagementTags.accountRow(account.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        ) {
            LedgerAccountIcon(kind = account.kind)
            Text(
                text = account.name,
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (isDefault) {
                Text(
                    text = "默认",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.Primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                        .background(BlueLedgerTokens.PrimarySoft)
                        .padding(horizontal = BlueLedgerTokens.SpaceS, vertical = BlueLedgerTokens.SpaceXs),
                )
            }
            Text(
                text = LedgerIcons.accountKindLabel(account.kind),
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }

        LedgerAmountText(
            cents = entry.balanceCent,
            prefix = "¥",
            style = LedgerTextStyles.largeAmount,
            color = if (entry.balanceCent < 0L) BlueLedgerTokens.Risk else BlueLedgerTokens.TextPrimary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = "期初 ¥" + moneyOrMask(account.openingBalanceCent),
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "关联 ${entry.transactionCount} 笔账单",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }

        LedgerDivider(modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerTextButton(
                text = "关联账单",
                onClick = onOpenTransactions,
                testTag = ManagementTags.accountTransactions(account.id),
            )
            if (!isDefault) {
                LedgerTextButton(
                    text = "设为默认",
                    onClick = onSetDefault,
                    testTag = ManagementTags.accountSetDefault(account.id),
                )
            }
            LedgerTextButton(
                text = "编辑",
                onClick = onEdit,
                testTag = ManagementTags.accountEdit(account.id),
            )
            LedgerTextButton(
                text = "归档",
                onClick = onArchive,
                testTag = ManagementTags.accountArchive(account.id),
            )
        }
    }
}

@Composable
private fun ArchivedAccountRow(
    entry: AccountWithBalance,
    onRestore: () -> Unit,
    onOpenTransactions: () -> Unit,
) {
    LedgerSectionCard(
        modifier = Modifier.testTag(ManagementTags.accountRow(entry.account.id)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        ) {
            LedgerAccountIcon(kind = entry.account.kind)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.account.name,
                    style = LedgerTextStyles.bodyStrong,
                    color = BlueLedgerTokens.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "已归档 · 新账单不再可选 · 关联 ${entry.transactionCount} 笔",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                )
            }
            LedgerAmountText(
                cents = entry.balanceCent,
                prefix = "¥",
                style = LedgerTextStyles.rowAmount,
                color = BlueLedgerTokens.TextSecondary,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerTextButton(
                text = "关联账单",
                onClick = onOpenTransactions,
                testTag = ManagementTags.accountTransactions(entry.account.id),
            )
            TextButton(
                onClick = onRestore,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag(ManagementTags.accountRestore(entry.account.id)),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Unarchive,
                    contentDescription = null,
                    tint = BlueLedgerTokens.Primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = " 恢复",
                    style = LedgerTextStyles.bodyStrong,
                    color = BlueLedgerTokens.Primary,
                )
            }
        }
    }
}

// ───────────────────────── 编辑弹层 ─────────────────────────

@Composable
private fun AccountEditorDialog(
    editor: AccountEditorState,
    onNameChanged: (String) -> Unit,
    onKindSelected: (AccountKind) -> Unit,
    onOpeningChanged: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(BlueLedgerTokens.RadiusCard),
        containerColor = BlueLedgerTokens.Surface,
        modifier = Modifier.testTag(ManagementTags.ACCOUNTS_EDITOR_DIALOG),
        title = {
            Text(
                text = if (editor.isNew) "新增账户" else "编辑账户",
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
            ) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = onNameChanged,
                    label = { Text("账户名称（1—20 字符）") },
                    singleLine = true,
                    isError = editor.errorMessage != null,
                    shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ManagementTags.ACCOUNTS_NAME_FIELD),
                )
                Text(
                    text = "账户类型",
                    style = LedgerTextStyles.fieldLabel,
                    color = BlueLedgerTokens.TextSecondary,
                )
                Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
                    AccountKind.entries.chunked(2).forEach { rowKinds ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
                        ) {
                            rowKinds.forEach { kind ->
                                AccountKindOption(
                                    kind = kind,
                                    selected = kind == editor.kind,
                                    onClick = { onKindSelected(kind) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (rowKinds.size == 1) {
                                Column(modifier = Modifier.weight(1f)) {}
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = editor.openingText,
                    onValueChange = onOpeningChanged,
                    label = { Text("初始余额（元，可填负数）") },
                    singleLine = true,
                    isError = editor.errorMessage != null,
                    shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ManagementTags.ACCOUNTS_OPENING_FIELD),
                )
                if (!editor.isNew && editor.balanceBeforeCent != null) {
                    val after = editor.balanceAfterCent ?: editor.balanceBeforeCent
                    Text(
                        text = "余额将从 ¥${moneyOrMask(editor.balanceBeforeCent)} 变为 ¥${moneyOrMask(after)}" +
                            "（只调整账户基础数据，不新增收入账单）",
                        style = LedgerTextStyles.caption,
                        color = BlueLedgerTokens.TextSecondary,
                        modifier = Modifier.testTag(ManagementTags.ACCOUNTS_BALANCE_PREVIEW),
                    )
                }
                editor.errorMessage?.let { message ->
                    LedgerErrorBanner(message = message, testTag = "account_editor_error")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !editor.saving,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag(ManagementTags.ACCOUNTS_SAVE),
            ) {
                Text(
                    text = if (editor.saving) "保存中…" else "保存",
                    style = LedgerTextStyles.button,
                    color = BlueLedgerTokens.Primary,
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !editor.saving,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag(ManagementTags.ACCOUNTS_CANCEL),
            ) {
                Text(
                    text = "取消",
                    style = LedgerTextStyles.button,
                    color = BlueLedgerTokens.TextSecondary,
                )
            }
        },
    )
}

@Composable
private fun AccountKindOption(
    kind: AccountKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput)
    Row(
        modifier = modifier
            .heightIn(min = BlueLedgerTokens.MinTouchTarget)
            .clip(shape)
            .background(if (selected) BlueLedgerTokens.PrimarySoft else BlueLedgerTokens.Surface)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = BlueLedgerTokens.SpaceS, vertical = BlueLedgerTokens.SpaceXs)
            .testTag(ManagementTags.accountKindOption(kind.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Icon(
            imageVector = LedgerIcons.accountKindIcon(kind),
            contentDescription = null,
            tint = if (selected) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = LedgerIcons.accountKindLabel(kind),
            style = LedgerTextStyles.body,
            color = if (selected) BlueLedgerTokens.Primary else BlueLedgerTokens.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ───────────────────────── 归档弹层 ─────────────────────────

@Composable
private fun ArchiveAccountDialog(
    state: AccountsUiState,
    target: AccountWithBalance,
    onReplacementSelected: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hint = when {
        state.archiveBlockedByLastActive -> "至少保留一个未归档账户，当前无法归档。"
        state.archiveNeedsReplacement -> "归档默认账户前请先选择新的默认账户。"
        else -> "归档后该账户不再出现在新账单里，历史账单与统计仍保留，随时可以恢复。"
    }
    LedgerConfirmDialog(
        title = "归档「${target.account.name}」？",
        message = hint,
        confirmText = "归档",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        testTag = ManagementTags.ACCOUNTS_ARCHIVE_DIALOG,
        testTagConfirm = "btn_account_archive_confirm",
        testTagDismiss = "btn_account_archive_cancel",
    ) {
        if (state.isPendingDefault) {
            Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
                Text(
                    text = "归档后的默认账户",
                    style = LedgerTextStyles.fieldLabel,
                    color = BlueLedgerTokens.TextSecondary,
                )
                state.activeAccounts
                    .filter { it.account.id != target.account.id }
                    .forEach { candidate ->
                        val selected = candidate.account.id == state.pendingReplacementId
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
                                    onClick = { onReplacementSelected(candidate.account.id) },
                                )
                                .padding(horizontal = BlueLedgerTokens.SpaceM, vertical = BlueLedgerTokens.SpaceS)
                                .testTag(ManagementTags.accountReplacementOption(candidate.account.id)),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = candidate.account.name,
                                style = LedgerTextStyles.body,
                                color = if (selected) BlueLedgerTokens.Primary else BlueLedgerTokens.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                Text(
                                    text = "将设为默认",
                                    style = LedgerTextStyles.caption,
                                    color = BlueLedgerTokens.Primary,
                                )
                            }
                        }
                    }
            }
        }
        if (!state.canConfirmArchive) {
            LedgerErrorBanner(message = hint, testTag = "archive_account_blocked")
        }
    }
}

/** 金额文案：金额隐藏开启时统一为 `••••`（不从页面本地状态判断）。 */
@Composable
private fun moneyOrMask(cents: Long): String =
    if (LocalHideAmounts.current) LEDGER_HIDDEN_AMOUNT_MASK else Money.format(cents)
