package com.blueledger.app.feature.statistics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.app.ui.LocalHideAmounts
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.lifecycle.ObserveScreenSubscriptions
import com.blueledger.app.core.designsystem.LedgerProgressBar
import com.blueledger.app.core.designsystem.LedgerDivider
import com.blueledger.app.core.designsystem.LedgerAmountText
import com.blueledger.app.core.designsystem.LedgerCategoryIcon
import com.blueledger.app.core.designsystem.LedgerEmptyState
import com.blueledger.app.core.designsystem.LedgerErrorState
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSectionHeader
import com.blueledger.app.core.designsystem.LedgerSegmentedToggle
import com.blueledger.app.core.designsystem.LedgerSummaryCard
import com.blueledger.app.core.designsystem.LedgerTextButton
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.model.CategorySlice
import com.blueledger.app.core.model.EntryLaunch
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.StatisticsTab
import com.blueledger.app.core.model.TransactionFilterSeed
import com.blueledger.app.core.model.TransactionType
import java.time.YearMonth

/**
 * S05/S06 的冻结入口（docs/公共接口.md §7），签名不得改动。
 *
 * 数据全部来自 `repository.observeMonthAnalysis` / `observeYearAnalysis`；
 * 钻取通过 [onDrillDown] 交给总控的导航（账单列表），记账入口通过 [onOpenEntry]。
 * 年度页「点月份进入该月月报」是页内分段切换（PRD §4.4：切换月度统计并保留年份、月份），
 * 不经过导航；「查看该月账单」才使用 [onDrillDown]。
 */
@Composable
fun StatisticsRoute(
    repository: LedgerRepository,
    clock: Clock,
    initialTab: StatisticsTab?,
    initialYearMonth: YearMonth?,
    initialYear: Int?,
    onDrillDown: (TransactionFilterSeed) -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
) {
    val statisticsViewModel: StatisticsViewModel = viewModel(
        key = "statistics",
        factory = viewModelFactory {
            initializer {
                StatisticsViewModel(
                    repository = repository,
                    clock = clock,
                    initialTab = initialTab,
                    initialYearMonth = initialYearMonth,
                    initialYear = initialYear,
                )
            }
        },
    )
    ObserveScreenSubscriptions(statisticsViewModel)
    val state by statisticsViewModel.state.collectAsStateWithLifecycle()
    val hidden = LocalHideAmounts.current

    StatisticsScreen(
        state = state,
        viewModel = statisticsViewModel,
        hidden = hidden,
        pickerYears = statisticsViewModel.pickerYears,
        onDrillDown = onDrillDown,
        onOpenEntry = onOpenEntry,
    )
}

/** S05/S06 页面本体（不含导航，钻取与记账通过回调交回上层）。 */
@Composable
fun StatisticsScreen(
    state: StatisticsUiState,
    viewModel: StatisticsViewModel,
    hidden: Boolean,
    pickerYears: List<Int>,
    onDrillDown: (TransactionFilterSeed) -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
) {
    var showMonthPicker by remember { mutableStateOf(false) }
    var showYearPicker by remember { mutableStateOf(false) }

    if (showMonthPicker) {
        MonthPickerDialog(
            selected = state.month,
            currentMonth = state.currentMonth,
            onDismiss = { showMonthPicker = false },
            onPick = { month ->
                viewModel.onPickMonth(month)
                showMonthPicker = false
            },
        )
    }
    if (showYearPicker) {
        YearPickerDialog(
            selected = state.year,
            years = pickerYears,
            onDismiss = { showYearPicker = false },
            onPick = { year ->
                viewModel.onPickYear(year)
                showYearPicker = false
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Surface)
            .testTag(StatisticsTags.SCREEN)
            .verticalScroll(rememberScrollState())
            .padding(
                start = BlueLedgerTokens.PageHorizontal,
                end = BlueLedgerTokens.PageHorizontal,
                top = BlueLedgerTokens.PageVertical,
                bottom = BlueLedgerTokens.SpaceHuge,
            ),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SectionGap),
    ) {
        StatisticsHeader(state = state, onTabSelected = viewModel::onTabSelected)

        // 只有「当前周期对应的那一份分析」就绪才渲染内容：
        // 避免切换月份/类型时短暂显示上一份数据（那会让用户以为新周期就是这个金额）。
        val panelReady = if (state.isMonthTab) {
            state.monthPanel?.let { it.month == state.month && it.type == state.type } == true
        } else {
            state.yearPanel?.year == state.year
        }

        when {
            state.errorMessage != null -> LedgerErrorState(
                message = state.errorMessage,
                onRetry = viewModel::onRetry,
                testTag = StatisticsTags.ERROR,
            )

            !panelReady -> LedgerLoadingState(
                testTag = StatisticsTags.LOADING,
            )

            else -> {
                if (state.isMonthTab) {
                    MonthTabContent(
                        state = state,
                        hidden = hidden,
                        viewModel = viewModel,
                        showMonthPicker = { showMonthPicker = true },
                        onDrillDown = onDrillDown,
                        onOpenEntry = onOpenEntry,
                    )
                } else {
                    YearTabContent(
                        state = state,
                        hidden = hidden,
                        viewModel = viewModel,
                        showYearPicker = { showYearPicker = true },
                        onDrillDown = onDrillDown,
                        onOpenEntry = onOpenEntry,
                    )
                }
            }
        }
    }
}

// ───────────────────────── 顶部标题与周期选择 ─────────────────────────

@Composable
private fun StatisticsHeader(
    state: StatisticsUiState,
    onTabSelected: (StatisticsTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Text(
            text = "收支统计",
            style = LedgerTextStyles.pageTitle,
            color = BlueLedgerTokens.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        LedgerSegmentedToggle(
            options = listOf(StatisticsTab.MONTH, StatisticsTab.YEAR),
            selected = state.tab,
            label = { tab -> if (tab == StatisticsTab.MONTH) "月度" else "年度" },
            onSelect = onTabSelected,
            modifier = Modifier.width(148.dp),
            testTags = { tab -> if (tab == StatisticsTab.MONTH) StatisticsTags.TAB_MONTH else StatisticsTags.TAB_YEAR },
            segmentedSemantics = "统计周期：月度 / 年度",
        )
    }
}

@Composable
private fun PeriodSelector(
    label: String,
    labelTag: String,
    pickerDescription: String,
    onOpenPicker: () -> Unit,
    canGoNext: Boolean,
    nextDescription: String,
    prevTag: String,
    nextTag: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                .testTag(labelTag)
                .clickable(role = Role.Button, onClickLabel = pickerDescription, onClick = onOpenPicker)
                .semantics { contentDescription = "$label，$pickerDescription" },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = label,
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ArrowDropDown,
                contentDescription = null,
                tint = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(BlueLedgerTokens.MinTouchTarget)
                .testTag(prevTag),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronLeft,
                contentDescription = "上一${if (label.startsWith("20")) "期" else "期"}",
                tint = BlueLedgerTokens.TextPrimary,
            )
        }
        IconButton(
            onClick = onNext,
            enabled = canGoNext,
            modifier = Modifier
                .size(BlueLedgerTokens.MinTouchTarget)
                .testTag(nextTag),
        ) {
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = nextDescription,
                tint = if (canGoNext) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
            )
        }
    }
}

// ───────────────────────── S05 月度统计 ─────────────────────────

@Composable
private fun MonthTabContent(
    state: StatisticsUiState,
    hidden: Boolean,
    viewModel: StatisticsViewModel,
    showMonthPicker: () -> Unit,
    onDrillDown: (TransactionFilterSeed) -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
) {
    PeriodSelector(
        label = StatisticsText.month(state.month),
        labelTag = StatisticsTags.MONTH_LABEL,
        pickerDescription = "选择月份",
        onOpenPicker = showMonthPicker,
        canGoNext = state.canGoNextMonth,
        nextDescription = "下一个月",
        prevTag = StatisticsTags.PREV_MONTH,
        nextTag = StatisticsTags.NEXT_MONTH,
        onPrevious = viewModel::onPreviousMonth,
        onNext = viewModel::onNextMonth,
    )

    val panel = state.monthPanel
    if (panel == null) {
        LedgerLoadingState(testTag = StatisticsTags.LOADING)
        return
    }

    LedgerSummaryCard(
        label = "本月结余",
        summary = panel.summary,
        incomeLabel = "本月收入",
        expenseLabel = "本月支出",
        hidden = hidden,
        testTag = StatisticsTags.SUMMARY_CARD,
        compact = true,
    )

    val typeLabel = StatisticsText.typeLabel(state.type)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Text(
            text = "共 ${panel.count} 笔账单",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.testTag(StatisticsTags.SUMMARY_COUNT),
        )
        Text(
            text = panel.averageDailyCent?.let { average ->
                "日均$typeLabel ${StatisticsText.amountText(average, hidden)} 元（${panel.daysInPeriod} 天）"
            } ?: "日均$typeLabel —（该月尚未开始）",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.testTag(StatisticsTags.MONTH_AVERAGE),
        )
    }

    // 类型切换同时控制环图、日趋势与排行：三者读同一份 MonthAnalysis。
    LedgerSegmentedToggle(
        options = listOf(TransactionType.EXPENSE, TransactionType.INCOME),
        selected = state.type,
        label = { type -> if (type == TransactionType.EXPENSE) "支出分析" else "收入分析" },
        onSelect = viewModel::onTypeSelected,
        accent = { type -> if (type == TransactionType.INCOME) BlueLedgerTokens.Income else BlueLedgerTokens.Primary },
        testTags = { type ->
            if (type == TransactionType.EXPENSE) StatisticsTags.TYPE_EXPENSE else StatisticsTags.TYPE_INCOME
        },
        segmentedSemantics = "分析类型：支出 / 收入",
    )

    // ── 日趋势 ──
    LedgerSectionCard(contentPadding = PaddingValues(vertical = BlueLedgerTokens.SpaceM), elevation = 0.dp) {
        LedgerSectionHeader(
            title = "每日${typeLabel}趋势",
            subtitle = if (panel.periodEnd != null) {
                "1 日 — ${panel.periodEnd.dayOfMonth} 日"
            } else {
                "该月尚未开始"
            },
        )
        StatisticsDailyTrendChart(
            points = panel.daily,
            type = state.type,
            hidden = hidden,
            selectedIndex = state.selectedDayIndex,
            onSelectIndex = viewModel::onSelectDay,
        )
        DayReadout(
            panel = panel,
            state = state,
            hidden = hidden,
        )
        DayDetailList(panel = panel, type = state.type, hidden = hidden)
    }

    // ── 分类排行（完整，不合并） ──
    LedgerSectionCard(contentPadding = PaddingValues(vertical = BlueLedgerTokens.SpaceM), elevation = 0.dp) {
        LedgerSectionHeader(
            title = "分类排行",
            subtitle = if (hidden) "金额已隐藏" else "共 ${panel.slices.size} 类",
            modifier = Modifier.testTag(StatisticsTags.RANKING_TITLE),
        )
        if (panel.slices.isEmpty()) {
            Text(
                text = panel.emptyText(),
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
            )
        } else {
            panel.slices.forEachIndexed { index, slice ->
                RankingRow(
                    rank = index + 1,
                    slice = slice,
                    type = state.type,
                    hidden = hidden,
                    onClick = {
                        onDrillDown(
                            TransactionFilterSeed(
                                yearMonth = panel.month,
                                type = state.type,
                                categoryId = slice.categoryId,
                            ),
                        )
                    },
                )
            }
        }
    }

    // ── 分类环图 + 图例 ──
    LedgerSectionCard(contentPadding = PaddingValues(vertical = BlueLedgerTokens.SpaceM), elevation = 0.dp) {
        LedgerSectionHeader(
            title = "分类占比",
            subtitle = if (hidden) "金额已隐藏" else "共 ${panel.ringSlices.size} 项",
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val stacked = maxWidth < 360.dp
            if (stacked) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
                ) {
                    StatisticsRingChart(
                        slices = panel.ringSlices,
                        totalCent = panel.typeTotalCent,
                        centerLabel = "${typeLabel}分类",
                        emptyText = panel.emptyText(),
                        hidden = hidden,
                        diameter = 156.dp,
                    )
                    RingLegend(
                        panel = panel,
                        hidden = hidden,
                        onDrillDown = onDrillDown,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatisticsRingChart(
                        slices = panel.ringSlices,
                        totalCent = panel.typeTotalCent,
                        centerLabel = "${typeLabel}分类",
                        emptyText = panel.emptyText(),
                        hidden = hidden,
                        diameter = 156.dp,
                    )
                    RingLegend(
                        panel = panel,
                        hidden = hidden,
                        onDrillDown = onDrillDown,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        if (!panel.hasTypeData) {
            Text(
                text = panel.emptyText(),
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
            )
        }
    }

    if (panel.count == 0) {
        LedgerEmptyState(
            title = "这个月还没有账单",
            description = "有账单后，这里会显示收支趋势。可点底部加号开始记录。",
            testTag = StatisticsTags.EMPTY_STATE,
        )
    }
}

@Composable
private fun RingLegend(
    panel: MonthPanel,
    hidden: Boolean,
    onDrillDown: (TransactionFilterSeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (panel.ringSlices.isEmpty()) {
        Text(
            text = panel.emptyText(),
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
    ) {
        panel.ringSlices.forEach { slice ->
            val tag = slice.categoryId?.let { StatisticsTags.legendRow(it) } ?: StatisticsTags.LEGEND_MERGED
            val description = buildString {
                append(slice.name)
                append(' ')
                append(StatisticsText.ratioText(slice.ratioPermille, hidden))
                append('，')
                append(StatisticsText.amountText(slice.amountCent, hidden))
                if (hidden) append(" 元，金额已隐藏")
                if (slice.drillable) append("，点击查看该分类账单") else append("，图表合并项，完整排行见下方")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
                    .testTag(tag)
                    .then(
                        if (slice.drillable) {
                            Modifier
                                .clickable(
                                    role = Role.Button,
                                    onClickLabel = "${slice.name} 分类账单",
                                    onClick = {
                                        onDrillDown(
                                            TransactionFilterSeed(
                                                yearMonth = panel.month,
                                                type = panel.type,
                                                categoryId = slice.categoryId,
                                            ),
                                        )
                                    },
                                )
                        } else {
                            Modifier
                        },
                    )
                    .padding(vertical = BlueLedgerTokens.SpaceXs)
                    .semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(CategoryPalette.colorOf(slice)),
                )
                Text(
                    text = slice.name,
                    style = LedgerTextStyles.body,
                    color = BlueLedgerTokens.TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = StatisticsText.amountText(slice.amountCent, hidden),
                    style = LedgerTextStyles.rowAmount,
                    color = BlueLedgerTokens.TextPrimary,
                )
                Text(
                    text = StatisticsText.ratioText(slice.ratioPermille, hidden),
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(56.dp),
                )
            }
        }
        if (panel.ringSlices.any { it.merged }) {
            Text(
                text = "「${StatisticsText.MERGED_SLICE_NAME}」是图表合并项，不是真实分类；完整排行见下方。",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }
    }
}

/** 点按趋势后的读数：完整日期 + 当前类型 + 准确金额（金额隐藏时全部掩码）。 */
@Composable
private fun DayReadout(
    panel: MonthPanel,
    state: StatisticsUiState,
    hidden: Boolean,
) {
    val selected = state.selectedDay
    val typeLabel = StatisticsText.typeLabel(state.type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
            .testTag(StatisticsTags.TREND_READOUT),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
    ) {
        Text(
            text = if (selected != null) {
                "${StatisticsText.fullDate(selected.date)}・$typeLabel " +
                    "${StatisticsText.amountText(selected.amountCent, hidden)} 元"
            } else {
                "点按上方图表查看某一天的$typeLabel"
            },
            style = LedgerTextStyles.bodyStrong,
            color = if (selected != null) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
        )
    }
    if (selected != null) {
        Text(
            text = "当天${typeLabel}占本月${typeLabel}的 " +
                StatisticsText.ratioText(permilleOf(selected.amountCent, panel.typeTotalCent), hidden),
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
    }
}

/** 日趋势明细：区间内每一天（含 0），保证大字体/屏幕阅读器能得到与图表等价的信息。 */
@Composable
private fun DayDetailList(
    panel: MonthPanel,
    type: TransactionType,
    hidden: Boolean,
) {
    if (panel.daily.isEmpty()) return
    val typeLabel = StatisticsText.typeLabel(type)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        panel.daily.forEach { point ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 32.dp)
                    .testTag(StatisticsTags.dayDetailRow(point.date.dayOfMonth))
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${StatisticsText.fullDate(point.date)}，$typeLabel " +
                            StatisticsText.amountText(point.amountCent, hidden) + " 元"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = StatisticsText.day(point.date),
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    text = StatisticsText.amountText(point.amountCent, hidden),
                    style = LedgerTextStyles.body,
                    color = if (point.amountCent > 0L) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 分类排行（完整列表，保留所有真实分类）。
 * 点击进入「同月 + 同类型 + 该分类」的账单列表。
 */
@Composable
private fun RankingRow(
    rank: Int,
    slice: CategorySlice,
    type: TransactionType,
    hidden: Boolean,
    onClick: () -> Unit,
) {
    val typeLabel = StatisticsText.typeLabel(type)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
            .testTag(StatisticsTags.rankingRow(slice.categoryId))
            .clickable(
                role = Role.Button,
                onClickLabel = "${slice.name} 分类账单",
                onClick = onClick,
            )
            .padding(vertical = BlueLedgerTokens.SpaceXs)
            .semantics(mergeDescendants = true) {
                contentDescription = "第 $rank 名 ${slice.name}，$typeLabel " +
                    StatisticsText.amountText(slice.amountCent, hidden) + " 元，占 " +
                    StatisticsText.ratioText(slice.ratioPermille, hidden) + "，点击查看该分类账单"
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        LedgerCategoryIcon(iconKey = slice.iconKey, selected = false, diameter = 40.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(slice.name, style = LedgerTextStyles.bodyStrong, color = BlueLedgerTokens.TextPrimary,
                    modifier = Modifier.weight(1f), maxLines = 1)
                Text(StatisticsText.ratioText(slice.ratioPermille, hidden), style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary, modifier = Modifier.padding(horizontal = BlueLedgerTokens.SpaceS))
                LedgerAmountText(slice.amountCent, isIncome = type == TransactionType.INCOME,
                    hidden = hidden, style = LedgerTextStyles.rowAmount)
            }
            LedgerProgressBar(
                fraction = if (hidden) null else slice.ratioPermille / 1000f,
                color = if (type == TransactionType.INCOME) BlueLedgerTokens.Income else BlueLedgerTokens.Primary,
                height = 5.dp, modifier = Modifier.heightIn(min = 5.dp),
            )
        }
    }
}

// ───────────────────────── S06 年度统计 ─────────────────────────

@Composable
private fun YearTabContent(
    state: StatisticsUiState,
    hidden: Boolean,
    viewModel: StatisticsViewModel,
    showYearPicker: () -> Unit,
    onDrillDown: (TransactionFilterSeed) -> Unit,
    onOpenEntry: (EntryLaunch) -> Unit,
) {
    PeriodSelector(
        label = StatisticsText.year(state.year),
        labelTag = StatisticsTags.YEAR_LABEL,
        pickerDescription = "选择年份",
        onOpenPicker = showYearPicker,
        canGoNext = state.canGoNextYear,
        nextDescription = "下一年",
        prevTag = StatisticsTags.PREV_YEAR,
        nextTag = StatisticsTags.NEXT_YEAR,
        onPrevious = viewModel::onPreviousYear,
        onNext = viewModel::onNextYear,
    )

    val panel = state.yearPanel
    if (panel == null) {
        LedgerLoadingState(testTag = StatisticsTags.LOADING)
        return
    }

    val cutoff = buildString {
        append(StatisticsText.cutoffText(panel.cutoff))
        if (panel.isCurrentYear) append("・未到月份不计入统计")
    }
    Text(
        text = cutoff,
        style = LedgerTextStyles.caption,
        color = BlueLedgerTokens.TextSecondary,
        modifier = Modifier.testTag(StatisticsTags.YEAR_CUTOFF),
    )

    LedgerSummaryCard(
        label = "年度结余",
        summary = panel.summary,
        incomeLabel = "年度收入",
        expenseLabel = "年度支出",
        hidden = hidden,
        testTag = StatisticsTags.YEAR_SUMMARY_CARD,
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
    ) {
        Text(
            text = "共 ${panel.count} 笔账单",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
        Text(
            text = panel.averageMonthlyExpenseCent?.let { average ->
                val prefix = if (panel.isCurrentYear) "截至目前月均支出" else "全年月均支出"
                "$prefix ${StatisticsText.amountText(average, hidden)} 元" +
                    "（按 ${panel.reachedMonthCount} 个月）"
            } ?: "月均支出 —（该年尚未开始）",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.testTag(StatisticsTags.YEAR_AVERAGE),
        )
    }

    LedgerSectionCard(contentPadding = PaddingValues(vertical = BlueLedgerTokens.SpaceM), elevation = 0.dp) {
        LedgerSectionHeader(title = "月度收支对照")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
        ) {
            StatisticsLegendDot(
                color = BlueLedgerTokens.Primary,
                label = "支出",
                testTag = StatisticsTags.YEAR_LEGEND_EXPENSE,
            )
            StatisticsLegendDot(
                color = BlueLedgerTokens.Income,
                label = "收入",
                testTag = StatisticsTags.YEAR_LEGEND_INCOME,
            )
        }
        StatisticsYearBarChart(
            columns = panel.columns,
            hidden = hidden,
            selectedMonth = state.selectedMonth,
            onSelectMonth = viewModel::onSelectMonth,
        )
        Text(
            text = "点月份查看月报・11 / 12 月等未到月份不可查看",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
        SelectedMonthCard(
            state = state,
            hidden = hidden,
            onOpenMonthReport = { month -> viewModel.onOpenMonthReport(month) },
            onDrillDown = onDrillDown,
        )
    }

    LedgerSectionCard(contentPadding = PaddingValues(vertical = BlueLedgerTokens.SpaceM), elevation = 0.dp) {
        LedgerSectionHeader(title = "月份明细", subtitle = "人民币 / 元")
        panel.columns.forEach { column ->
            YearMonthRow(
                year = panel.year,
                column = column,
                hidden = hidden,
                onOpenMonthReport = { viewModel.onOpenMonthReport(YearMonth.of(panel.year, column.month)) },
                onOpenBills = {
                    onDrillDown(
                        TransactionFilterSeed(
                            yearMonth = YearMonth.of(panel.year, column.month),
                        ),
                    )
                },
            )
        }
    }

    if (!panel.hasAnyRecords) {
        LedgerEmptyState(
            title = "${panel.year} 年还没有账单",
            description = "有账单后，这里会显示全年收支趋势。",
            testTag = StatisticsTags.EMPTY_STATE,
        )
    }
}

@Composable
private fun SelectedMonthCard(
    state: StatisticsUiState,
    hidden: Boolean,
    onOpenMonthReport: (YearMonth) -> Unit,
    onDrillDown: (TransactionFilterSeed) -> Unit,
) {
    val column = state.selectedYearColumn ?: return
    val yearMonth = YearMonth.of(state.year, column.month)
    LedgerSectionCard(
        modifier = Modifier
            .padding(top = BlueLedgerTokens.SpaceM)
            .testTag(StatisticsTags.SELECTED_MONTH_CARD),
    ) {
        Text(
            text = StatisticsText.month(yearMonth),
            style = LedgerTextStyles.cardTitle,
            color = BlueLedgerTokens.TextPrimary,
        )
        Text(
            text = "${rowSummaryText(column, hidden)}",
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LedgerTextButton(
                text = "查看该月月报",
                onClick = { onOpenMonthReport(yearMonth) },
            )
            LedgerTextButton(
                text = "查看该月账单",
                onClick = {
                    onDrillDown(TransactionFilterSeed(yearMonth = yearMonth))
                },
                testTag = StatisticsTags.SELECTED_MONTH_BILLS,
            )
        }
    }
}

private fun rowSummaryText(column: YearMonthColumn, hidden: Boolean): String {
    if (!column.reached) return "未到月份，暂不计入统计"
    return "支出 ${StatisticsText.amountText(column.expenseCent, hidden)} 元・" +
        "收入 ${StatisticsText.amountText(column.incomeCent, hidden)} 元・" +
        "结余 ${StatisticsText.amountText(column.balanceCent, hidden)} 元"
}

/** 月份明细行：未到月份显示占位而不是 0，且不可点击进入有效月报。 */
@Composable
private fun YearMonthRow(
    year: Int,
    column: YearMonthColumn,
    hidden: Boolean,
    onOpenMonthReport: () -> Unit,
    onOpenBills: () -> Unit,
) {
    val yearMonth = YearMonth.of(year, column.month)
    val description = when {
        !column.reached -> "${StatisticsText.month(yearMonth)}，未到月份，不可查看"
        hidden -> "${StatisticsText.month(yearMonth)}，金额已隐藏"
        else -> "${StatisticsText.month(yearMonth)}，支出 ${StatisticsText.money(column.expenseCent)} 元，" +
            "收入 ${StatisticsText.money(column.incomeCent)} 元，" +
            "结余 ${StatisticsText.money(column.balanceCent)} 元"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = BlueLedgerTokens.ListRowMinHeight)
            .clip(RoundedCornerShape(BlueLedgerTokens.RadiusInput))
            .testTag(StatisticsTags.yearMonthRow(column.month))
            .then(
                if (column.drillable) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = "${StatisticsText.month(yearMonth)}月报",
                        onClick = onOpenMonthReport,
                    )
                } else {
                    Modifier
                },
            )
            .padding(vertical = BlueLedgerTokens.SpaceXs)
            .semantics(mergeDescendants = true) { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${column.month} 月",
                style = LedgerTextStyles.bodyStrong,
                color = if (column.reached) BlueLedgerTokens.TextPrimary else BlueLedgerTokens.TextSecondary,
            )
            Text(
                text = rowSummaryText(column, hidden),
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                maxLines = 2,
            )
        }
        if (!column.reached) {
            Text(
                text = "未到月份",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        } else {
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                    .testTag(StatisticsTags.yearMonthBills(column.month))
                    .clickable(
                        role = Role.Button,
                        onClickLabel = "${StatisticsText.month(yearMonth)}账单",
                        onClick = onOpenBills,
                    )
                    .padding(horizontal = BlueLedgerTokens.SpaceM),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "账单",
                    style = LedgerTextStyles.bodyStrong,
                    color = BlueLedgerTokens.Primary,
                )
            }
        }
    }
}

// ───────────────────────── 周期选择弹层 ─────────────────────────

@Composable
private fun MonthPickerDialog(
    selected: YearMonth,
    currentMonth: YearMonth,
    onDismiss: () -> Unit,
    onPick: (YearMonth) -> Unit,
) {
    var pickerYear by remember(selected) { mutableStateOf(selected.year) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(StatisticsTags.MONTH_PICKER),
        title = { Text("选择月份", style = LedgerTextStyles.cardTitle, color = BlueLedgerTokens.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(
                        onClick = { pickerYear -= 1 },
                        modifier = Modifier.size(BlueLedgerTokens.MinTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronLeft,
                            contentDescription = "上一年",
                            tint = BlueLedgerTokens.TextPrimary,
                        )
                    }
                    Text(
                        text = StatisticsText.year(pickerYear),
                        style = LedgerTextStyles.cardTitle,
                        color = BlueLedgerTokens.TextPrimary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { if (pickerYear < currentMonth.year) pickerYear += 1 },
                        enabled = pickerYear < currentMonth.year,
                        modifier = Modifier.size(BlueLedgerTokens.MinTouchTarget),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = "下一年",
                            tint = if (pickerYear < currentMonth.year) {
                                BlueLedgerTokens.TextPrimary
                            } else {
                                BlueLedgerTokens.TextSecondary
                            },
                        )
                    }
                }
                (1..12).chunked(4).forEach { rowMonths ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
                    ) {
                        rowMonths.forEach { monthValue ->
                            val candidate = YearMonth.of(pickerYear, monthValue)
                            val future = candidate.isAfter(currentMonth)
                            val isSelected = candidate == selected
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                                    .clip(RoundedCornerShape(BlueLedgerTokens.RadiusChip))
                                    .background(
                                        if (isSelected) BlueLedgerTokens.PrimarySoft else BlueLedgerTokens.Surface,
                                    )
                                    .testTag(StatisticsTags.monthOption(candidate))
                                    .clickable(
                                        enabled = !future,
                                        role = Role.Button,
                                        onClickLabel = StatisticsText.month(candidate),
                                        onClick = { onPick(candidate) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$monthValue 月",
                                    style = if (isSelected) LedgerTextStyles.bodyStrong else LedgerTextStyles.body,
                                    color = when {
                                        future -> BlueLedgerTokens.TextSecondary.copy(alpha = 0.5f)
                                        isSelected -> BlueLedgerTokens.Primary
                                        else -> BlueLedgerTokens.TextPrimary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = LedgerTextStyles.button, color = BlueLedgerTokens.Primary)
            }
        },
    )
}

@Composable
private fun YearPickerDialog(
    selected: Int,
    years: List<Int>,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(StatisticsTags.YEAR_PICKER),
        title = { Text("选择年份", style = LedgerTextStyles.cardTitle, color = BlueLedgerTokens.TextPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                years.forEach { year ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = BlueLedgerTokens.MinTouchTarget)
                            .testTag(StatisticsTags.yearOption(year))
                            .clickable(role = Role.Button, onClickLabel = StatisticsText.year(year)) {
                                onPick(year)
                            }
                            .padding(horizontal = BlueLedgerTokens.SpaceS),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = StatisticsText.year(year),
                            style = if (year == selected) LedgerTextStyles.bodyStrong else LedgerTextStyles.body,
                            color = if (year == selected) BlueLedgerTokens.Primary else BlueLedgerTokens.TextPrimary,
                        )
                        if (year == selected) {
                            Text(
                                text = "・当前选择",
                                style = LedgerTextStyles.caption,
                                color = BlueLedgerTokens.Primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", style = LedgerTextStyles.button, color = BlueLedgerTokens.Primary)
            }
        },
    )
}
