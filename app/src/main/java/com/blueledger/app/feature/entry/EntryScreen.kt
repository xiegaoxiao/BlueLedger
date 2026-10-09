package com.blueledger.app.feature.entry

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import com.blueledger.app.feature.reference.ReferencePreferences
import com.blueledger.app.feature.reference.ReferenceTabs
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.LedgerCategoryCell
import com.blueledger.app.core.designsystem.LedgerComponentSizes
import com.blueledger.app.core.designsystem.LedgerConfirmDialog
import com.blueledger.app.core.designsystem.LedgerEmptyState
import com.blueledger.app.core.designsystem.LedgerErrorBanner
import com.blueledger.app.core.designsystem.LedgerFormRow
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerPrimaryButton
import com.blueledger.app.core.designsystem.LedgerSecondaryButton
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSegmentedToggle
import com.blueledger.app.core.designsystem.LedgerSuccessBanner
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTints
import com.blueledger.app.core.designsystem.LedgerTopBar
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.TransactionType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** S02 表单区 testTag（可滚动）。 */
const val TAG_ENTRY_FORM: String = "entry_form"

/** S02 底部固定操作区 testTag（键盘 + 保存，不随表单滚动）。 */
const val TAG_ENTRY_BOTTOM: String = "entry_bottom"

/** 页面顶栏容器 testTag（用于验证不重复叠加状态栏内边距）。 */
const val TAG_ENTRY_TOP_BAR: String = "entry_top_bar"

/** 金额显示 testTag（用户明确的命名，保持不变）。 */
const val TAG_AMOUNT_DISPLAY: String = "amount_display"

/** 金额显示容器的稳定别名（供 A7 验收框架定位，与 amount_display 指向同一数值）。 */
const val TAG_AMOUNT_VALUE: String = "entry_amount_value"

/** 保存按钮 testTag。 */
const val TAG_SAVE: String = "btn_save"

/** 保存并再记按钮 testTag。 */
const val TAG_SAVE_AND_NEW: String = "btn_save_and_new"

/**
 * S02 快速记账 / 编辑账单的冻结入口。
 *
 * 签名由总控 NavHost 直接调用，**不得改动**。
 *
 * @param editTransactionId null 表示新增；非 null 表示编辑该账单（保留原 id 与 createdAt）。
 * @param initialType 新增模式预选类型（首页/快捷入口）。
 * @param initialCategoryId 新增模式预选分类（首页快捷分类）。
 * @param onExit 保存成功或用户放弃修改后返回来源页。
 */
@Composable
fun EntryRoute(
    repository: LedgerRepository,
    clock: Clock,
    editTransactionId: String?,
    initialType: TransactionType?,
    initialCategoryId: String?,
    onExit: () -> Unit,
    categoryFirst: Boolean = false,
    onOpenCategories: ((TransactionType) -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) { ReferencePreferences(context) }
    val resolvedType = initialType ?: preferences.defaultType
    val entryViewModel: EntryViewModel = viewModel(
        key = "entry:" + (editTransactionId ?: "new"),
        factory = viewModelFactory {
            initializer {
                EntryViewModel(
                    repository = repository,
                    clock = clock,
                    editTransactionId = editTransactionId,
                    initialType = resolvedType,
                    initialCategoryId = initialCategoryId,
                    preferredAccounts = TransactionType.entries.associateWith { type ->
                        if (preferences.accountAssociation) preferences.defaultAccount(type) ?: com.blueledger.app.core.model.Defaults.DEFAULT_ACCOUNT_ID
                        else com.blueledger.app.core.model.Defaults.DEFAULT_ACCOUNT_ID
                    },
                )
            }
        },
    )
    val state by entryViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(entryViewModel) {
        entryViewModel.events.collect { event ->
            if (event is EntryEvent.Exit) onExit()
        }
    }

    EntryScreen(state = state, viewModel = entryViewModel, categoryFirst = categoryFirst, onOpenCategories = onOpenCategories)
}

/**
 * S02 页面本体。
 *
 * 布局（docs/AI开发提示词.md §9「键盘硬性布局要求」）：
 * - 上部：类型 / 金额 / 分类 / 日期 / 账户 / 备注 —— **独立垂直滚动**。
 * - 底部：完整四行数字键盘 + 保存区 —— **固定**，随 IME / 手势导航 / 三键导航安全区上移。
 * - 使用真实可用窗口高度，不写死任何设计稿尺寸。
 */
@Composable
fun EntryScreen(
    state: EntryUiState,
    viewModel: EntryViewModel,
    modifier: Modifier = Modifier,
    categoryFirst: Boolean = false,
    onOpenCategories: ((TransactionType) -> Unit)? = null,
) {
    var amountPanelOpen by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.selectedCategoryId) {
        if (state.selectedCategoryId != null) amountPanelOpen = true
    }
    val backDispatcherOwner = LocalOnBackPressedDispatcherOwner.current
    if (backDispatcherOwner != null) {
        BackHandler(enabled = true) { viewModel.onBackPressed() }
    }

    val pagePadding = if (categoryFirst) {
        LocalConfiguration.current.screenWidthDp.dp * BlueLedgerTokens.ReferenceCategoryInsetFraction
    } else if (LocalConfiguration.current.screenWidthDp < 360) {
        BlueLedgerTokens.PageHorizontalCompact
    } else {
        BlueLedgerTokens.PageHorizontal
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Surface),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 顶部安全区由总控 Scaffold 的 contentWindowInsets(Top + Horizontal) → innerPadding 统一提供；
            // 本页**不得**再叠加 statusBarsPadding()，否则顶部会多出一段状态栏高度（总控裁决 2026-10-07）。
            if (categoryFirst) Box(Modifier.fillMaxWidth().background(BlueLedgerTokens.Primary).heightIn(min = BlueLedgerTokens.ReferenceEntryTitleHeight).testTag(TAG_ENTRY_TOP_BAR)) {
                Box(Modifier.width(166.dp).align(Alignment.Center)) {
                    ReferenceTabs(listOf(TransactionType.EXPENSE, TransactionType.INCOME), state.type,
                        { if (it.isExpense) "支出" else "收入" },
                        { amountPanelOpen = false; viewModel.onTypeSelected(it) },
                        { if (it.isExpense) "type_expense" else "type_income" }, onBrand = true)
                }
                TextButton(onClick = viewModel::onBackPressed, modifier = Modifier.align(Alignment.CenterEnd).testTag("btn_back")) { Text("取消", color = BlueLedgerTokens.OnBrand) }
            } else LedgerTopBar(
                title = if (state.isEditMode) "编辑" else "",
                onBack = viewModel::onBackPressed,
                modifier = Modifier.background(BlueLedgerTokens.Primary).testTag(TAG_ENTRY_TOP_BAR),
                contentColor = BlueLedgerTokens.OnBrand,
                actions = {
                LedgerSegmentedToggle(
                options = listOf(TransactionType.EXPENSE, TransactionType.INCOME), selected = state.type,
                label = { if (it.isExpense) "支出" else "收入" },
                onSelect = { amountPanelOpen = false; viewModel.onTypeSelected(it) },
                testTags = { if (it.isExpense) "type_expense" else "type_income" },
                modifier = Modifier.width(200.dp),
                segmentedSemantics = "收支类型",
                )
                },
            )
            when {
                state.loading -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LedgerLoadingState(message = "正在读取账单…")
                    }
                }

                state.missingTransaction -> {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        LedgerEmptyState(
                            title = "这笔账单不存在",
                            description = "它可能已被删除。你可以返回列表查看其他记录。",
                            actionText = "返回",
                            onAction = viewModel::onBackPressed,
                            icon = Icons.Outlined.Inbox,
                            testTag = "entry_missing_transaction",
                        )
                    }
                }

                else -> {
                    EntryFormSection(
                        state = state,
                        viewModel = viewModel,
                        pagePadding = pagePadding,
                        showDetails = !categoryFirst,
                        onSettings = onOpenCategories?.let { open -> { open(state.type) } },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                    if (!categoryFirst || amountPanelOpen) EntryBottomArea(
                        state = state,
                        viewModel = viewModel,
                        pagePadding = pagePadding,
                        amountPanelOpen = amountPanelOpen,
                        onOpenAmount = { focusManager.clearFocus(); amountPanelOpen = true },
                        flat = categoryFirst,
                    )
                }
            }
        }

        if (state.showDiscardDialog) {
            LedgerConfirmDialog(
                title = "放弃本次修改？",
                message = "当前填写的内容还没有保存，返回后将会丢失。",
                confirmText = "放弃修改",
                dismissText = "继续编辑",
                destructive = true,
                onConfirm = viewModel::onDiscardConfirmed,
                onDismiss = viewModel::onDiscardDismissed,
                testTag = "dialog_discard",
                testTagConfirm = "btn_discard",
                testTagDismiss = "btn_keep_editing",
            )
        }
    }
}

// ───────────────────────── 上部：独立滚动的表单 ─────────────────────────

@Composable
private fun EntryFormSection(
    state: EntryUiState,
    viewModel: EntryViewModel,
    pagePadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
    showDetails: Boolean = true,
    onSettings: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .testTag(TAG_ENTRY_FORM)
            .padding(horizontal = pagePadding),
    ) {
        Spacer(Modifier.height(if (showDetails) BlueLedgerTokens.SpaceM else 0.dp))

        CategorySection(state = state, viewModel = viewModel, showHeading = showDetails, onSettings = onSettings)

        Spacer(Modifier.height(BlueLedgerTokens.SpaceL))

        if (showDetails) Text(
            text = "账单信息",
            style = LedgerTextStyles.bodyStrong,
            color = BlueLedgerTokens.TextPrimary,
        )
        if (showDetails) {
            Spacer(Modifier.height(BlueLedgerTokens.SpaceM))
            DetailsCard(state = state, viewModel = viewModel)
        }

        state.dateError?.let { message ->
            Spacer(Modifier.height(BlueLedgerTokens.SpaceS))
            LedgerErrorBanner(message = message, testTag = "error_date")
        }

        // 正文底部可见余量：最后一行内容不贴住键盘。
        Spacer(Modifier.height(BlueLedgerTokens.SpaceXxl))
    }
}

@Composable
private fun AmountCard(state: EntryUiState, onClick: () -> Unit, flat: Boolean = false) {
    val isIncome = state.type.isIncome
    val accent = if (isIncome) BlueLedgerTokens.Income else BlueLedgerTokens.Primary
    val amountScroll = rememberScrollState()

    LaunchedEffect(state.amount.text) {
        amountScroll.scrollTo(amountScroll.maxValue)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BlueLedgerTokens.SpaceXs, vertical = BlueLedgerTokens.SpaceS)
            .testTag("amount_card"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(amountScroll),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "¥",
                style = LedgerTextStyles.amountPrefix,
                fontSize = if (flat) BlueLedgerTokens.AmountMedium else LedgerTextStyles.amountPrefix.fontSize,
                color = accent,
                modifier = Modifier.padding(end = BlueLedgerTokens.SpaceXs),
            )
            // 金额显示节点：内层 Text 是用户明确要求的 amount_display；
            // 外层容器提供稳定别名 entry_amount_value（供 A7 验收框架定位），
            // 两节点都不做语义合并，因此两个 tag 都能在合并树里找到。
            Box(
                modifier = Modifier
                    .testTag(TAG_AMOUNT_VALUE)
                    .semantics {
                        contentDescription = if (state.amount.isEmpty) {
                            "金额未输入"
                        } else {
                            (if (isIncome) "收入金额 " else "支出金额 ") + state.amountDisplayText + " 元"
                        }
                    },
            ) {
                Text(
                    text = state.amountDisplayText,
                    style = LedgerTextStyles.entryAmount,
                    fontSize = if (flat) BlueLedgerTokens.AmountLarge else LedgerTextStyles.entryAmount.fontSize,
                    color = if (state.amount.isEmpty) LedgerTints.amountPlaceholder() else accent,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.testTag(TAG_AMOUNT_DISPLAY).clickable(onClick = onClick),
                )
            }
        }
    }
}

@Composable
private fun CategorySection(state: EntryUiState, viewModel: EntryViewModel, showHeading: Boolean = true, onSettings: (() -> Unit)? = null) {
    val displayed = state.categories + if (onSettings != null) listOf(com.blueledger.app.core.model.LedgerCategory("__entry_settings", state.type, "设置", "category_other", Int.MAX_VALUE)) else emptyList()
    val columns = if (LocalConfiguration.current.screenWidthDp < 320 || LocalDensity.current.fontScale > 1.5f) {
        3
    } else {
        CATEGORY_COLUMNS
    }
    // 校验失败时把分类错误滚到可视区，避免在长表单里看不见原因。
    val errorRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.categoryError) {
        if (state.categoryError != null) errorRequester.bringIntoView()
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (showHeading) Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "选择分类",
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = state.selectedCategory?.name ?: "尚未选择",
                style = LedgerTextStyles.caption,
                color = if (state.selectedCategoryId == null) {
                    BlueLedgerTokens.TextSecondary
                } else {
                    BlueLedgerTokens.Primary
                },
            )
        }

        if (state.categories.isEmpty()) {
            Text(
                text = "该类型暂无可用分类，请到分类管理中新建。",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.padding(vertical = BlueLedgerTokens.SpaceM),
            )
        } else {
            // 小屏与大字号改成三列，分类名称和触控区都保留足够空间。
            // 分类数量有限，直接按行排列，避免嵌套滚动。
            displayed.chunked(columns).forEach { rowItems ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = if (showHeading) BlueLedgerTokens.SpaceXs else BlueLedgerTokens.SpaceM),
                    horizontalArrangement = Arrangement.spacedBy(if (showHeading) BlueLedgerTokens.SpaceS else 0.dp),
                ) {
                    rowItems.forEach { category ->
                        if (category.id == "__entry_settings") Column(Modifier.weight(1f).clickable { onSettings?.invoke() }
                            .padding(vertical = if (showHeading) BlueLedgerTokens.SpaceS else 0.dp).testTag("entry_category_settings"), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(50.dp).clip(androidx.compose.foundation.shape.CircleShape).background(BlueLedgerTokens.Background), contentAlignment = Alignment.Center) {
                                Icon(Icons.Outlined.Settings, null, tint = BlueLedgerTokens.TextPrimary)
                            }
                            Text("设置", style = LedgerTextStyles.categoryLabel, color = BlueLedgerTokens.TextPrimary)
                        } else LedgerCategoryCell(
                            name = category.name,
                            iconKey = category.iconKey,
                            selected = category.id == state.selectedCategoryId,
                            archived = category.isArchived,
                            onClick = { viewModel.onCategorySelected(category.id) },
                            modifier = Modifier.weight(1f),
                            testTag = "category_" + category.id,
                            verticalPadding = if (showHeading) BlueLedgerTokens.SpaceS else 0.dp,
                        )
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        state.categoryError?.let { message ->
            Text(
                text = message,
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.Risk,
                modifier = Modifier
                    .padding(top = BlueLedgerTokens.SpaceXs)
                    .bringIntoViewRequester(errorRequester)
                    .testTag("error_category"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailsCard(state: EntryUiState, viewModel: EntryViewModel) {
    var datePickerOpen by remember { mutableStateOf(false) }
    var accountMenuOpen by remember { mutableStateOf(false) }
    val accountErrorRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.accountError) {
        if (state.accountError != null) accountErrorRequester.bringIntoView()
    }

    LedgerSectionCard {
        LedgerFormRow(
            label = "日期",
            icon = Icons.Outlined.CalendarMonth,
            value = formatDate(state.occurredOn),
            onClick = { datePickerOpen = true },
            showDivider = true,
            testTag = "field_date",
        )

        Box(modifier = Modifier.fillMaxWidth()) {
            LedgerFormRow(
                label = "账户",
                icon = Icons.Outlined.AccountBalanceWallet,
                value = state.selectedAccount?.account?.name,
                placeholder = "请选择账户",
                onClick = { accountMenuOpen = true },
                showDivider = true,
                testTag = "field_account",
            )
            DropdownMenu(
                expanded = accountMenuOpen,
                onDismissRequest = { accountMenuOpen = false },
                modifier = Modifier.testTag("account_menu"),
            ) {
                state.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = account.account.name +
                                    if (account.account.isArchived) "（已归档）" else "",
                                style = LedgerTextStyles.body,
                            )
                        },
                        onClick = {
                            viewModel.onAccountSelected(account.account.id)
                            accountMenuOpen = false
                        },
                        modifier = Modifier.testTag("account_option_" + account.account.id),
                    )
                }
            }
        }


        if (state.accountError != null) {
            Text(
                text = state.accountError,
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.Risk,
                modifier = Modifier
                    .padding(top = BlueLedgerTokens.SpaceXs)
                    .bringIntoViewRequester(accountErrorRequester)
                    .testTag("error_account"),
            )
        }
    }

    if (datePickerOpen) {
        EntryDatePickerDialog(
            initialDate = state.occurredOn,
            today = state.today,
            onDismiss = { datePickerOpen = false },
            onConfirm = { date ->
                viewModel.onDateSelected(date)
                datePickerOpen = false
            },
        )
    }
}

@Composable
private fun NoteRow(state: EntryUiState, viewModel: EntryViewModel) {
    val noteRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(state.numericKeyboardVisible, state.showNoteCounter) {
        if (!state.numericKeyboardVisible || state.showNoteCounter) noteRequester.bringIntoView()
    }
    Column(modifier = Modifier.fillMaxWidth().bringIntoViewRequester(noteRequester)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(vertical = BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = null,
                tint = BlueLedgerTokens.TextSecondary,
                modifier = Modifier
                    .size(22.dp)
                    .padding(end = BlueLedgerTokens.SpaceXs),
            )
            Text(
                text = "备注",
                style = LedgerTextStyles.fieldLabel,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.padding(end = BlueLedgerTokens.SpaceM),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (state.note.isEmpty()) {
                    Text(
                        text = "这笔钱花在了哪里？",
                        style = LedgerTextStyles.body,
                        color = BlueLedgerTokens.TextSecondary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = state.note,
                    onValueChange = viewModel::onNoteChanged,
                    singleLine = true,
                    textStyle = LedgerTextStyles.body.copy(color = BlueLedgerTokens.TextPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(BlueLedgerTokens.Primary),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("field_note")
                        .onFocusChanged { focusState ->
                            viewModel.onNoteFocusChanged(focusState.isFocused)
                        },
                )
            }
        }
        if (state.showNoteCounter) {
            Text(
                text = "还可输入 ${state.noteRemaining} 字（最多 ${Limits.MAX_NOTE_LENGTH} 字）",
                style = LedgerTextStyles.caption,
                color = if (state.noteRemaining <= 0) BlueLedgerTokens.Risk else BlueLedgerTokens.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = BlueLedgerTokens.SpaceXs)
                    .testTag("note_counter"),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryDatePickerDialog(
    initialDate: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit,
) {
    val todayUtcMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val pickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = utcTimeMillis <= todayUtcMillis

            override fun isSelectableYear(year: Int): Boolean = year <= today.year
        },
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val millis = pickerState.selectedDateMillis
                    if (millis != null) {
                        onConfirm(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate(),
                        )
                    } else {
                        onDismiss()
                    }
                },
                modifier = Modifier.testTag("date_confirm"),
            ) {
                Text("确定", style = LedgerTextStyles.button)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("date_dismiss")) {
                Text("取消", style = LedgerTextStyles.button)
            }
        },
    ) {
        DatePicker(state = pickerState, modifier = Modifier.testTag("date_picker"))
    }
}

// ───────────────────────── 底部：固定键盘与保存区 ─────────────────────────

/**
 * 底部固定区。**不参与表单滚动**：
 * - 金额模式显示自定义四行数字键盘；
 * - 备注聚焦（系统 IME 弹出）时隐藏自定义键盘，只保留保存区，且整体在 IME 之上；
 * - 手势导航/三键导航安全区通过 `safeDrawing` 底部内边距处理。
 */
@Composable
private fun EntryBottomArea(
    state: EntryUiState,
    viewModel: EntryViewModel,
    pagePadding: androidx.compose.ui.unit.Dp,
    amountPanelOpen: Boolean,
    onOpenAmount: () -> Unit,
    flat: Boolean = false,
) {
    var datePickerOpen by remember { mutableStateOf(false) }
    if (datePickerOpen) {
        com.blueledger.app.feature.reference.ReferenceDatePicker(state.occurredOn, state.today,
            onDismiss = { datePickerOpen = false },
            onConfirm = { viewModel.onDateSelected(it); datePickerOpen = false })
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(TAG_ENTRY_BOTTOM)
            .background(BlueLedgerTokens.Surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)),
    ) {
        // 可用高度很小时只收紧间距（**不缩小任何字号、不隐藏保存按钮**）：
        // 给四行键盘 + 保存区留出确定空间，表单（本来就可滚）先被压缩。
        val compact = maxHeight < 600.dp
        val keyGap = if (flat) 1.dp else if (compact) 4.dp else LedgerComponentSizes.keypadGap
        val keyPadding = if (flat) 0.dp else if (compact) 4.dp else BlueLedgerTokens.SpaceS
        val sectionGap = if (compact) 4.dp else BlueLedgerTokens.SpaceS

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = if (flat) 0.dp else pagePadding),
        ) {
            state.savedMessage?.let { message ->
                LedgerSuccessBanner(
                    message = message,
                    actionText = "知道了",
                    onAction = viewModel::onSavedMessageShown,
                )
                Spacer(Modifier.height(sectionGap))
            }

            // 保存失败横幅固定在底部区而不是滚动表单里：小屏/大字号下也一定能看到重试入口。
            state.saveError?.let { message ->
                LedgerErrorBanner(
                    message = message,
                    actionText = "重试",
                    onAction = viewModel::onSave,
                    testTag = "error_banner",
                )
                Spacer(Modifier.height(sectionGap))
            }

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!state.isEditMode) {
                    if (flat) TextButton(onClick = viewModel::onSaveAndNew, modifier = Modifier.width(100.dp).testTag(TAG_SAVE_AND_NEW), enabled = state.actionsEnabled) { Text("再记一笔", color = BlueLedgerTokens.TextSecondary) }
                    else LedgerSecondaryButton("保存并再记", viewModel::onSaveAndNew,
                        modifier = Modifier.width(112.dp), enabled = state.actionsEnabled,
                        loading = state.saving, testTag = TAG_SAVE_AND_NEW)
                }
                Box(Modifier.weight(1f)) { AmountCard(state, onClick = onOpenAmount, flat = flat) }
            }
            state.amountError?.let { LedgerErrorBanner(message = it, testTag = "error_amount") }
            Box(Modifier.padding(horizontal = if (flat) 16.dp else 0.dp)) { NoteRow(state, viewModel) }
            if (flat) {
                val context = androidx.compose.ui.platform.LocalContext.current
                val preferences = remember(context) { ReferencePreferences(context) }
                if (preferences.accountAssociation) {
                    var accountsOpen by remember { mutableStateOf(false) }
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        TextButton(onClick = { accountsOpen = true }, modifier = Modifier.testTag("field_account")) { Text(state.selectedAccount?.account?.name ?: "账户") }
                        DropdownMenu(accountsOpen, { accountsOpen = false }) {
                            state.accounts.forEach { account -> DropdownMenuItem(text = { Text(account.account.name) }, onClick = { viewModel.onAccountSelected(account.account.id); accountsOpen = false }) }
                        }
                    }
                }
            }

            if (state.numericKeyboardVisible && amountPanelOpen) {
                EntryAmountKeyboard(
                    onDigit = viewModel::onDigit,
                    onDecimalPoint = viewModel::onDecimalPoint,
                    onDelete = viewModel::onDelete,
                    keyGap = keyGap,
                    keyPadding = keyPadding,
                    onDate = { datePickerOpen = true },
                    dateLabel = if (state.occurredOn == state.today) "今天" else "${state.occurredOn.monthValue}/${state.occurredOn.dayOfMonth}",
                    onAdd = { viewModel.onOperator('+') },
                    onSubtract = { viewModel.onOperator('-') },
                    onDone = { if (state.calculation != null) viewModel.onCalculate() else viewModel.onSave() },
                    doneLabel = if (state.calculation != null) "=" else if (state.isEditMode) "保存修改" else "完成",
                    actionsEnabled = state.actionsEnabled,
                )
            }

            Spacer(Modifier.height(sectionGap))

            if (!state.numericKeyboardVisible || !amountPanelOpen) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = sectionGap),
                horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LedgerPrimaryButton(
                    text = state.saveButtonText,
                    onClick = viewModel::onSave,
                    modifier = Modifier.weight(1.4f),
                    enabled = state.actionsEnabled,
                    loading = state.saving,
                    testTag = TAG_SAVE,
                )
            }
        }
    }
}

// ───────────────────────── 辅助 ─────────────────────────

/** 分类网格列数（320dp 压力宽度下仍满足 ≥48dp 触控区）。 */
private const val CATEGORY_COLUMNS = 4

internal fun formatDate(date: LocalDate): String =
    "${date.year} / ${twoDigits(date.monthValue)} / ${twoDigits(date.dayOfMonth)}"

private fun twoDigits(value: Int): String = if (value < 10) "0$value" else value.toString()
