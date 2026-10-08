package com.blueledger.app.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.BackupRecord
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategorySlice
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.LedgerAccount
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerError
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
import com.blueledger.app.core.model.Limits
import com.blueledger.app.core.model.MonthAnalysis
import com.blueledger.app.core.model.MoneySummary
import com.blueledger.app.core.model.MutationResult
import com.blueledger.app.core.model.Outcome
import com.blueledger.app.core.model.RestoreResult
import com.blueledger.app.core.model.SaveResult
import com.blueledger.app.core.model.TransactionDraft
import com.blueledger.app.core.model.TransactionFilter
import com.blueledger.app.core.model.TransactionPageState
import com.blueledger.app.core.model.TransactionType
import com.blueledger.app.core.model.TransactionWithRefs
import com.blueledger.app.core.model.ValidatedLedgerSnapshot
import com.blueledger.app.core.model.ValidationCode
import com.blueledger.app.core.model.YearAnalysis
import com.blueledger.app.core.model.YearMonthAmount
import com.blueledger.app.core.time.DateRanges
import com.blueledger.app.core.time.EpochDayBounds
import com.blueledger.app.data.local.AccountBalanceRow
import com.blueledger.app.data.local.AccountEntity
import com.blueledger.app.data.local.BudgetEntity
import com.blueledger.app.data.local.CategoryEntity
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.local.MonthRow
import com.blueledger.app.data.local.SettingsEntity
import com.blueledger.app.data.local.TransactionEntity
import com.blueledger.app.data.local.TransactionRefRow
import com.blueledger.app.data.local.YearRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * 真实 Room 数据层：蓝记所有业务数据的唯一事实来源。
 *
 * 关键实现约束：
 * 1. 所有查询排除软删除账单；分类/账户归档不排除其历史有效账单。
 * 2. 金额是 Long 整数分，聚合同样用 64 位整数（必要时退化为 BigDecimal），不使用浮点累加。
 * 3. 写操作在 [db] 事务中完成，并用 [writeMutex] 串行化；恢复与其它写互斥。
 * 4. 业务校验在本层执行（[LedgerValidation] / [SnapshotValidator]），不信任 UI。
 * 5. 分页只影响 [observeTransactions] 的 items，[observeFilteredSummary] 忽略分页。
 */
class LedgerRepositoryImpl(
    private val db: LedgerDatabase,
    private val clock: Clock,
    private val idGenerator: LedgerIdGenerator = UuidLedgerIdGenerator(),
    private val tokenGenerator: () -> String = { UUID.randomUUID().toString() },
    private val writeMutex: Mutex = Mutex(),
) : LedgerRepository {

    private val transactionDao = db.transactionDao()
    private val categoryDao = db.categoryDao()
    private val accountDao = db.accountDao()
    private val budgetDao = db.budgetDao()
    private val settingsDao = db.settingsDao()
    private val undoRegistry = UndoRegistry()

    // ═══════════════════════════ 读 ═══════════════════════════

    override fun observeTransactions(filter: TransactionFilter): Flow<TransactionPageState> {
        val range = filterEpochDayRange(filter)
        val pattern = searchPattern(filter.query)
        val type = filter.type
        val categoryId = filter.categoryId?.takeIf { it.isNotBlank() }
        val accountId = filter.accountId?.takeIf { it.isNotBlank() }
        val limit = filter.limit.coerceAtLeast(1)
        val offset = filter.offset.coerceAtLeast(0)
        return transactionDao.observeTick()
            .map {
                db.withTransaction {
                    val rows = transactionDao.pageRows(
                        fromEpochDay = range.fromInclusive,
                        toEpochDay = range.toInclusive,
                        type = type,
                        categoryId = categoryId,
                        accountId = accountId,
                        pattern = pattern,
                        limit = limit,
                        offset = offset,
                    )
                    val total = transactionDao.countRows(
                        fromEpochDay = range.fromInclusive,
                        toEpochDay = range.toInclusive,
                        type = type,
                        categoryId = categoryId,
                        accountId = accountId,
                        pattern = pattern,
                    )
                    TransactionPageState(
                        items = rows.map { it.toTransactionWithRefs() },
                        totalCount = total,
                        hasMore = offset + rows.size < total,
                    )
                }
            }
            .distinctUntilChanged()
    }

    override fun observeFilteredSummary(filter: TransactionFilter): Flow<MoneySummary> {
        val range = filterEpochDayRange(filter)
        return transactionDao.observeSummary(
            fromEpochDay = range.fromInclusive,
            toEpochDay = range.toInclusive,
            type = filter.type,
            categoryId = filter.categoryId?.takeIf { it.isNotBlank() },
            accountId = filter.accountId?.takeIf { it.isNotBlank() },
            pattern = searchPattern(filter.query),
        ).map { MoneySummary(it.incomeCent, it.expenseCent, it.totalCount) }
            .distinctUntilChanged()
    }

    override fun observeTransaction(id: String): Flow<LedgerTransaction?> =
        transactionDao.observeById(id).map { it?.toModel() }.distinctUntilChanged()

    override fun observeMonthSummary(month: YearMonth): Flow<MoneySummary> {
        val periodEnd = DateRanges.periodEnd(month, clock.today()) ?: return flowOf(MoneySummary.EMPTY)
        return transactionDao.observeSummary(
            fromEpochDay = DateRanges.monthStart(month).toEpochDay(),
            toEpochDay = periodEnd.toEpochDay(),
            type = null,
            categoryId = null,
            accountId = null,
            pattern = "",
        ).map { MoneySummary(it.incomeCent, it.expenseCent, it.totalCount) }
            .distinctUntilChanged()
    }

    override fun observeMonthAnalysis(month: YearMonth, type: TransactionType): Flow<MonthAnalysis> {
        val today = clock.today()
        val periodEnd = DateRanges.periodEnd(month, today)
            ?: return flowOf(MonthAnalysis(month = month, type = type, daily = emptyList(), periodEnd = null))
        val start = DateRanges.monthStart(month)
        return transactionDao.observeMonthRows(start.toEpochDay(), periodEnd.toEpochDay())
            .map { rows -> buildMonthAnalysis(month, type, start, periodEnd, rows) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    override fun observeYearAnalysis(year: Int): Flow<YearAnalysis> {
        val today = clock.today()
        if (year > today.year) {
            return flowOf(
                YearAnalysis(
                    year = year,
                    months = unreachedYearMonths(),
                    cutoff = null,
                    isCurrentYear = false,
                    reachedMonthCount = 0,
                ),
            )
        }
        val range = DateRanges.yearEpochDayRange(year, today)
            ?: return flowOf(YearAnalysis(year = year, months = unreachedYearMonths()))
        return transactionDao.observeYearRows(range.first, range.last)
            .map { rows -> buildYearAnalysis(year, today, rows) }
            .distinctUntilChanged()
            .flowOn(Dispatchers.Default)
    }

    override fun observeCategories(
        type: TransactionType,
        includeArchived: Boolean,
    ): Flow<List<LedgerCategory>> =
        categoryDao.observeByType(type, includeArchived)
            .onStart { ensureSeeded() }
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()

    override fun observeAccounts(includeArchived: Boolean): Flow<List<AccountWithBalance>> =
        accountDao.observeWithBalance(includeArchived)
            .onStart { ensureSeeded() }
            .map { list -> list.map { it.toModel() } }
            .distinctUntilChanged()

    override fun observeBudget(month: YearMonth): Flow<BudgetState> = budgetDao.observeState(
        yearMonth = month.toString(),
        fromEpochDay = DateRanges.monthStart(month).toEpochDay(),
        toEpochDay = DateRanges.monthEnd(month).toEpochDay(),
    ).map { BudgetState(yearMonth = month, budgetCent = it.budgetCent, usedCent = it.usedCent) }
        .distinctUntilChanged()

    override fun observeSettings(): Flow<LedgerSettings> =
        settingsDao.observe()
            .onStart { ensureSeeded() }
            .map { it?.toModel() ?: Defaults.defaultSettings() }
            .distinctUntilChanged()

    override fun observeBackupRecord(): Flow<BackupRecord> =
        combine(settingsDao.observe(), transactionDao.observeActiveCount()) { settings, activeCount ->
            BackupRecord(
                succeededAt = settings?.lastBackupAtEpochMillis?.let { Instant.ofEpochMilli(it) },
                fileName = settings?.lastBackupFileName,
                transactionCount = activeCount,
            )
        }.distinctUntilChanged()

    // ═══════════════════════════ 初始化 ═══════════════════════════

    override suspend fun initializeIfNeeded(): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            MutationResult.Success()
        }

    /**
     * 幂等补种默认数据（分类 / 账户 / 设置）。多次调用不重复插入，也**不插入任何演示账单**。
     *
     * 该函数会顺带修复失效引用：默认账户指向不存在或已归档账户时，改指第一个可用账户；
     * 最近使用账户失效时置空。这样归档、恢复之后设置里的引用始终有效。
     */
    private suspend fun ensureSeeded() {
        db.withTransaction {
            val now = clock.now().toEpochMilli()

            val activeAccountId: String = accountDao.firstActive()?.id ?: run {
                val existingDefault = accountDao.getById(Defaults.DEFAULT_ACCOUNT_ID)
                if (existingDefault == null) {
                    accountDao.insert(Defaults.DEFAULT_ACCOUNT.toEntity(now))
                } else {
                    accountDao.setArchived(Defaults.DEFAULT_ACCOUNT_ID, false, now)
                }
                Defaults.DEFAULT_ACCOUNT_ID
            }

            val settings = settingsDao.get()
            if (settings == null) {
                settingsDao.insert(
                    Defaults.defaultSettings().copy(defaultAccountId = activeAccountId).toEntity(now),
                )
            } else {
                val defaultAccount = accountDao.getById(settings.defaultAccountId)
                if (defaultAccount == null || defaultAccount.isArchived) {
                    settingsDao.updateDefaultAccount(activeAccountId, now)
                }
                settings.lastUsedAccountId?.let { lastUsed ->
                    val account = accountDao.getById(lastUsed)
                    if (account == null || account.isArchived) {
                        settingsDao.updateLastUsedAccount(null, now)
                    }
                }
            }

            for (type in TransactionType.entries) {
                if (categoryDao.countByType(type) == 0) {
                    val specs = if (type == TransactionType.EXPENSE) {
                        Defaults.EXPENSE_CATEGORIES
                    } else {
                        Defaults.INCOME_CATEGORIES
                    }
                    categoryDao.insertAll(specs.map { it.toEntity(now) })
                }
            }
        }
    }

    // ═══════════════════════════ 写：账单 ═══════════════════════════

    override suspend fun createTransaction(draft: TransactionDraft, requestId: String): SaveResult =
        writeOperation({ SaveResult.Failure(it) }) {
            val normalizedRequestId = requestId.trim().takeIf { it.isNotEmpty() }
            db.withTransaction {
                normalizedRequestId?.let { key ->
                    val existing = transactionDao.findByRequestId(key)
                    if (existing != null) {
                        return@withTransaction idempotentResult(existing, draft)
                    }
                }
                validateDraft(draft, original = null)?.let { return@withTransaction SaveResult.Failure(it) }

                val now = clock.now()
                val entity = TransactionEntity(
                    id = idGenerator.newId(),
                    type = draft.type,
                    amountCent = draft.amountCent,
                    categoryId = draft.categoryId,
                    accountId = draft.accountId,
                    occurredOnEpochDay = draft.occurredOn.toEpochDay(),
                    note = draft.note,
                    noteKey = LedgerValidation.normalizeKey(draft.note),
                    createdAtEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                    deletedAtEpochMillis = null,
                    requestId = normalizedRequestId,
                )
                try {
                    transactionDao.insert(entity)
                } catch (e: SQLiteConstraintException) {
                    // 并发下唯一索引命中：复用已存在记录，绝不产生第二条。
                    val existing = normalizedRequestId?.let { transactionDao.findByRequestId(it) }
                        ?: throw e
                    return@withTransaction idempotentResult(existing, draft)
                }
                settingsDao.updateLastUsedAccount(draft.accountId, now.toEpochMilli())
                SaveResult.Success(entity.id, created = true)
            }
        }

    override suspend fun updateTransaction(id: String, draft: TransactionDraft): SaveResult =
        writeOperation({ SaveResult.Failure(it) }) {
            db.withTransaction {
                // 原记录必须从数据库读取，不接受 UI 声称的“原值”。
                val current = transactionDao.getById(id)
                    ?: return@withTransaction SaveResult.Failure(
                        LedgerError.NotFound("transaction", id, "记录不存在或已被删除"),
                    )
                if (current.deletedAtEpochMillis != null) {
                    return@withTransaction SaveResult.Failure(
                        LedgerError.NotFound("transaction", id, "记录不存在或已被删除"),
                    )
                }
                validateDraft(draft, original = current.toModel())
                    ?.let { return@withTransaction SaveResult.Failure(it) }

                val now = clock.now()
                val updated = transactionDao.updateActive(
                    id = id,
                    type = draft.type,
                    amountCent = draft.amountCent,
                    categoryId = draft.categoryId,
                    accountId = draft.accountId,
                    occurredOnEpochDay = draft.occurredOn.toEpochDay(),
                    note = draft.note,
                    noteKey = LedgerValidation.normalizeKey(draft.note),
                    updatedAtEpochMillis = now.toEpochMilli(),
                )
                if (updated == 0) {
                    return@withTransaction SaveResult.Failure(
                        LedgerError.NotFound("transaction", id, "记录不存在或已被删除"),
                    )
                }
                settingsDao.updateLastUsedAccount(draft.accountId, now.toEpochMilli())
                SaveResult.Success(id, created = false)
            }
        }

    override suspend fun softDeleteTransaction(id: String): Outcome<DeleteReceipt> =
        writeOperation({ Outcome.Failure(it) }) {
            db.withTransaction {
                val current = transactionDao.getById(id)
                    ?: return@withTransaction Outcome.Failure(
                        LedgerError.NotFound("transaction", id, "记录不存在或已被删除"),
                    )
                if (current.deletedAtEpochMillis != null) {
                    return@withTransaction Outcome.Failure(
                        LedgerError.NotFound("transaction", id, "记录不存在或已被删除"),
                    )
                }
                val now = clock.now()
                val changed = transactionDao.softDelete(
                    id = id,
                    deletedAtEpochMillis = now.toEpochMilli(),
                    updatedAtEpochMillis = now.toEpochMilli(),
                )
                if (changed == 0) {
                    return@withTransaction Outcome.Failure(
                        LedgerError.NotFound("transaction", id, "记录不存在或已被删除"),
                    )
                }
                val token = tokenGenerator()
                undoRegistry.register(id, token, now.toEpochMilli())
                Outcome.Success(
                    DeleteReceipt(
                        transactionId = id,
                        token = token,
                        deletedAt = now,
                        original = current.toModel(),
                    ),
                )
            }
        }

    override suspend fun undoDelete(receipt: DeleteReceipt): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                // 1) 令牌必须仍是该账单当前有效的删除事件令牌（用过即失效，被新删除替换也失效）。
                val entry = undoRegistry.current(receipt.transactionId)
                if (entry == null || entry.token != receipt.token) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Validation(
                            ValidationCode.UNDO_TOKEN_INVALID,
                            "撤销已失效",
                        ),
                    )
                }
                // 2) 撤销窗口：clock.now() <= receipt.deletedAt + 5000ms。
                val now = clock.now()
                val deadline = receipt.deletedAt.toEpochMilli() + Limits.UNDO_WINDOW_MILLIS
                if (now.toEpochMilli() > deadline) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Validation(
                            ValidationCode.UNDO_TOKEN_EXPIRED,
                            "撤销时间已过",
                        ),
                    )
                }
                // 3) 目标记录必须仍是同一删除事件产生的软删除状态。
                val current = transactionDao.getById(receipt.transactionId)
                if (current == null ||
                    current.deletedAtEpochMillis == null ||
                    current.deletedAtEpochMillis != receipt.deletedAt.toEpochMilli()
                ) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(
                            ValidationCode.UNDO_TARGET_CONFLICT,
                            "该记录状态已变化，无法撤销",
                        ),
                    )
                }
                // 4) 恢复原 ID 与原字段，不新建副本。
                val original = receipt.original
                val restored = transactionDao.restoreDeleted(
                    id = original.id,
                    type = original.type,
                    amountCent = original.amountCent,
                    categoryId = original.categoryId,
                    accountId = original.accountId,
                    occurredOnEpochDay = original.occurredOn.toEpochDay(),
                    note = original.note,
                    noteKey = LedgerValidation.normalizeKey(original.note),
                    updatedAtEpochMillis = now.toEpochMilli(),
                )
                if (restored == 0) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(
                            ValidationCode.UNDO_TARGET_CONFLICT,
                            "该记录状态已变化，无法撤销",
                        ),
                    )
                }
                undoRegistry.consume(receipt.transactionId)
                MutationResult.Success(receipt.transactionId)
            }
        }

    // ═══════════════════════════ 写：分类 ═══════════════════════════

    override suspend fun upsertCategory(command: CategoryCommand): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val name = command.name.trim()
                LedgerValidation.categoryName(name)?.let { return@withTransaction MutationResult.Failure(it) }
                LedgerValidation.iconKey(command.iconKey)
                    ?.let { return@withTransaction MutationResult.Failure(it) }
                val nameKey = LedgerValidation.normalizeKey(name)
                val now = clock.now().toEpochMilli()

                if (command.id == null) {
                    val duplicated = categoryDao.findByNameKey(command.type, nameKey)
                    if (duplicated != null) {
                        return@withTransaction MutationResult.Failure(
                            duplicateCategoryNameError(),
                        )
                    }
                    val sortOrder = command.sortOrder
                        ?: ((categoryDao.maxSortOrder(command.type) ?: -10) + 10)
                    val id = idGenerator.newId()
                    categoryDao.insert(
                        CategoryEntity(
                            id = id,
                            type = command.type,
                            name = name,
                            nameKey = nameKey,
                            iconKey = command.iconKey,
                            sortOrder = sortOrder,
                            isArchived = false,
                            isFallback = false,
                            createdAtEpochMillis = now,
                            updatedAtEpochMillis = now,
                        ),
                    )
                    MutationResult.Success(id)
                } else {
                    val existing = categoryDao.getById(command.id)
                        ?: return@withTransaction MutationResult.Failure(
                            LedgerError.NotFound("category", command.id),
                        )
                    if (existing.type != command.type) {
                        return@withTransaction MutationResult.Failure(
                            LedgerError.Conflict(
                                ValidationCode.CATEGORY_TYPE_IMMUTABLE,
                                "分类类型不可修改",
                            ),
                        )
                    }
                    val duplicated = categoryDao.findByNameKey(command.type, nameKey)
                    if (duplicated != null && duplicated.id != existing.id) {
                        return@withTransaction MutationResult.Failure(duplicateCategoryNameError())
                    }
                    categoryDao.updateDetails(
                        id = existing.id,
                        name = name,
                        nameKey = nameKey,
                        iconKey = command.iconKey,
                        sortOrder = command.sortOrder ?: existing.sortOrder,
                        updatedAtEpochMillis = now,
                    )
                    MutationResult.Success(existing.id)
                }
            }
        }

    override suspend fun reorderCategories(type: TransactionType, ids: List<String>): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val existing = categoryDao.listByType(type)
                val existingIds = existing.map { it.id }.toSet()
                val valid = ids.isNotEmpty() &&
                    ids.size == ids.toSet().size &&
                    ids.toSet() == existingIds
                if (!valid) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(
                            ValidationCode.CATEGORY_REORDER_INVALID,
                            "排序列表必须且只能包含该类型的全部分类",
                        ),
                    )
                }
                val now = clock.now().toEpochMilli()
                ids.forEachIndexed { index, id ->
                    categoryDao.updateSortOrder(id, index * 10, now)
                }
                MutationResult.Success()
            }
        }

    override suspend fun setCategoryArchived(id: String, archived: Boolean): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val existing = categoryDao.getById(id)
                    ?: return@withTransaction MutationResult.Failure(
                        LedgerError.NotFound("category", id),
                    )
                if (existing.isArchived == archived) {
                    return@withTransaction MutationResult.Success(id)
                }
                if (archived) {
                    if (existing.isFallback) {
                        return@withTransaction MutationResult.Failure(
                            LedgerError.Conflict(
                                ValidationCode.CATEGORY_FALLBACK_PROTECTED,
                                "兜底分类不可归档",
                            ),
                        )
                    }
                    val others = categoryDao.countOtherActive(existing.type, existing.id)
                    if (others == 0) {
                        return@withTransaction MutationResult.Failure(
                            LedgerError.Conflict(
                                ValidationCode.CATEGORY_LAST_ACTIVE,
                                "至少保留一个可用分类",
                            ),
                        )
                    }
                }
                categoryDao.setArchived(id, archived, clock.now().toEpochMilli())
                MutationResult.Success(id)
            }
        }

    // ═══════════════════════════ 写：账户 ═══════════════════════════

    override suspend fun migrateCategory(sourceId: String, targetId: String): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val source = categoryDao.getById(sourceId)
                    ?: return@withTransaction MutationResult.Failure(LedgerError.NotFound("category", sourceId))
                val target = categoryDao.getById(targetId)
                    ?: return@withTransaction MutationResult.Failure(LedgerError.NotFound("category", targetId))
                if (sourceId == targetId || source.type != target.type || target.isArchived) {
                    return@withTransaction MutationResult.Failure(LedgerError.Validation(
                        ValidationCode.CATEGORY_TYPE_MISMATCH, "请选择同类型的另一可用分类"))
                }
                transactionDao.migrateCategory(sourceId, targetId, clock.now().toEpochMilli())
                MutationResult.Success(targetId)
            }
        }

    override suspend fun upsertAccount(command: AccountCommand): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val name = command.name.trim()
                LedgerValidation.accountName(name)?.let { return@withTransaction MutationResult.Failure(it) }
                LedgerValidation.openingBalance(command.openingBalanceCent)
                    ?.let { return@withTransaction MutationResult.Failure(it) }
                if ((command.note?.length ?: 0) > Limits.MAX_NOTE_LENGTH) {
                    return@withTransaction MutationResult.Failure(LedgerError.Validation(
                        ValidationCode.NOTE_TOO_LONG, "备注过长"))
                }
                val nameKey = LedgerValidation.normalizeKey(name)
                val now = clock.now().toEpochMilli()

                if (command.id == null) {
                    if (accountDao.findActiveByNameKey(nameKey) != null) {
                        return@withTransaction MutationResult.Failure(duplicateAccountNameError())
                    }
                    val id = idGenerator.newId()
                    accountDao.insert(
                        AccountEntity(
                            id = id,
                            name = name,
                            nameKey = nameKey,
                            kind = command.kind,
                            openingBalanceCent = command.openingBalanceCent,
                            isArchived = false,
                            createdAtEpochMillis = now,
                            updatedAtEpochMillis = now,
                            note = command.note.orEmpty(),
                            openingHistory = com.blueledger.app.core.model.AssetHistory.record("", clock.today(), command.openingBalanceCent),
                        ),
                    )
                    MutationResult.Success(id)
                } else {
                    val existing = accountDao.getById(command.id)
                        ?: return@withTransaction MutationResult.Failure(
                            LedgerError.NotFound("account", command.id),
                        )
                    val duplicated = accountDao.findActiveByNameKey(nameKey)
                    // 只有“未归档账户范围”内唯一；归档账户改名不受已归档同名账户影响。
                    if (duplicated != null && duplicated.id != existing.id) {
                        return@withTransaction MutationResult.Failure(duplicateAccountNameError())
                    }
                    accountDao.updateDetails(
                        id = existing.id,
                        name = name,
                        nameKey = nameKey,
                        kind = command.kind.name,
                        openingBalanceCent = command.openingBalanceCent,
                        updatedAtEpochMillis = now,
                    )
                    val oldHistory = existing.openingHistory.ifEmpty {
                        com.blueledger.app.core.model.AssetHistory.record("", java.time.LocalDate.of(1970, 1, 1), existing.openingBalanceCent)
                    }
                    val history = if (command.openingBalanceCent == existing.openingBalanceCent) existing.openingHistory
                        else com.blueledger.app.core.model.AssetHistory.record(oldHistory, clock.today(), command.openingBalanceCent)
                    accountDao.updateMetadata(existing.id, command.note ?: existing.note, history)
                    MutationResult.Success(existing.id)
                }
            }
        }

    override suspend fun setAccountArchived(
        id: String,
        archived: Boolean,
        replacementDefaultId: String?,
    ): MutationResult = writeOperation({ MutationResult.Failure(it) }) {
        db.withTransaction {
            val existing = accountDao.getById(id)
                ?: return@withTransaction MutationResult.Failure(LedgerError.NotFound("account", id))
            val now = clock.now().toEpochMilli()

            if (archived) {
                if (existing.isArchived) return@withTransaction MutationResult.Success(id)
                if (accountDao.countActive() <= 1) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(ValidationCode.ACCOUNT_LAST_ACTIVE, "至少保留一个可用账户"),
                    )
                }
                val settings = settingsDao.get()
                if (settings?.defaultAccountId == id) {
                    val replacement = replacementDefaultId?.takeIf { it.isNotBlank() && it != id }
                        ?: return@withTransaction MutationResult.Failure(
                            LedgerError.Conflict(
                                ValidationCode.ACCOUNT_ARCHIVE_REQUIRES_REPLACEMENT,
                                "归档默认账户前请先选择新的默认账户",
                            ),
                        )
                    val replacementAccount = accountDao.getById(replacement)
                    if (replacementAccount == null || replacementAccount.isArchived) {
                        return@withTransaction MutationResult.Failure(
                            LedgerError.Conflict(
                                ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT,
                                "新默认账户不存在或已归档",
                            ),
                        )
                    }
                    settingsDao.updateDefaultAccount(replacement, now)
                }
                if (settings?.lastUsedAccountId == id) {
                    settingsDao.updateLastUsedAccount(null, now)
                }
                accountDao.setArchived(id, true, now)
                MutationResult.Success(id)
            } else {
                if (!existing.isArchived) return@withTransaction MutationResult.Success(id)
                // 恢复归档账户时若与现有可用账户重名，必须先重命名，不能破坏唯一约束。
                if (accountDao.findActiveByNameKey(existing.nameKey) != null) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(
                            ValidationCode.ACCOUNT_NAME_DUPLICATE,
                            "已有同名可用账户，请先重命名",
                        ),
                    )
                }
                accountDao.setArchived(id, false, now)
                MutationResult.Success(id)
            }
        }
    }

    override suspend fun setDefaultAccount(id: String): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val account = accountDao.getById(id)
                    ?: return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT, "账户不存在"),
                    )
                if (account.isArchived) {
                    return@withTransaction MutationResult.Failure(
                        LedgerError.Conflict(
                            ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT,
                            "归档账户不能设为默认账户",
                        ),
                    )
                }
                settingsDao.updateDefaultAccount(id, clock.now().toEpochMilli())
                MutationResult.Success(id)
            }
        }

    // ═══════════════════════════ 写：预算与设置 ═══════════════════════════

    override suspend fun setMonthlyBudget(amountCent: Long?, selectedMonth: YearMonth): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            if (amountCent != null) LedgerValidation.budget(amountCent)?.let { return@writeOperation MutationResult.Failure(it) }
            db.withTransaction {
                settingsDao.updateMonthlyBudget(amountCent, clock.now().toEpochMilli())
                budgetDao.delete(selectedMonth.toString())
                MutationResult.Success()
            }
        }

    override suspend fun setBudget(month: YearMonth, amountCent: Long): MutationResult {
        LedgerValidation.budget(amountCent)?.let { error -> return MutationResult.Failure(error) }
        return writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                val now = clock.now().toEpochMilli()
                val existing = budgetDao.get(month.toString())
                budgetDao.upsert(
                    BudgetEntity(
                        yearMonth = month.toString(),
                        amountCent = amountCent,
                        createdAtEpochMillis = existing?.createdAtEpochMillis ?: now,
                        updatedAtEpochMillis = now,
                    ),
                )
                MutationResult.Success(month.toString())
            }
        }
    }

    override suspend fun removeBudget(month: YearMonth): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                budgetDao.delete(month.toString())
                MutationResult.Success(month.toString())
            }
        }

    override suspend fun setHideAmounts(hidden: Boolean): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                settingsDao.updateHideAmounts(hidden, clock.now().toEpochMilli())
                MutationResult.Success()
            }
        }

    override suspend fun recordBackupSuccess(at: Instant, fileName: String): MutationResult =
        writeOperation({ MutationResult.Failure(it) }) {
            db.withTransaction {
                settingsDao.updateBackupRecord(
                    atEpochMillis = at.toEpochMilli(),
                    fileName = fileName,
                    updatedAtEpochMillis = clock.now().toEpochMilli(),
                )
                MutationResult.Success()
            }
        }

    // ═══════════════════════════ 备份与恢复 ═══════════════════════════

    override suspend fun exportConsistentSnapshot(): LedgerSnapshot = db.withTransaction {
        val settings = settingsDao.get()?.toModel() ?: Defaults.defaultSettings()
        LedgerSnapshot(
            exportedAt = clock.now(),
            currency = settings.currency,
            transactions = transactionDao.getAllActive().map { it.toModel() },
            categories = categoryDao.listAll().map { it.toModel() },
            accounts = accountDao.listAll().map { it.toModel() },
            budgets = budgetDao.listAll().map { it.toModel() },
            settings = settings,
        )
    }

    override suspend fun restoreValidatedSnapshot(
        snapshot: ValidatedLedgerSnapshot,
    ): RestoreResult = try {
        writeMutex.withLock {
            db.withTransaction {
                val incoming = snapshot.snapshot
                // 写入前再次核对日期、引用与业务限制；任何失败都保留旧库。
                SnapshotValidator.validate(incoming, clock.today())
                    ?.let { return@withTransaction RestoreResult.Failure(it) }

                val now = clock.now().toEpochMilli()
                val currentSettings = settingsDao.get()

                transactionDao.deleteAll()
                categoryDao.deleteAll()
                accountDao.deleteAll()
                budgetDao.deleteAll()
                settingsDao.deleteAll()

                if (incoming.transactions.isNotEmpty()) {
                    transactionDao.insertAll(incoming.transactions.map { it.toEntity() })
                }
                if (incoming.categories.isNotEmpty()) {
                    categoryDao.insertAll(incoming.categories.map { it.toEntity(now) })
                }
                if (incoming.accounts.isNotEmpty()) {
                    accountDao.insertAll(incoming.accounts.map { it.toEntity(now) })
                }
                if (incoming.budgets.isNotEmpty()) {
                    budgetDao.insertAll(incoming.budgets.map { it.toEntity(now) })
                }

                val restoredAccountIds = incoming.accounts.map { it.id }.toSet()
                val archivedAccountIds = incoming.accounts.filter { it.isArchived }.map { it.id }.toSet()
                val lastUsed = incoming.settings.lastUsedAccountId
                    ?.takeIf { it in restoredAccountIds && it !in archivedAccountIds }
                settingsDao.insert(
                    SettingsEntity(
                        defaultAccountId = incoming.settings.defaultAccountId,
                        lastUsedAccountId = lastUsed,
                        hideAmounts = incoming.settings.hideAmounts,
                        monthlyBudgetCent = incoming.settings.monthlyBudgetCent,
                        currency = incoming.settings.currency,
                        // 恢复后保留设备当前的“最近成功备份时间”，不把备份文件里的旧时间当作刚备份成功。
                        lastBackupAtEpochMillis = currentSettings?.lastBackupAtEpochMillis,
                        lastBackupFileName = currentSettings?.lastBackupFileName,
                        updatedAtEpochMillis = now,
                    ),
                )

                // 旧账本的删除凭据不得作用于新账本。
                undoRegistry.clear()

                RestoreResult.Success(
                    transactionCount = incoming.transactions.count { it.deletedAt == null },
                    categoryCount = incoming.categories.size,
                    accountCount = incoming.accounts.size,
                    budgetCount = incoming.budgets.size,
                )
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        RestoreResult.Failure(storageError(e, "恢复失败，已保留原有数据"))
    }

    // ═══════════════════════════ 内部工具 ═══════════════════════════

    /** 统一写操作外壳：串行化 + 补种默认数据 + 异常转可读失败，绝不吞掉异常后返回成功。 */
    private suspend fun <R> writeOperation(
        onFailure: (LedgerError) -> R,
        block: suspend () -> R,
    ): R = try {
        writeMutex.withLock {
            ensureSeeded()
            block()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        onFailure(storageError(e))
    }

    private fun idempotentResult(existing: TransactionEntity, draft: TransactionDraft): SaveResult {
        val matches = existing.toModel().matchesDraft(
            type = draft.type,
            amountCent = draft.amountCent,
            categoryId = draft.categoryId,
            accountId = draft.accountId,
            occurredOn = draft.occurredOn,
            note = draft.note,
        )
        return if (matches) {
            SaveResult.Success(existing.id, created = false)
        } else {
            SaveResult.Failure(
                LedgerError.Validation(
                    ValidationCode.DUPLICATE_REQUEST,
                    "该笔账单已保存，请勿重复提交",
                ),
            )
        }
    }

    private suspend fun validateDraft(
        draft: TransactionDraft,
        original: LedgerTransaction?,
    ): LedgerError? {
        val today = clock.today()
        LedgerValidation.amount(draft.amountCent)?.let { return it }
        LedgerValidation.note(draft.note)?.let { return it }
        LedgerValidation.occurredOn(draft.occurredOn, today)?.let { return it }

        if (draft.categoryId.isBlank()) {
            return LedgerError.Validation(ValidationCode.CATEGORY_REQUIRED, "请选择分类")
        }
        val category = categoryDao.getById(draft.categoryId)
            ?: return LedgerError.Validation(ValidationCode.CATEGORY_NOT_FOUND, "分类不存在")
        if (category.type != draft.type) {
            return LedgerError.Validation(ValidationCode.CATEGORY_TYPE_MISMATCH, "分类与收支类型不一致")
        }
        // 编辑时允许保留“数据库原记录中的同一分类”，且要求类型未变化；其余情况必须使用未归档分类。
        val keepsOriginalCategory = original != null &&
            original.categoryId == draft.categoryId &&
            original.type == draft.type
        if (category.isArchived && !keepsOriginalCategory) {
            return LedgerError.Validation(ValidationCode.CATEGORY_ARCHIVED, "该分类已归档，请选择其它分类")
        }

        if (draft.accountId.isBlank()) {
            return LedgerError.Validation(ValidationCode.ACCOUNT_REQUIRED, "请选择账户")
        }
        val account = accountDao.getById(draft.accountId)
            ?: return LedgerError.Validation(ValidationCode.ACCOUNT_NOT_FOUND, "账户不存在")
        val keepsOriginalAccount = original != null && original.accountId == draft.accountId
        if (account.isArchived && !keepsOriginalAccount) {
            return LedgerError.Validation(ValidationCode.ACCOUNT_ARCHIVED, "该账户已归档，请选择其它账户")
        }
        return null
    }

    private fun filterEpochDayRange(filter: TransactionFilter): EpochDayBounds {
        var from = filter.from
        var to = filter.to
        val month = filter.yearMonth
        if (month != null) {
            val start = DateRanges.monthStart(month)
            val end = DateRanges.monthEnd(month)
            from = if (from == null) start else maxOf(from, start)
            to = if (to == null) end else minOf(to, end)
        }
        return EpochDayBounds(fromInclusive = from?.toEpochDay(), toInclusive = to?.toEpochDay())
    }

    /** 归一化查询词：trim + lowercase，`%`/`_`/`\` 按普通字符转义。 */
    private fun searchPattern(raw: String): String {
        val normalized = LedgerValidation.normalizeKey(raw)
        if (normalized.isEmpty()) return ""
        return "%" + LedgerValidation.escapeLike(normalized) + "%"
    }

    private fun buildMonthAnalysis(
        month: YearMonth,
        type: TransactionType,
        start: LocalDate,
        periodEnd: LocalDate,
        rows: List<MonthRow>,
    ): MonthAnalysis {
        var incomeCent = 0L
        var expenseCent = 0L
        var count = 0
        var typeTotalCent = 0L
        val dailyTotals = HashMap<Long, Long>()
        val sliceAccumulators = LinkedHashMap<String, SliceAccumulator>()

        for (row in rows) {
            count += 1
            if (row.type == TransactionType.INCOME) {
                incomeCent = Math.addExact(incomeCent, row.amountCent)
            } else {
                expenseCent = Math.addExact(expenseCent, row.amountCent)
            }
            if (row.type != type) continue
            typeTotalCent = Math.addExact(typeTotalCent, row.amountCent)
            dailyTotals[row.occurredOnEpochDay] =
                Math.addExact(dailyTotals[row.occurredOnEpochDay] ?: 0L, row.amountCent)
            val accumulator = sliceAccumulators.getOrPut(row.categoryId) {
                SliceAccumulator(
                    categoryId = row.categoryId,
                    name = row.categoryName,
                    iconKey = row.categoryIconKey,
                    sortOrder = row.categorySortOrder,
                )
            }
            accumulator.amountCent = Math.addExact(accumulator.amountCent, row.amountCent)
        }

        val daily = DateRanges.daysBetween(start, periodEnd).map { date ->
            DailyAmount(date = date, amountCent = dailyTotals[date.toEpochDay()] ?: 0L)
        }
        val slices = sliceAccumulators.values
            .filter { it.amountCent > 0L }
            .sortedWith(
                compareByDescending<SliceAccumulator> { it.amountCent }
                    .thenBy { it.sortOrder }
                    .thenBy { it.categoryId },
            )
            .map {
                CategorySlice(
                    categoryId = it.categoryId,
                    name = it.name.ifEmpty { "未知分类" },
                    iconKey = it.iconKey,
                    amountCent = it.amountCent,
                    ratioPermille = ratioPermille(it.amountCent, typeTotalCent),
                )
            }

        return MonthAnalysis(
            month = month,
            type = type,
            incomeCent = incomeCent,
            expenseCent = expenseCent,
            count = count,
            typeTotalCent = typeTotalCent,
            daily = daily,
            slices = slices,
            periodEnd = periodEnd,
        )
    }

    private fun buildYearAnalysis(year: Int, today: LocalDate, rows: List<YearRow>): YearAnalysis {
        val incomeByMonth = LongArray(13)
        val expenseByMonth = LongArray(13)
        val countByMonth = IntArray(13)
        var incomeCent = 0L
        var expenseCent = 0L
        var count = 0

        for (row in rows) {
            val month = DateRanges.dateOf(row.occurredOnEpochDay).monthValue
            if (month !in 1..12) continue
            count += 1
            countByMonth[month] += 1
            if (row.type == TransactionType.INCOME) {
                incomeCent = Math.addExact(incomeCent, row.amountCent)
                incomeByMonth[month] = Math.addExact(incomeByMonth[month], row.amountCent)
            } else {
                expenseCent = Math.addExact(expenseCent, row.amountCent)
                expenseByMonth[month] = Math.addExact(expenseByMonth[month], row.amountCent)
            }
        }

        val months = (1..12).map { month ->
            YearMonthAmount(
                month = month,
                incomeCent = incomeByMonth[month],
                expenseCent = expenseByMonth[month],
                reached = DateRanges.isMonthReached(year, month, today),
                hasRecords = countByMonth[month] > 0,
            )
        }
        return YearAnalysis(
            year = year,
            incomeCent = incomeCent,
            expenseCent = expenseCent,
            count = count,
            months = months,
            cutoff = DateRanges.yearPeriodEnd(year, today),
            isCurrentYear = year == today.year,
            reachedMonthCount = DateRanges.reachedMonthCount(year, today),
        )
    }

    private fun unreachedYearMonths(): List<YearMonthAmount> =
        (1..12).map { YearMonthAmount(month = it, reached = false, hasRecords = false) }

    /** 百分比 × 10，四舍五入；总额为 0 时返回 0，不产生 NaN。 */
    private fun ratioPermille(amountCent: Long, totalCent: Long): Int {
        if (totalCent <= 0L || amountCent <= 0L) return 0
        if (amountCent <= Long.MAX_VALUE / 1000L) {
            return ((amountCent * 1000L + totalCent / 2L) / totalCent).toInt()
        }
        return BigDecimal.valueOf(amountCent)
            .multiply(BigDecimal.valueOf(1000L))
            .divide(BigDecimal.valueOf(totalCent), 0, RoundingMode.HALF_UP)
            .toInt()
    }

    private fun duplicateCategoryNameError(): LedgerError.Conflict = LedgerError.Conflict(
        ValidationCode.CATEGORY_NAME_DUPLICATE,
        "同类型已存在同名分类",
    )

    private fun duplicateAccountNameError(): LedgerError.Conflict = LedgerError.Conflict(
        ValidationCode.ACCOUNT_NAME_DUPLICATE,
        "已存在同名账户",
    )

    private fun storageError(e: Throwable, message: String = "数据保存失败，请重试"): LedgerError.Storage =
        LedgerError.Storage(message = message, causeClass = e::class.java.simpleName)

    private class SliceAccumulator(
        val categoryId: String,
        val name: String,
        val iconKey: String,
        val sortOrder: Int,
        var amountCent: Long = 0L,
    )
}

// ═══════════════════════════ 映射 ═══════════════════════════

private const val UNKNOWN_CATEGORY_NAME = "未知分类"
private const val UNKNOWN_ACCOUNT_NAME = "未知账户"

internal fun TransactionEntity.toModel(): LedgerTransaction = LedgerTransaction(
    id = id,
    type = type,
    amountCent = amountCent,
    categoryId = categoryId,
    accountId = accountId,
    occurredOn = DateRanges.dateOf(occurredOnEpochDay),
    note = note,
    createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
    deletedAt = deletedAtEpochMillis?.let { Instant.ofEpochMilli(it) },
)

internal fun LedgerTransaction.toEntity(requestId: String? = null): TransactionEntity = TransactionEntity(
    id = id,
    type = type,
    amountCent = amountCent,
    categoryId = categoryId,
    accountId = accountId,
    occurredOnEpochDay = occurredOn.toEpochDay(),
    note = note,
    noteKey = LedgerValidation.normalizeKey(note),
    createdAtEpochMillis = createdAt.toEpochMilli(),
    updatedAtEpochMillis = updatedAt.toEpochMilli(),
    deletedAtEpochMillis = deletedAt?.toEpochMilli(),
    requestId = requestId,
)

internal fun TransactionRefRow.toTransactionWithRefs(): TransactionWithRefs = TransactionWithRefs(
    transaction = LedgerTransaction(
        id = id,
        type = type,
        amountCent = amountCent,
        categoryId = categoryId,
        accountId = accountId,
        occurredOn = DateRanges.dateOf(occurredOnEpochDay),
        note = note,
        createdAt = Instant.ofEpochMilli(createdAtEpochMillis),
        updatedAt = Instant.ofEpochMilli(updatedAtEpochMillis),
        deletedAt = deletedAtEpochMillis?.let { Instant.ofEpochMilli(it) },
    ),
    categoryName = categoryName.ifEmpty { UNKNOWN_CATEGORY_NAME },
    categoryIconKey = categoryIconKey,
    categoryArchived = categoryArchived,
    accountName = accountName.ifEmpty { UNKNOWN_ACCOUNT_NAME },
)

internal fun CategoryEntity.toModel(): LedgerCategory = LedgerCategory(
    id = id,
    type = type,
    name = name,
    iconKey = iconKey,
    sortOrder = sortOrder,
    isArchived = isArchived,
    isFallback = isFallback,
)

internal fun LedgerCategory.toEntity(nowMillis: Long): CategoryEntity = CategoryEntity(
    id = id,
    type = type,
    name = name.trim(),
    nameKey = LedgerValidation.normalizeKey(name),
    iconKey = iconKey,
    sortOrder = sortOrder,
    isArchived = isArchived,
    isFallback = isFallback,
    createdAtEpochMillis = nowMillis,
    updatedAtEpochMillis = nowMillis,
)

internal fun Defaults.CategorySpec.toEntity(nowMillis: Long): CategoryEntity = toCategory().toEntity(nowMillis)

internal fun AccountEntity.toModel(): LedgerAccount = LedgerAccount(
    id = id,
    name = name,
    kind = kind,
    openingBalanceCent = openingBalanceCent,
    isArchived = isArchived,
    note = note,
    openingHistory = openingHistory,
)

internal fun LedgerAccount.toEntity(nowMillis: Long): AccountEntity = AccountEntity(
    id = id,
    name = name.trim(),
    nameKey = LedgerValidation.normalizeKey(name),
    kind = kind,
    openingBalanceCent = openingBalanceCent,
    isArchived = isArchived,
    createdAtEpochMillis = nowMillis,
    updatedAtEpochMillis = nowMillis,
    note = note,
    openingHistory = openingHistory,
)

internal fun AccountBalanceRow.toModel(): AccountWithBalance = AccountWithBalance(
    account = LedgerAccount(
        id = id,
        name = name,
        kind = kind,
        openingBalanceCent = openingBalanceCent,
        isArchived = isArchived,
        note = note,
        openingHistory = openingHistory,
    ),
    balanceCent = balanceCent,
    transactionCount = transactionCount,
)

internal fun BudgetEntity.toModel(): com.blueledger.app.core.model.MonthlyBudget =
    com.blueledger.app.core.model.MonthlyBudget(
        yearMonth = YearMonth.parse(yearMonth),
        amountCent = amountCent,
    )

internal fun com.blueledger.app.core.model.MonthlyBudget.toEntity(nowMillis: Long): BudgetEntity = BudgetEntity(
    yearMonth = yearMonth.toString(),
    amountCent = amountCent,
    createdAtEpochMillis = nowMillis,
    updatedAtEpochMillis = nowMillis,
)

internal fun SettingsEntity.toModel(): LedgerSettings = LedgerSettings(
    defaultAccountId = defaultAccountId,
    lastUsedAccountId = lastUsedAccountId,
    hideAmounts = hideAmounts,
    currency = currency,
    lastBackupAt = lastBackupAtEpochMillis?.let { Instant.ofEpochMilli(it) },
    lastBackupFileName = lastBackupFileName,
    monthlyBudgetCent = monthlyBudgetCent,
)

internal fun LedgerSettings.toEntity(nowMillis: Long): SettingsEntity = SettingsEntity(
    defaultAccountId = defaultAccountId,
    lastUsedAccountId = lastUsedAccountId,
    hideAmounts = hideAmounts,
    currency = currency,
    lastBackupAtEpochMillis = lastBackupAt?.toEpochMilli(),
    lastBackupFileName = lastBackupFileName,
    updatedAtEpochMillis = nowMillis,
    monthlyBudgetCent = monthlyBudgetCent,
)
