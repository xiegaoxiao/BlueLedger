package com.blueledger.app.feature.entry

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 记账 ViewModel 的状态与规则单测（不渲染 UI，验证业务规则而非像素）。
 *
 * 覆盖：默认值、类型切换、日期未来拒绝、备注上限、两套键盘互斥、
 * 连续记账清空规则、账户默认顺序、编辑模式缺失记录。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EntryViewModelTest {

    private lateinit var harness: EntryTestHarness

    @Before
    fun setUp() {
        harness = EntryTestHarness()
    }

    private fun viewModel(
        editTransactionId: String? = null,
        initialType: TransactionType? = null,
        initialCategoryId: String? = null,
    ): EntryViewModel {
        val vm = EntryViewModel(
            repository = harness.repository,
            clock = harness.clock,
            editTransactionId = editTransactionId,
            initialType = initialType,
            initialCategoryId = initialCategoryId,
        )
        idleMainLooper()
        return vm
    }

    @Test
    fun `新增模式默认支出与设备今日`() {
        val vm = viewModel()
        val state = vm.state.value
        assertFalse(state.isEditMode)
        assertEquals(TransactionType.EXPENSE, state.type)
        assertEquals(LocalDate.of(2026, 10, 7), state.occurredOn)
        assertEquals(LocalDate.of(2026, 10, 7), state.today)
        assertTrue(state.amount.isEmpty)
        assertEquals("0.00", state.amount.displayText)
        assertEquals(com.blueledger.app.core.model.Defaults.EXPENSE_CATEGORIES.size, state.categories.size)
    }

    @Test
    fun `账户默认使用默认账户`() {
        val vm = viewModel()
        assertEquals("acc_default", vm.state.value.selectedAccountId)
    }

    @Test
    fun `账户默认优先最近一次有效使用`() {
        runBlocking {
            val created = harness.repository.upsertAccount(
                AccountCommand(name = "钱包", kind = AccountKind.CASH, openingBalanceCent = 20000L),
            )
            val walletId = (created as MutationResult.Success).id!!
            harness.repository.createTransaction(
                TransactionDraft(
                    type = TransactionType.EXPENSE,
                    amountCent = 100L,
                    categoryId = "cat_expense_food",
                    accountId = walletId,
                    occurredOn = LocalDate.of(2026, 10, 5),
                ),
                requestId = "last-used",
            )
        }
        val vm = viewModel()
        assertEquals("钱包", vm.state.value.selectedAccount?.account?.name)
    }

    @Test
    fun `首页快捷分类在新增模式预选`() {
        val vm = viewModel(initialCategoryId = "cat_expense_transport")
        assertEquals("cat_expense_transport", vm.state.value.selectedCategoryId)
        assertEquals("交通", vm.state.value.selectedCategory?.name)
    }

    @Test
    fun `切换类型清空分类但保留金额与备注`() {
        val vm = viewModel(initialCategoryId = "cat_expense_food")
        vm.onDigit('8')
        vm.onNoteChanged("午饭")
        vm.onTypeSelected(TransactionType.INCOME)

        val state = vm.state.value
        assertEquals(TransactionType.INCOME, state.type)
        assertNull(state.selectedCategoryId)
        assertEquals("8", state.amount.text)
        assertEquals("午饭", state.note)
        assertEquals(com.blueledger.app.core.model.Defaults.INCOME_CATEGORIES.size, state.categories.size)
        assertTrue(state.categories.all { it.type == TransactionType.INCOME })
    }

    @Test
    fun `未来日期被拒绝并给出提示`() {
        val vm = viewModel()
        vm.onDateSelected(LocalDate.of(2026, 10, 8))
        assertEquals(LocalDate.of(2026, 10, 7), vm.state.value.occurredOn)
        assertEquals("暂不支持记录未来日期", vm.state.value.dateError)

        vm.onDateSelected(LocalDate.of(2026, 9, 30))
        assertEquals(LocalDate.of(2026, 9, 30), vm.state.value.occurredOn)
        assertNull(vm.state.value.dateError)
    }

    @Test
    fun `备注最多 200 字符并显示剩余字数`() {
        val vm = viewModel()
        vm.onNoteChanged("字".repeat(210))
        assertEquals(200, vm.state.value.note.length)
        assertTrue(vm.state.value.showNoteCounter)
        assertEquals(0, vm.state.value.noteRemaining)

        vm.onNoteChanged("字".repeat(10))
        assertFalse(vm.state.value.showNoteCounter)
    }

    @Test
    fun `备注聚焦隐藏数字键盘失焦恢复`() {
        val vm = viewModel()
        assertTrue(vm.state.value.numericKeyboardVisible)

        vm.onNoteFocusChanged(true)
        assertFalse(vm.state.value.numericKeyboardVisible)

        vm.onNoteFocusChanged(false)
        assertTrue(vm.state.value.numericKeyboardVisible)
    }

    @Test
    fun `未修改时不认为有未保存变化`() {
        val vm = viewModel(initialCategoryId = "cat_expense_food")
        assertFalse(vm.hasUnsavedChanges())

        vm.onDigit('1')
        assertTrue(vm.hasUnsavedChanges())

        vm.onDelete()
        assertFalse(vm.hasUnsavedChanges())
    }

    @Test
    fun `编辑模式缺失记录给出专门状态`() {
        val vm = viewModel(editTransactionId = "not-exist")
        assertTrue(vm.state.value.missingTransaction)
        assertFalse(vm.state.value.loading)
    }

    @Test
    fun `编辑模式加载原值且修改类型后必须重选分类`() {
        val id = harness.seed(amountCent = 1250L, note = "旧备注")
        val vm = viewModel(editTransactionId = id)

        val loaded = vm.state.value
        assertTrue(loaded.isEditMode)
        assertEquals("12.50", loaded.amount.text)
        assertEquals(1250L, loaded.amountCents)
        assertEquals("cat_expense_food", loaded.selectedCategoryId)
        assertEquals("旧备注", loaded.note)
        assertEquals(LocalDate.of(2026, 10, 6), loaded.occurredOn)
        assertEquals(id, loaded.transactionId)
        assertFalse(vm.hasUnsavedChanges())

        vm.onTypeSelected(TransactionType.INCOME)
        assertNull(vm.state.value.selectedCategoryId)
        assertEquals("12.50", vm.state.value.amount.text)
        assertEquals("旧备注", vm.state.value.note)
    }

    @Test
    fun `连续记账保存后保留类型日期账户并清空金额分类备注`() {
        val vm = viewModel()
        vm.onDigit('1')
        vm.onDigit('2')
        vm.onDecimalPoint()
        vm.onDigit('5')
        vm.onDigit('0')
        vm.onNoteChanged("午饭")
        vm.onCategorySelected("cat_expense_food")
        idleMainLooper()

        vm.onSaveAndNew()
        idleMainLooper()
        idleMainLooper()

        val state = vm.state.value
        assertEquals(1250L, harness.transactions().first().amountCent)
        assertEquals("", state.amount.text)
        assertNull(state.selectedCategoryId)
        assertEquals("", state.note)
        assertEquals(TransactionType.EXPENSE, state.type)
        assertEquals("acc_default", state.selectedAccountId)
        assertEquals("已记下 ¥12.50", state.savedMessage)
        assertTrue(state.numericKeyboardVisible)
    }

    @Test
    fun `保存失败保留输入与重试提示`() {
        val vm = viewModel()
        vm.onDigit('5')
        vm.onCategorySelected("cat_expense_food")
        harness.repository.failNextWrite = com.blueledger.app.core.model.LedgerError.Storage("磁盘写入失败")

        vm.onSave()
        idleMainLooper()
        idleMainLooper()

        val state = vm.state.value
        assertEquals("5", state.amount.text)
        assertEquals("cat_expense_food", state.selectedCategoryId)
        assertEquals("磁盘写入失败", state.saveError)
        assertTrue(harness.transactions().isEmpty())
    }
}
