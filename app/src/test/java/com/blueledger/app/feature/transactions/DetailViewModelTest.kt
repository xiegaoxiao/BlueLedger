package com.blueledger.app.feature.transactions

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.blueledger.app.acceptance.Expect
import com.blueledger.app.acceptance.Fx
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * S04 账单详情 ViewModel 测试：字段来源、缺失状态、删除与撤销凭据。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DetailViewModelTest {

    private lateinit var harness: TransactionsTestHarness

    @Before
    fun setUp() {
        harness = TransactionsTestHarness()
    }

    @Test
    fun `详情字段与关联名称来自仓库`() {
        val seeded = harness.seedAcceptance()
        val state = harness.detailViewModel(seeded.transactionId("T9")).state.value

        assertFalse(state.loading)
        val transaction = requireNotNull(state.transaction)
        assertEquals(2_850L, transaction.amountCent)
        assertEquals(TransactionType.EXPENSE, transaction.type)
        assertEquals("餐饮", state.categoryName)
        assertEquals(Fx.ACCOUNT_B_NAME, state.accountName)
        assertEquals(LocalDate.of(2026, 10, 7), transaction.occurredOn)
        assertEquals("晚餐", transaction.note)
        assertFalse(state.isIncome)
        assertFalse(state.isMissing)
    }

    @Test
    fun `收入账单的详情类型正确`() {
        val seeded = harness.seedAcceptance()
        val state = harness.detailViewModel(seeded.transactionId("T8")).state.value

        assertTrue(state.isIncome)
        assertEquals("兼职", state.categoryName)
        assertEquals(80_000L, requireNotNull(state.transaction).amountCent)
    }

    @Test
    fun `无备注时为空字符串而不是 null`() {
        harness.initialize()
        val id = harness.addTransaction(note = "", requestId = "detail-no-note")
        val state = harness.detailViewModel(id).state.value

        assertEquals("", requireNotNull(state.transaction).note)
    }

    @Test
    fun `记录不存在时进入缺失状态且不显示旧内容`() {
        harness.seedAcceptance()
        val state = harness.detailViewModel("no-such-transaction").state.value

        assertTrue(state.isMissing)
        assertNull(state.transaction)
        assertFalse(state.loading)
    }

    @Test
    fun `被软删除的账单进入缺失状态`() {
        val seeded = harness.seedAcceptance()
        val id = seeded.transactionId("T9")
        runBlocking { harness.repository.softDeleteTransaction(id) }

        val state = harness.detailViewModel(id).state.value
        assertTrue(state.isMissing)
        assertNull(state.transaction)
    }

    @Test
    fun `编辑保留 createdAt 并让最近修改时间可区分`() {
        harness.initialize()
        val id = harness.addTransaction(note = "原始备注", requestId = "detail-edit")
        val created = runBlocking { harness.repository.observeTransaction(id).first() }!!

        // 推进可注入时钟以后再编辑，updatedAt 才会与 createdAt 不同。
        harness.clock.setInstant(harness.clock.now().plusSeconds(3_600))
        val result = runBlocking {
            harness.repository.updateTransaction(
                id,
                TransactionDraft(
                    type = TransactionType.EXPENSE,
                    amountCent = 2_500L,
                    categoryId = "cat_expense_food",
                    accountId = "acc_default",
                    occurredOn = LocalDate.of(2026, 10, 6),
                    note = "改过的备注",
                ),
            )
        }
        assertTrue(result is SaveResult.Success)

        val state = harness.detailViewModel(id).state.value
        val transaction = requireNotNull(state.transaction)
        assertEquals("编辑不改变创建时间", created.createdAt, transaction.createdAt)
        assertNotEquals(transaction.createdAt, transaction.updatedAt)
        assertTrue("更新过时要能显示最近修改时间", state.hasBeenUpdated)
        assertEquals("改过的备注", transaction.note)
        assertEquals(2_500L, transaction.amountCent)
    }

    @Test
    fun `确认删除发出携带撤销凭据的事件并且不自己返回`() {
        val seeded = harness.seedAcceptance()
        val id = seeded.transactionId("T9")
        val model = harness.detailViewModel(id)

        model.onDeleteRequested()
        assertTrue(model.state.value.showDeleteDialog)

        model.onDeleteConfirmed()
        settleMainLooper()

        val event = runBlocking { withTimeout(5_000L) { model.events.first() } }
        val receipt = (event as DetailEvent.Deleted).receipt
        assertEquals(id, receipt.transactionId)
        assertEquals(2_850L, receipt.original.amountCent)
        assertTrue(model.state.value.deleted)

        // 软删除后普通查询看不到这笔账单。
        assertNull(runBlocking { harness.repository.observeTransaction(id).first() })

        // 撤销恢复原 id 与原字段，不新建副本。
        val undone = runBlocking { harness.repository.undoDelete(receipt) }
        assertTrue(undone.isSuccess)
        val restored = runBlocking { harness.repository.observeTransaction(id).first() }
        assertNotNull(restored)
        assertEquals(id, restored!!.id)
        assertEquals(2_850L, restored.amountCent)
        assertEquals("晚餐", restored.note)
    }

    @Test
    fun `删除失败时保留记录并给出原因`() {
        val seeded = harness.seedAcceptance()
        val id = seeded.transactionId("T9")
        val model = harness.detailViewModel(id)

        harness.repository.failNextWrite =
            com.blueledger.app.core.model.LedgerError.Storage("模拟写入失败")
        model.onDeleteRequested()
        model.onDeleteConfirmed()
        settleMainLooper()

        val state = model.state.value
        assertNotNull("删除失败必须有可读原因", state.deleteError)
        assertFalse(state.deleting)
        assertFalse(state.deleted)
        assertNotNull("失败后记录必须还在", state.transaction)
    }

    @Test
    fun `删除期间的重复确认不会重复提交`() {
        val seeded = harness.seedAcceptance()
        val id = seeded.transactionId("T5")
        val model = harness.detailViewModel(id)

        model.onDeleteRequested()
        model.onDeleteConfirmed()
        // 第二次确认必须被忽略：不产生第二个事件，也不把「记录不存在」写成删除失败。
        model.onDeleteConfirmed()
        settleMainLooper()

        val event = runBlocking { withTimeout(5_000L) { model.events.first() } }
        assertEquals(id, (event as DetailEvent.Deleted).receipt.transactionId)
        assertNull(model.state.value.deleteError)

        val remaining = runBlocking {
            harness.repository.observeTransactions(
                com.blueledger.app.core.model.TransactionFilter(yearMonth = Fx.OCT_2026, limit = 100),
            ).first().totalCount
        }
        assertEquals("只删除一笔", Expect.OCT_COUNT - 1, remaining)
    }
}
