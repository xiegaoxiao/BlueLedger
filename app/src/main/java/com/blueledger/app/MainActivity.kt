package com.blueledger.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import com.blueledger.app.app.ui.BlueLedgerApp

/**
 * 单 Activity 宿主。全部界面由 Compose + 原生导航承载，不使用 WebView。
 *
 * windowSoftInputMode="adjustResize"（见 AndroidManifest）配合 Compose 的
 * WindowInsets.ime，保证记账页的自定义键盘与保存区在系统 IME 弹出时仍然可见。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        // The app uses a light surface even when the device is in dark mode.
        // Keep system-bar icons legible over the light status/navigation areas.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        super.onCreate(savedInstanceState)
        val container = (application as BlueLedgerApplication).container
        setContent {
            BlueLedgerApp(container)
        }
    }
}
