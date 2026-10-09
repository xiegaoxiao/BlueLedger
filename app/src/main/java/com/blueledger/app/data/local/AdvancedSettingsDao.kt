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
}
