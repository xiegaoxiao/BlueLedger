package com.blueledger.app.di

import android.content.Context
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerBackupCodec
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.core.time.SystemClock
import com.blueledger.app.data.local.LedgerDatabase
import com.blueledger.app.data.repository.LedgerRepositoryImpl
import com.blueledger.app.feature.backup.JsonBackupCodec

/**
 * 生产容器：真实 Room 持久化 + 系统时钟。
 *
 * 这是**正式用户数据路径**：
 * - [LedgerDatabase] 是文件型 SQLite（`blueledger.db`），只注册显式迁移，
 *   没有 `fallbackToDestructiveMigration()`——迁移缺失时启动失败而不是静默丢账本。
 * - [SystemClock] 每次读时区都重新取系统值，不会把首次启动的时区永久冻结。
 *
 * 与 [BootstrapAppContainer] 的区别：后者用内存仓库，只服务于 G0 骨架与 UI 联调，
 * 任何交付物都不得使用它。
 */
class ProductionAppContainer(context: Context) : AppContainer {

    override val clock: Clock = SystemClock()

    private val database: LedgerDatabase = LedgerDatabase.create(context.applicationContext)

    override val ledgerRepository: LedgerRepository = LedgerRepositoryImpl(
        db = database,
        clock = clock,
    )

    /** A6 交付的正式备份编解码器：JSON 往返 + 全量校验（校验通过才产生 ValidatedLedgerSnapshot）。 */
    override val backupCodec: LedgerBackupCodec = JsonBackupCodec(clock)
}
