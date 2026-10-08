package com.blueledger.app.data

import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidationCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 账单写操作：新增/编辑/软删除/撤销、requestId 幂等、跨月编辑、归档引用规则。
 * 每个用例都从 §12 原始夹具重新开始，互不污染。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerTransactionWriteTest : LedgerRoomTestBase() {

    @Before
    fun seed() = runDb { seedAcceptanceFixture() }

    private fun draft(
        amountCent: Long = 2_850L,
        type: TransactionType = TransactionType.EXPENSE,
        categoryId: String = FOOD,
        accountId: String = fixtureIds.accountB,
        date: LocalDate = LocalDate.of(2026, 10, 7),
        note: String = "新增晚餐",
    ) = TransactionDraft(
        type = type,
        amountCent = amountCent,
        categoryId = categoryId,
        accountId = accountId,
        occurredOn = date,
        note = note,
    )

    // ───────────────────── 新增 ─────────────────────

    @Test
    fun `create_stores_exact_cents_and_refreshes_all_views`() = runDb {
        val result = repository.createTransaction(draft(amountCent = 2_850L), "req-1").requireSaved()
        assertTrue(result.created)

        val summary = repository.observeMonthSummary(october).first()
        assertEquals(310_730L, summary.expenseCent)
        assertEquals(8, summary.count)

        val accounts = repository.observeAccounts(includeArchived = false).first().associateBy { it.account.name }
        assertEquals(89_270L, accounts.getValue("B钱包").balanceCent)

        val stored = repository.observeTransaction(result.transactionId).first()
        assertNotNull(stored)
        assertEquals(2_850L, stored!!.amountCent)
        assertEquals(LocalDate.of(2026, 10, 7), stored.occurredOn)
    }

    @Test
    fun `create_sets_last_used_account_and_keeps_default`() = runDb {
        repository.createTransaction(draft(accountId = fixtureIds.accountA), "req-1").requireSaved()
        val settings = repository.observeSettings().first()
        assertEquals(fixtureIds.accountA, settings.lastUsedAccountId)
        assertEquals(DEFAULT_ACCOUNT_ID, settings.defaultAccountId)
    }

    @Test
    fun `same_request_id_creates_only_one_record`() = runDb {
        val first = repository.createTransaction(draft(), "req-same").requireSaved()
        val second = repository.createTransaction(draft(), "req-same").requireSaved()
        assertTrue(first.created)
        assertTrue(!second.created)
        assertEquals(first.transactionId, second.transactionId)
        assertEquals(8, repository.observeMonthSummary(october).first().count)
    }

    @Test
    fun `same_request_id_with_different_payload_is_rejected`() = runDb {
        repository.createTransaction(draft(amountCent = 2_850L), "req-same").requireSaved()
        val conflict = repository.createTransaction(draft(amountCent = 3_000L), "req-same")
        conflict.requireFailure().requireValidationCode(ValidationCode.DUPLICATE_REQUEST)
        assertEquals(8, repository.observeMonthSummary(october).first().count)
    }

    @Test
    fun `different_request_ids_create_separate_records`() = runDb {
        repository.createTransaction(draft(), "req-a").requireSaved()
        repository.createTransaction(draft(), "req-b").requireSaved()
        assertEquals(9, repository.observeMonthSummary(october).first().count)
    }

    @Test
    fun `blank_request_id_does_not_break_idempotency_column`() = runDb {
        repository.createTransaction(draft(), "").requireSaved()
        repository.createTransaction(draft(), "").requireSaved()
        assertEquals(9, repository.observeMonthSummary(october).first().count)
    }

    @Test
    fun `concurrent_same_request_id_never_duplicates`() = runDb {
        coroutineScope {
            val results = (1..8).map {
                async(Dispatchers.IO) { repository.createTransaction(draft(), "req-race") }
            }.map { it.await() }
            assertTrue(results.all { it is com.blueledger.app.core.model.SaveResult.Success })
            assertEquals(1, results.map { (it as com.blueledger.app.core.model.SaveResult.Success).transactionId }.distinct().size)
        }
        assertEquals(8, repository.observeMonthSummary(october).first().count)
    }

    // ───────────────────── 新增校验（不信任 UI） ─────────────────────

    @Test
    fun `create_rejects_invalid_business_inputs`() = runDb {
        repository.createTransaction(draft(date = LocalDate.of(2026, 10, 8)), "r1")
            .requireFailure().requireValidationCode(ValidationCode.DATE_FUTURE)
        repository.createTransaction(draft(amountCent = 0L), "r2")
            .requireFailure().requireValidationCode(ValidationCode.AMOUNT_ZERO)
        // 负金额与零金额必须是不同的错误码（A7 §12 验收断言）
        repository.createTransaction(draft(amountCent = -1L), "r2b")
            .requireFailure().requireValidationCode(ValidationCode.AMOUNT_NEGATIVE)
        repository.createTransaction(draft(amountCent = 1_000_000_000L), "r3")
            .requireFailure().requireValidationCode(ValidationCode.AMOUNT_OUT_OF_RANGE)
        repository.createTransaction(draft(note = "n".repeat(201)), "r4")
            .requireFailure().requireValidationCode(ValidationCode.NOTE_TOO_LONG)
        repository.createTransaction(draft(categoryId = ""), "r5")
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_REQUIRED)
        repository.createTransaction(draft(categoryId = "missing"), "r6")
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_NOT_FOUND)
        repository.createTransaction(draft(categoryId = SALARY), "r7")
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_TYPE_MISMATCH)
        repository.createTransaction(draft(accountId = ""), "r8")
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_REQUIRED)
        repository.createTransaction(draft(accountId = "missing"), "r9")
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_NOT_FOUND)

        // 失败不写库
        assertEquals(7, repository.observeMonthSummary(october).first().count)
        assertEquals(9, db.transactionDao().countAll())
    }

    @Test
    fun `note_at_200_characters_is_accepted`() = runDb {
        val note = "备".repeat(200)
        val saved = repository.createTransaction(draft(note = note), "req-200").requireSaved()
        assertEquals(note, repository.observeTransaction(saved.transactionId).first()!!.note)
    }

    // ───────────────────── 编辑 ─────────────────────

    @Test
    fun `update_preserves_id_and_created_at`() = runDb {
        val before = repository.observeTransaction("T4").first()!!
        val saved = repository.updateTransaction(
            "T4",
            draft(amountCent = 320_000L, categoryId = fixtureIds.housingCategoryId, accountId = fixtureIds.accountA, date = LocalDate.of(2026, 10, 1), note = "十月房租"),
        ).requireSaved()
        assertTrue(!saved.created)
        assertEquals("T4", saved.transactionId)

        val after = repository.observeTransaction("T4").first()!!
        assertEquals(before.id, after.id)
        assertEquals(before.createdAt, after.createdAt)
        assertEquals(320_000L, after.amountCent)
        assertEquals(clock.now(), after.updatedAt)
    }

    @Test
    fun `update_amount_refreshes_month_year_account_and_budget`() = runDb {
        repository.updateTransaction(
            "T4",
            draft(amountCent = 320_000L, categoryId = fixtureIds.housingCategoryId, accountId = fixtureIds.accountA, date = LocalDate.of(2026, 10, 1), note = "十月房租"),
        ).requireSaved()

        val octoberSummary = repository.observeMonthSummary(october).first()
        assertEquals(327_880L, octoberSummary.expenseCent)
        assertEquals(752_120L, octoberSummary.balanceCent)

        val year = repository.observeYearAnalysis(2026).first()
        assertEquals(337_880L, year.expenseCent)
        assertEquals(1_242_120L, year.balanceCent)

        val accountA = repository.observeAccounts(false).first().first { it.account.name == "A银行卡" }
        assertEquals(1_270_000L, accountA.balanceCent)

        val budget = repository.observeBudget(october).first()
        assertEquals(72_120L, budget.remainingCent)
    }

    @Test
    fun `changing_date_moves_the_record_between_months_without_double_counting`() = runDb {
        repository.updateTransaction(
            "T4",
            draft(amountCent = 300_000L, categoryId = fixtureIds.housingCategoryId, accountId = fixtureIds.accountA, date = LocalDate.of(2026, 9, 30), note = "十月房租"),
        ).requireSaved()

        val septemberSummary = repository.observeMonthSummary(september).first()
        assertEquals(310_000L, septemberSummary.expenseCent)
        assertEquals(190_000L, septemberSummary.balanceCent)

        val octoberSummary = repository.observeMonthSummary(october).first()
        assertEquals(7_880L, octoberSummary.expenseCent)
        assertEquals(1_072_120L, octoberSummary.balanceCent)

        // 年度只算一次，账户余额不变
        val year = repository.observeYearAnalysis(2026).first()
        assertEquals(317_880L, year.expenseCent)
        assertEquals(9, year.count)
        val accountA = repository.observeAccounts(false).first().first { it.account.name == "A银行卡" }
        assertEquals(1_290_000L, accountA.balanceCent)
        assertEquals(392_120L, repository.observeBudget(october).first().remainingCent)
    }

    @Test
    fun `changing_type_requires_matching_category`() = runDb {
        // 类型切换但保留原支出分类 → 分类与类型不一致，必须重新选择收入分类
        repository.updateTransaction(
            "T9",
            draft(amountCent = 2_850L, type = TransactionType.INCOME, categoryId = FOOD, accountId = fixtureIds.accountB, date = LocalDate.of(2026, 10, 7), note = "晚餐"),
        ).requireFailure().requireValidationCode(ValidationCode.CATEGORY_TYPE_MISMATCH)

        repository.updateTransaction(
            "T9",
            draft(amountCent = 2_850L, type = TransactionType.INCOME, categoryId = GIFT, accountId = fixtureIds.accountB, date = LocalDate.of(2026, 10, 7), note = "礼金"),
        ).requireSaved()

        val octoberSummary = repository.observeMonthSummary(october).first()
        assertEquals(1_082_850L, octoberSummary.incomeCent)
        assertEquals(305_030L, octoberSummary.expenseCent)
        assertEquals(777_820L, octoberSummary.balanceCent)

        val accountB = repository.observeAccounts(false).first().first { it.account.name == "B钱包" }
        // T9 由支出变收入：B = 期初 20,000 + 收入 (80,000 + 2,850) − 支出 (10 + 20 + 5,000) = 97,820
        assertEquals(97_820L, accountB.balanceCent)
    }

    @Test
    fun `update_of_missing_transaction_fails`() = runDb {
        repository.updateTransaction("missing", draft()).requireFailure()
        repository.softDeleteTransaction("missing").requireFailure()
    }

    @Test
    fun `update_rejects_future_date_and_invalid_amount`() = runDb {
        repository.updateTransaction(
            "T9",
            draft(date = LocalDate.of(2026, 10, 8)),
        ).requireFailure().requireValidationCode(ValidationCode.DATE_FUTURE)
        repository.updateTransaction("T9", draft(amountCent = 0L))
            .requireFailure().requireValidationCode(ValidationCode.AMOUNT_ZERO)
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
    }

    // ───────────────────── 归档引用规则 ─────────────────────

    @Test
    fun `archived_category_is_blocked_for_new_records_but_allowed_for_original_reference`() = runDb {
        repository.setCategoryArchived(FOOD, true).requireSuccess()

        repository.createTransaction(draft(categoryId = FOOD), "req-1")
            .requireFailure().requireValidationCode(ValidationCode.CATEGORY_ARCHIVED)

        // 编辑原记录可以保留数据库原记录中的同一分类
        repository.updateTransaction(
            "T5",
            draft(amountCent = 10L, categoryId = FOOD, accountId = fixtureIds.accountB, date = LocalDate.of(2026, 10, 2), note = "早餐改"),
        ).requireSaved()

        // 改成别的可用分类后，再改回归档分类必须被拒绝
        repository.updateTransaction(
            "T5",
            draft(amountCent = 10L, categoryId = TRANSPORT, accountId = fixtureIds.accountB, date = LocalDate.of(2026, 10, 2), note = "早餐"),
        ).requireSaved()
        repository.updateTransaction(
            "T5",
            draft(amountCent = 10L, categoryId = FOOD, accountId = fixtureIds.accountB, date = LocalDate.of(2026, 10, 2), note = "早餐"),
        ).requireFailure().requireValidationCode(ValidationCode.CATEGORY_ARCHIVED)

        // 归档分类的历史账单仍参与统计
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
        val expenseSlices = repository.observeMonthAnalysis(october, TransactionType.EXPENSE).first().slices
        assertEquals(listOf("住房", "交通", "餐饮"), expenseSlices.map { it.name })
    }

    @Test
    fun `archived_account_is_blocked_for_new_records_but_history_stays`() = runDb {
        repository.setAccountArchived(fixtureIds.accountB, true, null).requireSuccess()

        repository.createTransaction(draft(), "req-1")
            .requireFailure().requireValidationCode(ValidationCode.ACCOUNT_ARCHIVED)

        // 编辑原记录可保留原账户
        repository.updateTransaction(
            "T5",
            draft(amountCent = 10L, categoryId = FOOD, accountId = fixtureIds.accountB, date = LocalDate.of(2026, 10, 2), note = "早餐"),
        ).requireSaved()

        // 归档账户的历史账单继续参与统计与余额计算
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
        val archivedAccount = repository.observeAccounts(includeArchived = true).first()
            .first { it.account.name == "B钱包" }
        assertTrue(archivedAccount.account.isArchived)
        assertEquals(92_120L, archivedAccount.balanceCent)
        assertTrue(repository.observeAccounts(includeArchived = false).first().none { it.account.id == fixtureIds.accountB })
    }

    // ───────────────────── 软删除与撤销 ─────────────────────

    @Test
    fun `soft_delete_excludes_from_all_aggregates_and_undo_restores_same_id`() = runDb {
        val receipt = repository.softDeleteTransaction("T9").requireValue()
        assertEquals("T9", receipt.transactionId)
        assertEquals("T9", receipt.original.id)
        assertEquals(2_850L, receipt.original.amountCent)

        val afterDelete = repository.observeMonthSummary(october).first()
        assertEquals(305_030L, afterDelete.expenseCent)
        assertEquals(774_970L, afterDelete.balanceCent)
        assertEquals(6, afterDelete.count)
        assertNull(repository.observeTransaction("T9").first())
        assertEquals(
            307_880L,
            repository.observeFilteredSummary(TransactionFilter(yearMonth = october, type = TransactionType.EXPENSE)).first()
                .expenseCent + 2_850L,
        )

        val accountB = repository.observeAccounts(false).first().first { it.account.name == "B钱包" }
        assertEquals(94_970L, accountB.balanceCent)

        repository.undoDelete(receipt).requireSuccess()

        val restored = repository.observeTransaction("T9").first()
        assertNotNull(restored)
        assertEquals("T9", restored!!.id)
        assertEquals(2_850L, restored.amountCent)
        assertEquals(LocalDate.of(2026, 10, 7), restored.occurredOn)
        assertEquals("晚餐", restored.note)
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
        assertEquals(92_120L, repository.observeAccounts(false).first().first { it.account.name == "B钱包" }.balanceCent)
        // 不产生副本
        assertEquals(9, db.transactionDao().countAll())
    }

    @Test
    fun `undo_within_window_boundary_is_allowed`() = runDb {
        val receipt = repository.softDeleteTransaction("T9").requireValue()
        clock.advanceMillis(5_000L)
        repository.undoDelete(receipt).requireSuccess()
        assertNotNull(repository.observeTransaction("T9").first())
    }

    @Test
    fun `undo_after_expiry_is_rejected`() = runDb {
        val receipt = repository.softDeleteTransaction("T9").requireValue()
        clock.advanceMillis(5_001L)
        repository.undoDelete(receipt).requireFailure()
            .requireValidationCode(ValidationCode.UNDO_TOKEN_EXPIRED)
        assertNull(repository.observeTransaction("T9").first())
    }

    @Test
    fun `undo_token_cannot_be_reused`() = runDb {
        val receipt = repository.softDeleteTransaction("T9").requireValue()
        repository.undoDelete(receipt).requireSuccess()
        repository.undoDelete(receipt).requireFailure()
            .requireValidationCode(ValidationCode.UNDO_TOKEN_INVALID)
        assertEquals(9, db.transactionDao().countAll())
    }

    @Test
    fun `undo_token_is_invalidated_by_a_newer_delete`() = runDb {
        val firstReceipt = repository.softDeleteTransaction("T9").requireValue()
        repository.undoDelete(firstReceipt).requireSuccess()
        repository.softDeleteTransaction("T9").requireValue()
        repository.undoDelete(firstReceipt).requireFailure()
            .requireValidationCode(ValidationCode.UNDO_TOKEN_INVALID)
    }

    @Test
    fun `soft_delete_twice_fails_and_delete_of_deleted_is_not_found`() = runDb {
        repository.softDeleteTransaction("T9").requireValue()
        repository.softDeleteTransaction("T9").requireFailure()
        repository.updateTransaction("T9", draft()).requireFailure()
    }

    // ───────────────────── 搜索归一化 ─────────────────────

    @Test
    fun `search_is_case_insensitive_and_escapes_wildcards`() = runDb {
        // 备注里真的含有 %；分类用交通，避免影响下面「餐饮」分类名的条数断言
        repository.createTransaction(
            draft(note = "Coffee Time 100%", categoryId = TRANSPORT),
            "req-1",
        ).requireSaved()
        assertEquals(
            1,
            repository.observeTransactions(TransactionFilter(query = "coffee")).first().totalCount,
        )
        assertEquals(
            1,
            repository.observeTransactions(TransactionFilter(query = "COFFEE")).first().totalCount,
        )
        assertEquals(
            1,
            repository.observeTransactions(TransactionFilter(query = "100%")).first().totalCount,
        )
        assertEquals(
            1,
            repository.observeTransactions(TransactionFilter(query = "100% ", yearMonth = october)).first().totalCount,
        )
        // `%` 与 `_` 按普通字符处理：`%` 只命中备注里真的写了 `%` 的那一条（若是通配符会命中全部 10 条）
        val percentMatches = repository.observeTransactions(TransactionFilter(query = "%")).first()
        assertEquals(1, percentMatches.totalCount)
        assertEquals("Coffee Time 100%", percentMatches.items.single().note)
        assertEquals(0, repository.observeTransactions(TransactionFilter(query = "_")).first().totalCount)
        // 分类/账户重命名后，列表与搜索同步使用新名称
        repository.upsertCategory(
            com.blueledger.app.core.model.CategoryCommand(
                id = FOOD,
                type = TransactionType.EXPENSE,
                name = "吃饭",
                iconKey = com.blueledger.app.core.model.CategoryIcons.RESTAURANT,
            ),
        ).requireSuccess()
        val renamed = repository.observeTransactions(TransactionFilter(yearMonth = october)).first()
        assertEquals("吃饭", renamed.items.first { it.id == "T9" }.categoryName)
        assertEquals(
            3,
            repository.observeTransactions(TransactionFilter(yearMonth = october, query = "吃饭")).first().totalCount,
        )
        assertEquals(0, repository.observeTransactions(TransactionFilter(query = "餐饮")).first().totalCount)
    }

    // ───────────────────── 观察流随写入自动刷新 ─────────────────────

    @Test
    fun `observers_emit_updated_aggregates_after_writes`(): Unit = runDb {
        coroutineScope {
            val budgetValues = mutableListOf<Long>()
            val accountValues = mutableListOf<Long>()
            val categoryCounts = mutableListOf<Int>()

            val budgetJob = launch {
                repository.observeBudget(october).map { it.usedCent }.distinctUntilChanged()
                    .collect { budgetValues += it }
            }
            val accountJob = launch {
                repository.observeAccounts(includeArchived = false)
                    .map { list -> list.first { it.account.name == "B钱包" }.balanceCent }
                    .distinctUntilChanged()
                    .collect { accountValues += it }
            }
            val categoryJob = launch {
                repository.observeCategories(TransactionType.EXPENSE, includeArchived = false)
                    .map { it.size }
                    .distinctUntilChanged()
                    .collect { categoryCounts += it }
            }
            try {
                awaitUntil { budgetValues.isNotEmpty() && accountValues.isNotEmpty() && categoryCounts.isNotEmpty() }
                assertEquals(307_880L, budgetValues.first())
                assertEquals(92_120L, accountValues.first())
                // 默认支出分类 + 夹具新增的「住房」
                assertEquals(com.blueledger.app.core.model.Defaults.EXPENSE_CATEGORIES.size + 1, categoryCounts.first())

                repository.softDeleteTransaction("T9").requireValue()
                awaitUntil { budgetValues.size >= 2 && accountValues.size >= 2 }
                assertEquals(305_030L, budgetValues[1])
                assertEquals(94_970L, accountValues[1])

                repository.setCategoryArchived(TRANSPORT, true).requireSuccess()
                awaitUntil { categoryCounts.size >= 2 }
                assertEquals(com.blueledger.app.core.model.Defaults.EXPENSE_CATEGORIES.size, categoryCounts[1])

                repository.createTransaction(draft(amountCent = 1_000L), "req-live").requireSaved()
                awaitUntil { budgetValues.size >= 3 && accountValues.size >= 3 }
                assertEquals(306_030L, budgetValues[2])
                assertEquals(93_970L, accountValues[2])
            } finally {
                budgetJob.cancel()
                accountJob.cancel()
                categoryJob.cancel()
            }
        }
    }

    private suspend fun awaitUntil(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(20L)
        }
    }

    // ───────────────────── 设置 ─────────────────────

    @Test
    fun `hide_amounts_is_persisted_globally`() = runDb {
        assertTrue(!repository.observeSettings().first().hideAmounts)
        repository.setHideAmounts(true).requireSuccess()
        assertTrue(repository.observeSettings().first().hideAmounts)
        repository.setHideAmounts(false).requireSuccess()
        assertTrue(!repository.observeSettings().first().hideAmounts)
    }
}
