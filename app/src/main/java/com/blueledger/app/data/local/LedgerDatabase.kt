package com.blueledger.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * 蓝记唯一持久化数据库。
 *
 * 迁移策略（禁止破坏性迁移）：
 * - 每个 schema 变更都必须新增一个 [androidx.room.migration.Migration] 并加入 [LedgerMigrations.ALL]。
 * - **绝不**调用 `fallbackToDestructiveMigration()`，迁移未覆盖时构建/启动直接失败，
 *   而不是静默丢账本。
 * - schema JSON 由 KSP 导出到 `app/schemas/`（app/build.gradle.kts 已配置 room.schemaLocation），
 *   需要时可作为迁移测试的黄金文件。
 *
 * v2 为资产备注、余额调整历史和每月预算增加字段；v1 → v2 使用显式迁移保留旧账本。
 * v3 增加高级配置表；v2 → v3 不改既有表及软删除记录。
 */
@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        BudgetEntity::class,
        SettingsEntity::class,
        AdvancedSettingsEntity::class,
    ],
    version = LedgerDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(LedgerConverters::class)
abstract class LedgerDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun categoryDao(): CategoryDao

    abstract fun accountDao(): AccountDao

    abstract fun budgetDao(): BudgetDao

    abstract fun settingsDao(): SettingsDao

    abstract fun advancedSettingsDao(): AdvancedSettingsDao

    companion object {
        const val VERSION: Int = 3

        const val DATABASE_NAME: String = "blueledger.db"

        /** 生产构建：文件型数据库，只注册显式迁移。 */
        fun create(context: Context, name: String = DATABASE_NAME): LedgerDatabase =
            Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, name)
                .addMigrations(*LedgerMigrations.ALL)
                .build()
    }
}
