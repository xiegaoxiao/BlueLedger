package com.blueledger.app.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
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
import com.blueledger.app.core.designsystem.LedgerEmptyState
import com.blueledger.app.core.designsystem.LedgerErrorState
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerMoney
import com.blueledger.app.core.designsystem.LedgerSecondaryButton
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSegmentedToggle
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTransactionRow
import com.blueledger.app.core.designsystem.LedgerTints
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionType
import java.time.LocalDate
import java.time.YearMonth

// ───────────────────────── 稳定测试标识（A7 与 A3 单测共用） ─────────────────────────

const val TAG_TX_SCREEN: String = "transactions_screen"
const val TAG_TX_MONTH_LABEL: String = "tx_month_label"
const val TAG_TX_PREV_MONTH: String = "tx_prev_month"
const val TAG_TX_NEXT_MONTH: String = "tx_next_month"
const val TAG_TX_BACK_TO_CURRENT: String = "tx_back_to_current"
const val TAG_TX_SEARCH: String = "tx_search"
const val TAG_TX_SEARCH_CLEAR: String = "tx_search_clear"
const val TAG_TX_FILTER_ALL: String = "tx_filter_all"
const val TAG_TX_FILTER_EXPENSE: String = "tx_filter_expense"
const val TAG_TX_FILTER_INCOME: String = "tx_filter_income"
const val TAG_TX_FILTER_CATEGORY: String = "tx_filter_category"
const val TAG_TX_FILTER_ACCOUNT: String = "tx_filter_account"
const val TAG_TX_FILTER_RESET: String = "tx_filter_reset"
const val TAG_TX_SUMMARY: String = "tx_summary"
const val TAG_TX_LOAD_MORE: String = "tx_load_more"
const val TAG_TX_EMPTY_LEDGER: String = "tx_empty_ledger"
const val TAG_TX_EMPTY_MONTH: String = "tx_empty_month"
const val TAG_TX_EMPTY_FILTER: String = "tx_empty_filter"
const val TAG_TX_RECORD_BUTTON: String = "tx_record_button"

fun txDayGroupTag(date: LocalDate): String = "tx_group_$date"

fun txRowTag(transactionId: String): String = "tx_row_$transactionId"

fun txCategoryOptionTag(categoryId: String): String = "tx_category_option_$categoryId"

fun txAccountOptionTag(accountId: String): String = "tx_account_option_$accountId"

/**
 * S03 账单列表的冻结入口。
 *
 * 签名由总控 NavHost 直接调用，**不得改动**。
 *
 * @param initialSeed 钻取种子（统计 / 账户 / 首页「查看全部」带入）；只在 ViewModel 首次创建时应用，
 *   返回本页时不会覆盖用户已改过的筛选。
 * @param onOpenDetail 点击账单行进入 S04。
 * @param onOpenEntry 「记一笔」入口（空态主动作）。
 */
@Composable
fun TransactionsRoute(
    repository: LedgerRepository,
    clock: Clock,
    initialSeed: TransactionFilterSeed,
    onOpenDetail: (String) -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
) {
    val transactionsViewModel: TransactionsViewModel = viewModel(
        key = "transactions",
        factory = viewModelFactory {
            initializer {
                TransactionsViewModel(
                    repository = repository,
                    clock = clock,
                    seed = initialSeed,
                    initialPageSize = com.blueledger.app.core.model.TransactionFilter.DEFAULT_LIMIT,
                )
            }
        },
    )
    ObserveScreenSubscriptions(transactionsViewModel)
    val state by transactionsViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(transactionsViewModel) { transactionsViewModel.refreshDate() }

    TransactionsScreen(
        state = state,
        onQueryChanged = transactionsViewModel::onQueryChanged,
        onClearQuery = transactionsViewModel::onClearQuery,
        onTypeSelected = transactionsViewModel::onTypeSelected,
        onCategorySelected = transactionsViewModel::onCategorySelected,
        onAccountSelected = transactionsViewModel::onAccountSelected,
        onResetFilters = transactionsViewModel::onResetFilters,
        onPreviousMonth = transactionsViewModel::onPreviousMonth,
        onNextMonth = transactionsViewModel::onNextMonth,
        onCurrentMonth = transactionsViewModel::onCurrentMonth,
        onLoadMore = transactionsViewModel::loadMore,
        onRetry = transactionsViewModel::onRetry,
        onOpenDetail = onOpenDetail,
        onOpenEntry = onOpenEntry,
    )
}

/** S03 页面本体（无状态，便于测试与 Preview 直接喂 UiState）。 */
@Composable
fun TransactionsScreen(
    state: TransactionsUiState,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    onTypeSelected: (TransactionType?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onAccountSelected: (String?) -> Unit,
    onResetFilters: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val pagePadding = PaddingValues(
        start = BlueLedgerTokens.PageHorizontal,
        end = BlueLedgerTokens.PageHorizontal,
        top = BlueLedgerTokens.PageVertical,
        bottom = BlueLedgerTokens.SpaceHuge,
    )

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Surface)
            .testTag(TAG_TX_SCREEN),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
    ) {
        item(key = "header") { TransactionsHeader() }

        item(key = "month") {
            LedgerMonthNavigator(
                month = state.month,
                isCurrentMonth = state.isCurrentMonth,
                canGoNext = state.canGoToNextMonth,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
                onCurrent = onCurrentMonth,
                labelTag = TAG_TX_MONTH_LABEL,
                prevTag = TAG_TX_PREV_MONTH,
                nextTag = TAG_TX_NEXT_MONTH,
                currentTag = TAG_TX_BACK_TO_CURRENT,
            )
        }

        item(key = "search") {
            SearchField(
                query = state.query,
                onQueryChanged = onQueryChanged,
                onClearQuery = onClearQuery,
            )
        }

        item(key = "filters") {
            FilterBar(
                state = state,
                onTypeSelected = onTypeSelected,
                onCategorySelected = onCategorySelected,
                onAccountSelected = onAccountSelected,
                onResetFilters = onResetFilters,
            )
        }

        when {
            state.loading -> item(key = "loading") {
                LedgerSectionCard { LedgerLoadingState() }
            }

            state.readErrorMessage != null -> item(key = "error") {
                LedgerErrorState(message = state.readErrorMessage, onRetry = onRetry)
            }

            else -> {
                if (!state.isLedgerEmpty) {
                    item(key = "summary") { SummaryBar(state = state) }
                }

                when {
                    state.isFilteredEmpty -> item(key = "empty_filter") {
                        LedgerEmptyState(
                            title = "没有找到符合条件的账单",
                            description = "试着换个搜索词，或清除筛选条件后重新查看。",
                            actionText = "清除筛选条件",
                            onAction = onResetFilters,
                            icon = Icons.Outlined.Search,
                            testTag = TAG_TX_EMPTY_FILTER,
                        )
                    }

                    state.isLedgerEmpty -> item(key = "empty_ledger") {
                        LedgerEmptyState(
                            title = "账本还是空的",
                            description = "点底部蓝色加号，开始记录第一笔。保存后这里会按月列出账单。",
                            icon = Icons.Outlined.Inbox,
                            testTag = TAG_TX_EMPTY_LEDGER,
                        )
                    }

                    state.isMonthEmpty -> item(key = "empty_month") {
                        MonthEmptyState(
                            state = state,
                            onCurrentMonth = onCurrentMonth,
                        )
                    }

                    else -> {
                        items(
                            items = state.groups,
                            key = { group -> group.date.toString() },
                        ) { group ->
                            DayGroupCard(
                                group = group,
                                today = state.today,
                                hideAmounts = state.hideAmounts,
                                onOpenDetail = onOpenDetail,
                            )
                        }

                        if (state.hasMore) {
                            item(key = "load_more") {
                                LedgerSecondaryButton(
                                    text = "加载更多（还有 " + (state.page.totalCount - state.shownCount) + " 笔）",
                                    onClick = onLoadMore,
                                    testTag = TAG_TX_LOAD_MORE,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 头部与月份 ─────────────────────────

@Composable
private fun TransactionsHeader(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Text(
            text = "账单",
            style = LedgerTextStyles.pageTitle,
            color = BlueLedgerTokens.TextPrimary,
        )
        Text(
            text = "生活的每一笔，都在这里",
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
        )
    }
}

// ───────────────────────── 搜索与筛选 ─────────────────────────

@Composable
private fun SearchField(
    query: String,
    onQueryChanged: (String) -> Unit,
    onClearQuery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput)
    BasicTextField(
        value = query,
        onValueChange = onQueryChanged,
        singleLine = true,
        textStyle = LedgerTextStyles.body.copy(color = BlueLedgerTokens.TextPrimary),
        cursorBrush = SolidColor(BlueLedgerTokens.Primary),
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_TX_SEARCH),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .clip(shape)
                    .background(BlueLedgerTokens.Surface)
                    .border(BlueLedgerTokens.CardElevation, BlueLedgerTokens.Border, shape)
                    .padding(horizontal = BlueLedgerTokens.SpaceM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = null,
                    tint = BlueLedgerTokens.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Box(modifier = Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(
                            text = "搜索备注、分类或账户",
                            style = LedgerTextStyles.body,
                            color = BlueLedgerTokens.TextSecondary.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClearQuery,
                        modifier = Modifier
                            .size(BlueLedgerTokens.MinTouchTarget)
                            .testTag(TAG_TX_SEARCH_CLEAR),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Close,
                            contentDescription = "清除搜索内容",
                            tint = BlueLedgerTokens.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        },
    )
}

/** 收支类型筛选：全部 / 支出 / 收入。 */
private enum class TypeFilterOption(val label: String, val value: TransactionType?, val tag: String) {
    ALL("全部", null, TAG_TX_FILTER_ALL),
    EXPENSE("支出", TransactionType.EXPENSE, TAG_TX_FILTER_EXPENSE),
    INCOME("收入", TransactionType.INCOME, TAG_TX_FILTER_INCOME),
}

@Composable
private fun FilterBar(
    state: TransactionsUiState,
    onTypeSelected: (TransactionType?) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onAccountSelected: (String?) -> Unit,
    onResetFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = TypeFilterOption.entries.firstOrNull { it.value == state.type } ?: TypeFilterOption.ALL

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        LedgerSegmentedToggle(
            options = TypeFilterOption.entries,
            selected = selected,
            label = { it.label },
            onSelect = { onTypeSelected(it.value) },
            accent = { if (it.value == TransactionType.INCOME) BlueLedgerTokens.Income else BlueLedgerTokens.Primary },
            testTags = { it.tag },
            segmentedSemantics = "收支筛选",
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterDropdown(
                text = state.selectedCategoryName ?: "全部分类",
                active = state.categoryId != null,
                testTag = TAG_TX_FILTER_CATEGORY,
                modifier = Modifier.weight(1f),
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text("全部分类", style = LedgerTextStyles.body) },
                    onClick = { onCategorySelected(null); dismiss() },
                )
                state.categoryOptions.forEach { category ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (category.isArchived) category.name + "（已归档）" else category.name,
                                style = LedgerTextStyles.body,
                            )
                        },
                        onClick = { onCategorySelected(category.id); dismiss() },
                        modifier = Modifier.testTag(txCategoryOptionTag(category.id)),
                    )
                }
            }

            FilterDropdown(
                text = state.selectedAccountName ?: "全部账户",
                active = state.accountId != null,
                testTag = TAG_TX_FILTER_ACCOUNT,
                modifier = Modifier.weight(1f),
            ) { dismiss ->
                DropdownMenuItem(
                    text = { Text("全部账户", style = LedgerTextStyles.body) },
                    onClick = { onAccountSelected(null); dismiss() },
                )
                state.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (account.account.isArchived) {
                                    account.account.name + "（已归档）"
                                } else {
                                    account.account.name
                                },
                                style = LedgerTextStyles.body,
                            )
                        },
                        onClick = { onAccountSelected(account.account.id); dismiss() },
                        modifier = Modifier.testTag(txAccountOptionTag(account.account.id)),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "可按分类和账户筛选",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "重置",
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.Primary,
                modifier = Modifier
                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                    .testTag(TAG_TX_FILTER_RESET)
                    .clickable(role = Role.Button, onClickLabel = "重置筛选", onClick = onResetFilters)
                    .padding(horizontal = BlueLedgerTokens.SpaceM),
            )
        }
    }
}

/** 筛选下拉：白底描边，选中后高亮；点击展开菜单。 */
@Composable
private fun FilterDropdown(
    text: String,
    active: Boolean,
    testTag: String,
    modifier: Modifier = Modifier,
    items: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusChip)

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                .clip(shape)
                .background(if (active) BlueLedgerTokens.PrimarySoft else BlueLedgerTokens.Surface)
                .border(
                    BlueLedgerTokens.CardElevation,
                    if (active) BlueLedgerTokens.Primary else BlueLedgerTokens.Border,
                    shape,
                )
                .testTag(testTag)
                .clickable(role = Role.Button, onClickLabel = text, onClick = { expanded = true })
                .padding(horizontal = BlueLedgerTokens.SpaceM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = text,
                style = if (active) LedgerTextStyles.bodyStrong else LedgerTextStyles.body,
                color = if (active) BlueLedgerTokens.Primary else BlueLedgerTokens.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = if (active) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = BlueLedgerTokens.Surface,
        ) {
            items { expanded = false }
        }
    }
}

// ───────────────────────── 汇总栏 ─────────────────────────

@Composable
private fun SummaryBar(state: TransactionsUiState, modifier: Modifier = Modifier) {
    LedgerSectionCard(
        modifier = modifier.testTag(TAG_TX_SUMMARY),
        contentPadding = PaddingValues(BlueLedgerTokens.SpaceL),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "筛选结果",
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "共 " + state.summary.count + " 笔",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }

        Spacer(Modifier.size(BlueLedgerTokens.SpaceS))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
        ) {
            SummaryCell(
                label = "收入",
                cents = state.summary.incomeCent,
                hidden = state.hideAmounts,
                tone = AmountTone.INCOME,
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                label = "支出",
                cents = state.summary.expenseCent,
                hidden = state.hideAmounts,
                tone = AmountTone.EXPENSE,
                modifier = Modifier.weight(1f),
            )
            SummaryCell(
                label = "结余",
                cents = state.summary.balanceCent,
                hidden = state.hideAmounts,
                tone = AmountTone.BALANCE,
                modifier = Modifier.weight(1f),
            )
        }

        if (!state.countMatchesShownRows) {
            Spacer(Modifier.size(BlueLedgerTokens.SpaceXs))
            Text(
                text = "已显示 " + state.shownCount + " 笔，可继续加载更多",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }
    }
}

/**
 * 汇总额度格：标签 + 两位小数金额。
 * 金额隐藏时连[语义]一起替换，避免 TalkBack 仍读出真实金额（PRD B10）。
 */
@Composable
private fun SummaryCell(
    label: String,
    cents: Long,
    hidden: Boolean,
    tone: AmountTone,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
        Text(
            text = label,
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            maxLines = 1,
        )
        Text(
            text = if (hidden) LEDGER_HIDDEN_AMOUNT_MASK else LedgerMoney.format(cents),
            style = LedgerTextStyles.bodyStrong,
            color = when {
                hidden -> BlueLedgerTokens.TextPrimary
                cents < 0L -> BlueLedgerTokens.Risk
                tone == AmountTone.INCOME -> BlueLedgerTokens.Income
                tone == AmountTone.EXPENSE -> BlueLedgerTokens.Primary
                else -> BlueLedgerTokens.TextPrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = if (hidden) {
                        label + "金额已隐藏"
                    } else if (cents < 0L) {
                        label + " 负 " + LedgerMoney.format(-cents) + " 元"
                    } else {
                        label + " " + LedgerMoney.format(cents) + " 元"
                    }
                },
        )
    }
}

/** 汇总金额的语气：收入青绿、支出主蓝、结余中性（负数时风险色）。 */
private enum class AmountTone { INCOME, EXPENSE, BALANCE }

// ───────────────────────── 日期分组与账单行 ─────────────────────────

@Composable
private fun DayGroupCard(
    group: TransactionDayGroup,
    today: LocalDate,
    hideAmounts: Boolean,
    onOpenDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LedgerSectionCard(
        modifier = modifier.testTag(txDayGroupTag(group.date)),
        contentPadding = PaddingValues(vertical = BlueLedgerTokens.SpaceS),
        shape = RoundedCornerShape(0.dp),
        elevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BlueLedgerTokens.SpaceM, vertical = BlueLedgerTokens.SpaceXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
        ) {
            Text(
                text = TransactionFormat.dayGroupTitle(group.date, today),
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            if (group.incomeCent > 0L) {
                DaySubtotal(label = "收入", cents = group.incomeCent, isIncome = true, hidden = hideAmounts)
            }
            if (group.expenseCent > 0L) {
                DaySubtotal(label = "支出", cents = group.expenseCent, isIncome = false, hidden = hideAmounts)
            }
        }

        LedgerDivider()

        group.items.forEachIndexed { index, item ->
            LedgerTransactionRow(
                categoryName = item.categoryName,
                iconKey = item.categoryIconKey,
                type = item.type,
                amountCent = item.amountCent,
                title = item.note.ifBlank { item.categoryName },
                subtitle = TransactionFormat.rowSubtitle(
                    categoryName = item.categoryName,
                    accountName = item.accountName,
                    date = item.occurredOn,
                    noteIsBlank = item.note.isBlank(),
                ),
                hideAmount = hideAmounts,
                archived = item.categoryArchived,
                onClick = { onOpenDetail(item.id) },
                testTag = txRowTag(item.id),
                modifier = Modifier.padding(horizontal = BlueLedgerTokens.SpaceS),
            )
            if (index != group.items.lastIndex) {
                LedgerDivider(
                    modifier = Modifier.padding(
                        start = 64.dp,
                        end = BlueLedgerTokens.SpaceM,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DaySubtotal(
    label: String,
    cents: Long,
    isIncome: Boolean,
    hidden: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = if (hidden) {
                label + "金额已隐藏"
            } else {
                label + " " + LedgerMoney.format(cents) + " 元"
            }
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Text(
            text = label,
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
        Text(
            text = if (hidden) LEDGER_HIDDEN_AMOUNT_MASK else LedgerMoney.format(cents),
            style = LedgerTextStyles.caption,
            color = when {
                hidden -> BlueLedgerTokens.TextSecondary
                isIncome -> BlueLedgerTokens.Income
                else -> BlueLedgerTokens.Primary
            },
            maxLines = 1,
        )
    }
}

@Composable
private fun MonthEmptyState(
    state: TransactionsUiState,
    onCurrentMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LedgerEmptyState(
            title = "这个月还没有账单",
            description = TransactionFormat.monthLabel(state.month) + " 没有记录，可切换到其他月份查看。",
            icon = Icons.Outlined.Inbox,
            testTag = TAG_TX_EMPTY_MONTH,
        )
        if (!state.isCurrentMonth) {
            Text(
                text = "回到本月",
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.Primary,
                modifier = Modifier
                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                    .clickable(role = Role.Button, onClickLabel = "回到本月", onClick = onCurrentMonth)
                    .padding(horizontal = BlueLedgerTokens.SpaceL),
            )
        }
    }
}
