package com.blueledger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.Flow

/**
 * 账单 DAO。
 *
 * 所有“正常查询”都带 `deletedAtEpochMillis IS NULL`；排序统一为
 * `occurredOnEpochDay DESC, createdAtEpochMillis DESC, id ASC`（id 为最终稳定键）。
 */
@Dao
interface TransactionDao {
    @Query("UPDATE transactions SET categoryId = :targetId, updatedAtEpochMillis = :now WHERE categoryId = :sourceId AND deletedAtEpochMillis IS NULL")
    suspend fun migrateCategory(sourceId: String, targetId: String, now: Long): Int

    // ───────────────────────── 观察 ─────────────────────────

    /**
     * 失效通知用的“心跳”查询：同时引用 transactions / categories / accounts 三张表，
     * 因此三张表任一变化（新增账单、改分类名、改账户名）都会重新触发下游查询，
     * 保证列表中的分类名/账户名与账单在一起刷新。
     */
    @Query(
        "SELECT COUNT(*) FROM transactions t " +
            "LEFT JOIN categories c ON c.id = t.categoryId " +
            "LEFT JOIN accounts a ON a.id = t.accountId",
    )
    fun observeTick(): Flow<Int>

    /** 账单分页（含分类/账户展示字段）。 */
    @Query(
        """
        SELECT t.id AS id,
               t.type AS type,
               t.amountCent AS amountCent,
               t.categoryId AS categoryId,
               t.accountId AS accountId,
               t.occurredOnEpochDay AS occurredOnEpochDay,
               t.note AS note,
               t.createdAtEpochMillis AS createdAtEpochMillis,
               t.updatedAtEpochMillis AS updatedAtEpochMillis,
               t.deletedAtEpochMillis AS deletedAtEpochMillis,
               COALESCE(c.name, '') AS categoryName,
               COALESCE(c.iconKey, '') AS categoryIconKey,
               COALESCE(c.isArchived, 0) AS categoryArchived,
               COALESCE(a.name, '') AS accountName
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        LEFT JOIN accounts a ON a.id = t.accountId
        WHERE t.deletedAtEpochMillis IS NULL
          AND (:fromEpochDay IS NULL OR t.occurredOnEpochDay >= :fromEpochDay)
          AND (:toEpochDay IS NULL OR t.occurredOnEpochDay <= :toEpochDay)
          AND (:type IS NULL OR t.type = :type)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:accountId IS NULL OR t.accountId = :accountId)
          AND (:pattern = '' OR t.noteKey LIKE :pattern ESCAPE '\'
               OR EXISTS (SELECT 1 FROM categories c2 WHERE c2.id = t.categoryId AND c2.nameKey LIKE :pattern ESCAPE '\')
               OR EXISTS (SELECT 1 FROM accounts a2 WHERE a2.id = t.accountId AND a2.nameKey LIKE :pattern ESCAPE '\'))
        ORDER BY t.occurredOnEpochDay DESC, t.createdAtEpochMillis DESC, t.id ASC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun pageRows(
        fromEpochDay: Long?,
        toEpochDay: Long?,
        type: TransactionType?,
        categoryId: String?,
        accountId: String?,
        pattern: String,
        limit: Int,
        offset: Int,
    ): List<TransactionRefRow>

    /** 与 [pageRows] 完全相同的谓词，但忽略分页，返回命中总数。 */
    @Query(
        """
        SELECT COUNT(*) FROM transactions t
        WHERE t.deletedAtEpochMillis IS NULL
          AND (:fromEpochDay IS NULL OR t.occurredOnEpochDay >= :fromEpochDay)
          AND (:toEpochDay IS NULL OR t.occurredOnEpochDay <= :toEpochDay)
          AND (:type IS NULL OR t.type = :type)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:accountId IS NULL OR t.accountId = :accountId)
          AND (:pattern = '' OR t.noteKey LIKE :pattern ESCAPE '\'
               OR EXISTS (SELECT 1 FROM categories c2 WHERE c2.id = t.categoryId AND c2.nameKey LIKE :pattern ESCAPE '\')
               OR EXISTS (SELECT 1 FROM accounts a2 WHERE a2.id = t.accountId AND a2.nameKey LIKE :pattern ESCAPE '\'))
        """,
    )
    suspend fun countRows(
        fromEpochDay: Long?,
        toEpochDay: Long?,
        type: TransactionType?,
        categoryId: String?,
        accountId: String?,
        pattern: String,
    ): Int

    /** 与 [pageRows] 完全相同的谓词汇总，忽略分页。 */
    @Query(
        """
        SELECT COALESCE(SUM(CASE WHEN t.type = 'INCOME' THEN t.amountCent ELSE 0 END), 0) AS incomeCent,
               COALESCE(SUM(CASE WHEN t.type = 'EXPENSE' THEN t.amountCent ELSE 0 END), 0) AS expenseCent,
               COUNT(*) AS totalCount
        FROM transactions t
        WHERE t.deletedAtEpochMillis IS NULL
          AND (:fromEpochDay IS NULL OR t.occurredOnEpochDay >= :fromEpochDay)
          AND (:toEpochDay IS NULL OR t.occurredOnEpochDay <= :toEpochDay)
          AND (:type IS NULL OR t.type = :type)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:accountId IS NULL OR t.accountId = :accountId)
          AND (:pattern = '' OR t.noteKey LIKE :pattern ESCAPE '\'
               OR EXISTS (SELECT 1 FROM categories c2 WHERE c2.id = t.categoryId AND c2.nameKey LIKE :pattern ESCAPE '\')
               OR EXISTS (SELECT 1 FROM accounts a2 WHERE a2.id = t.accountId AND a2.nameKey LIKE :pattern ESCAPE '\'))
        """,
    )
    fun observeSummary(
        fromEpochDay: Long?,
        toEpochDay: Long?,
        type: TransactionType?,
        categoryId: String?,
        accountId: String?,
        pattern: String,
    ): Flow<SummaryRow>

    /** 单笔有效账单（软删除视为不存在）。 */
    @Query("SELECT * FROM transactions WHERE id = :id AND deletedAtEpochMillis IS NULL")
    fun observeById(id: String): Flow<TransactionEntity?>

    /** 该月全部有效账单原始行，一次查询供摘要/日趋势/分类切片共用，避免同一页面出现混合口径。 */
    @Query(
        """
        SELECT t.categoryId AS categoryId,
               t.amountCent AS amountCent,
               t.type AS type,
               t.occurredOnEpochDay AS occurredOnEpochDay,
               COALESCE(c.name, '') AS categoryName,
               COALESCE(c.iconKey, '') AS categoryIconKey,
               COALESCE(c.sortOrder, 0) AS categorySortOrder
        FROM transactions t
        LEFT JOIN categories c ON c.id = t.categoryId
        WHERE t.deletedAtEpochMillis IS NULL
          AND t.occurredOnEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY t.occurredOnEpochDay ASC, t.createdAtEpochMillis ASC, t.id ASC
        """,
    )
    fun observeMonthRows(fromEpochDay: Long, toEpochDay: Long): Flow<List<MonthRow>>

    /** 该年全部有效账单原始行，供 12 个月柱图与年度摘要共用。 */
    @Query(
        """
        SELECT t.amountCent AS amountCent,
               t.type AS type,
               t.occurredOnEpochDay AS occurredOnEpochDay
        FROM transactions t
        WHERE t.deletedAtEpochMillis IS NULL
          AND t.occurredOnEpochDay BETWEEN :fromEpochDay AND :toEpochDay
        ORDER BY t.occurredOnEpochDay ASC, t.createdAtEpochMillis ASC, t.id ASC
        """,
    )
    fun observeYearRows(fromEpochDay: Long, toEpochDay: Long): Flow<List<YearRow>>

    /** 有效账单数（最近成功备份的记录展示用）。 */
    @Query("SELECT COUNT(*) FROM transactions WHERE deletedAtEpochMillis IS NULL")
    fun observeActiveCount(): Flow<Int>

    // ───────────────────────── 读取（一次） ─────────────────────────

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    /** 幂等键查询：同一次提交重复调用时复用已存在的记录。 */
    @Query("SELECT * FROM transactions WHERE requestId = :requestId LIMIT 1")
    suspend fun findByRequestId(requestId: String): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE deletedAtEpochMillis IS NULL ORDER BY occurredOnEpochDay DESC, createdAtEpochMillis DESC, id ASC")
    suspend fun getAllActive(): List<TransactionEntity>

    @Query("SELECT * FROM transactions ORDER BY occurredOnEpochDay ASC, createdAtEpochMillis ASC, id ASC")
    suspend fun getAll(): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun countAll(): Int

    // ───────────────────────── 写入 ─────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<TransactionEntity>)

    @Query(
        """
        UPDATE transactions
        SET type = :type,
            amountCent = :amountCent,
            categoryId = :categoryId,
            accountId = :accountId,
            occurredOnEpochDay = :occurredOnEpochDay,
            note = :note,
            noteKey = :noteKey,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id AND deletedAtEpochMillis IS NULL
        """,
    )
    suspend fun updateActive(
        id: String,
        type: TransactionType,
        amountCent: Long,
        categoryId: String,
        accountId: String,
        occurredOnEpochDay: Long,
        note: String,
        noteKey: String,
        updatedAtEpochMillis: Long,
    ): Int

    /** 软删除：只写 deletedAt/updatedAt，记录与 id 保留。 */
    @Query(
        "UPDATE transactions SET deletedAtEpochMillis = :deletedAtEpochMillis, " +
            "updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE id = :id AND deletedAtEpochMillis IS NULL",
    )
    suspend fun softDelete(id: String, deletedAtEpochMillis: Long, updatedAtEpochMillis: Long): Int

    /** 撤销软删除：恢复原 id 与原业务字段，不新建副本。 */
    @Query(
        """
        UPDATE transactions
        SET type = :type,
            amountCent = :amountCent,
            categoryId = :categoryId,
            accountId = :accountId,
            occurredOnEpochDay = :occurredOnEpochDay,
            note = :note,
            noteKey = :noteKey,
            deletedAtEpochMillis = NULL,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE id = :id AND deletedAtEpochMillis IS NOT NULL
        """,
    )
    suspend fun restoreDeleted(
        id: String,
        type: TransactionType,
        amountCent: Long,
        categoryId: String,
        accountId: String,
        occurredOnEpochDay: Long,
        note: String,
        noteKey: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()
}
