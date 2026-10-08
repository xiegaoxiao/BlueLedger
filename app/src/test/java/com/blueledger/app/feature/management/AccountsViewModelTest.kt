package com.blueledger.app.feature.management

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.TransactionType
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
 * S09 账户管理 ViewModel 单测。
 *
 * 覆盖：派生余额与月份无关、正/零/负期初、重名、最后账户保护、
 * 归档默认账户必须先指定新默认、恢复归档重名冲突、期初修改不产生收入、失败展示。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccountsViewModelTest {

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
    }

    private fun viewModel(repository: LedgerRepository = harness.repository): AccountsViewModel {
        val vm = AccountsViewModel(repository)
        harness.settle()
        return vm
    }

    @Test
    fun `默认只有一个账户且为默认账户`() {
        val vm = viewModel()
        assertEquals(1, vm.state.value.activeAccounts.size)
        assertEquals("acc_default", vm.state.value.defaultAccountId)
        assertEquals(0L, vm.state.value.activeAccounts.first().balanceCent)
    }

    @Test
    fun `派生余额等于期初加收入减支出且与月份无关`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        // 九月收入 5000.00、十月支出 100.00（跨月，用来证明余额不随月份筛选变化）。
        harness.seedIncome(amountCent = 500_000L, month = ManagementHarness.SEP_2026, accountId = walletId)
        harness.seedExpense(amountCent = 10_000L, month = ManagementHarness.OCT_2026, accountId = walletId)
        harness.settle()

        val wallet = viewModel().state.value.account(walletId)!!
        assertEquals(20_000L + 500_000L - 10_000L, wallet.balanceCent)
        assertEquals(510_000L, wallet.balanceCent)
        assertEquals(2, wallet.transactionCount)
        assertEquals(20_000L, wallet.openingBalanceCent)
        assertEquals(490_000L, wallet.netChangeCent)
    }

    @Test
    fun `全部账户余额汇总只统计未归档账户`() {
        harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        val oldCardId = harness.seedAccount("旧卡", AccountKind.BANK_CARD, openingBalanceCent = 999_999L)
        val vm = viewModel()

        assertEquals("归档前汇总包含全部账户", 20_000L + 999_999L, vm.state.value.totalActiveBalanceCent)

        vm.onRequestArchive(oldCardId)
        vm.onConfirmArchive()
        harness.settle()
        assertEquals("归档账户不计入汇总", 20_000L, vm.state.value.totalActiveBalanceCent)
    }

    @Test
    fun `新增账户支持负期初且不生成收入账单`() {
        val before = harness.monthSummary(ManagementHarness.OCT_2026)
        val vm = viewModel()

        vm.onAddAccount()
        vm.onNameChanged("信用卡")
        vm.onKindSelected(AccountKind.BANK_CARD)
        vm.onOpeningChanged("-200.00")
        vm.onEditorSave()
        harness.settle()

        val created = harness.accounts().firstOrNull { it.account.name == "信用卡" }
        assertNotNull("账户必须真的写入仓库", created)
        assertEquals(-20_000L, created!!.account.openingBalanceCent)
        assertEquals(AccountKind.BANK_CARD, created.account.kind)
        assertEquals("负期初不改变账户余额推导", -20_000L, created.balanceCent)

        val after = harness.monthSummary(ManagementHarness.OCT_2026)
        assertEquals("期初余额不是收入", before, after)
        assertEquals(0, after.count)
    }

    @Test
    fun `账户名重复被拒绝名称长度边界生效`() {
        harness.seedAccount("钱包")
        val vm = viewModel()

        vm.onAddAccount()
        vm.onNameChanged("钱包")
        vm.onEditorSave()
        harness.settle()
        assertEquals("已存在名为「钱包」的账户", vm.state.value.editor?.errorMessage)

        vm.onNameChanged("")
        vm.onEditorSave()
        harness.settle()
        assertEquals("请输入账户名称", vm.state.value.editor?.errorMessage)

        vm.onNameChanged("一二三四五六七八九十一二三四五六七八九十一")
        vm.onEditorSave()
        harness.settle()
        assertEquals("账户名称最多 20 个字符", vm.state.value.editor?.errorMessage)

        vm.onNameChanged("一二三四五六七八九十一二三四五六七八九十")
        vm.onEditorSave()
        harness.settle()
        assertNotNull("20 字符应通过", harness.accounts().firstOrNull { it.account.name.length == 20 })
    }

    @Test
    fun `最后一个可用账户不能归档`() {
        val vm = viewModel()
        vm.onRequestArchive("acc_default")
        harness.settle()

        assertNull("只有一个账户时不应进入确认", vm.state.value.pendingArchiveId)
        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("至少保留一个未归档账户", vm.state.value.banner!!.message)
        assertFalse(harness.account("acc_default")!!.account.isArchived)
    }

    @Test
    fun `归档普通账户不需要替换默认账户`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        val vm = viewModel()

        vm.onRequestArchive(walletId)
        assertNotNull(vm.state.value.pendingArchive)
        assertNull("非默认账户不需要替换", vm.state.value.pendingReplacementId)

        vm.onConfirmArchive()
        harness.settle()
        assertTrue(harness.account(walletId)!!.account.isArchived)
        assertEquals("acc_default", harness.settings().defaultAccountId)
    }

    @Test
    fun `归档默认账户前必须指定新的默认账户`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        val vm = viewModel()

        vm.onRequestArchive("acc_default")
        assertTrue("默认账户归档必须进入替换流程", vm.state.value.isPendingDefault)
        assertEquals("已预选唯一候选：$walletId", walletId, vm.state.value.pendingReplacementId)
        assertTrue(vm.state.value.canConfirmArchive)

        vm.onConfirmArchive()
        harness.settle()
        assertTrue(harness.account("acc_default")!!.account.isArchived)
        assertEquals("归档后默认账户必须换成有效账户", walletId, harness.settings().defaultAccountId)
    }

    @Test
    fun `数据层在没有替换账户时拒绝归档默认账户`() {
        harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.settle()
        val result = runBlocking {
            harness.repository.setAccountArchived("acc_default", true, replacementDefaultId = null)
        }
        assertTrue("数据层必须兜底拒绝：$result", result is MutationResult.Failure)
        assertEquals(
            com.blueledger.app.core.model.ValidationCode.ACCOUNT_ARCHIVE_REQUIRES_REPLACEMENT,
            (result as MutationResult.Failure).error.let {
                (it as com.blueledger.app.core.model.LedgerError.Conflict).code
            },
        )
        assertFalse(harness.account("acc_default")!!.account.isArchived)
    }

    @Test
    fun `归档前替换账户未选时不会静默失败`() {
        harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        val vm = viewModel()
        vm.onRequestArchive("acc_default")
        // 手动清掉预选：模拟用户未选择任何替换账户的情况（界面会出现该状态）。
        vm.onReplacementSelected("")
        vm.onConfirmArchive()
        harness.settle()

        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("归档默认账户前请先选择新的默认账户", vm.state.value.banner!!.message)
        assertFalse(harness.account("acc_default")!!.account.isArchived)
    }

    @Test
    fun `恢复归档重名冲突必须先重命名`() {
        val firstId = harness.seedAccount("备用金")
        runBlocking { harness.repository.setAccountArchived(firstId, true, null) }
        // 归档后可以新建同名账户（唯一性只在未归档范围内）。
        val secondId = harness.seedAccount("备用金")
        harness.settle()

        val vm = viewModel()
        vm.onRestoreAccount(firstId)
        harness.settle()

        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("已有同名账户「备用金」，请先重命名再恢复", vm.state.value.banner!!.message)
        assertTrue(harness.account(firstId)!!.account.isArchived)
        assertFalse(harness.account(secondId)!!.account.isArchived)
    }

    @Test
    fun `设置默认账户写入设置且拒绝归档账户`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        val vm = viewModel()

        vm.onSetDefaultAccount(walletId)
        harness.settle()
        assertEquals(walletId, harness.settings().defaultAccountId)

        // 钱包此时是默认账户：归档它必须指定新的默认账户（数据层约束）。
        runBlocking {
            harness.repository.setAccountArchived(walletId, true, replacementDefaultId = "acc_default")
        }
        harness.settle()
        assertTrue(harness.account(walletId)!!.account.isArchived)

        vm.onSetDefaultAccount(walletId)
        harness.settle()
        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("该账户不可用，无法设为默认账户", vm.state.value.banner!!.message)
        assertEquals("acc_default", harness.settings().defaultAccountId)
    }

    @Test
    fun `编辑期初余额展示修改前后余额且不新增收入`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.seedExpense(amountCent = 5_000L, month = ManagementHarness.OCT_2026, accountId = walletId)
        harness.settle()
        val summaryBefore = harness.monthSummary(ManagementHarness.OCT_2026)

        val vm = viewModel()
        vm.onEditAccount(walletId)
        assertEquals(15_000L, vm.state.value.editor!!.balanceBeforeCent)

        vm.onOpeningChanged("-185.00")
        // 期初 -185.00 + 已发生净变动 -50.00 = -235.00
        assertEquals(-23_500L, vm.state.value.editor!!.balanceAfterCent)

        vm.onEditorSave()
        harness.settle()
        assertEquals(-18_500L, harness.account(walletId)!!.account.openingBalanceCent)
        assertEquals(-23_500L, harness.account(walletId)!!.balanceCent)
        assertEquals("修改期初不改变月收支汇总", summaryBefore, harness.monthSummary(ManagementHarness.OCT_2026))
    }

    @Test
    fun `数据层失败时账户弹层保留输入并展示原因`() {
        harness.repository.failNextWrite = LedgerError.Storage("保存失败，内容已保留，请重试")
        val vm = viewModel()
        vm.onAddAccount()
        vm.onNameChanged("钱包")
        vm.onEditorSave()
        harness.settle()

        assertEquals("保存失败，内容已保留，请重试", vm.state.value.editor?.errorMessage)
        assertNull(harness.accounts().firstOrNull { it.account.name == "钱包" })
        assertNull(vm.state.value.banner?.takeIf { !it.isError })
    }

    @Test
    fun `期初余额超限被拒绝`() {
        val vm = viewModel()
        vm.onAddAccount()
        vm.onNameChanged("超大账户")
        vm.onOpeningChanged("10000000")
        vm.onEditorSave()
        harness.settle()
        assertEquals("初始余额不能超过 9,999,999.99 元", vm.state.value.editor?.errorMessage)
        assertNull(harness.accounts().firstOrNull { it.account.name == "超大账户" })
    }

    @Test
    fun `归档账户不出现在可用列表但历史账单仍参与统计`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.seedExpense(amountCent = 5_000L, month = ManagementHarness.OCT_2026, accountId = walletId)
        val summaryBefore = harness.monthSummary(ManagementHarness.OCT_2026)
        val vm = viewModel()

        vm.onRequestArchive(walletId)
        vm.onConfirmArchive()
        harness.settle()

        assertFalse(vm.state.value.activeAccounts.any { it.account.id == walletId })
        assertTrue(vm.state.value.archivedAccounts.any { it.account.id == walletId })
        assertFalse(harness.accounts(includeArchived = false).any { it.account.id == walletId })
        // 归档不排除历史有效账单：余额与月汇总都不变。
        assertEquals(15_000L, harness.account(walletId)!!.balanceCent)
        assertEquals(summaryBefore, harness.monthSummary(ManagementHarness.OCT_2026))
    }
}
