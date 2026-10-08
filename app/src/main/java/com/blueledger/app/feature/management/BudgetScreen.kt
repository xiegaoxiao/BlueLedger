package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBackIos
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
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
import com.blueledger.app.core.designsystem.LedgerAmountText
import com.blueledger.app.core.designsystem.LedgerConfirmDialog
import com.blueledger.app.core.designsystem.LedgerErrorBanner
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerPrimaryButton
import com.blueledger.app.core.designsystem.LedgerProgressBar
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSuccessBanner
import com.blueledger.app.core.designsystem.LedgerTextButton
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTopBar
import com.blueledger.app.core.money.Money
import com.blueledger.app.core.model.BudgetStatus
import java.time.YearMonth

/**
 * S08 月度预算（冻结入口，签名由总控 NavHost 直接调用，不得改动）。
 *
 * 只做**月度总支出**预算，不做分类预算与收入预算；
 * 未设置月份不显示假进度条，也不把 0 当分母。
 */
@Composable
fun BudgetRoute(
    repository: LedgerRepository,
    clock: Clock,
    initialYearMonth: YearMonth?,
    onBack: () -> Unit,
) {
    val budgetViewModel: BudgetViewModel = viewModel(
        key = "budget:" + (initialYearMonth?.toString() ?: "current"),
        factory = viewModelFactory {
            initializer {
                BudgetViewModel(
                    repository = repository,
                    clock = clock,
                    initialYearMonth = initialYearMonth,
                )
            }
        },
    )
    val state by budgetViewModel.state.collectAsStateWithLifecycle()
    BudgetScreen(
        state = state,
        onBack = onBack,
        onPreviousMonth = budgetViewModel::onPreviousMonth,
        onNextMonth = budgetViewModel::onNextMonth,
        onStartEdit = budgetViewModel::onStartEdit,
        onCancelEdit = budgetViewModel::onCancelEdit,
        onAmountChanged = budgetViewModel::onAmountChanged,
        onSave = budgetViewModel::onSaveBudget,
        onUsePreviousMonth = budgetViewModel::onUsePreviousMonth,
        onRequestRemove = budgetViewModel::onRequestRemove,
        onCancelRemove = budgetViewModel::onCancelRemove,
        onConfirmRemove = budgetViewModel::onConfirmRemove,
        onBannerShown = budgetViewModel::onBannerShown,
    )
}

@Composable
fun BudgetScreen(
    state: BudgetUiState,
    onBack: () -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onAmountChanged: (String) -> Unit,
    onSave: () -> Unit,
    onUsePreviousMonth: () -> Unit,
    onRequestRemove: () -> Unit,
    onCancelRemove: () -> Unit,
    onConfirmRemove: () -> Unit,
    onBannerShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val month = state.month
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Background)
            .navigationBarsPadding()
            .testTag(ManagementTags.BUDGET_SCREEN),
    ) {
        LedgerTopBar(title = "月度预算", onBack = onBack)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = BlueLedgerTokens.PageHorizontal),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
        ) {
            MonthSwitcher(
                month = month,
                onPrevious = onPreviousMonth,
                onNext = onNextMonth,
            )

            state.banner?.let { banner ->
                if (banner.isError) {
                    LedgerErrorBanner(
                        message = banner.message,
                        actionText = "知道了",
                        onAction = onBannerShown,
                        testTag = ManagementTags.BUDGET_BANNER,
                    )
                } else {
                    LedgerSuccessBanner(
                        message = banner.message,
                        actionText = "知道了",
                        onAction = onBannerShown,
                        testTag = ManagementTags.BUDGET_BANNER,
                    )
                }
            }

            if (state.loading) {
                LedgerLoadingState(message = "正在读取预算…", testTag = ManagementTags.BUDGET_LOADING)
            } else {
                BudgetSummaryCard(state = state)
                BudgetEditorCard(
                    state = state,
                    onAmountChanged = onAmountChanged,
                    onSave = onSave,
                    onStartEdit = onStartEdit,
                    onCancelEdit = onCancelEdit,
                    onUsePreviousMonth = onUsePreviousMonth,
                    onRequestRemove = onRequestRemove,
                )
            }

            Text(
                text = "预算是计划，记录是习惯。\n一步一步，让消费更从容。",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = BlueLedgerTokens.SpaceXl),
            )
        }
    }

    if (state.showRemoveConfirm) {
        LedgerConfirmDialog(
            title = "移除 ${month.chineseLabel()}预算？",
            message = "只会移除这个月的预算设置，账单和支出金额不受影响；移除后该月回到「未设置」。",
            confirmText = "移除预算",
            onConfirm = onConfirmRemove,
            onDismiss = onCancelRemove,
            destructive = true,
            testTag = ManagementTags.BUDGET_REMOVE_DIALOG,
            testTagConfirm = "btn_budget_remove_confirm",
            testTagDismiss = "btn_budget_remove_cancel",
        )
    }
}

// ───────────────────────── 月份切换 ─────────────────────────

@Composable
private fun MonthSwitcher(
    month: YearMonth,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = BlueLedgerTokens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = month.chineseLabel(),
            style = LedgerTextStyles.cardTitle,
            color = BlueLedgerTokens.TextPrimary,
            modifier = Modifier
                .weight(1f)
                .testTag(ManagementTags.BUDGET_MONTH_LABEL),
        )
        IconButton(
            onClick = onPrevious,
            modifier = Modifier
                .size(BlueLedgerTokens.MinTouchTarget)
                .testTag(ManagementTags.BUDGET_PREV_MONTH),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowBackIos,
                contentDescription = "上一个月",
                tint = BlueLedgerTokens.Primary,
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier
                .size(BlueLedgerTokens.MinTouchTarget)
                .testTag(ManagementTags.BUDGET_NEXT_MONTH),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.ArrowForwardIos,
                contentDescription = "下一个月",
                tint = BlueLedgerTokens.Primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ───────────────────────── 摘要卡 ─────────────────────────

@Composable
private fun BudgetSummaryCard(state: BudgetUiState) {
    val budget = state.budget
    val hide = LocalHideAmounts.current

    LedgerSectionCard {
        if (budget == null || !budget.isSet) {
            Text(
                text = BudgetPresentation.UNSET_TITLE,
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.testTag(ManagementTags.BUDGET_UNSET_HINT),
            )
            Text(
                text = "未设置时不会计算使用率，也不会显示进度条；" +
                    "记账照常进行，预算只是提醒。",
                style = LedgerTextStyles.body,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
            )
            return@LedgerSectionCard
        }

        val exceeded = budget.status == BudgetStatus.EXCEEDED
        val remaining = budget.remainingCent ?: 0L
        Text(
            text = if (exceeded) "已超支" else "本月还可支出",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
        )
        LedgerAmountText(
            cents = if (exceeded) -remaining else remaining,
            prefix = "¥",
            style = LedgerTextStyles.heroAmount,
            color = if (exceeded) BlueLedgerTokens.Risk else BlueLedgerTokens.PrimaryDeep,
            testTag = ManagementTags.BUDGET_REMAINING_TEXT,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceXs),
        )
        Text(
            text = if (hide) {
                "已支出 $LEDGER_HIDDEN_AMOUNT_MASK"
            } else {
                BudgetPresentation.usedText(budget)
            },
            style = LedgerTextStyles.body,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier
                .padding(top = BlueLedgerTokens.SpaceS)
                .testTag(ManagementTags.BUDGET_USED_TEXT),
        )
        LedgerProgressBar(
            fraction = budget.progressFraction,
            color = if (exceeded) BlueLedgerTokens.Risk else BlueLedgerTokens.Primary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceM),
            testTag = ManagementTags.BUDGET_PROGRESS_TRACK,
            fillTestTag = ManagementTags.BUDGET_PROGRESS_FILL,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = BlueLedgerTokens.SpaceS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "已用 " + (BudgetPresentation.ratioText(budget) ?: "-"),
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier
                    .weight(1f)
                    .testTag(ManagementTags.BUDGET_RATIO_TEXT),
            )
            Text(
                text = if (hide) {
                    "月预算 $LEDGER_HIDDEN_AMOUNT_MASK"
                } else {
                    BudgetPresentation.budgetText(budget) ?: ""
                },
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.testTag(ManagementTags.BUDGET_AMOUNT_TEXT),
            )
        }
        BudgetPresentation.statusText(budget)?.let { statusText ->
            Text(
                // 金额隐藏时状态文字不含具体金额（只保留「已超支」这类状态），避免泄露。
                text = if (hide && budget.status == BudgetStatus.EXCEEDED) "已超支" else statusText,
                style = LedgerTextStyles.bodyStrong,
                color = if (exceeded || budget.status == BudgetStatus.EXHAUSTED) {
                    BlueLedgerTokens.Risk
                } else {
                    BlueLedgerTokens.PrimaryDeep
                },
                modifier = Modifier
                    .padding(top = BlueLedgerTokens.SpaceS)
                    .testTag(ManagementTags.BUDGET_STATUS_TEXT),
            )
        }
    }
}

// ───────────────────────── 编辑卡 ─────────────────────────

@Composable
private fun BudgetEditorCard(
    state: BudgetUiState,
    onAmountChanged: (String) -> Unit,
    onSave: () -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onUsePreviousMonth: () -> Unit,
    onRequestRemove: () -> Unit,
) {
    val showEditor = state.editing || !state.isSet
    LedgerSectionCard {
        Text(
            text = "每月预算金额",
            style = LedgerTextStyles.cardTitle,
            color = BlueLedgerTokens.TextPrimary,
        )

        if (showEditor) {
            OutlinedTextField(
                value = state.amountText,
                onValueChange = onAmountChanged,
                label = { Text("预算金额（元）") },
                placeholder = { Text("例如 8000") },
                singleLine = true,
                isError = state.fieldError != null,
                shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BlueLedgerTokens.SpaceM)
                    .testTag(ManagementTags.BUDGET_AMOUNT_FIELD),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = BlueLedgerTokens.SpaceS),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LedgerTextButton(
                    text = "沿用上月金额",
                    onClick = onUsePreviousMonth,
                    enabled = !state.saving,
                    testTag = ManagementTags.BUDGET_REUSE_PREVIOUS,
                )
                if (state.isSet) {
                    LedgerTextButton(
                        text = "取消",
                        onClick = onCancelEdit,
                        enabled = !state.saving,
                        testTag = "btn_budget_cancel_edit",
                    )
                }
            }
            state.fieldError?.let { message ->
                LedgerErrorBanner(
                    message = message,
                    modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
                    testTag = ManagementTags.BUDGET_ERROR,
                )
            }
            LedgerPrimaryButton(
                text = "保存预算",
                onClick = onSave,
                loading = state.saving,
                modifier = Modifier.padding(top = BlueLedgerTokens.SpaceM),
                testTag = ManagementTags.BUDGET_SAVE,
            )
        } else {
            state.budget?.let { budget ->
                Text(
                    // 金额隐藏时同样不显示具体预算金额。
                    text = "当前预算 ¥" + moneyOrMask(budget.budgetCent ?: 0L),
                    style = LedgerTextStyles.body,
                    color = BlueLedgerTokens.TextPrimary,
                    modifier = Modifier.padding(top = BlueLedgerTokens.SpaceS),
                )
            }
            LedgerPrimaryButton(
                text = "修改预算",
                onClick = onStartEdit,
                modifier = Modifier.padding(top = BlueLedgerTokens.SpaceM),
                testTag = ManagementTags.BUDGET_EDIT,
            )
        }

        Text(
            text = "只设置当前月份预算，其他月份独立保存。\n只统计支出，账户期初余额不会占用预算。",
            style = LedgerTextStyles.caption,
            color = BlueLedgerTokens.TextSecondary,
            modifier = Modifier.padding(top = BlueLedgerTokens.SpaceM),
        )

        if (state.isSet) {
            LedgerTextButton(
                text = "移除本月预算",
                onClick = onRequestRemove,
                enabled = !state.removing,
                modifier = Modifier
                    .padding(top = BlueLedgerTokens.SpaceM)
                    .testTag(ManagementTags.BUDGET_REMOVE),
            )
        }
    }
}

/** 金额文案：金额隐藏开启时统一为 `••••`（统一读 LocalHideAmounts，不做页面本地判断）。 */
@Composable
private fun moneyOrMask(cents: Long): String =
    if (LocalHideAmounts.current) LEDGER_HIDDEN_AMOUNT_MASK else Money.format(cents)
