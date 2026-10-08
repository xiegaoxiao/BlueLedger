package com.blueledger.app

import android.app.Application
import com.blueledger.app.di.AppContainer
import com.blueledger.app.di.ProductionAppContainer

/**
 * 应用入口。持有唯一的 [AppContainer]。
 *
 * 正式构建使用 [ProductionAppContainer]（真实 Room 持久化）。
 * G0 引导用的 `BootstrapAppContainer`（内存仓库）已被替换，只保留给 UI 联调与测试，
 * **不再进入正式用户数据路径**。
 */
class BlueLedgerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = createContainer()
        com.blueledger.app.feature.reference.LedgerReminders.reschedule(this)
    }

    private fun createContainer(): AppContainer = ProductionAppContainer(this)
}
