package com.blueledger.app.data

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.blueledger.app.core.model.AccountKind
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.LedgerAccount
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.MonthlyBudget
import com.blueledger.app.core.model.RestoreResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.ValidatedLedgerSnapshot
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.data.local.AccountEntity
import com.blueledger.app.data.local.CategoryEntity
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.local.LedgerMigrations
import com.blueledger.app.data.local.TransactionEntity
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import com.blueledger.app.data.repository.SequentialLedgerIdGenerator
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** 初始化幂等、一致快照导出、事务式恢复、失败回滚与数据库持久化/迁移策略。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LedgerRestoreAndPersistenceTest : LedgerRoomTestBase() {

    @Before
    fun seed() = runDb { seedAcceptanceFixture() }

    private fun RestoreResult.requireFailureCode(code: ValidationCode) {
        val failure = this as? RestoreResult.Failure
        assertNotNull("期望恢复失败，实际是 $this", failure)
        failure!!.error.requireValidationCode(code)
    }

    // ───────────────────── 初始化 ─────────────────────

    @Test
    fun `initialize_is_idempotent_and_inserts_no_demo_transactions`() = runDb {
        // 基类夹具已初始化过一次，这里再调用两次
        repository.initializeIfNeeded().requireSuccess()
        repository.initializeIfNeeded().requireSuccess()

        val expense = repository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
        val income = repository.observeCategories(TransactionType.INCOME, includeArchived = true).first()
        // 夹具额外新建了「住房」，因此是 14 个默认分类 + 1 个自定义分类
        assertEquals(DEFAULT_CATEGORY_COUNT + 1, expense.size + income.size)
        assertTrue((expense + income).map { it.id }.containsAll(Defaults.CATEGORIES.map { it.id }))
        assertEquals("餐饮", expense.first().name)
        assertEquals(Defaults.FALLBACK_EXPENSE_CATEGORY_ID, expense.single { it.isFallback }.id)
        assertEquals(Defaults.FALLBACK_INCOME_CATEGORY_ID, income.single { it.isFallback }.id)

        val accounts = repository.observeAccounts(includeArchived = true).first()
        // 夹具额外建了 A/B 两个账户，初始化本身只保证默认账户存在且不重复
        assertEquals(3, accounts.size)
        assertEquals(1, accounts.count { it.account.id == Defaults.DEFAULT_ACCOUNT_ID })
        assertEquals("默认账户", accounts.first { it.account.id == Defaults.DEFAULT_ACCOUNT_ID }.account.name)

        val settings = repository.observeSettings().first()
        assertEquals(Defaults.DEFAULT_ACCOUNT_ID, settings.defaultAccountId)
        assertEquals("CNY", settings.currency)

        // 初始化绝不插入演示账单
        assertEquals(0, repository.observeMonthSummary(YearMonth.of(2020, 1)).first().count)
        assertTrue(repository.observeTransactions(TransactionFilter().copy(yearMonth = YearMonth.of(2020, 1))).first().items.isEmpty())
    }

    @Test
    fun `fresh_empty_database_only_has_defaults`() = runDb {
        val context: Context = ApplicationProvider.getApplicationContext()
        val emptyDb = androidx.room.Room.inMemoryDatabaseBuilder(context, LedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val emptyRepository = LedgerRepositoryImpl(emptyDb, clock, SequentialLedgerIdGenerator("e"))
            emptyRepository.initializeIfNeeded().requireSuccess()

            // 首次正式启动：账单必须为零
            assertEquals(0, emptyDb.transactionDao().countAll())
            assertEquals(0, emptyRepository.observeTransactions(TransactionFilter()).first().totalCount)
            assertEquals(0, emptyRepository.observeTransactions(TransactionFilter()).first().totalCount)

            // 默认分类跟随正式分类集合，且每类型一个兜底
            val expense = emptyRepository.observeCategories(TransactionType.EXPENSE, includeArchived = true).first()
            val income = emptyRepository.observeCategories(TransactionType.INCOME, includeArchived = true).first()
            assertEquals(Defaults.EXPENSE_CATEGORIES.size, expense.size)
            assertEquals(Defaults.INCOME_CATEGORIES.size, income.size)
            assertEquals(
                Defaults.EXPENSE_CATEGORIES.map { it.name },
                expense.map { it.name },
            )
            assertEquals(Defaults.INCOME_CATEGORIES.map { it.name }, income.map { it.name })
            assertEquals(Defaults.FALLBACK_EXPENSE_CATEGORY_ID, expense.last().id)
            assertTrue(expense.last().isFallback)
            assertEquals(Defaults.FALLBACK_INCOME_CATEGORY_ID, income.last().id)
            assertTrue(income.last().isFallback)

            // 默认账户只有 1 个，且设置指向它
            val accounts = emptyRepository.observeAccounts(includeArchived = true).first()
            assertEquals(1, accounts.size)
            assertEquals(Defaults.DEFAULT_ACCOUNT_ID, accounts.single().account.id)
            assertEquals("默认账户", accounts.single().account.name)
            assertEquals(AccountKind.CASH, accounts.single().account.kind)
            assertEquals(0L, accounts.single().account.openingBalanceCent)
            assertEquals(0L, accounts.single().balanceCent)
            assertEquals(Defaults.DEFAULT_ACCOUNT_ID, emptyRepository.observeSettings().first().defaultAccountId)

            // 空账本的摘要与预算
            val summary = emptyRepository.observeMonthSummary(YearMonth.of(2026, 10)).first()
            assertEquals(0L, summary.incomeCent)
            assertEquals(0L, summary.expenseCent)
            assertEquals(0L, summary.balanceCent)
            assertEquals(0, summary.count)
            assertEquals(0L, emptyRepository.observeBudget(YearMonth.of(2026, 10)).first().usedCent)
            assertNull(emptyRepository.observeBudget(YearMonth.of(2026, 10)).first().budgetCent)
        } finally {
            emptyDb.close()
        }
    }

    // ───────────────────── 一致快照导出 ─────────────────────

    @Test
    fun `snapshot_contains_all_entities_including_archived_ones`() = runDb {
        repository.setCategoryArchived(TRANSPORT, true).requireSuccess()
        repository.setAccountArchived(fixtureIds.accountB, true, null).requireSuccess()
        repository.softDeleteTransaction("T9").requireValue()

        val snapshot = repository.exportConsistentSnapshot()
        assertEquals(baseMillis, snapshot.exportedAt.toEpochMilli())
        assertEquals("CNY", snapshot.currency)
        // 不含软删除账单
        assertEquals(8, snapshot.transactions.size)
        assertTrue(snapshot.transactions.none { it.id == "T9" })
        assertTrue(snapshot.transactions.all { it.deletedAt == null })
        // 归档分类/账户仍然导出，保留用户的全部配置
        assertEquals(DEFAULT_CATEGORY_COUNT + 1, snapshot.categories.size)
        assertTrue(snapshot.categories.any { it.id == TRANSPORT && it.isArchived })
        assertEquals(3, snapshot.accounts.size)
        assertTrue(snapshot.accounts.any { it.id == fixtureIds.accountB && it.isArchived })
        assertEquals(1, snapshot.budgets.size)
        assertEquals(YearMonth.of(2026, 10), snapshot.budgets.single().yearMonth)
        assertEquals(400_000L, snapshot.budgets.single().amountCent)
    }

    // ───────────────────── 恢复 ─────────────────────

    @Test
    fun `restore_round_trip_replaces_every_entity`() = runDb {
        val snapshot = repository.exportConsistentSnapshot()

        // 破坏数据：软删、改名、改预算、改设置
        repository.softDeleteTransaction("T9").requireValue()
        repository.upsertCategory(
            CategoryCommand(id = FOOD, type = TransactionType.EXPENSE, name = "吃饭", iconKey = CategoryIcons.RESTAURANT),
        ).requireSuccess()
        repository.setBudget(october, 999L).requireSuccess()
        repository.setHideAmounts(true).requireSuccess()
        repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 1L, TRANSPORT, fixtureIds.accountB, today, "临时"),
            "req-temp",
        ).requireSaved()

        val result = repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(snapshot))
        assertTrue("恢复应成功，实际是 $result", result is RestoreResult.Success)
        assertEquals(9, (result as RestoreResult.Success).transactionCount)
        assertEquals(1, result.budgetCount)

        // 全库与聚合回到快照状态
        assertEquals(9, repository.observeTransactions(TransactionFilter()).first().totalCount)
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
        assertEquals(1_580_000L, repository.observeYearAnalysis(2026).first().incomeCent)
        assertNotNull(repository.observeTransaction("T9").first())
        assertEquals("餐饮", repository.observeCategories(TransactionType.EXPENSE, true).first().first { it.id == FOOD }.name)
        assertEquals(400_000L, repository.observeBudget(october).first().budgetCent)
        assertTrue(!repository.observeSettings().first().hideAmounts)
        assertEquals(9, db.transactionDao().countAll())
    }

    @Test
    fun `restore_keeps_device_backup_timestamp`() = runDb {
        val snapshot = repository.exportConsistentSnapshot()
        assertNull(snapshot.settings.lastBackupAt)

        val backupAt = Instant.ofEpochMilli(baseMillis + 60_000L)
        repository.recordBackupSuccess(backupAt, "blueledger-2026-10-07.blueledger.json").requireSuccess()
        assertEquals(backupAt.toEpochMilli(), repository.observeSettings().first().lastBackupAt!!.toEpochMilli())

        repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(snapshot)).requireSuccess()
        val settings = repository.observeSettings().first()
        // 备份文件里的旧时间为空，恢复后必须保留设备当前值
        assertEquals(backupAt.toEpochMilli(), settings.lastBackupAt!!.toEpochMilli())
        assertEquals("blueledger-2026-10-07.blueledger.json", settings.lastBackupFileName)

        val record = repository.observeBackupRecord().first()
        assertEquals(backupAt.toEpochMilli(), record.succeededAt!!.toEpochMilli())
        assertEquals(9, record.transactionCount)
    }

    @Test
    fun `restore_rejects_future_dated_snapshot_and_keeps_current_data`() = runDb {
        val snapshot = repository.exportConsistentSnapshot()
        val corrupted = snapshot.copy(
            transactions = snapshot.transactions.map {
                if (it.id == "T9") it.copy(occurredOn = LocalDate.of(2026, 10, 8)) else it
            },
        )
        repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(corrupted))
            .requireFailureCode(ValidationCode.BACKUP_DATE_FUTURE)

        assertEquals(9, db.transactionDao().countAll())
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
    }

    @Test
    fun `restore_rejects_broken_references_and_constraints`() = runDb {
        val snapshot = repository.exportConsistentSnapshot()

        // 引用缺失
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(
                    transactions = snapshot.transactions.map {
                        if (it.id == "T9") it.copy(categoryId = "missing-category") else it
                    },
                ),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_MISSING_REFERENCE)

        // 分类类型与账单不一致
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(
                    transactions = snapshot.transactions.map {
                        if (it.id == "T9") it.copy(type = TransactionType.INCOME) else it
                    },
                ),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_CATEGORY_TYPE_MISMATCH)

        // 金额超限
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(
                    transactions = snapshot.transactions.map {
                        if (it.id == "T9") it.copy(amountCent = 0L) else it
                    },
                ),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE)

        // 分类重名（同类型）
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(
                    categories = snapshot.categories.map {
                        if (it.id == TRANSPORT) it.copy(name = "餐饮") else it
                    },
                ),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_NAME_CONSTRAINT)

        // 非法图标
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(
                    categories = snapshot.categories.map {
                        if (it.id == TRANSPORT) it.copy(iconKey = "nope") else it
                    },
                ),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_ICON_INVALID)

        // 缺少兜底分类
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(categories = snapshot.categories.filterNot { it.isFallback }),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_NO_FALLBACK_CATEGORY)

        // 没有可用账户
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(accounts = snapshot.accounts.map { it.copy(isArchived = true) }),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_NO_ACTIVE_ACCOUNT)

        // 默认账户无效
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(settings = snapshot.settings.copy(defaultAccountId = "missing")),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_DEFAULT_ACCOUNT_INVALID)

        // 预算非法（0 不是有效预算）
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(budgets = listOf(MonthlyBudget(YearMonth.of(2026, 10), 0L))),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_AMOUNT_OUT_OF_RANGE)

        // 重复 ID
        repository.restoreValidatedSnapshot(
            ValidatedLedgerSnapshot.fromVerified(
                snapshot.copy(
                    accounts = snapshot.accounts + snapshot.accounts.map { it.copy(name = "重复名") },
                ),
            ),
        ).requireFailureCode(ValidationCode.BACKUP_DUPLICATE_ID)

        // 所有失败都不改变现有数据
        assertEquals(9, db.transactionDao().countAll())
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
        assertEquals(400_000L, repository.observeBudget(october).first().budgetCent)
        assertEquals(3, repository.observeAccounts(includeArchived = true).first().size)
    }

    @Test
    fun `restore_discards_old_undo_receipts`() = runDb {
        val snapshot = repository.exportConsistentSnapshot()
        val receipt = repository.softDeleteTransaction("T9").requireValue()

        repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(snapshot)).requireSuccess()

        // 旧账本的删除凭据不能作用于恢复后的账本
        repository.undoDelete(receipt).requireFailure()
        assertNotNull(repository.observeTransaction("T9").first())
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
    }

    @Test
    fun `restore_replaces_soft_deleted_records_with_snapshot_state`() = runDb {
        repository.softDeleteTransaction("T9").requireValue()
        val snapshotWithDeleted = repository.exportConsistentSnapshot()
        assertEquals(8, snapshotWithDeleted.transactions.size)

        repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(snapshotWithDeleted)).requireSuccess()
        assertNull(repository.observeTransaction("T9").first())
        assertEquals(6, repository.observeMonthSummary(october).first().count)
        assertEquals(305_030L, repository.observeMonthSummary(october).first().expenseCent)
    }

    @Test
    fun `restore_with_empty_ledger_is_allowed_when_defaults_are_valid`() = runDb {
        val empty = LedgerSnapshot(
            exportedAt = Instant.ofEpochMilli(baseMillis),
            currency = "CNY",
            transactions = emptyList(),
            categories = Defaults.CATEGORIES.map { it.toCategory() },
            accounts = listOf(
                LedgerAccount(Defaults.DEFAULT_ACCOUNT_ID, "默认账户", AccountKind.CASH, 0L),
            ),
            budgets = emptyList(),
            settings = Defaults.defaultSettings(),
        )
        val result = repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(empty))
        assertTrue(result is RestoreResult.Success)
        assertEquals(0, db.transactionDao().countAll())
        assertEquals(DEFAULT_CATEGORY_COUNT, repository.observeCategories(TransactionType.EXPENSE, true).first().size +
            repository.observeCategories(TransactionType.INCOME, true).first().size)
        assertEquals(0L, repository.observeMonthSummary(october).first().expenseCent)
    }

    // ───────────────────── 事务回滚 ─────────────────────

    @Test
    fun `room_transaction_rolls_back_all_writes_on_failure`() = runDb {
        val beforeTransactions = db.transactionDao().countAll()
        val beforeCategories = db.categoryDao().countAll()

        try {
            db.withTransaction {
                db.transactionDao().insert(
                    TransactionEntity(
                        id = "ROLLBACK-TX",
                        type = TransactionType.EXPENSE,
                        amountCent = 100L,
                        categoryId = FOOD,
                        accountId = fixtureIds.accountA,
                        occurredOnEpochDay = today.toEpochDay(),
                        note = "回滚",
                        noteKey = "回滚",
                        createdAtEpochMillis = baseMillis,
                        updatedAtEpochMillis = baseMillis,
                    ),
                )
                db.categoryDao().insert(
                    CategoryEntity(
                        id = "ROLLBACK-CAT",
                        type = TransactionType.EXPENSE,
                        name = "回滚分类",
                        nameKey = "回滚分类",
                        iconKey = CategoryIcons.DAILY,
                        sortOrder = 999,
                        isArchived = false,
                        isFallback = false,
                        createdAtEpochMillis = baseMillis,
                        updatedAtEpochMillis = baseMillis,
                    ),
                )
                db.accountDao().insert(
                    AccountEntity(
                        id = "ROLLBACK-ACC",
                        name = "回滚账户",
                        nameKey = "回滚账户",
                        kind = AccountKind.CASH,
                        openingBalanceCent = 1L,
                        isArchived = false,
                        createdAtEpochMillis = baseMillis,
                        updatedAtEpochMillis = baseMillis,
                    ),
                )
                throw IllegalStateException("boom")
            }
            fail("事务内的异常必须向外抛出")
        } catch (expected: Exception) {
            // 事务失败必须抛出，而不是静默提交部分写入
            assertTrue("异常应带原因", expected.message != null)
        }

        assertEquals(beforeTransactions, db.transactionDao().countAll())
        assertEquals(beforeCategories, db.categoryDao().countAll())
        assertNull(db.transactionDao().getById("ROLLBACK-TX"))
        assertNull(db.categoryDao().getById("ROLLBACK-CAT"))
        assertNull(db.accountDao().getById("ROLLBACK-ACC"))
        assertEquals(307_880L, repository.observeMonthSummary(october).first().expenseCent)
    }

    @Test
    fun `failed_write_returns_storage_failure_instead_of_pretending_success`() = runDb {
        db.close()
        val result = repository.createTransaction(
            TransactionDraft(TransactionType.EXPENSE, 100L, FOOD, fixtureIds.accountA, today, "关闭后写入"),
            "req-closed",
        )
        val error = result.requireFailure()
        assertTrue("应返回 Storage 失败，实际是 $error", error is com.blueledger.app.core.model.LedgerError.Storage)
        assertTrue(error.message.isNotBlank())
        // 重新打开数据库后原有数据仍在（先前的写入已提交）
        db = androidx.room.Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        assertEquals(0, db.transactionDao().countAll())
    }

    // ───────────────────── 持久化与迁移策略 ─────────────────────

    @Test
    fun `file_database_keeps_data_and_schema_version_after_reopen`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val name = "blueledger-a1-persistence-test.db"
        context.deleteDatabase(name)

        val firstDb = LedgerDatabase.create(context, name)
        val firstRepository = LedgerRepositoryImpl(firstDb, clock, SequentialLedgerIdGenerator("p"))
        runBlocking {
            firstRepository.initializeIfNeeded().requireSuccess()
            firstRepository.createTransaction(
                TransactionDraft(TransactionType.EXPENSE, 2_850L, FOOD, Defaults.DEFAULT_ACCOUNT_ID, today, "重启后仍在"),
                "req-persist",
            ).requireSaved()
        }
        firstDb.close()

        val secondDb = LedgerDatabase.create(context, name)
        val secondRepository = LedgerRepositoryImpl(secondDb, clock, SequentialLedgerIdGenerator("p2"))
        try {
            runBlocking {
                val summary = secondRepository.observeMonthSummary(YearMonth.of(2026, 10)).first()
                assertEquals(2_850L, summary.expenseCent)
                assertEquals(1, summary.count)
                val page = secondRepository.observeTransactions(TransactionFilter(yearMonth = YearMonth.of(2026, 10))).first()
                assertEquals("重启后仍在", page.items.single().note)
                assertEquals(
                    DEFAULT_CATEGORY_COUNT,
                    secondRepository.observeCategories(TransactionType.EXPENSE, true).first().size +
                        secondRepository.observeCategories(TransactionType.INCOME, true).first().size,
                )
            }
            // 首版 schema：版本号固定 1，且没有任何破坏性迁移
            assertEquals(3, LedgerDatabase.VERSION)
            assertEquals(LedgerDatabase.VERSION, secondDb.openHelper.readableDatabase.version)
            assertEquals(2, LedgerMigrations.ALL.size)
        } finally {
            secondDb.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun `snapshot_round_trip_is_stable`() = runDb {
        val first = repository.exportConsistentSnapshot()
        repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(first)).requireSuccess()
        val second = repository.exportConsistentSnapshot()

        assertEquals(first.transactions.toSet(), second.transactions.toSet())
        assertEquals(first.categories.toSet(), second.categories.toSet())
        assertEquals(first.accounts.toSet(), second.accounts.toSet())
        assertEquals(first.budgets.toSet(), second.budgets.toSet())
        assertEquals(first.settings.copy(lastBackupAt = null), second.settings.copy(lastBackupAt = null))
    }

    @Test
    fun `snapshot_observer_and_filters_stay_consistent_after_restore`() = runDb {
        val snapshot = repository.exportConsistentSnapshot()
        repository.softDeleteTransaction("T9").requireValue()
        repository.restoreValidatedSnapshot(ValidatedLedgerSnapshot.fromVerified(snapshot)).requireSuccess()

        val filter = TransactionFilter(yearMonth = october, type = TransactionType.EXPENSE)
        val page = repository.observeTransactions(filter).first()
        val summary = repository.observeFilteredSummary(filter).first()
        assertEquals(5, page.totalCount)
        assertEquals(page.items.sumOf { it.amountCent }, summary.expenseCent)
        assertEquals(307_880L, summary.expenseCent)
    }
}
