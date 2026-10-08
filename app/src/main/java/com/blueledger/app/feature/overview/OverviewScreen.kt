package com.blueledger.app.feature.overview

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.blueledger.app.core.designsystem.LedgerCategoryCell
import com.blueledger.app.core.designsystem.LedgerComponentSizes
import com.blueledger.app.core.designsystem.LedgerEmptyState
import com.blueledger.app.core.designsystem.LedgerErrorState
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerMoney
import com.blueledger.app.core.designsystem.LedgerProgressBar
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSectionHeader
import com.blueledger.app.core.designsystem.LedgerSummaryCard
import com.blueledger.app.core.designsystem.LedgerTextButton
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTransactionRow
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.BudgetStatus
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.money.Money
import com.blueledger.app.feature.transactions.LedgerMonthNavigator
import com.blueledger.app.feature.transactions.TransactionFormat
import java.time.YearMonth

// ───────────────────────── 稳定测试标识 ─────────────────────────

const val TAG_OVERVIEW_SCREEN: String = "overview_screen"
const val TAG_OVERVIEW_TODAY: String = "overview_today"
const val TAG_OVERVIEW_HIDE_TOGGLE: String = "overview_hide_toggle"
const val TAG_OVERVIEW_MONTH_LABEL: String = "overview_month_label"
const val TAG_OVERVIEW_PREV_MONTH: String = "overview_prev_month"
const val TAG_OVERVIEW_NEXT_MONTH: String = "overview_next_month"
const val TAG_OVERVIEW_BACK_TO_CURRENT: String = "overview_back_to_current"
const val TAG_OVERVIEW_SUMMARY_CARD: String = "overview_summary_card"
const val TAG_OVERVIEW_STATS_ENTRY: String = "overview_stats_entry"
const val TAG_OVERVIEW_QUICK_SECTION: String = "overview_quick_section"
const val TAG_OVERVIEW_BUDGET_CARD: String = "overview_budget_card"
const val TAG_OVERVIEW_BUDGET_SET: String = "overview_budget_set"
const val TAG_OVERVIEW_BUDGET_PROGRESS: String = "overview_budget_progress"
const val TAG_OVERVIEW_BUDGET_RATIO: String = "overview_budget_ratio"
const val TAG_OVERVIEW_RECENT_SECTION: String = "overview_recent_section"
const val TAG_OVERVIEW_VIEW_ALL: String = "overview_view_all"
const val TAG_OVERVIEW_EMPTY_FIRST: String = "overview_empty_first"
const val TAG_OVERVIEW_EMPTY_MONTH: String = "overview_empty_month"

fun overviewQuickTag(categoryId: String): String = "overview_quick_$categoryId"

fun overviewRowTag(transactionId: String): String = "overview_row_$transactionId"

/**
 * S01 总览首页的冻结入口。
 *
 * 签名由总控 NavHost 直接调用，**不得改动**。
 *
 * @param onOpenDetail 最近账单行点击（S01 要求「点击进入详情」）。
 * @param onOpenTransactions 「查看全部」带当前所选月份进入 S03。
 * @param onOpenStatistics 摘要卡 / 统计入口带所选月份进入 S05。
 * @param onOpenBudget 预算卡带所选月份进入 S08。
 */
@Composable
fun OverviewRoute(
    repository: LedgerRepository,
    clock: Clock,
    onOpenEntry: (EntryLaunch) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenTransactions: (YearMonth) -> Unit,
    onOpenStatistics: (YearMonth) -> Unit,
    onOpenBudget: (YearMonth) -> Unit,
) {
    val overviewViewModel: OverviewViewModel = viewModel(
        key = "overview",
        factory = viewModelFactory {
            initializer { OverviewViewModel(repository = repository, clock = clock) }
        },
    )
    ObserveScreenSubscriptions(overviewViewModel)
    val state by overviewViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(overviewViewModel) { overviewViewModel.refreshDate() }

    OverviewScreen(
        state = state,
        onPreviousMonth = overviewViewModel::onPreviousMonth,
        onNextMonth = overviewViewModel::onNextMonth,
        onCurrentMonth = overviewViewModel::onCurrentMonth,
        onToggleHideAmounts = overviewViewModel::onToggleHideAmounts,
        onRetry = overviewViewModel::onRetry,
        onOpenEntry = onOpenEntry,
        onOpenDetail = onOpenDetail,
        onOpenTransactions = onOpenTransactions,
        onOpenStatistics = onOpenStatistics,
        onOpenBudget = onOpenBudget,
    )
}

/** S01 页面本体（无状态）。 */
@Composable
fun OverviewScreen(
    state: OverviewUiState,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onCurrentMonth: () -> Unit,
    onToggleHideAmounts: () -> Unit,
    onRetry: () -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenTransactions: (YearMonth) -> Unit,
    onOpenStatistics: (YearMonth) -> Unit,
    onOpenBudget: (YearMonth) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(BlueLedgerTokens.Surface)
            .testTag(TAG_OVERVIEW_SCREEN),
        contentPadding = PaddingValues(bottom = BlueLedgerTokens.SpaceHuge),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
    ) {
        item(key = "header") {
            Column(
                Modifier.fillMaxWidth().background(BlueLedgerTokens.PrimarySoft)
                    .padding(horizontal = BlueLedgerTokens.PageHorizontal, vertical = BlueLedgerTokens.SpaceL),
                verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
            ) {
                OverviewHeader(state, onToggleHideAmounts)
                LedgerMonthNavigator(
                    month = state.month, isCurrentMonth = state.isCurrentMonth,
                    canGoNext = state.canGoToNextMonth, onPrevious = onPreviousMonth,
                    onNext = onNextMonth, onCurrent = onCurrentMonth,
                    labelTag = TAG_OVERVIEW_MONTH_LABEL, prevTag = TAG_OVERVIEW_PREV_MONTH,
                    nextTag = TAG_OVERVIEW_NEXT_MONTH, currentTag = TAG_OVERVIEW_BACK_TO_CURRENT,
                )
                if (!state.loading && state.readErrorMessage == null) {
                    LedgerSummaryCard(
                        label = state.balanceLabel, summary = state.summary,
                        incomeLabel = state.incomeLabel, expenseLabel = state.expenseLabel,
                        hidden = state.hideAmounts, compact = true,
                        testTag = TAG_OVERVIEW_SUMMARY_CARD,
                        modifier = Modifier.clickable(role = Role.Button,
                            onClickLabel = "查看所选月份统计", onClick = { onOpenStatistics(state.month) }),
                    )
                }
            }
        }
        when {
            state.loading -> item(key = "loading") { LedgerLoadingState() }
            state.readErrorMessage != null -> item(key = "error") {
                LedgerErrorState(message = state.readErrorMessage, onRetry = onRetry)
            }
            else -> {
                item(key = "actions") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = BlueLedgerTokens.PageHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM)) {
                        LedgerTextButton("全部账单", { onOpenTransactions(state.month) }, modifier = Modifier.weight(1f))
                        LedgerTextButton("月度图表", { onOpenStatistics(state.month) }, modifier = Modifier.weight(1f), testTag = TAG_OVERVIEW_STATS_ENTRY)
                        LedgerTextButton("月度预算", { onOpenBudget(state.month) }, modifier = Modifier.weight(1f))
                    }
                }
                item(key = "recent") {
                    RecentSection(state, onOpenDetail, { onOpenTransactions(state.month) },
                        onOpenEntry, onCurrentMonth,
                        Modifier.padding(horizontal = BlueLedgerTokens.PageHorizontal))
                }
                if (state.quickCategories.isNotEmpty()) {
                    item(key = "quick") {
                        QuickPickSection(state,
                            { id -> onOpenEntry(EntryLaunch(type = TransactionType.EXPENSE, categoryId = id)) },
                            Modifier.padding(horizontal = BlueLedgerTokens.PageHorizontal))
                    }
                }
                item(key = "budget") {
                    BudgetCard(state, { onOpenBudget(state.month) },
                        Modifier.padding(horizontal = BlueLedgerTokens.PageHorizontal))
                }
            }
        }
    }
}

// ───────────────────────── 顶部与入口 ─────────────────────────

@Composable
private fun OverviewHeader(
    state: OverviewUiState,
    onToggleHideAmounts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = "蓝记",
                style = LedgerTextStyles.pageTitle,
                color = BlueLedgerTokens.TextPrimary,
            )
            Text(
                text = TransactionFormat.dateWithWeekday(state.today),
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.testTag(TAG_OVERVIEW_TODAY),
            )
        }

        // 金额显示开关：与 S10 的开关读写同一个仓库设置，不是本页局部开关。
        Box(
            modifier = Modifier
                .size(BlueLedgerTokens.MinTouchTarget)
                .clip(CircleShape)
                .background(BlueLedgerTokens.PrimarySoft)
                .testTag(TAG_OVERVIEW_HIDE_TOGGLE)
                .clickable(
                    role = Role.Switch,
                    onClickLabel = if (state.hideAmounts) "显示金额" else "隐藏金额",
                    onClick = onToggleHideAmounts,
                )
                .semantics {
                    contentDescription = if (state.hideAmounts) "金额已隐藏，点击显示金额" else "点击隐藏金额"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (state.hideAmounts) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = null,
                tint = BlueLedgerTokens.Primary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun StatsEntry(
    month: YearMonth,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
            .testTag(TAG_OVERVIEW_STATS_ENTRY)
            .clickable(role = Role.Button, onClickLabel = "查看统计", onClick = onClick)
            .padding(horizontal = BlueLedgerTokens.SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "查看 " + TransactionFormat.monthLabel(month) + " 统计",
            style = LedgerTextStyles.bodyStrong,
            color = BlueLedgerTokens.Primary,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = BlueLedgerTokens.Primary,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun QuickPickSection(
    state: OverviewUiState,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_OVERVIEW_QUICK_SECTION),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        LedgerSectionHeader(title = "常用分类", subtitle = "轻点分类，快速填写")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        ) {
            state.quickCategories.forEach { category ->
                LedgerCategoryCell(
                    name = category.name,
                    iconKey = category.iconKey,
                    selected = false,
                    onClick = { onPick(category.id) },
                    testTag = overviewQuickTag(category.id),
                )
            }
        }
    }
}

// ───────────────────────── 预算卡 ─────────────────────────

@Composable
private fun BudgetCard(
    state: OverviewUiState,
    onOpenBudget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val budget = state.budget
    val hasBudget = budget != null && budget.isSet

    LedgerSectionCard(
        modifier = modifier
            .testTag(TAG_OVERVIEW_BUDGET_CARD)
            .clickable(role = Role.Button, onClickLabel = "打开月度预算", onClick = onOpenBudget),
        contentPadding = PaddingValues(BlueLedgerTokens.CardPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = state.budgetLabel,
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            if (hasBudget && budget != null) {
                Text(
                    text = if (state.hideAmounts) {
                        LEDGER_HIDDEN_AMOUNT_MASK
                    } else {
                        // ratioPermille 是「百分比 × 10」的整数，一位小数四舍五入（7697 → 77.0%）。
                        budget.ratioPermille?.let { Money.formatPermille(it) }.orEmpty()
                    },
                    style = LedgerTextStyles.caption,
                    color = budgetTone(budget.status),
                    modifier = Modifier.testTag(TAG_OVERVIEW_BUDGET_RATIO),
                )
            }
        }

        Spacer(Modifier.size(BlueLedgerTokens.SpaceS))

        if (budget == null) {
            // 预算还没读到时不能冒充「未设置」：保留卡片结构并如实说明正在读取。
            Text(
                text = "正在读取预算…",
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
            )
            return@LedgerSectionCard
        }

        if (!hasBudget) {
            // 未设置预算：不显示假进度条，只给明确设置入口（PRD S01 / S08）。
            Text(
                text = "还没有为这个月设置预算。",
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
            )
            Spacer(Modifier.size(BlueLedgerTokens.SpaceXs))
            LedgerTextButton(
                text = BudgetCopy.actionLabel(hasBudget = false),
                onClick = onOpenBudget,
                testTag = TAG_OVERVIEW_BUDGET_SET,
            )
            return@LedgerSectionCard
        }

        // 已设置：进度条最多 100%，超支不溢出；金额隐藏时不画填充比例（图形也不泄露）。
        // 外层显式给出轨道高度：LazyColumn 的 item 高度是无界的，进度条自身只声明
        // heightIn(min)，在无界父约束下容易塌成 0 高（A3 实测过），这里用 A2 的尺寸令牌兜住。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(LedgerComponentSizes.progressTrackHeight)
                .testTag(TAG_OVERVIEW_BUDGET_PROGRESS),
        ) {
            LedgerProgressBar(
                fraction = if (state.hideAmounts) null else budget.progressFraction,
                color = budgetTone(budget.status),
                height = LedgerComponentSizes.progressTrackHeight,
            )
        }

        Spacer(Modifier.size(BlueLedgerTokens.SpaceS))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
        ) {
            BudgetFigure(
                label = "已用",
                amount = budgetAmount(state.hideAmounts, budget.usedCent),
                hidden = state.hideAmounts,
                semanticsText = "已用 " + LedgerMoney.format(budget.usedCent) + " 元",
                alignEnd = false,
                modifier = Modifier.weight(1f),
            )
            BudgetFigure(
                label = "预算",
                amount = budgetAmount(state.hideAmounts, budget.budgetCent ?: 0L),
                hidden = state.hideAmounts,
                semanticsText = "预算 " + LedgerMoney.format(budget.budgetCent ?: 0L) + " 元",
                alignEnd = true,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.size(BlueLedgerTokens.SpaceXs))

        BudgetStatusLine(budget = budget, hideAmounts = state.hideAmounts)
    }
}

@Composable
private fun BudgetFigure(
    label: String,
    amount: String,
    hidden: Boolean,
    semanticsText: String,
    alignEnd: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = if (hidden) "金额已隐藏" else semanticsText
        },
        horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        Text(text = label, style = LedgerTextStyles.caption, color = BlueLedgerTokens.TextSecondary)
        Text(
            text = amount,
            style = LedgerTextStyles.bodyStrong,
            color = BlueLedgerTokens.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 剩余 / 超支提示：文字与颜色同时表达状态，不能只靠颜色。 */
@Composable
private fun BudgetStatusLine(
    budget: BudgetState,
    hideAmounts: Boolean,
    modifier: Modifier = Modifier,
) {
    val exceeded = budget.exceededCent
    val remaining = budget.remainingCent
    val text = when {
        exceeded != null -> "已超支 " + budgetAmount(hideAmounts, exceeded)
        remaining != null -> "还可支出 " + budgetAmount(hideAmounts, remaining)
        else -> ""
    }
    val semanticsText = when {
        exceeded != null -> "已超支 " + LedgerMoney.format(exceeded) + " 元"
        remaining != null -> "还可支出 " + LedgerMoney.format(remaining) + " 元"
        else -> ""
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Text(
            text = BudgetCopy.statusLabel(budget.status),
            style = LedgerTextStyles.caption,
            color = budgetTone(budget.status),
        )
        Text(
            text = text,
            style = LedgerTextStyles.bodyStrong,
            color = budgetTone(budget.status),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = if (hideAmounts) "金额已隐藏" else semanticsText },
        )
    }
}

private fun budgetAmount(hidden: Boolean, cents: Long): String =
    if (hidden) LEDGER_HIDDEN_AMOUNT_MASK else LedgerMoney.format(cents)

private fun budgetTone(status: BudgetStatus): androidx.compose.ui.graphics.Color = when (status) {
    BudgetStatus.UNSET -> BlueLedgerTokens.TextSecondary
    BudgetStatus.NORMAL -> BlueLedgerTokens.Primary
    BudgetStatus.NEAR_LIMIT -> BlueLedgerTokens.Primary
    BudgetStatus.EXHAUSTED -> BlueLedgerTokens.Risk
    BudgetStatus.EXCEEDED -> BlueLedgerTokens.Risk
}

// ───────────────────────── 最近账单 ─────────────────────────

@Composable
private fun RecentSection(
    state: OverviewUiState,
    onOpenDetail: (String) -> Unit,
    onOpenTransactions: () -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
    onCurrentMonth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TAG_OVERVIEW_RECENT_SECTION),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        LedgerSectionHeader(
            title = "最近账单",
            action = {
                LedgerTextButton(
                    text = "查看全部",
                    onClick = onOpenTransactions,
                    testTag = TAG_OVERVIEW_VIEW_ALL,
                )
            },
        )

        when {
            state.recentTransactions.isNotEmpty() -> LedgerSectionCard(
                contentPadding = PaddingValues(
                    horizontal = BlueLedgerTokens.SpaceS,
                    vertical = BlueLedgerTokens.SpaceS,
                ),
            ) {
                state.recentTransactions.groupBy { it.occurredOn }.forEach { (date, transactions) ->
                    com.blueledger.app.core.designsystem.LedgerDivider()
                    Text(TransactionFormat.dateWithWeekday(date), style = LedgerTextStyles.caption,
                        color = BlueLedgerTokens.TextSecondary,
                        modifier = Modifier.padding(vertical = BlueLedgerTokens.SpaceM))
                    transactions.forEach { item ->
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
                        hideAmount = state.hideAmounts,
                        archived = item.categoryArchived,
                        onClick = { onOpenDetail(item.id) },
                        testTag = overviewRowTag(item.id),
                    )
                    }
                }
            }

            state.isLedgerEmpty -> LedgerEmptyState(
                title = "账本还是空的",
                description = "点底部蓝色加号，开始记录第一笔。",
                icon = Icons.Outlined.Inbox,
                testTag = TAG_OVERVIEW_EMPTY_FIRST,
            )

            else -> Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                LedgerEmptyState(
                    title = "这个月还没有账单",
                    description = TransactionFormat.monthLabel(state.month) + " 暂无记录。可切换月份查看，或点底部加号新增。",
                    icon = Icons.Outlined.Inbox,
                    testTag = TAG_OVERVIEW_EMPTY_MONTH,
                )
                if (!state.isCurrentMonth) {
                    LedgerTextButton(
                        text = "回到本月",
                        onClick = onCurrentMonth,
                        testTag = TAG_OVERVIEW_BACK_TO_CURRENT + "_in_empty",
                    )
                }
            }
        }
    }
}
