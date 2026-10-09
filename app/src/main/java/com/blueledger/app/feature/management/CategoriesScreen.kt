package com.blueledger.app.feature.management

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
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
import androidx.activity.compose.BackHandler
import com.blueledger.app.app.ui.BlueLedgerTokens
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
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
    Box(Modifier.fillMaxSize()) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(BlueLedgerTokens.Background)
            .testTag(ManagementTags.CATEGORIES_SCREEN),
    ) {        ReferenceTitle(
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
        BackHandler(enabled = !editor.saving) { onEditorDismiss() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = .35f))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            CategoryEditorPage(
                editor = editor,
                onNameChanged = onEditorNameChanged,
                onIconSelected = onEditorIconSelected,
                onSave = onEditorSave,
                onDismiss = onEditorDismiss,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
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

// ───────────────────────── 编辑页 ─────────────────────────

/** 参考应用的整页式编辑器：顶栏取消／完成、大图标预览、按分组排列的圆形图标。 */
@Composable
private fun CategoryEditorPage(
    editor: CategoryEditorState,
    onNameChanged: (String) -> Unit,
    onIconSelected: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .fillMaxHeight(.96f)
            .clip(RoundedCornerShape(topStart = BlueLedgerTokens.RadiusCard, topEnd = BlueLedgerTokens.RadiusCard))
            .background(BlueLedgerTokens.Surface)
            .testTag(ManagementTags.CATEGORIES_EDITOR_DIALOG),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text(
                text = when {
                    editor.isNew && editor.type == TransactionType.INCOME -> "添加收入类别"
                    editor.isNew -> "添加支出类别"
                    else -> "编辑类别"
                },
                style = LedgerTextStyles.cardTitle,
                color = BlueLedgerTokens.TextPrimary,
                modifier = Modifier.align(Alignment.Center),
            )
            TextButton(
                onClick = onDismiss,
                enabled = !editor.saving,
                modifier = Modifier.align(Alignment.CenterStart).testTag(ManagementTags.CATEGORIES_CANCEL),
            ) {
                Text("取消", style = LedgerTextStyles.button, color = BlueLedgerTokens.TextPrimary)
            }
            val canSave = editor.name.isNotBlank() && !editor.saving
            TextButton(onClick = onSave, enabled = canSave, modifier = Modifier.align(Alignment.CenterEnd).testTag(ManagementTags.CATEGORIES_SAVE)) {
                Text(
                    text = if (editor.saving) "保存中…" else "完成",
                    style = LedgerTextStyles.button,
                    color = if (canSave) BlueLedgerTokens.Primary else BlueLedgerTokens.TextSecondary,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Box(Modifier.fillMaxWidth().padding(top = BlueLedgerTokens.SpaceHuge), contentAlignment = Alignment.Center) {
                LedgerCategoryIcon(iconKey = editor.iconKey, selected = true, diameter = 72.dp)
            }
            OutlinedTextField(
                value = editor.name,
                onValueChange = onNameChanged,
                placeholder = { Text("输入分类名称（1—12 字符）", color = BlueLedgerTokens.TextSecondary) },
                singleLine = true,
                isError = editor.errorMessage != null,
                shape = RoundedCornerShape(BlueLedgerTokens.RadiusInput),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = BlueLedgerTokens.Background,
                    unfocusedContainerColor = BlueLedgerTokens.Background,
                    focusedBorderColor = BlueLedgerTokens.Primary,
                    unfocusedBorderColor = BlueLedgerTokens.Background,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = BlueLedgerTokens.PageHorizontal, vertical = BlueLedgerTokens.SpaceM)
                    .testTag(ManagementTags.CATEGORIES_NAME_FIELD),
            )
            Text(
                text = "收支类型：${if (editor.type == TransactionType.EXPENSE) "支出" else "收入"}（建立后不可更改）",
                style = LedgerTextStyles.caption,
                color = BlueLedgerTokens.TextSecondary,
                modifier = Modifier.padding(horizontal = BlueLedgerTokens.PageHorizontal),
            )
            editor.errorMessage?.let { message ->
                Box(Modifier.padding(horizontal = BlueLedgerTokens.PageHorizontal, vertical = BlueLedgerTokens.SpaceS)) {
                    LedgerErrorBanner(message = message, testTag = "category_editor_error")
                }
            }
            editorIconSections().forEach { (title, keys) ->
                Text(
                    text = title,
                    style = LedgerTextStyles.bodyStrong,
                    color = BlueLedgerTokens.TextPrimary,
                    modifier = Modifier.fillMaxWidth().padding(top = BlueLedgerTokens.SpaceXl, bottom = BlueLedgerTokens.SpaceM)
                        .testTag(ManagementTags.categoryIconGroup(title)),
                    textAlign = TextAlign.Center,
                )
                keys.chunked(5).forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = BlueLedgerTokens.PageHorizontalCompact),
                        horizontalArrangement = Arrangement.spacedBy(BlueLedgerTokens.SpaceS),
                    ) {
                        rowKeys.forEach { key ->
                            CategoryIconChoice(
                                iconKey = key,
                                selected = key == editor.iconKey,
                                onClick = { onIconSelected(key) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        repeat(5 - rowKeys.size) { Box(modifier = Modifier.weight(1f)) }
                    }
                }
            }
            Spacer(Modifier.height(BlueLedgerTokens.SpaceHuge).navigationBarsPadding())
        }
    }
}

/** 分组只影响编辑器展示顺序，图标 key 的合法性仍以 [CategoryIcons.SELECTABLE] 为准。 */
private val EDITOR_ICON_GROUPS: List<Pair<String, List<String>>> = listOf(
    "常用" to listOf(
        CategoryIcons.RESTAURANT, CategoryIcons.TRANSPORT, CategoryIcons.SHOPPING, CategoryIcons.DAILY, CategoryIcons.HOUSING,
        CategoryIcons.ENTERTAINMENT, CategoryIcons.MEDICAL, CategoryIcons.STUDY,
    ),
    "收入" to listOf(CategoryIcons.SALARY, CategoryIcons.BONUS, CategoryIcons.PART_TIME, CategoryIcons.GIFT),
    "生活" to listOf(
        "vegetables", "fruit", "snacks", "sports", "phone", "clothes", "beauty", "household", "children", "elders",
        "social", "travel", "tobacco", "digital", "car", "books", "pet", "cashgift", "present", "office", "investment",
    ),
)

/** 分组未覆盖的图标统一落到「其他」，保证每个可选图标只会出现一次。 */
internal fun editorIconSections(): List<Pair<String, List<String>>> {
    val covered = EDITOR_ICON_GROUPS.flatMap { it.second }.toSet()
    return EDITOR_ICON_GROUPS + ("其他" to CategoryIcons.SELECTABLE.filter { it !in covered })
}

@Composable
private fun CategoryIconChoice(
    iconKey: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        LedgerCategoryIcon(
            iconKey = iconKey,
            selected = selected,
            diameter = BlueLedgerTokens.MinTouchTarget,
            modifier = Modifier
                .clip(CircleShape)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                .semantics { contentDescription = LedgerIcons.iconLabel(iconKey) }
                .testTag(ManagementTags.categoryIconOption(iconKey)),
        )
    }
}
