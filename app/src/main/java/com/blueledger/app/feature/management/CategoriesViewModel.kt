package com.blueledger.app.feature.management

import androidx.lifecycle.ViewModel
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 分类编辑弹层状态。[categoryId] 为 null 表示新增。 */
data class CategoryEditorState(
    val categoryId: String? = null,
    /** 新建分类的收支类型在弹层打开时就固定（页面分段决定），**不提供改类型入口**。 */
    val type: TransactionType,
    val name: String = "",
    val iconKey: String = CategoryIcons.RESTAURANT,
    val saving: Boolean = false,
    val errorMessage: String? = null,
) {
    val isNew: Boolean get() = categoryId == null
}

/** S07 分类管理 UI 状态。 */
data class CategoriesUiState(
    val type: TransactionType = TransactionType.EXPENSE,
    val loading: Boolean = true,
    /** 当前类型的**全部**分类（含归档）。 */
    val categories: List<LedgerCategory> = emptyList(),
    val editor: CategoryEditorState? = null,
    val pendingArchiveId: String? = null,
    val banner: ManagementBanner? = null,
) {
    val activeCategories: List<LedgerCategory> get() = categories.filter { !it.isArchived }
    val archivedCategories: List<LedgerCategory> get() = categories.filter { it.isArchived }

    fun category(id: String): LedgerCategory? = categories.firstOrNull { it.id == id }

    /** 正在确认归档的分类；兜底分类不允许归档。 */
    val pendingArchive: LedgerCategory?
        get() = pendingArchiveId?.let { category(it) }?.takeIf { !it.isFallback }
}

/**
 * S07 分类管理的状态与规则。
 *
 * 业务约束（重名含归档、兜底分类不可归档、每类型至少保留一个可用分类、类型不可变更）
 * **由数据层最终裁决**；这里只做即时反馈，并原样展示仓库返回的 [MutationResult.Failure]。
 * 归档不等于删除：历史账单与统计继续引用原分类 ID。
 */
class CategoriesViewModel(
    private val repository: LedgerRepository,
    initialType: TransactionType = TransactionType.EXPENSE,
) : ViewModel() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(CategoriesUiState(type = initialType))
    val state: StateFlow<CategoriesUiState> = _state.asStateFlow()

    init {
        scope.launch {
            _state
                .map { it.type }
                .distinctUntilChanged()
                .collectLatest { type ->
                    repository.observeCategories(type, includeArchived = true).collect { list ->
                        _state.update {
                            it.copy(
                                loading = false,
                                categories = list.sortedWith(
                                    compareBy({ it.sortOrder }, { it.id }),
                                ),
                            )
                        }
                    }
                }
        }
    }

    // ───────────────────────── 分区与新增 ─────────────────────────

    fun onTypeSelected(type: TransactionType) {
        if (_state.value.type == type) return
        _state.update { it.copy(type = type, banner = null, editor = null, pendingArchiveId = null) }
    }

    fun onAddCategory() {
        val type = _state.value.type
        _state.update {
            it.copy(
                banner = null,
                editor = CategoryEditorState(
                    categoryId = null,
                    type = type,
                    name = "",
                    iconKey = defaultIconFor(type),
                ),
            )
        }
    }

    fun onEditCategory(id: String) {
        val category = _state.value.category(id) ?: return
        _state.update {
            it.copy(
                banner = null,
                editor = CategoryEditorState(
                    categoryId = category.id,
                    // 编辑时收支类型原样带回，界面不提供修改入口。
                    type = category.type,
                    name = category.name,
                    iconKey = category.iconKey,
                ),
            )
        }
    }

    fun onEditorNameChanged(text: String) {
        // 不做静默截断：超出 1—12 字符时由 validateName 给出明确原因，用户看得见。
        _state.update { current ->
            current.editor?.let { current.copy(editor = it.copy(name = text, errorMessage = null)) } ?: current
        }
    }

    fun onEditorIconSelected(iconKey: String) {
        if (!CategoryIcons.isValidKey(iconKey)) return
        _state.update { current ->
            current.editor?.let { current.copy(editor = it.copy(iconKey = iconKey, errorMessage = null)) } ?: current
        }
    }

    fun onEditorDismissed() {
        _state.update { if (it.editor?.saving == true) it else it.copy(editor = null) }
    }

    fun onEditorSave() {
        val editor = _state.value.editor ?: return
        if (editor.saving) return
        val name = editor.name.trim()
        validateName(name, editor.type, editor.categoryId)?.let { message ->
            _state.update { it.copy(editor = it.editor?.copy(errorMessage = message)) }
            return
        }
        _state.update { it.copy(editor = it.editor?.copy(saving = true, errorMessage = null)) }

        scope.launch {
            val result = repository.upsertCategory(
                CategoryCommand(
                    id = editor.categoryId,
                    type = editor.type,
                    name = name,
                    iconKey = editor.iconKey,
                ),
            )
            when (result) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        editor = null,
                        banner = ManagementBanner.success(
                            if (editor.isNew) "已新增分类「$name」" else "已保存「$name」",
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    // 数据层是唯一裁决者：失败时保留输入并原样展示原因。
                    it.copy(editor = it.editor?.copy(saving = false, errorMessage = result.error.readableMessage()))
                }
            }
        }
    }

    // ───────────────────────── 排序 ─────────────────────────

    fun onMoveCategoryUp(id: String) = moveCategory(id, delta = -1)

    fun onMoveCategoryDown(id: String) = moveCategory(id, delta = +1)

    fun onReorderActiveCategories(ids: List<String>) {
        val current = _state.value
        val active = current.activeCategories.map { it.id }
        if (ids.size != active.size || ids.toSet() != active.toSet() || ids == active) return
        val all = ids + current.archivedCategories.map { it.id }
        scope.launch {
            when (val result = repository.reorderCategories(current.type, all)) {
                is MutationResult.Success -> _state.update { it.copy(banner = null) }
                is MutationResult.Failure -> _state.update { it.copy(banner = ManagementBanner.error(result.error.readableMessage())) }
            }
        }
    }

    private fun moveCategory(id: String, delta: Int) {
        val current = _state.value
        val ordered = CategoryOrdering.moveWithinSection(current.categories, id, delta) ?: return
        scope.launch {
            when (val result = repository.reorderCategories(current.type, ordered)) {
                is MutationResult.Success -> _state.update { it.copy(banner = null) }
                is MutationResult.Failure -> _state.update {
                    it.copy(banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    // ───────────────────────── 归档 / 恢复 ─────────────────────────

    fun onRequestArchive(id: String) {
        val category = _state.value.category(id) ?: return
        if (category.isFallback) {
            _state.update {
                it.copy(banner = ManagementBanner.error("兜底分类「${category.name}」不能归档"))
            }
            return
        }
        _state.update { it.copy(pendingArchiveId = id, banner = null) }
    }

    fun onCancelArchive() {
        _state.update { it.copy(pendingArchiveId = null) }
    }

    fun onConfirmArchive() {
        val category = _state.value.pendingArchive ?: return
        _state.update { it.copy(pendingArchiveId = null) }
        scope.launch {
            when (val result = repository.setCategoryArchived(category.id, true)) {
                is MutationResult.Success -> _state.update {
                    it.copy(
                        banner = ManagementBanner.success(
                            "已归档「${category.name}」，历史账单与统计不受影响",
                        ),
                    )
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onRestoreCategory(id: String) {
        val category = _state.value.category(id) ?: return
        scope.launch {
            when (val result = repository.setCategoryArchived(category.id, false)) {
                is MutationResult.Success -> _state.update {
                    it.copy(banner = ManagementBanner.success("已恢复「${category.name}」"))
                }

                is MutationResult.Failure -> _state.update {
                    it.copy(banner = ManagementBanner.error(result.error.readableMessage()))
                }
            }
        }
    }

    fun onBannerShown() {
        _state.update { it.copy(banner = null) }
    }

    // ───────────────────────── 校验 ─────────────────────────

    /** 返回 null 表示通过；否则返回可读原因（与数据层文案一致）。 */
    private fun validateName(name: String, type: TransactionType, selfId: String?): String? {
        if (name.length < Limits.MIN_CATEGORY_NAME_LENGTH) return "请输入分类名称"
        if (name.length > Limits.MAX_CATEGORY_NAME_LENGTH) {
            return "分类名称最多 ${Limits.MAX_CATEGORY_NAME_LENGTH} 个字符"
        }
        // 重复检查包含归档分类，避免恢复归档分类时出现歧义。
        val duplicate = _state.value.categories.any {
            it.type == type && it.id != selfId && it.name == name
        }
        return if (duplicate) "同类型下已存在名为「$name」的分类" else null
    }

    override fun onCleared() {
        scope.cancel()
        super.onCleared()
    }

    companion object {
        /** 输入框允许的最大长度（超出部分不进入状态，避免把超长名字提交给数据层）。 */
        const val NAME_INPUT_LIMIT: Int = Limits.MAX_CATEGORY_NAME_LENGTH

        fun defaultIconFor(type: TransactionType): String =
            if (type == TransactionType.INCOME) CategoryIcons.SALARY else CategoryIcons.RESTAURANT
    }
}

/**
 * 分类排序（纯函数，便于单测）。
 *
 * 展示顺序 = 未归档分类（按 sortOrder、id）→ 已归档分类。
 * 上/下移只在本分区内交换；提交给仓库的 id 列表始终是该类型**全部**分类的完整顺序
 * （契约要求 `reorderCategories` 的 ids 与现有分类集合一致）。
 */
object CategoryOrdering {

    /**
     * 展示顺序：**未归档分类在前、已归档在后**，各自内部按 `sortOrder` → `id`。
     * 与页面的「我的分类」+「已归档」两个分区完全一致。
     */
    fun orderedIds(categories: List<LedgerCategory>): List<String> =
        sorted(categories).sortedBy { it.isArchived }.map { it.id }

    fun moveWithinSection(categories: List<LedgerCategory>, id: String, delta: Int): List<String>? {
        if (delta == 0) return null
        val sorted = sorted(categories)
        val target = sorted.firstOrNull { it.id == id } ?: return null
        val section = sorted.filter { it.isArchived == target.isArchived }
        val indexInSection = section.indexOfFirst { it.id == id }
        val targetIndex = indexInSection + delta
        if (indexInSection < 0 || targetIndex !in section.indices) return null

        val reorderedSection = section.toMutableList().apply {
            val moved = removeAt(indexInSection)
            add(targetIndex, moved)
        }
        val active = reorderedSection.filter { !it.isArchived }
        val archived = reorderedSection.filter { it.isArchived }
        val inactive = sorted.filterNot { it.isArchived == target.isArchived }
        val result = (active + inactive.filter { !it.isArchived } + archived + inactive.filter { it.isArchived })
            .map { it.id }
        return result.takeIf { it.size == sorted.size }
    }

    private fun sorted(categories: List<LedgerCategory>): List<LedgerCategory> =
        categories.sortedWith(compareBy({ it.sortOrder }, { it.id }))
}
