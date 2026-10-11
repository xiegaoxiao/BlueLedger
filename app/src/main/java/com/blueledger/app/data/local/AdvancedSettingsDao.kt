package com.blueledger.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "advanced_settings")
data class AdvancedSettingsEntity(@PrimaryKey val id: Int = 1, val payload: String)

@Dao
interface AdvancedSettingsDao {
    @Query("SELECT * FROM advanced_settings WHERE id = 1") fun observe(): Flow<AdvancedSettingsEntity?>
    @Query("SELECT * FROM advanced_settings WHERE id = 1") suspend fun get(): AdvancedSettingsEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(value: AdvancedSettingsEntity)
    // Newer app versions split large settings JSON across ordered rows.
    @Query("SELECT * FROM advanced_settings ORDER BY id") fun observeChunks(): Flow<List<AdvancedSettingsEntity>>
    @Query("SELECT * FROM advanced_settings ORDER BY id") suspend fun getChunks(): List<AdvancedSettingsEntity>
    @Query("DELETE FROM advanced_settings") suspend fun deleteChunks()
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertChunks(rows: List<AdvancedSettingsEntity>)
    @Transaction suspend fun replaceChunks(rows: List<AdvancedSettingsEntity>) {
        deleteChunks()
        insertChunks(rows)
    }
}
