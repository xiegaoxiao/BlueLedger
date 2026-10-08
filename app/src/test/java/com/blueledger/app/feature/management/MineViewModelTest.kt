package com.blueledger.app.feature.management

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.LedgerError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * S10 我的与设置 ViewModel 单测。
 *
 * 重点：金额隐藏是**全局设置**（写入仓库、由 observeSettings 回流），
 * 默认账户切换、备份状态真实性、失败不得吞掉。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MineViewModelTest {

    private lateinit var harness: ManagementHarness

    @Before
    fun setUp() {
        harness = ManagementHarness()
    }

    private fun viewModel(repository: LedgerRepository = harness.repository): MineViewModel {
        val vm = MineViewModel(repository, harness.clock)
        harness.settle()
        return vm
    }

    @Test
    fun `金额隐藏是全局设置不是页面本地开关`() {
        val vm = viewModel()
        assertFalse(vm.state.value.hideAmounts)

        vm.onHideAmountsToggled(true)
        harness.settle()

        assertTrue("状态必须来自仓库设置回流", vm.state.value.hideAmounts)
        assertTrue("必须真的写入仓库设置（首页/统计/账户共用）", harness.settings().hideAmounts)
        assertTrue(vm.state.value.banner!!.message.contains("已开启金额隐藏"))

        vm.onHideAmountsToggled(false)
        harness.settle()
        assertFalse(harness.settings().hideAmounts)
        assertTrue(vm.state.value.banner!!.message.contains("已关闭金额隐藏"))
    }

    @Test
    fun `金额隐藏写入失败时设置保持原值并说明原因`() {
        harness.repository.failNextWrite = LedgerError.Storage("写入失败，请重试")
        val vm = viewModel()

        vm.onHideAmountsToggled(true)
        harness.settle()

        assertFalse("失败不得假装已切换", harness.settings().hideAmounts)
        assertFalse(vm.state.value.hideAmounts)
        assertTrue(vm.state.value.banner!!.isError)
        assertEquals("写入失败，请重试", vm.state.value.banner!!.message)
    }

    @Test
    fun `重复点击同一状态不会重复写入`() {
        val vm = viewModel()
        vm.onHideAmountsToggled(true)
        harness.settle()
        vm.onBannerShown()
        // 已经是 true，再点一次 true：既不应写入，也不应产生新提示。
        vm.onHideAmountsToggled(true)
        harness.settle()
        assertTrue(harness.settings().hideAmounts)
        assertNull(vm.state.value.banner)
    }

    @Test
    fun `默认账户显示名称并可切换`() {
        val walletId = harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        val vm = viewModel()
        assertEquals("默认账户", vm.state.value.defaultAccountName)

        vm.onRequestAccountPicker()
        assertTrue(vm.state.value.showAccountPicker)

        vm.onDefaultAccountSelected(walletId)
        harness.settle()
        assertEquals(walletId, harness.settings().defaultAccountId)
        assertEquals("钱包", vm.state.value.defaultAccountName)
        assertFalse(vm.state.value.showAccountPicker)
        assertTrue(vm.state.value.banner!!.message.contains("已将「钱包」设为默认账户"))
    }

    @Test
    fun `默认账户切换失败保留原默认并提示原因`() {
        harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.repository.failNextWrite = LedgerError.Storage("写入失败，请重试")
        val vm = viewModel()
        val before = harness.settings().defaultAccountId

        vm.onDefaultAccountSelected("acc_wallet_missing")
        harness.settle()

        assertEquals(before, harness.settings().defaultAccountId)
        assertTrue(vm.state.value.banner!!.isError)
    }

    @Test
    fun `最近备份时间来自仓库真实记录`() {
        val vm = viewModel()
        assertNull("没有成功备份时必须显示为未备份", vm.state.value.backupRecord.succeededAt)

        runBlocking {
            harness.repository.recordBackupSuccess(
                at = ManagementHarness.CLOCK_INSTANT,
                fileName = "blueledger-2026-10-07.blueledger.json",
            )
        }
        harness.settle()
        assertEquals(ManagementHarness.CLOCK_INSTANT, vm.state.value.backupRecord.succeededAt)
        assertEquals("blueledger-2026-10-07.blueledger.json", vm.state.value.backupRecord.fileName)
    }

    @Test
    fun `概览数字来自真实账本`() {
        harness.seedAccount("钱包", AccountKind.E_WALLET, openingBalanceCent = 20_000L)
        harness.seedExpense(amountCent = 2850L, month = ManagementHarness.OCT_2026)
        harness.seedIncome(amountCent = 500_000L, month = ManagementHarness.SEP_2026)
        runBlocking { harness.repository.setBudget(ManagementHarness.OCT_2026, 200_000L) }
        harness.settle()

        val state = viewModel().state.value
        assertEquals(2, state.transactionCount)
        assertEquals(2, state.activeAccounts.size)
        assertEquals(com.blueledger.app.core.model.Defaults.CATEGORIES.size, state.categoryCount)
        assertEquals(200_000L, state.currentMonthBudgetCent)
        assertEquals(ManagementHarness.OCT_2026, state.currentMonth)
    }

    @Test
    fun `不再有登录入口相关状态`() {
        // 页面不提供登录/会员入口，ViewModel 也不持有任何账号态。
        val state = viewModel().state.value
        assertTrue(state.activeAccounts.none { it.account.name.contains("登录") })
        assertFalse(state.hideAmounts)
    }
}
