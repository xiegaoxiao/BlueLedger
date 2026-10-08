package com.blueledger.app.demo

import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.BackupRecord
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.CategoryIcons
import com.blueledger.app.core.model.CategorySlice
import com.blueledger.app.core.model.DailyAmount
import com.blueledger.app.core.model.Defaults
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.FieldRef
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * G0 阶段由总控提供的**共享内存仓库**。
 *
 * 定位（重要）：
 * - 它是 UI 代理在真实 Room 实现就绪前的联调对象，也是验收夹具的注入点。
 * - 它**不是**正式用户数据路径：AppContainer 在正式构建中一律使用 A1 的 Room 实现。
 * - 行为必须与 Room 实现保持同一口径（同样的排序键、同样的校验、同样的聚合），
 *   否则用它开发出来的 UI 会在联调时行为不一致。
 *
 * 与冻结契约一致的关键点：
 * - amountCent 全为 Long 整数分，聚合不做任何浮点累加。
 * - 排序：occurredOn DESC → createdAt DESC → id ASC。
 * - 搜索：备注/分类名/账户名 trim + lowercase 后 contains，`%`/`_` 是普通字符。
 * - 撤销凭据 5 秒有效、一次性、与删除事件绑定。
 * - 分类排行同额：amountCent DESC → sortOrder ASC → id ASC。
 */
class InMemoryLedgerRepository(
    private val clock: Clock,
    initialCategories: List<LedgerCategory> = emptyList(),
    initialAccounts: List<com.blueledger.app.core.model.LedgerAccount> = emptyList(),
    initialTransactions: List<LedgerTransaction> = emptyList(),
    initialBudgets: Map<YearMonth, Long> = emptyMap(),
    initialSettings: LedgerSettings? = null,
) : LedgerRepository {

    /** 错误注入：返回非 null 时对应写操作直接失败，用于验证 UI 的失败态。 */
    var failNextWrite: LedgerError? = null

    private data class State(
        val categories: List<LedgerCategory>,
        val accounts: List<com.blueledger.app.core.model.LedgerAccount>,
        val transactions: List<LedgerTransaction>,
        val budgets: Map<YearMonth, Long>,
        val settings: LedgerSettings,
        val processedRequestIds: Map<String, String>,
        val activeDeleteTokens: Map<String, String>,
        val deleteSequence: Long,
    )

    private val state = MutableStateFlow(
        State(
            categories = initialCategories,
            accounts = initialAccounts,
            transactions = initialTransactions,
            budgets = initialBudgets,
            settings = initialSettings ?: Defaults.defaultSettings(),
            processedRequestIds = emptyMap(),
            activeDeleteTokens = emptyMap(),
            deleteSequence = 0L,
        ),
    )

    // ───────────────────────── 读 ─────────────────────────

    override fun observeTransactions(filter: TransactionFilter): Flow<TransactionPageState> =
        state.map { s ->
            val matched = sortedForList(s.transactions.filter { matches(it, filter, s) })
            val items = when {
                filter.offset >= matched.size -> emptyList()
                filter.limit >= matched.size - filter.offset -> matched.drop(filter.offset)
                else -> matched.drop(filter.offset).take(filter.limit)
            }
            TransactionPageState(
                items = items.map { it.withRefs(s) },
                totalCount = matched.size,
                hasMore = filter.offset + items.size < matched.size,
            )
        }

    override fun observeFilteredSummary(filter: TransactionFilter): Flow<MoneySummary> =
        state.map { s -> summarize(s.transactions.filter { matches(it, filter, s) }) }

    override fun observeTransaction(id: String): Flow<LedgerTransaction?> =
        state.map { s -> s.transactions.firstOrNull { it.id == id && it.deletedAt == null } }

    override fun observeMonthSummary(month: YearMonth): Flow<MoneySummary> =
        state.map { s -> summarize(s.transactions.filter { it.deletedAt == null && YearMonth.from(it.occurredOn) == month }) }

    override fun observeMonthAnalysis(month: YearMonth, type: TransactionType): Flow<MonthAnalysis> =
        state.map { s -> buildMonthAnalysis(s, month, type) }

    override fun observeYearAnalysis(year: Int): Flow<YearAnalysis> =
        state.map { s -> buildYearAnalysis(s, year) }

    override fun observeCategories(type: TransactionType, includeArchived: Boolean): Flow<List<LedgerCategory>> =
        state.map { s ->
            s.categories
                .filter { it.type == type && (includeArchived || !it.isArchived) }
                .sortedWith(compareBy({ it.sortOrder }, { it.id }))
        }

    override fun observeAccounts(includeArchived: Boolean): Flow<List<AccountWithBalance>> =
        state.map { s ->
            s.accounts
                .filter { includeArchived || !it.isArchived }
                .sortedWith(compareBy({ it.isArchived }, { it.name }))
                .map { account -> account.withBalance(s) }
        }

    override fun observeBudget(month: YearMonth): Flow<BudgetState> =
        state.map { s ->
            BudgetState(
                yearMonth = month,
                budgetCent = s.budgets[month] ?: s.settings.monthlyBudgetCent,
                usedCent = s.transactions
                    .filter { it.deletedAt == null && it.type == TransactionType.EXPENSE && YearMonth.from(it.occurredOn) == month }
                    .sumOf { it.amountCent },
            )
        }

    override fun observeSettings(): Flow<LedgerSettings> = state.map { it.settings }

    override fun observeBackupRecord(): Flow<BackupRecord> = state.map { s ->
        BackupRecord(
            succeededAt = s.settings.lastBackupAt,
            fileName = s.settings.lastBackupFileName,
            transactionCount = s.transactions.count { it.deletedAt == null },
        )
    }

    // ───────────────────────── 写 ─────────────────────────

    override suspend fun initializeIfNeeded(): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        state.update { s ->
            val categories = if (s.categories.isEmpty()) Defaults.CATEGORIES.map { it.toCategory() } else s.categories
            val accounts = if (s.accounts.isEmpty()) listOf(Defaults.DEFAULT_ACCOUNT) else s.accounts
            val defaultId = s.settings.defaultAccountId.takeIf { id -> accounts.any { it.id == id && !it.isArchived } }
                ?: accounts.firstOrNull { !it.isArchived }?.id
                ?: accounts.first().id
            s.copy(
                categories = categories,
                accounts = accounts,
                settings = s.settings.copy(defaultAccountId = defaultId),
            )
        }
        return MutationResult.Success()
    }

    override suspend fun createTransaction(draft: TransactionDraft, requestId: String): SaveResult {
        failNextWrite?.let { failNextWrite = null; return SaveResult.Failure(it) }
        var result: SaveResult = SaveResult.Failure(storageFailure())
        state.update { s ->
            s.processedRequestIds[requestId]?.let { existing ->
                result = SaveResult.Success(existing, created = false)
                return@update s
            }
            validateDraft(draft, s, editingId = null)?.let { error ->
                result = SaveResult.Failure(error)
                return@update s
            }
            val now = clock.now()
            val id = UUID.randomUUID().toString()
            val tx = LedgerTransaction(
                id = id,
                type = draft.type,
                amountCent = draft.amountCent,
                categoryId = draft.categoryId,
                accountId = draft.accountId,
                occurredOn = draft.occurredOn,
                note = draft.note,
                createdAt = now,
                updatedAt = now,
                deletedAt = null,
            )
            result = SaveResult.Success(id, created = true)
            s.copy(
                transactions = s.transactions + tx,
                processedRequestIds = s.processedRequestIds + (requestId to id),
                settings = s.settings.copy(lastUsedAccountId = draft.accountId),
            )
        }
        return result
    }

    override suspend fun updateTransaction(id: String, draft: TransactionDraft): SaveResult {
        failNextWrite?.let { failNextWrite = null; return SaveResult.Failure(it) }
        var result: SaveResult = SaveResult.Failure(
            LedgerError.NotFound("transaction", id),
        )
        state.update { s ->
            val existing = s.transactions.firstOrNull { it.id == id && it.deletedAt == null }
            if (existing == null) {
                result = SaveResult.Failure(LedgerError.NotFound("transaction", id))
                return@update s
            }
            validateDraft(draft, s, editingId = id)?.let { error ->
                result = SaveResult.Failure(error)
                return@update s
            }
            val updated = existing.copy(
                type = draft.type,
                amountCent = draft.amountCent,
                categoryId = draft.categoryId,
                accountId = draft.accountId,
                occurredOn = draft.occurredOn,
                note = draft.note,
                updatedAt = clock.now(),
            )
            result = SaveResult.Success(id, created = false)
            s.copy(
                transactions = s.transactions.map { if (it.id == id) updated else it },
                settings = s.settings.copy(lastUsedAccountId = draft.accountId),
            )
        }
        return result
    }

    override suspend fun softDeleteTransaction(id: String): Outcome<DeleteReceipt> {
        failNextWrite?.let { failNextWrite = null; return Outcome.Failure(it) }
        var result: Outcome<DeleteReceipt> = Outcome.Failure(LedgerError.NotFound("transaction", id))
        state.update { s ->
            val existing = s.transactions.firstOrNull { it.id == id && it.deletedAt == null }
            if (existing == null) {
                result = Outcome.Failure(LedgerError.NotFound("transaction", id))
                return@update s
            }
            val deletedAt = clock.now()
            val seq = s.deleteSequence + 1
            val token = "$id#$seq"
            val receipt = DeleteReceipt(transactionId = id, token = token, deletedAt = deletedAt, original = existing)
            result = Outcome.Success(receipt)
            s.copy(
                transactions = s.transactions.map { if (it.id == id) it.copy(deletedAt = deletedAt, updatedAt = deletedAt) else it },
                activeDeleteTokens = s.activeDeleteTokens + (id to token),
                deleteSequence = seq,
            )
        }
        return result
    }

    override suspend fun undoDelete(receipt: DeleteReceipt): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        var result: MutationResult = MutationResult.Failure(
            LedgerError.Validation(ValidationCode.UNDO_TOKEN_INVALID, "撤销已失效，请重新确认这笔账单", FieldRef.AMOUNT),
        )
        state.update { s ->
            val activeToken = s.activeDeleteTokens[receipt.transactionId]
            val existing = s.transactions.firstOrNull { it.id == receipt.transactionId }
            when {
                activeToken == null || activeToken != receipt.token || existing?.deletedAt == null -> {
                    result = MutationResult.Failure(
                        LedgerError.Validation(ValidationCode.UNDO_TOKEN_INVALID, "撤销已失效，请重新确认这笔账单"),
                    )
                    s
                }
                clock.now().isAfter(receipt.deletedAt.plusMillis(Limits.UNDO_WINDOW_MILLIS)) -> {
                    result = MutationResult.Failure(
                        LedgerError.Validation(ValidationCode.UNDO_TOKEN_EXPIRED, "撤销时间已过（5 秒），账单仍可在账单列表中查看"),
                    )
                    s.copy(activeDeleteTokens = s.activeDeleteTokens - receipt.transactionId)
                }
                else -> {
                    result = MutationResult.Success(receipt.transactionId)
                    s.copy(
                        transactions = s.transactions.map {
                            if (it.id == receipt.transactionId) receipt.original else it
                        },
                        activeDeleteTokens = s.activeDeleteTokens - receipt.transactionId,
                    )
                }
            }
        }
        return result
    }

    override suspend fun upsertCategory(command: CategoryCommand): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        val name = command.name.trim()
        if (name.length < Limits.MIN_CATEGORY_NAME_LENGTH) {
            return MutationResult.Failure(LedgerError.Validation(ValidationCode.CATEGORY_NAME_EMPTY, "请输入分类名称", FieldRef.NAME))
        }
        if (name.length > Limits.MAX_CATEGORY_NAME_LENGTH) {
            return MutationResult.Failure(
                LedgerError.Validation(ValidationCode.CATEGORY_NAME_TOO_LONG, "分类名称最多 ${Limits.MAX_CATEGORY_NAME_LENGTH} 个字符", FieldRef.NAME),
            )
        }
        if (!CategoryIcons.isValidKey(command.iconKey)) {
            return MutationResult.Failure(LedgerError.Validation(ValidationCode.ICON_KEY_INVALID, "请选择有效的分类图标", FieldRef.ICON))
        }

        var result: MutationResult = MutationResult.Success()
        state.update { s ->
            val duplicate = s.categories.any {
                it.type == command.type && it.id != command.id && it.name.equals(name, ignoreCase = false)
            }
            if (duplicate) {
                result = MutationResult.Failure(
                    LedgerError.Conflict(ValidationCode.CATEGORY_NAME_DUPLICATE, "同类型下已存在名为「$name」的分类"),
                )
                return@update s
            }
            if (command.id == null) {
                val id = "cat_" + UUID.randomUUID().toString().take(12)
                val order = command.sortOrder ?: ((s.categories.filter { it.type == command.type }.maxOfOrNull { it.sortOrder } ?: -10) + 10)
                result = MutationResult.Success(id)
                s.copy(
                    categories = s.categories + LedgerCategory(
                        id = id, type = command.type, name = name, iconKey = command.iconKey,
                        sortOrder = order, isArchived = false, isFallback = false,
                    ),
                )
            } else {
                val existing = s.categories.firstOrNull { it.id == command.id }
                    ?: return@update run {
                        result = MutationResult.Failure(LedgerError.NotFound("category", command.id))
                        s
                    }
                if (existing.type != command.type) {
                    result = MutationResult.Failure(
                        LedgerError.Conflict(ValidationCode.CATEGORY_TYPE_IMMUTABLE, "已建立的分类不能改变收支类型，请新建一个分类"),
                    )
                    return@update s
                }
                result = MutationResult.Success(command.id)
                s.copy(
                    categories = s.categories.map {
                        if (it.id == command.id) {
                            it.copy(
                                name = name,
                                iconKey = command.iconKey,
                                sortOrder = command.sortOrder ?: it.sortOrder,
                            )
                        } else {
                            it
                        }
                    },
                )
            }
        }
        return result
    }

    override suspend fun reorderCategories(type: TransactionType, ids: List<String>): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        var result: MutationResult = MutationResult.Success()
        state.update { s ->
            val current = s.categories.filter { it.type == type }.map { it.id }.toSet()
            if (current != ids.toSet()) {
                result = MutationResult.Failure(
                    LedgerError.Validation(ValidationCode.CATEGORY_REORDER_INVALID, "排序列表与现有分类不一致，请重试"),
                )
                return@update s
            }
            val order = ids.withIndex().associate { (index, id) -> id to index * 10 }
            s.copy(categories = s.categories.map { if (it.type == type) it.copy(sortOrder = order[it.id] ?: it.sortOrder) else it })
        }
        return result
    }

    override suspend fun setCategoryArchived(id: String, archived: Boolean): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        var result: MutationResult = MutationResult.Success()
        state.update { s ->
            val target = s.categories.firstOrNull { it.id == id }
            if (target == null) {
                result = MutationResult.Failure(LedgerError.NotFound("category", id))
                return@update s
            }
            if (archived && target.isFallback) {
                result = MutationResult.Failure(
                    LedgerError.Conflict(ValidationCode.CATEGORY_FALLBACK_PROTECTED, "兜底分类「${target.name}」不能归档"),
                )
                return@update s
            }
            if (archived) {
                val remaining = s.categories.count { it.type == target.type && !it.isArchived && it.id != id }
                if (remaining == 0) {
                    result = MutationResult.Failure(
                        LedgerError.Conflict(ValidationCode.CATEGORY_LAST_ACTIVE, "每种收支类型至少保留一个可用分类"),
                    )
                    return@update s
                }
            }
            s.copy(categories = s.categories.map { if (it.id == id) it.copy(isArchived = archived) else it })
        }
        return result
    }

    override suspend fun upsertAccount(command: AccountCommand): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        val name = command.name.trim()
        if (name.length < Limits.MIN_ACCOUNT_NAME_LENGTH) {
            return MutationResult.Failure(LedgerError.Validation(ValidationCode.ACCOUNT_NAME_EMPTY, "请输入账户名称", FieldRef.NAME))
        }
        if (name.length > Limits.MAX_ACCOUNT_NAME_LENGTH) {
            return MutationResult.Failure(
                LedgerError.Validation(ValidationCode.ACCOUNT_NAME_TOO_LONG, "账户名称最多 ${Limits.MAX_ACCOUNT_NAME_LENGTH} 个字符", FieldRef.NAME),
            )
        }
        if (kotlin.math.abs(command.openingBalanceCent) > Limits.MAX_OPENING_BALANCE_CENT) {
            return MutationResult.Failure(
                LedgerError.Validation(
                    ValidationCode.OPENING_BALANCE_OUT_OF_RANGE,
                    "初始余额不能超过 9,999,999.99 元",
                    FieldRef.OPENING_BALANCE,
                ),
            )
        }
        var result: MutationResult = MutationResult.Success()
        state.update { s ->
            val duplicate = s.accounts.any { it.id != command.id && !it.isArchived && it.name == name }
            if (duplicate) {
                result = MutationResult.Failure(
                    LedgerError.Conflict(ValidationCode.ACCOUNT_NAME_DUPLICATE, "已存在名为「$name」的账户"),
                )
                return@update s
            }
            if (command.id == null) {
                val id = "acc_" + UUID.randomUUID().toString().take(12)
                result = MutationResult.Success(id)
                s.copy(
                    accounts = s.accounts + com.blueledger.app.core.model.LedgerAccount(
                        id = id, name = name, kind = command.kind,
                        openingBalanceCent = command.openingBalanceCent, isArchived = false,
                    ),
                )
            } else {
                if (s.accounts.none { it.id == command.id }) {
                    result = MutationResult.Failure(LedgerError.NotFound("account", command.id))
                    return@update s
                }
                result = MutationResult.Success(command.id)
                s.copy(
                    accounts = s.accounts.map {
                        if (it.id == command.id) {
                            it.copy(name = name, kind = command.kind, openingBalanceCent = command.openingBalanceCent)
                        } else {
                            it
                        }
                    },
                )
            }
        }
        return result
    }

    override suspend fun setAccountArchived(
        id: String,
        archived: Boolean,
        replacementDefaultId: String?,
    ): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        var result: MutationResult = MutationResult.Success()
        state.update { s ->
            val target = s.accounts.firstOrNull { it.id == id }
            if (target == null) {
                result = MutationResult.Failure(LedgerError.NotFound("account", id))
                return@update s
            }
            if (archived) {
                val remaining = s.accounts.filter { !it.isArchived && it.id != id }
                if (remaining.isEmpty()) {
                    result = MutationResult.Failure(
                        LedgerError.Conflict(ValidationCode.ACCOUNT_LAST_ACTIVE, "至少保留一个未归档账户"),
                    )
                    return@update s
                }
                var settings = s.settings
                if (s.settings.defaultAccountId == id) {
                    val replacement = replacementDefaultId?.let { rid -> remaining.firstOrNull { it.id == rid } }
                        ?: return@update run {
                            result = MutationResult.Failure(
                                LedgerError.Conflict(
                                    ValidationCode.ACCOUNT_ARCHIVE_REQUIRES_REPLACEMENT,
                                    "归档默认账户前请先选择新的默认账户",
                                ),
                            )
                            s
                        }
                    settings = settings.copy(defaultAccountId = replacement.id)
                }
                if (settings.lastUsedAccountId == id) settings = settings.copy(lastUsedAccountId = null)
                return@update s.copy(
                    accounts = s.accounts.map { if (it.id == id) it.copy(isArchived = true) else it },
                    settings = settings,
                )
            }
            val conflict = s.accounts.any { it.id != id && !it.isArchived && it.name == target.name }
            if (conflict) {
                result = MutationResult.Failure(
                    LedgerError.Conflict(ValidationCode.ACCOUNT_NAME_DUPLICATE, "已有同名账户「${target.name}」，请先重命名再恢复"),
                )
                return@update s
            }
            s.copy(accounts = s.accounts.map { if (it.id == id) it.copy(isArchived = false) else it })
        }
        return result
    }

    override suspend fun setDefaultAccount(id: String): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        var result: MutationResult = MutationResult.Success()
        state.update { s ->
            val target = s.accounts.firstOrNull { it.id == id && !it.isArchived }
            if (target == null) {
                result = MutationResult.Failure(
                    LedgerError.Conflict(ValidationCode.ACCOUNT_NOT_FOUND_FOR_DEFAULT, "该账户不可用，无法设为默认账户"),
                )
                return@update s
            }
            s.copy(settings = s.settings.copy(defaultAccountId = id))
        }
        return result
    }

    override suspend fun setMonthlyBudget(amountCent: Long?, selectedMonth: YearMonth): MutationResult {
        if (amountCent != null && (amountCent <= 0 || amountCent > Limits.MAX_BUDGET_CENT)) return MutationResult.Failure(LedgerError.Validation(ValidationCode.BUDGET_OUT_OF_RANGE, "预算金额不合法"))
        state.update { it.copy(settings = it.settings.copy(monthlyBudgetCent = amountCent), budgets = it.budgets - selectedMonth) }
        return MutationResult.Success()
    }

    override suspend fun setBudget(month: YearMonth, amountCent: Long): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        if (amountCent <= 0L) {
            return MutationResult.Failure(LedgerError.Validation(ValidationCode.BUDGET_NOT_POSITIVE, "预算必须大于 0 元", FieldRef.BUDGET))
        }
        if (amountCent > Limits.MAX_BUDGET_CENT) {
            return MutationResult.Failure(
                LedgerError.Validation(ValidationCode.BUDGET_OUT_OF_RANGE, "预算不能超过 9,999,999.99 元", FieldRef.BUDGET),
            )
        }
        state.update { it.copy(budgets = it.budgets + (month to amountCent)) }
        return MutationResult.Success()
    }

    override suspend fun removeBudget(month: YearMonth): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        state.update { it.copy(budgets = it.budgets - month) }
        return MutationResult.Success()
    }

    override suspend fun setHideAmounts(hidden: Boolean): MutationResult {
        failNextWrite?.let { failNextWrite = null; return MutationResult.Failure(it) }
        state.update { it.copy(settings = it.settings.copy(hideAmounts = hidden)) }
        return MutationResult.Success()
    }

    override suspend fun recordBackupSuccess(at: Instant, fileName: String): MutationResult {
        state.update { it.copy(settings = it.settings.copy(lastBackupAt = at, lastBackupFileName = fileName)) }
        return MutationResult.Success()
    }

    override suspend fun exportConsistentSnapshot(): LedgerSnapshot {
        val s = state.value
        return LedgerSnapshot(
            exportedAt = clock.now(),
            currency = s.settings.currency,
            transactions = s.transactions.filter { it.deletedAt == null },
            categories = s.categories,
            accounts = s.accounts,
            budgets = s.budgets.map { (month, amount) -> com.blueledger.app.core.model.MonthlyBudget(month, amount) },
            settings = s.settings,
        )
    }

    override suspend fun restoreValidatedSnapshot(snapshot: ValidatedLedgerSnapshot): RestoreResult {
        failNextWrite?.let { failNextWrite = null; return RestoreResult.Failure(it) }
        val snap = snapshot.snapshot
        val previousBackupAt = state.value.settings.lastBackupAt
        val previousBackupName = state.value.settings.lastBackupFileName
        state.value = State(
            categories = snap.categories,
            accounts = snap.accounts,
            transactions = snap.transactions,
            budgets = snap.budgets.associate { it.yearMonth to it.amountCent },
            // 最近成功备份时间属于“本机操作记录”，不随备份文件回滚。
            settings = snap.settings.copy(lastBackupAt = previousBackupAt, lastBackupFileName = previousBackupName),
            processedRequestIds = emptyMap(),
            activeDeleteTokens = emptyMap(),
            deleteSequence = 0L,
        )
        return RestoreResult.Success(
            transactionCount = snap.transactions.size,
            categoryCount = snap.categories.size,
            accountCount = snap.accounts.size,
            budgetCount = snap.budgets.size,
        )
    }

    // ───────────────────────── 内部：校验与聚合 ─────────────────────────

    private fun storageFailure(): LedgerError =
        LedgerError.Storage("保存失败，内容已保留，请重试")

    private fun validateDraft(draft: TransactionDraft, s: State, editingId: String?): LedgerError? {
        if (draft.amountCent < Limits.MIN_TRANSACTION_CENT) {
            return LedgerError.Validation(ValidationCode.AMOUNT_ZERO, "请输入大于 0 的金额", FieldRef.AMOUNT)
        }
        if (draft.amountCent > Limits.MAX_TRANSACTION_CENT) {
            return LedgerError.Validation(
                ValidationCode.AMOUNT_OUT_OF_RANGE, "金额不能超过 9,999,999.99 元", FieldRef.AMOUNT,
            )
        }
        if (draft.occurredOn.isAfter(clock.today())) {
            return LedgerError.Validation(ValidationCode.DATE_FUTURE, "暂不支持记录未来日期", FieldRef.DATE)
        }
        if (draft.note.length > Limits.MAX_NOTE_LENGTH) {
            return LedgerError.Validation(
                ValidationCode.NOTE_TOO_LONG, "备注最多 ${Limits.MAX_NOTE_LENGTH} 个字符", FieldRef.NOTE,
            )
        }
        val category = s.categories.firstOrNull { it.id == draft.categoryId }
            ?: return LedgerError.Validation(ValidationCode.CATEGORY_NOT_FOUND, "请选择分类", FieldRef.CATEGORY)
        if (category.type != draft.type) {
            return LedgerError.Validation(ValidationCode.CATEGORY_TYPE_MISMATCH, "该分类与当前收支类型不一致", FieldRef.CATEGORY)
        }
        val account = s.accounts.firstOrNull { it.id == draft.accountId }
            ?: return LedgerError.Validation(ValidationCode.ACCOUNT_NOT_FOUND, "请选择账户", FieldRef.ACCOUNT)

        // 归档项：只有“原记录本来就用的同一个分类/账户”才允许保留。
        val originalCategoryId = editingId?.let { id -> s.transactions.firstOrNull { it.id == id }?.categoryId }
        val originalAccountId = editingId?.let { id -> s.transactions.firstOrNull { it.id == id }?.accountId }
        if (category.isArchived && category.id != originalCategoryId) {
            return LedgerError.Validation(ValidationCode.CATEGORY_ARCHIVED, "「${category.name}」已归档，请选择其他分类", FieldRef.CATEGORY)
        }
        if (account.isArchived && account.id != originalAccountId) {
            return LedgerError.Validation(ValidationCode.ACCOUNT_ARCHIVED, "「${account.name}」已归档，请选择其他账户", FieldRef.ACCOUNT)
        }
        return null
    }

    private fun matches(t: LedgerTransaction, f: TransactionFilter, s: State): Boolean {
        if (t.deletedAt != null) return false
        if (f.type != null && t.type != f.type) return false
        if (f.categoryId != null && t.categoryId != f.categoryId) return false
        if (f.accountId != null && t.accountId != f.accountId) return false
        if (f.yearMonth != null && YearMonth.from(t.occurredOn) != f.yearMonth) return false
        if (f.from != null && t.occurredOn.isBefore(f.from)) return false
        if (f.to != null && t.occurredOn.isAfter(f.to)) return false
        val q = f.query.trim().lowercase()
        if (q.isNotEmpty()) {
            val categoryName = s.categories.firstOrNull { it.id == t.categoryId }?.name.orEmpty()
            val accountName = s.accounts.firstOrNull { it.id == t.accountId }?.name.orEmpty()
            val hit = t.note.lowercase().contains(q) ||
                categoryName.lowercase().contains(q) ||
                accountName.lowercase().contains(q)
            if (!hit) return false
        }
        return true
    }

    private fun sortedForList(list: List<LedgerTransaction>): List<LedgerTransaction> =
        list.sortedWith(
            compareByDescending<LedgerTransaction> { it.occurredOn }
                .thenByDescending { it.createdAt }
                .thenBy { it.id },
        )

    private fun summarize(list: List<LedgerTransaction>): MoneySummary = MoneySummary(
        incomeCent = list.filter { it.type == TransactionType.INCOME }.sumOf { it.amountCent },
        expenseCent = list.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCent },
        count = list.size,
    )

    private fun LedgerTransaction.withRefs(s: State): TransactionWithRefs {
        val category = s.categories.firstOrNull { it.id == categoryId }
        val account = s.accounts.firstOrNull { it.id == accountId }
        return TransactionWithRefs(
            transaction = this,
            categoryName = category?.name ?: "未分类",
            categoryIconKey = CategoryIcons.coerceForDisplay(category?.iconKey.orEmpty()),
            categoryArchived = category?.isArchived ?: false,
            accountName = account?.name ?: "未知账户",
        )
    }

    private fun com.blueledger.app.core.model.LedgerAccount.withBalance(s: State): AccountWithBalance {
        val related = s.transactions.filter { it.deletedAt == null && it.accountId == id }
        val income = related.filter { it.type == TransactionType.INCOME }.sumOf { it.amountCent }
        val expense = related.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCent }
        return AccountWithBalance(
            account = this,
            balanceCent = openingBalanceCent + income - expense,
            transactionCount = related.size,
        )
    }

    private fun buildMonthAnalysis(s: State, month: YearMonth, type: TransactionType): MonthAnalysis {
        val inMonth = s.transactions.filter { it.deletedAt == null && YearMonth.from(it.occurredOn) == month }
        val summary = summarize(inMonth)
        val current = clock.currentYearMonth()
        val periodEnd: LocalDate? = when {
            month.isAfter(current) -> null
            month == current -> clock.today()
            else -> month.atEndOfMonth()
        }
        val typed = inMonth.filter { it.type == type }
        val typeTotal = typed.sumOf { it.amountCent }
        val daily = if (periodEnd == null) {
            emptyList()
        } else {
            val byDay = typed.groupBy { it.occurredOn }.mapValues { (_, list) -> list.sumOf { it.amountCent } }
            (1..periodEnd.dayOfMonth).map { day ->
                val date = month.atDay(day)
                DailyAmount(date, byDay[date] ?: 0L)
            }
        }
        val slices = typed
            .groupBy { it.categoryId }
            .map { (categoryId, list) ->
                val category = s.categories.firstOrNull { it.id == categoryId }
                CategorySlice(
                    categoryId = categoryId,
                    name = category?.name ?: "未分类",
                    iconKey = CategoryIcons.coerceForDisplay(category?.iconKey.orEmpty()),
                    amountCent = list.sumOf { it.amountCent },
                    ratioPermille = if (typeTotal <= 0L) 0 else ((list.sumOf { it.amountCent } * 1000L + typeTotal / 2) / typeTotal).toInt(),
                )
            }
            .sortedWith(
                compareByDescending<CategorySlice> { it.amountCent }
                    .thenBy { slice -> s.categories.firstOrNull { it.id == slice.categoryId }?.sortOrder ?: Int.MAX_VALUE }
                    .thenBy { it.categoryId },
            )
        return MonthAnalysis(
            month = month,
            type = type,
            incomeCent = summary.incomeCent,
            expenseCent = summary.expenseCent,
            count = summary.count,
            typeTotalCent = typeTotal,
            daily = daily,
            slices = slices,
            periodEnd = periodEnd,
        )
    }

    private fun buildYearAnalysis(s: State, year: Int): YearAnalysis {
        val today = clock.today()
        val currentYear = today.year
        val inYear = s.transactions.filter { it.deletedAt == null && it.occurredOn.year == year }
        val summary = summarize(inYear)
        val reachedMonthCount = when {
            year < currentYear -> 12
            year == currentYear -> today.monthValue
            else -> 0
        }
        val months = (1..12).map { m ->
            val reached = year < currentYear || (year == currentYear && m <= today.monthValue)
            val monthTx = inYear.filter { it.occurredOn.monthValue == m }
            YearMonthAmount(
                month = m,
                incomeCent = if (reached) monthTx.filter { it.type == TransactionType.INCOME }.sumOf { it.amountCent } else 0L,
                expenseCent = if (reached) monthTx.filter { it.type == TransactionType.EXPENSE }.sumOf { it.amountCent } else 0L,
                reached = reached,
                hasRecords = reached && monthTx.isNotEmpty(),
            )
        }
        return YearAnalysis(
            year = year,
            incomeCent = summary.incomeCent,
            expenseCent = summary.expenseCent,
            count = summary.count,
            months = months,
            cutoff = if (year == currentYear) today else null,
            isCurrentYear = year == currentYear,
            reachedMonthCount = reachedMonthCount,
        )
    }
}
