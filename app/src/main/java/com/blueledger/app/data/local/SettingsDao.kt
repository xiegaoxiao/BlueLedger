package com.blueledger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 设置 DAO。单例行 `id = 1`；读不出行时由数据层兜底为默认设置并补种。 */
@Dao
interface SettingsDao {
    @Query("UPDATE ledger_settings SET monthlyBudgetCent = :cent, updatedAtEpochMillis = :now WHERE id = 1")
    suspend fun updateMonthlyBudget(cent: Long?, now: Long)

    @Query("SELECT * FROM ledger_settings WHERE id = ${SettingsEntity.SINGLETON_ID}")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM ledger_settings WHERE id = ${SettingsEntity.SINGLETON_ID}")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SettingsEntity)

    @Query("UPDATE ledger_settings SET defaultAccountId = :accountId, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = ${SettingsEntity.SINGLETON_ID}")
    suspend fun updateDefaultAccount(accountId: String, updatedAtEpochMillis: Long): Int

    @Query("UPDATE ledger_settings SET lastUsedAccountId = :accountId, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = ${SettingsEntity.SINGLETON_ID}")
    suspend fun updateLastUsedAccount(accountId: String?, updatedAtEpochMillis: Long): Int

    @Query("UPDATE ledger_settings SET hideAmounts = :hidden, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = ${SettingsEntity.SINGLETON_ID}")
    suspend fun updateHideAmounts(hidden: Boolean, updatedAtEpochMillis: Long): Int

    @Query(
        "UPDATE ledger_settings SET lastBackupAtEpochMillis = :atEpochMillis, " +
            "lastBackupFileName = :fileName, updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE id = ${SettingsEntity.SINGLETON_ID}",
    )
    suspend fun updateBackupRecord(atEpochMillis: Long, fileName: String, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM ledger_settings")
    suspend fun deleteAll()
}
