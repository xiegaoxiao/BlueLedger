package com.blueledger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 账户 DAO。
 *
 * 余额是派生值：`期初 + 全部有效收入 − 全部有效支出`，
 * 与月份/年份筛选无关，也不把期初余额混入收支统计。
 */
@Dao
interface AccountDao {

    @Query(
        """
        SELECT a.id AS id,
               a.name AS name,
               a.kind AS kind,
               a.openingBalanceCent AS openingBalanceCent,
               a.isArchived AS isArchived,
               a.note AS note,
               a.openingHistory AS openingHistory,
               a.openingBalanceCent + COALESCE((
                   SELECT SUM(CASE WHEN t.type = 'INCOME' THEN t.amountCent ELSE -t.amountCent END)
                   FROM transactions t
                   WHERE t.accountId = a.id AND t.deletedAtEpochMillis IS NULL
               ), 0) AS balanceCent,
               COALESCE((
                   SELECT COUNT(*)
                   FROM transactions t2
                   WHERE t2.accountId = a.id AND t2.deletedAtEpochMillis IS NULL
               ), 0) AS transactionCount
        FROM accounts a
        WHERE (:includeArchived = 1 OR a.isArchived = 0)
        ORDER BY a.isArchived ASC, a.createdAtEpochMillis ASC, a.id ASC
        """,
    )
    fun observeWithBalance(includeArchived: Boolean): Flow<List<AccountBalanceRow>>

    @Query("SELECT * FROM accounts WHERE id = :id")
    suspend fun getById(id: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE nameKey = :nameKey AND isArchived = 0 LIMIT 1")
    suspend fun findActiveByNameKey(nameKey: String): AccountEntity?

    @Query("SELECT * FROM accounts ORDER BY isArchived ASC, createdAtEpochMillis ASC, id ASC")
    suspend fun listAll(): List<AccountEntity>

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY createdAtEpochMillis ASC, id ASC")
    suspend fun listActive(): List<AccountEntity>

    @Query("SELECT COUNT(*) FROM accounts")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM accounts WHERE isArchived = 0")
    suspend fun countActive(): Int

    @Query("SELECT * FROM accounts WHERE isArchived = 0 ORDER BY createdAtEpochMillis ASC, id ASC LIMIT 1")
    suspend fun firstActive(): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: AccountEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<AccountEntity>)

    @Query("UPDATE accounts SET name = :name, nameKey = :nameKey, kind = :kind, openingBalanceCent = :openingBalanceCent, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun updateDetails(
        id: String,
        name: String,
        nameKey: String,
        kind: String,
        openingBalanceCent: Long,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("UPDATE accounts SET note = :note, openingHistory = :history WHERE id = :id")
    suspend fun updateMetadata(id: String, note: String, history: String)

    @Query("UPDATE accounts SET isArchived = :archived, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()
}
