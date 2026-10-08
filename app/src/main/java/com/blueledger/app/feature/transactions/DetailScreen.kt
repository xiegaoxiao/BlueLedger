package com.blueledger.app.feature.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Edit
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.designsystem.LedgerCategoryIcon
import com.blueledger.app.core.designsystem.LedgerConfirmDialog
import com.blueledger.app.core.designsystem.LedgerDivider
import com.blueledger.app.core.designsystem.LedgerEmptyState
import com.blueledger.app.core.designsystem.LedgerErrorState
import com.blueledger.app.core.designsystem.LedgerKeyValueRow
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerMoney
import com.blueledger.app.core.designsystem.LedgerPrimaryButton
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTints
import com.blueledger.app.core.designsystem.LedgerTopBar
import com.blueledger.app.core.model.DeleteReceipt
import java.time.ZoneId

// ───────────────────────── 稳定测试标识 ─────────────────────────

const val TAG_DETAIL_SCREEN: String = "detail_screen"
const val TAG_DETAIL_CATEGORY_ICON: String = "detail_category_icon"
const val TAG_DETAIL_CATEGORY_NAME: String = "detail_category_name"
const val TAG_DETAIL_CATEGORY: String = "detail_category"
const val TAG_DETAIL_AMOUNT: String = "detail_amount"
const val TAG_DETAIL_TYPE_CHIP: String = "detail_type_chip"
const val TAG_DETAIL_DATE: String = "detail_date"
const val TAG_DETAIL_CREATED_AT: String = "detail_created_at"
const val TAG_DETAIL_UPDATED_AT: String = "detail_updated_at"
const val TAG_DETAIL_ACCOUNT: String = "detail_account"
const val TAG_DETAIL_NOTE: String = "detail_note"
const val TAG_DETAIL_EDIT: String = "detail_edit"
const val TAG_DETAIL_DELETE: String = "detail_delete"
const val TAG_DETAIL_DELETE_DIALOG: String = "detail_delete_dialog"
const val TAG_DETAIL_MISSING: String = "detail_missing"

/**
 * S04 账单详情的冻结入口。
 *
 * 签名由总控 NavHost 直接调用，**不得改动**。
 *
 * @param onDeleted 软删除成功后携带撤销凭据返回上层；撤销提示由 NavHost 统一展示
 *   （5 秒窗口 = `Limits.UNDO_WINDOW_MILLIS`），本页不自己弹提示、不自己 popBackStack。
 * @param onEdit 编辑走独立导航事件进入 S02 编辑模式，本页不复制编辑表单。
 */
@Composable
fun DetailRoute(
    repository: LedgerRepository,
    clock: Clock,
    transactionId: String,
    onEdit: (String) -> Unit,
    onDeleted: (DeleteReceipt) -> Unit,
    onBack: () -> Unit,
) {
    val detailViewModel: DetailViewModel = viewModel(
        key = "detail:" + transactionId,
        factory = viewModelFactory {
            initializer {
                DetailViewModel(repository = repository, transactionId = transactionId)
            }
        },
    )
    val state by detailViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(detailViewModel) {
        detailViewModel.events.collect { event ->
            if (event is DetailEvent.Deleted) onDeleted(event.receipt)
        }
    }

    DetailScreen(
        state = state,
        zoneId = clock.zoneId(),
        onBack = onBack,
        onEdit = { onEdit(transactionId) },
        onDeleteRequested = detailViewModel::onDeleteRequested,
        onDeleteConfirmed = detailViewModel::onDeleteConfirmed,
        onDeleteDismissed = detailViewModel::onDeleteDismissed,
        onRetry = detailViewModel::onRetry,
    )
}

/** S04 页面本体（无状态）。 */
@Composable
fun DetailScreen(
    state: DetailUiState,
    zoneId: ZoneId,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onDeleteRequested: () -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            // 二级页面没有底部导航栏，自己处理系统导航栏安全区。
            .navigationBarsPadding()
            .testTag(TAG_DETAIL_SCREEN),
    ) {
        LedgerTopBar(title = "账单详情", onBack = onBack, showDivider = true)

        val transaction = state.transaction
        when {
            state.loading -> LedgerLoadingState(modifier = Modifier.weight(1f))

            state.readErrorMessage != null -> LedgerErrorState(
                message = state.readErrorMessage,
                onRetry = onRetry,
                modifier = Modifier.weight(1f),
            )

            state.isMissing || transaction == null -> MissingRecordState(
                onBack = onBack,
                modifier = Modifier.weight(1f),
            )

            else -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(
                            horizontal = BlueLedgerTokens.PageHorizontal,
                            vertical = BlueLedgerTokens.SpaceL,
                        ),
                    verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceL),
                ) {
                    DetailHero(state = state, zoneId = zoneId)
                    DetailFields(state = state, zoneId = zoneId)
                    Text(
                        text = "编辑或删除后，相关收支统计会同步更新。",
                        style = LedgerTextStyles.caption,
                        color = BlueLedgerTokens.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                DetailActions(
                    enabled = !state.deleting,
                    onEdit = onEdit,
                    onDelete = onDeleteRequested,
                )
            }
        }
    }

    if (state.showDeleteDialog) {
        DeleteConfirmDialog(
            state = state,
            zoneId = zoneId,
            onConfirm = onDeleteConfirmed,
            onDismiss = onDeleteDismissed,
        )
    }
}

// ───────────────────────── 主视觉 ─────────────────────────

@Composable
private fun DetailHero(state: DetailUiState, zoneId: ZoneId, modifier: Modifier = Modifier) {
    val transaction = state.transaction ?: return
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(BlueLedgerTokens.RadiusCard))
                .background(BlueLedgerTokens.PrimarySoft)
                .testTag(TAG_DETAIL_CATEGORY_ICON),
            contentAlignment = Alignment.Center,
        ) {
            LedgerCategoryIcon(
                iconKey = state.categoryIconKey,
                selected = false,
                diameter = 64.dp,
                archived = state.categoryArchived,
            )
        }

        Text(
            text = state.categoryName,
            style = LedgerTextStyles.bodyStrong,
            color = BlueLedgerTokens.TextPrimary,
            modifier = Modifier.testTag(TAG_DETAIL_CATEGORY_NAME),
        )

        // 支出「−」、收入「+」，两位小数；原始 amountCent 始终为正。
        // 详情是用户主动打开的明细，金额按真实值展示（PRD S10），但仍给出完整无障碍描述。
        Text(
            text = LedgerMoney.formatSigned(isIncome = state.isIncome, cents = transaction.amountCent),
            style = LedgerTextStyles.heroAmount,
            color = if (state.isIncome) BlueLedgerTokens.Income else BlueLedgerTokens.Primary,
            maxLines = 1,
            modifier = Modifier
                .testTag(TAG_DETAIL_AMOUNT)
                .semantics {
                    contentDescription = (if (state.isIncome) "收入 " else "支出 ") +
                        LedgerMoney.format(transaction.amountCent) + " 元"
                },
        )

        TypeChip(isIncome = state.isIncome)
    }
}

@Composable
private fun TypeChip(isIncome: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusChip)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (isIncome) LedgerTints.incomeSoft() else BlueLedgerTokens.PrimarySoft)
            .testTag(TAG_DETAIL_TYPE_CHIP)
            .padding(horizontal = BlueLedgerTokens.SpaceM, vertical = BlueLedgerTokens.SpaceXs),
    ) {
        Text(
            text = if (isIncome) "收入账单" else "支出账单",
            style = LedgerTextStyles.caption,
            color = if (isIncome) BlueLedgerTokens.Income else BlueLedgerTokens.Primary,
        )
    }
}

/** 只给详情主金额加语义：详情是用户主动打开的明细，按钮级信息必须完整可读。 */

// ───────────────────────── 字段区 ─────────────────────────

@Composable
private fun DetailFields(state: DetailUiState, zoneId: ZoneId, modifier: Modifier = Modifier) {
    val transaction = state.transaction ?: return
    LedgerSectionCard(
        modifier = modifier,
        contentPadding = PaddingValues(
            horizontal = BlueLedgerTokens.SpaceL,
            vertical = BlueLedgerTokens.SpaceS,
        ),
    ) {
        LedgerKeyValueRow(
            label = "账单分类",
            value = state.categoryName,
            testTag = TAG_DETAIL_CATEGORY,
        )
        LedgerDivider()
        LedgerKeyValueRow(
            label = "发生日期",
            value = TransactionFormat.fullDate(transaction.occurredOn),
            testTag = TAG_DETAIL_DATE,
        )
        LedgerDivider()
        LedgerKeyValueRow(
            label = "创建时间",
            value = TransactionFormat.dateTime(transaction.createdAt, zoneId),
            testTag = TAG_DETAIL_CREATED_AT,
        )
        if (state.hasBeenUpdated) {
            LedgerDivider()
            LedgerKeyValueRow(
                label = "最近修改",
                value = TransactionFormat.dateTime(transaction.updatedAt, zoneId),
                testTag = TAG_DETAIL_UPDATED_AT,
            )
        }
        LedgerDivider()
        LedgerKeyValueRow(
            label = "支付账户",
            value = state.accountName,
            testTag = TAG_DETAIL_ACCOUNT,
        )
        LedgerDivider()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = BlueLedgerTokens.SpaceM)
                .testTag(TAG_DETAIL_NOTE),
            verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
        ) {
            Text(
                text = "备注",
                style = LedgerTextStyles.fieldLabel,
                color = BlueLedgerTokens.TextSecondary,
            )
            // 完整备注：不截断行数，不允许出现 null。
            Text(
                text = transaction.note.ifBlank { "未填写备注" },
                style = LedgerTextStyles.body,
                color = if (transaction.note.isBlank()) {
                    BlueLedgerTokens.TextSecondary
                } else {
                    BlueLedgerTokens.TextPrimary
                },
            )
        }
    }
}

// ───────────────────────── 操作区 ─────────────────────────

@Composable
private fun DetailActions(
    enabled: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = BlueLedgerTokens.PageHorizontal,
                end = BlueLedgerTokens.PageHorizontal,
                top = BlueLedgerTokens.SpaceS,
                bottom = BlueLedgerTokens.SpaceL,
            ),
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RiskButton(
            text = "删除账单",
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.weight(1f),
        )
        LedgerPrimaryButton(
            text = "编辑账单",
            onClick = onEdit,
            enabled = enabled,
            testTag = TAG_DETAIL_EDIT,
            modifier = Modifier.weight(1.4f),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = null,
                    tint = BlueLedgerTokens.Surface,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
    }
}

/**
 * 风险色文字按钮（删除）。设计系统没有危险按钮，删除在 S04 是「低强调但必须看得见」的动作，
 * 因此这里用风险色文字 + 浅风险底，语义与触控区（≥48dp）与主按钮一致。
 */
@Composable
private fun RiskButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String = TAG_DETAIL_DELETE,
) {
    val shape = RoundedCornerShape(BlueLedgerTokens.RadiusButton)
    val contentColor = if (enabled) BlueLedgerTokens.Risk else BlueLedgerTokens.TextSecondary
    Box(
        modifier = modifier
            .heightIn(min = BlueLedgerTokens.ButtonMinHeight)
            .clip(shape)
            .background(if (enabled) LedgerTints.riskSoft() else BlueLedgerTokens.Surface)
            .testTag(testTag)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = text, onClick = onClick)
            .padding(horizontal = BlueLedgerTokens.SpaceM),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = LedgerTextStyles.button,
            color = contentColor,
            maxLines = 1,
        )
    }
}

// ───────────────────────── 删除确认 ─────────────────────────

/**
 * 删除确认弹窗。
 *
 * 必须包含**金额、分类、发生日期**三项摘要，避免「确定删除吗」式的笼统确认；
 * 删除后 5 秒内可撤销（撤销由 NavHost 展示）。
 */
@Composable
private fun DeleteConfirmDialog(
    state: DetailUiState,
    zoneId: ZoneId,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val transaction = state.transaction ?: return
    LedgerConfirmDialog(
        title = "删除这笔账单？",
        message = "确认后会从列表和统计中移除；5 秒内可以撤销，撤销会恢复同一笔记录。",
        confirmText = if (state.deleting) "删除中…" else "确认删除",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        destructive = true,
        testTag = TAG_DETAIL_DELETE_DIALOG,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
                LedgerKeyValueRow(
                    label = "金额",
                    value = LedgerMoney.formatSigned(state.isIncome, transaction.amountCent),
                    valueColor = if (state.isIncome) BlueLedgerTokens.Income else BlueLedgerTokens.Primary,
                )
                LedgerKeyValueRow(label = "分类", value = state.categoryName)
                LedgerKeyValueRow(
                    label = "发生日期",
                    value = TransactionFormat.fullDate(transaction.occurredOn),
                )
                if (state.deleteError != null) {
                    Text(
                        text = state.deleteError,
                        style = LedgerTextStyles.caption,
                        color = BlueLedgerTokens.Risk,
                    )
                }
            }
        },
    )
}

// ───────────────────────── 记录不存在 ─────────────────────────

@Composable
private fun MissingRecordState(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LedgerEmptyState(
            title = "账单不存在",
            description = "这笔账单可能已被删除，或链接已经失效。返回列表查看其他记录。",
            actionText = "返回账单列表",
            onAction = onBack,
            icon = Icons.AutoMirrored.Outlined.ReceiptLong,
            testTag = TAG_DETAIL_MISSING,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = BlueLedgerTokens.PageHorizontal),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
        ) {
            Icon(
                imageVector = Icons.Outlined.CalendarMonth,
                contentDescription = null,
                tint = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "被删除的账单不会出现在列表、统计与导出中。",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }
    }
}
