package com.blueledger.app.core.contract

import com.blueledger.app.core.model.AccountCommand
import com.blueledger.app.core.model.AccountWithBalance
import com.blueledger.app.core.model.BackupRecord
import com.blueledger.app.core.model.BudgetState
import com.blueledger.app.core.model.CategoryCommand
import com.blueledger.app.core.model.DeleteReceipt
import com.blueledger.app.core.model.LedgerCategory
import com.blueledger.app.core.model.LedgerSettings
import com.blueledger.app.core.model.LedgerSnapshot
import com.blueledger.app.core.model.LedgerTransaction
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
import com.blueledger.app.core.model.ValidatedLedgerSnapshot
import com.blueledger.app.core.model.YearAnalysis
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 可注入时钟。生产实现读取系统当前时间与**当前**系统时区
 * （不是首次启动时冻结的时区）；测试实现冻结在 2026-10-07T00:00:00+08:00[Asia/Shanghai]。
 *
 * 禁止在任何生产逻辑中使用 LocalDate.now() / Instant.now()。
 */
interface Clock {
    /** 当前瞬时。 */
    fun now(): Instant

    /** 当前系统时区。每次调用都重新读取，支持运行中时区变化。 */
    fun zoneId(): ZoneId

    /** 设备本地“今天”。默认实现由 [now] 与 [zoneId] 推出。 */
    fun today(): LocalDate = now().atZone(zoneId()).toLocalDate()

    /** 设备本地“当前年月”。 */
    fun currentYearMonth(): YearMonth = YearMonth.from(today())
}

/**
 * 蓝记的唯一数据事实来源。
 *
 * 通用规则（所有实现必须遵守）：
 * 1. 所有正常查询都排除软删除账单（deletedAt != null），
 *    但分类/账户归档**不**排除历史有效账单。
 * 2. 所有金额是 Long 整数分，聚合用 64 位数值，不允许 Float/Double 累加。
 * 3. 写操作在事务中原子完成；失败必须回滚并返回失败结果。
 * 4. 日期、引用、归档、唯一性等约束在数据层再次校验，不信任 UI。
 * 5. 分页只影响 [observeTransactions] 的 items；[observeFilteredSummary] 忽略分页。
 */
interface LedgerRepository {

    // ───────────────────────── 读 ─────────────────────────

    /** 账单列表。items 受分页影响；totalCount 不受分页影响。 */
    fun observeTransactions(filter: TransactionFilter): Flow<TransactionPageState>

    /** 筛选汇总。使用与 [observeTransactions] 完全相同的谓词，但忽略分页。 */
    fun observeFilteredSummary(filter: TransactionFilter): Flow<MoneySummary>

    /** 单笔账单。不存在或已软删除返回 null（S04「记录不存在」状态）。 */
    fun observeTransaction(id: String): Flow<LedgerTransaction?>

    /** 月度摘要（有效账单；收入/支出/笔数/结余）。 */
    fun observeMonthSummary(month: YearMonth): Flow<MoneySummary>

    /** 月度分析：摘要 + 完整日趋势 + 全部分类切片（当前类型）。 */
    fun observeMonthAnalysis(month: YearMonth, type: TransactionType): Flow<MonthAnalysis>

    /** 年度分析：12 个月的到达/未到状态、年度摘要、截止说明与月均分母。 */
    fun observeYearAnalysis(year: Int): Flow<YearAnalysis>

    /** 分类列表。[includeArchived] = false 时只返回未归档分类（新账单可选项）。 */
    fun observeCategories(type: TransactionType, includeArchived: Boolean): Flow<List<LedgerCategory>>

    /** 账户列表（含派生余额）。余额按全历史有效记录计算，与月份筛选无关。 */
    fun observeAccounts(includeArchived: Boolean): Flow<List<AccountWithBalance>>

    /** 某月预算状态。[BudgetState.budgetCent] 为 null 表示未设置。 */
    fun observeBudget(month: YearMonth): Flow<BudgetState>

    /** 全局设置（默认账户、金额隐藏、币种、最近成功备份时间）。 */
    fun observeSettings(): Flow<LedgerSettings>

    /** 最近一次成功备份/导出的记录。 */
    fun observeBackupRecord(): Flow<BackupRecord>

    // ───────────────────────── 写：账单 ─────────────────────────

    /** 幂等初始化：默认分类、默认账户、设置。多次调用不重复插入，且**不插入任何演示账单**。 */
    suspend fun initializeIfNeeded(): MutationResult

    /**
     * 新增账单。[requestId] 在一次用户提交中保持稳定：
     * 同一 requestId 重复调用只产生一条记录（防双击/重试重复入账）。
     */
    suspend fun createTransaction(draft: TransactionDraft, requestId: String): SaveResult

    /** 更新账单。保留原 id 与 createdAt。 */
    suspend fun updateTransaction(id: String, draft: TransactionDraft): SaveResult

    /** 软删除。返回的凭据携带原记录身份与本次删除事件的令牌。 */
    suspend fun softDeleteTransaction(id: String): Outcome<DeleteReceipt>

    /** 撤销软删除。恢复**原 id 与原字段**，不新建副本；凭据过期/重复/被替换时失败。 */
    suspend fun undoDelete(receipt: DeleteReceipt): MutationResult

    // ───────────────────────── 写：分类 ─────────────────────────

    suspend fun upsertCategory(command: CategoryCommand): MutationResult

    suspend fun reorderCategories(type: TransactionType, ids: List<String>): MutationResult

    /** 归档/取消归档分类。兜底分类不可归档；每类型至少保留一个可选分类。 */
    suspend fun setCategoryArchived(id: String, archived: Boolean): MutationResult

    /** 同类型分类之间原子转移全部有效账单；不修改账单身份、日期、金额。 */
    suspend fun migrateCategory(sourceId: String, targetId: String): MutationResult =
        MutationResult.Failure(com.blueledger.app.core.model.LedgerError.Storage("当前账本不支持分类转移"))

    // ───────────────────────── 写：账户 ─────────────────────────

    suspend fun upsertAccount(command: AccountCommand): MutationResult

    /**
     * 归档/取消归档账户。
     * 归档默认账户时必须提供 [replacementDefaultId]；
     * 恢复时若名称与现有可用账户重复，必须先重命名（返回 Conflict 而不是破坏唯一约束）。
     */
    suspend fun setAccountArchived(
        id: String,
        archived: Boolean,
        replacementDefaultId: String? = null,
    ): MutationResult

    suspend fun setDefaultAccount(id: String): MutationResult

    // ───────────────────────── 写：预算与设置 ─────────────────────────

    /** 设置某月预算。金额必须 > 0 且不超过上限；按月独立保存。 */
    suspend fun setBudget(month: YearMonth, amountCent: Long): MutationResult

    /** 移除某月预算（移除后回到「未设置」）。 */
    suspend fun removeBudget(month: YearMonth): MutationResult
    /** 每月统一预算；保留其他月份已经单独设置的预算，当前月份随本次设置更新。 */
    suspend fun setMonthlyBudget(amountCent: Long?, selectedMonth: YearMonth): MutationResult =
        MutationResult.Failure(com.blueledger.app.core.model.LedgerError.Storage("当前账本不支持每月预算"))

    suspend fun setHideAmounts(hidden: Boolean): MutationResult

    /** 记录一次成功备份/导出。失败或取消不得调用。 */
    suspend fun recordBackupSuccess(at: Instant, fileName: String): MutationResult

    // ───────────────────────── 备份与恢复 ─────────────────────────

    /** 在一致的读快照中导出全部实体（含归档分类/账户，不含软删除账单）。 */
    suspend fun exportConsistentSnapshot(): LedgerSnapshot

    /**
     * 用已验证快照**原子替换**全库。
     * - 与其它写操作互斥（恢复期间不得混入其它写入）
     * - 失败时保留旧库，不得出现半套数据
     * - 写入前再次核对日期、引用与业务限制
     */
    suspend fun restoreValidatedSnapshot(snapshot: ValidatedLedgerSnapshot): RestoreResult
}
