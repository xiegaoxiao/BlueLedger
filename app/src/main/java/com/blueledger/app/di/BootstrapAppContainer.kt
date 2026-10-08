package com.blueledger.app.di

import android.content.Context
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerBackupCodec
import com.blueledger.app.core.contract.LedgerRepository
import com.blueledger.app.demo.BootstrapSystemClock
import com.blueledger.app.demo.InMemoryLedgerRepository
import com.blueledger.app.feature.backup.JsonBackupCodec

/**
 * G0 引导容器：内存仓库 + 系统时钟。
 *
 * 这**不是**正式用户数据路径。它在 A1 的 Room 数据层就绪前让「骨架可运行、页面可联调」，
 * 集成阶段会被 [ProductionAppContainer] 取代。任何情况下都不能用它交付正式 APK。
 */
class BootstrapAppContainer(@Suppress("UNUSED_PARAMETER") context: Context) : AppContainer {
    override val clock: Clock = BootstrapSystemClock()

    override val ledgerRepository: LedgerRepository = InMemoryLedgerRepository(clock)

    override val backupCodec: LedgerBackupCodec = JsonBackupCodec(clock)
}
