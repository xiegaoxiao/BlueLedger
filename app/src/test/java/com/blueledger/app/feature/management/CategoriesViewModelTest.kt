package com.blueledger.app.feature.management

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.contract.LedgerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S07 分类管理 ViewModel 单测。
 *
 * 覆盖：新增、重名（含归档）、名称长度边界、兜底分类保护、归档/恢复、排序、
 * 数据层失败原样展示（不吞掉失败当成功）。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CategoriesViewModelTest {

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
    }

    private fun viewModel(repository: LedgerRepository = harness.repository): CategoriesViewModel {
        val vm = CategoriesViewModel(repository)
        harness.settle()
        return vm
    }

    @Test
    fun `默认参考分类按 sortOrder 展示`() {
        val vm = viewModel()
        assertEquals(Defaults.EXPENSE_CATEGORIES.size, vm.state.value.activeCategories.size)
        assertEquals("cat_expense_food", vm.state.value.activeCategories.first().id)

        vm.onTypeSelected(TransactionType.INCOME)
        harness.settle()
        assertEquals(Defaults.INCOME_CATEGORIES.size, vm.state.value.activeCategories.size)
        assertEquals("cat_income_salary", vm.state.value.activeCategories.first().id)
    }

    @Test
    fun `拖动排序一次提交完整列表且不修改账单`() {
        val vm = viewModel()
        val transactions = runBlocking { harness.repository.exportConsistentSnapshot() }.transactions
        val ids = vm.state.value.activeCategories.map { it.id }.toMutableList()
        val first = ids.removeAt(0)
        ids.add(3, first)
        vm.onReorderActiveCategories(ids)
        harness.settle()
        assertEquals(ids, vm.state.value.activeCategories.map { it.id })
        assertEquals(transactions, runBlocking { harness.repository.exportConsistentSnapshot() }.transactions)
    }

    @Test
    fun `拖动排序拒绝缺失或重复分类并保留归档集合`() {
        val vm = viewModel()
        vm.onRequestArchive("cat_expense_shopping")
        vm.onConfirmArchive()
        harness.settle()
        val active = vm.state.value.activeCategories.map { it.id }
        val archived = vm.state.value.archivedCategories.map { it.id }
        vm.onReorderActiveCategories(active.drop(1))
        vm.onReorderActiveCategories(active.dropLast(1) + active.first())
        harness.settle()
        assertEquals(active, vm.state.value.activeCategories.map { it.id })
        vm.onReorderActiveCategories(active.reversed())
        harness.settle()
        assertEquals(active.reversed(), vm.state.value.activeCategories.map { it.id })
        assertEquals(archived, vm.state.value.archivedCategories.map { it.id })
    }

    @Test
    fun `新增分类写入仓库并在列表末尾`() {
        val vm = viewModel()
        vm.onAddCategory()
        vm.onEditorNameChanged("园艺")
        vm.onEditorIconSelected(com.blueledger.app.core.model.CategoryIcons.STUDY)
        vm.onEditorSave()
        harness.settle()

        val created = harness.categories(TransactionType.EXPENSE).firstOrNull { it.name == "园艺" }
        assertNotNull("新增分类必须真的写入仓库", created)
        assertEquals(com.blueledger.app.core.model.CategoryIcons.STUDY, created!!.iconKey)
        assertEquals(Defaults.EXPENSE_CATEGORIES.maxOf { it.sortOrder } + 10, created.sortOrder)
        assertNull(vm.state.value.editor)
        assertFalse(vm.state.value.banner!!.isError)
        assertTrue(vm.state.value.banner!!.message.contains("已新增分类「园艺」"))
    }

    @Test
    fun `新增分类名称 trim 后保存`() {
        val vm = viewModel()
        vm.onAddCategory()
        vm.onEditorNameChanged("  自定义零食  ")
        vm.onEditorSave()
        harness.settle()
        assertNotNull(harness.categories(TransactionType.EXPENSE).firstOrNull { it.name == "自定义零食" })
    }

    @Test
    fun `重名被拒绝且包含已归档分类`() {
        runBlocking { harness.repository.setCategoryArchived("cat_expense_transport", true) }
        harness.settle()

        val vm = viewModel()
        vm.onAddCategory()
        vm.onEditorNameChanged("交通")
        vm.onEditorSave()
        harness.settle()

        val editor = vm.state.value.editor
        assertNotNull("重名时弹层必须保持打开，输入不丢", editor)
        assertEquals("同类型下已存在名为「交通」的分类", editor!!.errorMessage)
        assertEquals(
            "被拒绝时不得写入新分类",
            Defaults.EXPENSE_CATEGORIES.size,
            harness.categories(TransactionType.EXPENSE).size,
        )
    }

    @Test
    fun `名称长度边界 12 通过 13 被拒绝 空白被拒绝`() {
        val vm = viewModel()
        val twelve = "一二三四五六七八九十十一"
        assertEquals(12, twelve.length)

        vm.onAddCategory()
        vm.onEditorNameChanged(twelve)
        vm.onEditorSave()
        harness.settle()
        assertNotNull(harness.categories(TransactionType.EXPENSE).firstOrNull { it.name == twelve })

        vm.onAddCategory()
        vm.onEditorNameChanged(twelve + "三")
        vm.onEditorSave()
        harness.settle()
        assertEquals("分类名称最多 12 个字符", vm.state.value.editor?.errorMessage)

        vm.onEditorNameChanged("   ")
        vm.onEditorSave()
        harness.settle()
        assertEquals("请输入分类名称", vm.state.value.editor?.errorMessage)
    }

    @Test
    fun `兜底分类不能归档`() {
        val vm = viewModel()
        vm.onRequestArchive("cat_expense_other")
        harness.settle()

        assertNull("兜底分类不应进入归档确认", vm.state.value.pendingArchiveId)
        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("兜底分类「其他」不能归档", vm.state.value.banner!!.message)
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_other" }.isArchived)
    }

    @Test
    fun `归档需确认且恢复后回到可用列表`() {
        val vm = viewModel()
        vm.onRequestArchive("cat_expense_food")
        assertNotNull(vm.state.value.pendingArchive)

        vm.onConfirmArchive()
        harness.settle()
        assertTrue(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_food" }.isArchived)
        assertFalse(vm.state.value.banner!!.isError)
        assertEquals(Defaults.EXPENSE_CATEGORIES.size - 1, vm.state.value.activeCategories.size)
        assertEquals(1, vm.state.value.archivedCategories.size)

        vm.onRestoreCategory("cat_expense_food")
        harness.settle()
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_food" }.isArchived)
        assertEquals(Defaults.EXPENSE_CATEGORIES.size, vm.state.value.activeCategories.size)
    }

    @Test
    fun `取消归档确认不会改变数据`() {
        val vm = viewModel()
        vm.onRequestArchive("cat_expense_food")
        vm.onCancelArchive()
        harness.settle()
        assertNull(vm.state.value.pendingArchiveId)
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_food" }.isArchived)
    }

    @Test
    fun `排序上移下移只在本分区内生效`() {
        val vm = viewModel()
        vm.onMoveCategoryDown("cat_expense_food")
        harness.settle()
        assertEquals(
            listOf("cat_expense_shopping", "cat_expense_food"),
            harness.categories(TransactionType.EXPENSE).take(2).map { it.id },
        )

        vm.onMoveCategoryUp("cat_expense_food")
        harness.settle()
        assertEquals(
            listOf("cat_expense_food", "cat_expense_shopping"),
            harness.categories(TransactionType.EXPENSE).take(2).map { it.id },
        )

        // 第一位再上移：无变化，也不报错。
        vm.onMoveCategoryUp("cat_expense_food")
        harness.settle()
        assertNull(vm.state.value.banner)
        assertEquals(
            listOf("cat_expense_food", "cat_expense_shopping"),
            harness.categories(TransactionType.EXPENSE).take(2).map { it.id },
        )
    }

    @Test
    fun `归档分类在展示顺序中排到末尾且自身顺序保持`() {
        val vm = viewModel()
        vm.onMoveCategoryDown("cat_expense_food")
        harness.settle()
        vm.onRequestArchive("cat_expense_food")
        vm.onConfirmArchive()
        harness.settle()

        // 展示顺序 = 未归档（按 sortOrder）→ 已归档；归档项排末尾。
        val displayOrder = CategoryOrdering.orderedIds(vm.state.value.categories)
        assertEquals("cat_expense_food", displayOrder.last())
        assertEquals(
            listOf("cat_expense_shopping", "cat_expense_daily"),
            displayOrder.take(2),
        )
        // 仓库数据：餐饮已归档，且不再出现在可用列表里。
        assertTrue(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_food" }.isArchived)
        assertFalse(
            harness.categories(TransactionType.EXPENSE, includeArchived = false)
                .any { it.id == "cat_expense_food" },
        )
    }

    @Test
    fun `数据层失败必须原样展示而不是当作成功`() {
        harness.repository.failNextWrite = LedgerError.Storage("保存失败，内容已保留，请重试")
        val vm = viewModel()
        vm.onAddCategory()
        vm.onEditorNameChanged("园艺")
        vm.onEditorSave()
        harness.settle()

        val editor = vm.state.value.editor
        assertNotNull("失败时必须保留输入与弹层", editor)
        assertEquals("保存失败，内容已保留，请重试", editor!!.errorMessage)
        assertFalse("失败不得显示成功", vm.state.value.banner?.isError == false)
        assertNull(harness.categories(TransactionType.EXPENSE).firstOrNull { it.name == "园艺" })
    }

    @Test
    fun `归档失败时分类保持可用并说明原因`() {
        harness.repository.failNextWrite =
            LedgerError.Conflict(ValidationCode.CATEGORY_LAST_ACTIVE, "每种收支类型至少保留一个可用分类")
        val vm = viewModel()
        vm.onRequestArchive("cat_expense_food")
        vm.onConfirmArchive()
        harness.settle()

        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("每种收支类型至少保留一个可用分类", vm.state.value.banner!!.message)
        assertFalse(harness.categories(TransactionType.EXPENSE).first { it.id == "cat_expense_food" }.isArchived)
    }

    @Test
    fun `编辑分类不提供改类型入口且保留原类型`() {
        val vm = viewModel()
        vm.onTypeSelected(TransactionType.INCOME)
        harness.settle()
        vm.onEditCategory("cat_income_gift")
        // 编辑收入分类时页面分段可停在任意位置，但编辑器类型必须来自原分类。
        assertEquals(TransactionType.INCOME, vm.state.value.editor!!.type)
        assertEquals("礼金", vm.state.value.editor!!.name)
    }

    @Test
    fun `重命名后历史关联按 ID 保留`() {
        val transactionId = harness.seedExpense(amountCent = 2850L, month = ManagementHarness.OCT_2026)
        val vm = viewModel()
        vm.onEditCategory("cat_expense_food")
        vm.onEditorNameChanged("吃饭")
        vm.onEditorSave()
        harness.settle()

        val row = harness.transactions().first { it.id == transactionId }
        assertEquals("吃饭", row.categoryName)
        assertEquals("cat_expense_food", row.transaction.categoryId)
    }
}
