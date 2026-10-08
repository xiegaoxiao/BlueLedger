package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.RemoveCircle
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
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
import com.blueledger.app.core.designsystem.LedgerCategoryIcon
import com.blueledger.app.core.designsystem.LedgerConfirmDialog
import com.blueledger.app.core.designsystem.LedgerDivider
import com.blueledger.app.core.designsystem.LedgerErrorBanner
import com.blueledger.app.core.designsystem.LedgerIcons
import com.blueledger.app.core.designsystem.LedgerLoadingState
import com.blueledger.app.core.designsystem.LedgerSecondaryButton
import com.blueledger.app.core.designsystem.LedgerSectionCard
import com.blueledger.app.core.designsystem.LedgerSegmentedToggle
import com.blueledger.app.core.designsystem.LedgerSuccessBanner
import com.blueledger.app.core.designsystem.LedgerTextStyles
import com.blueledger.app.core.designsystem.LedgerTopBar
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.feature.reference.ReferenceTitle
import com.blueledger.app.feature.reference.ReferenceTabs
import kotlin.math.roundToInt

/**
 * S07 分类管理（冻结入口，签名由总控 NavHost 直接调用，不得改动）。
 *
 * 支出 / 收入分区；新增、改名、换图标、排序、归档与恢复。
 * 归档**不是删除**：历史账单与统计继续引用原分类 ID，界面文案明确说明这一点。
 */
@Composable
fun CategoriesRoute(
    repository: LedgerRepository,
    clock: Clock,
    onBack: () -> Unit,
    initialType: TransactionType? = null,
) {
    val categoriesViewModel: CategoriesViewModel = viewModel(
        key = "categories",
        factory = viewModelFactory {
            initializer { CategoriesViewModel(repository = repository, initialType = initialType ?: TransactionType.EXPENSE) }
        },
    )
    val state by categoriesViewModel.state.collectAsStateWithLifecycle()
    CategoriesScreen(
        state = state,
        onBack = onBack,
        onTypeSelected = categoriesViewModel::onTypeSelected,
        onAddCategory = categoriesViewModel::onAddCategory,
        onEditCategory = categoriesViewModel::onEditCategory,
        onMoveUp = categoriesViewModel::onMoveCategoryUp,
        onMoveDown = categoriesViewModel::onMoveCategoryDown,
        onRequestArchive = categoriesViewModel::onRequestArchive,
        onCancelArchive = categoriesViewModel::onCancelArchive,
        onConfirmArchive = categoriesViewModel::onConfirmArchive,
        onRestore = categoriesViewModel::onRestoreCategory,
        onEditorNameChanged = categoriesViewModel::onEditorNameChanged,
        onEditorIconSelected = categoriesViewModel::onEditorIconSelected,
        onEditorSave = categoriesViewModel::onEditorSave,
        onEditorDismiss = categoriesViewModel::onEditorDismissed,
        onBannerShown = categoriesViewModel::onBannerShown,
        onReorder = categoriesViewModel::onReorderActiveCategories,
    )
}

@Composable
fun CategoriesScreen(
    state: CategoriesUiState,
    onBack: () -> Unit,
    onTypeSelected: (TransactionType) -> Unit,
    onAddCategory: () -> Unit,
    onEditCategory: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRequestArchive: (String) -> Unit,
    onCancelArchive: () -> Unit,
    onConfirmArchive: () -> Unit,
    onRestore: (String) -> Unit,
    onEditorNameChanged: (String) -> Unit,
    onEditorIconSelected: (String) -> Unit,
    onEditorSave: () -> Unit,
    onEditorDismiss: () -> Unit,
    onBannerShown: () -> Unit,
    modifier: Modifier = Modifier,
    onReorder: (List<String>) -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Background)
            .testTag(ManagementTags.CATEGORIES_SCREEN),
    ) {
        ReferenceTitle(
            title = "类别设置", blue = true,
            onBack = onBack,
            action = {
                IconButton(
                    onClick = onAddCategory,
                    modifier = Modifier
                        .size(BlueLedgerTokens.MinTouchTarget)
                        .testTag(ManagementTags.CATEGORIES_ADD),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = "添加新分类",
                        tint = BlueLedgerTokens.Primary,
                    )
                }
            },
        )

        Box(Modifier.fillMaxWidth().background(BlueLedgerTokens.PrimarySoft).padding(horizontal = 36.dp, vertical = 8.dp)) {
            CompactCategoryTypeTabs(state.type, onTypeSelected)
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {

            state.banner?.let { banner ->
                if (banner.isError) {
                    LedgerErrorBanner(
                        message = banner.message,
                        actionText = "知道了",
                        onAction = onBannerShown,
                        testTag = ManagementTags.CATEGORIES_BANNER,
                    )
                } else {
                    LedgerSuccessBanner(
                        message = banner.message,
                        actionText = "知道了",
                        onAction = onBannerShown,
                        testTag = ManagementTags.CATEGORIES_BANNER,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "我的分类 · ${state.activeCategories.size} 个可用",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(ManagementTags.CATEGORIES_ACTIVE_COUNT),
                )
                Text(
                    text = "长按拖动排序",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                )
            }

            if (state.loading) {
                LedgerLoadingState(message = "正在读取分类…")
            } else {
                Column(Modifier.background(BlueLedgerTokens.Surface)) {
                    state.activeCategories.forEachIndexed { index, category ->
                        key(category.id) { CompactCategoryManageRow(
                            category = category,
                            canMoveUp = index > 0,
                            canMoveDown = index < state.activeCategories.lastIndex,
                            onEdit = { onEditCategory(category.id) },
                            onMoveUp = { onMoveUp(category.id) },
                            onMoveDown = { onMoveDown(category.id) },
                            onArchive = { onRequestArchive(category.id) },
                            onDrop = { offset ->
                                val destination = (index + offset).coerceIn(state.activeCategories.indices)
                                val ids = state.activeCategories.map { it.id }.toMutableList()
                                ids.add(destination, ids.removeAt(index))
                                onReorder(ids)
                            },
                        ) }
                    }
                }

                if (state.archivedCategories.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(ManagementTags.CATEGORIES_ARCHIVED_HEADER),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "已归档 · ${state.archivedCategories.size} 个",
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
                    Column(Modifier.background(BlueLedgerTokens.Surface)) {
                        state.archivedCategories.forEach { category ->
                            ArchivedCategoryRow(
                                category = category,
                                onRestore = { onRestore(category.id) },
                            )
                        }
                    }
                }
            }

            Text(
                text = "归档分类不删除历史账单\n每种收支类型至少保留一个可用分类",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BlueLedgerTokens.Surface).navigationBarsPadding(),
        ) {
            TextButton(onClick = onAddCategory, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(ManagementTags.CATEGORIES_ADD + "_bottom")) {
                Text("＋ 添加类别", color = BlueLedgerTokens.TextPrimary, style = LedgerTextStyles.bodyStrong)
            }
        }
    }

    state.pendingArchive?.let { category ->
        LedgerConfirmDialog(
            title = "归档「${category.name}」？",
            message = "归档后它不再出现在新账单里，已有账单和统计仍保留这个分类，随时可以恢复。",
            confirmText = "归档",
            onConfirm = onConfirmArchive,
            onDismiss = onCancelArchive,
            destructive = true,
            testTag = ManagementTags.CATEGORIES_ARCHIVE_DIALOG,
            testTagConfirm = "btn_category_archive_confirm",
            testTagDismiss = "btn_category_archive_cancel",
        )
    }

    state.editor?.let { editor ->
        CategoryEditorDialog(
            editor = editor,
            onNameChanged = onEditorNameChanged,
            onIconSelected = onEditorIconSelected,
            onSave = onEditorSave,
            onDismiss = onEditorDismiss,
        )
    }
}

// ───────────────────────── 列表行 ─────────────────────────

@Composable
private fun ArchivedCategoryRow(
    category: LedgerCategory,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = BlueLedgerTokens.ListRowMinHeight)
            .padding(
                start = BlueLedgerTokens.SpaceL,
                end = BlueLedgerTokens.SpaceL,
                top = BlueLedgerTokens.SpaceS,
                bottom = BlueLedgerTokens.SpaceS,
            )
            .testTag(ManagementTags.categoryRow(category.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceM),
    ) {
        LedgerCategoryIcon(iconKey = category.iconKey, selected = false, archived = true)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = category.name,
                style = LedgerTextStyles.bodyStrong,
                color = BlueLedgerTokens.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "已归档 · 新账单不再可选",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
            )
        }
        TextButton(
            onClick = onRestore,
            modifier = Modifier
                .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                .testTag(ManagementTags.categoryRestore(category.id)),
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

// ───────────────────────── 编辑弹层 ─────────────────────────

@Composable
private fun CategoryEditorDialog(
    editor: CategoryEditorState,
    onNameChanged: (String) -> Unit,
    onIconSelected: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(BlueLedgerTokens.RadiusCard),
        containerColor = BlueLedgerTokens.Surface,
        modifier = Modifier.testTag(ManagementTags.CATEGORIES_EDITOR_DIALOG),
        title = {
            Text(
                text = if (editor.isNew) {
                    if (editor.type == TransactionType.INCOME) "新增收入分类" else "新增支出分类"
                } else {
                    "编辑分类"
                },
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
                    label = { Text("分类名称（1—12 字符）") },
                    singleLine = true,
                    isError = editor.errorMessage != null,
                    shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(ManagementTags.CATEGORIES_NAME_FIELD),
                )
                Text(
                    text = "收支类型：${if (editor.type == TransactionType.EXPENSE) "支出" else "收入"}" +
                        "（建立后不可更改）",
                    style = LedgerTextStyles.caption,
                    color = BlueLedgerTokens.TextSecondary,
                )
                Text(
                    text = "选择图标",
                    style = LedgerTextStyles.fieldLabel,
                    color = BlueLedgerTokens.TextSecondary,
                )
                CategoryIconPicker(
                    selectedKey = editor.iconKey,
                    onSelect = onIconSelected,
                )
                editor.errorMessage?.let { message ->
                    LedgerErrorBanner(message = message, testTag = "category_editor_error")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = !editor.saving,
                modifier = Modifier
                    .heightIn(min = BlueLedgerTokens.MinTouchTarget)
                    .testTag(ManagementTags.CATEGORIES_SAVE),
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
                    .testTag(ManagementTags.CATEGORIES_CANCEL),
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
private fun CategoryIconPicker(
    selectedKey: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs)) {
        CategoryIcons.SELECTABLE.chunked(5).forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceXs),
            ) {
                rowKeys.forEach { key ->
                    LedgerCategoryCell(
                        name = LedgerIcons.iconLabel(key),
                        iconKey = key,
                        selected = key == selectedKey,
                        onClick = { onSelect(key) },
                        modifier = Modifier.weight(1f),
                        testTag = ManagementTags.categoryIconOption(key),
                    )
                }
                repeat(5 - rowKeys.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
