package com.blueledger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 月预算 DAO。`yearMonth`（`YYYY-MM`）为主键，按月独立。
 * 预算已用 = 该月**全部**有效支出（不做截止日截断，历史月与当前月口径一致）。
 */
@Dao
interface BudgetDao {

    /**
     * 一次查询同时取回「该月预算」与「该月已用支出」，
     * 两者来自同一个 SQL 快照，不会出现“预算已改、已用还是旧值”的混搭。
     * 预算子查询无行时为 NULL，即“未设置”。
     */
    @Query(
        """
        SELECT COALESCE((SELECT amountCent FROM monthly_budgets WHERE yearMonth = :yearMonth), (SELECT monthlyBudgetCent FROM ledger_settings WHERE id = 1)) AS budgetCent,
               COALESCE((
                   SELECT SUM(t.amountCent) FROM transactions t
                   WHERE t.deletedAtEpochMillis IS NULL
                     AND t.type = 'EXPENSE'
                     AND t.occurredOnEpochDay BETWEEN :fromEpochDay AND :toEpochDay
               ), 0) AS usedCent
        """,
    )
    fun observeState(yearMonth: String, fromEpochDay: Long, toEpochDay: Long): Flow<BudgetStateRow>

    @Query("SELECT * FROM monthly_budgets WHERE yearMonth = :yearMonth")
    suspend fun get(yearMonth: String): BudgetEntity?

    @Query("SELECT * FROM monthly_budgets ORDER BY yearMonth ASC")
    suspend fun listAll(): List<BudgetEntity>

    @Query("SELECT COUNT(*) FROM monthly_budgets")
    suspend fun countAll(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BudgetEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<BudgetEntity>)

    @Query("DELETE FROM monthly_budgets WHERE yearMonth = :yearMonth")
    suspend fun delete(yearMonth: String)

    @Query("DELETE FROM monthly_budgets")
    suspend fun deleteAll()
}
