package com.blueledger.app.di

import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerBackupCodec
import com.blueledger.app.core.contract.LedgerRepository

/**
 * 应用级依赖容器（手工 DI，不引入 Hilt/Koin）。
 *
 * 所有权：总控独占。
 *
 * 实现选择：
 * - 正式构建使用 [ProductionAppContainer]（真实 Room 持久化，A1 交付）。
 * - G0 引导期使用 [BootstrapAppContainer]（内存仓库），它**只**用于让骨架可运行与
 *   让 UI 代理在真实数据层就绪前联调；一旦 A1 交付，Application 就切到生产容器。
 */
interface AppContainer {
    val ledgerRepository: LedgerRepository

    val clock: Clock

    /** 备份/恢复编解码器（A6 交付的 `JsonBackupCodec`）。S11 数据管理页消费。 */
    val backupCodec: LedgerBackupCodec
}
