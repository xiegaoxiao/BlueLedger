package com.blueledger.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.blueledger.app.core.model.TransactionType
import kotlinx.coroutines.flow.Flow

/** 分类 DAO。[CategoryEntity] 上的 `(type, nameKey)` 唯一索引覆盖归档项。 */
@Dao
interface CategoryDao {

    @Query(
        "SELECT * FROM categories WHERE type = :type AND (:includeArchived = 1 OR isArchived = 0) " +
            "ORDER BY sortOrder ASC, id ASC",
    )
    fun observeByType(type: TransactionType, includeArchived: Boolean): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: String): CategoryEntity?

    /** 同类型名称查重（含归档）。 */
    @Query("SELECT * FROM categories WHERE type = :type AND nameKey = :nameKey LIMIT 1")
    suspend fun findByNameKey(type: TransactionType, nameKey: String): CategoryEntity?

    @Query("SELECT * FROM categories WHERE type = :type ORDER BY sortOrder ASC, id ASC")
    suspend fun listByType(type: TransactionType): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE type = :type AND isArchived = 0 ORDER BY sortOrder ASC, id ASC")
    suspend fun listActiveByType(type: TransactionType): List<CategoryEntity>

    @Query("SELECT * FROM categories ORDER BY type ASC, sortOrder ASC, id ASC")
    suspend fun listAll(): List<CategoryEntity>

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM categories WHERE type = :type")
    suspend fun countByType(type: TransactionType): Int

    @Query("SELECT COUNT(*) FROM categories WHERE type = :type AND isArchived = 0 AND id != :excludeId")
    suspend fun countOtherActive(type: TransactionType, excludeId: String): Int

    @Query("SELECT MAX(sortOrder) FROM categories WHERE type = :type")
    suspend fun maxSortOrder(type: TransactionType): Int?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: CategoryEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<CategoryEntity>)

    @Query("UPDATE categories SET name = :name, nameKey = :nameKey, iconKey = :iconKey, sortOrder = :sortOrder, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun updateDetails(
        id: String,
        name: String,
        nameKey: String,
        iconKey: String,
        sortOrder: Int,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("UPDATE categories SET sortOrder = :sortOrder, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int, updatedAtEpochMillis: Long): Int

    @Query("UPDATE categories SET isArchived = :archived, updatedAtEpochMillis = :updatedAtEpochMillis WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean, updatedAtEpochMillis: Long): Int

    @Query("DELETE FROM categories")
    suspend fun deleteAll()
}
