package com.blueledger.app.data.local

import androidx.room.migration.Migration

/**
 * 显式迁移清单。
 *
 * 规则（A1 独占数据库结构，其他代理不得改表）：
 * 1. 任何表/列/索引变化都必须把 `@Database(version = ...)` +1，并在此处新增一个 `Migration`。
 * 2. 迁移必须保留全部已有数据：只做 `ALTER TABLE ... ADD COLUMN` / `CREATE INDEX` /
 *    建新表后 `INSERT INTO ... SELECT`，不允许 `DROP TABLE` 后重建丢数据。
 * 3. 禁止 `fallbackToDestructiveMigration()`；缺失迁移时宁可启动失败也不丢账本。
 * 4. 新增迁移后必须补一个“升级保留数据”的测试。
 *
 * v1 是首版 schema，没有历史版本需要迁移，因此这里为空数组（不虚构无意义升级）。
 */
object LedgerMigrations {

    val V1_TO_V2: Migration = object : Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE accounts ADD COLUMN note TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE accounts ADD COLUMN openingHistory TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE ledger_settings ADD COLUMN monthlyBudgetCent INTEGER DEFAULT NULL")
            for (spec in com.blueledger.app.core.model.Defaults.CATEGORIES) {
                db.execSQL("INSERT OR IGNORE INTO categories (id,type,name,nameKey,iconKey,sortOrder,isArchived,isFallback,createdAtEpochMillis,updatedAtEpochMillis) VALUES (?,?,?,?,?,?,0,?,0,0)",
                    arrayOf<Any>(spec.id, spec.type.name, spec.name, spec.name.lowercase(), spec.iconKey, spec.sortOrder, if (spec.isFallback) 1 else 0))
            }
        }
    }
    val ALL: Array<Migration> = arrayOf(V1_TO_V2)
}
