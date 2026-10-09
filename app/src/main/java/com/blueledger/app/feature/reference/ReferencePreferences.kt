package com.blueledger.app.feature.reference

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.blueledger.app.core.model.TransactionType

/** 设备上的操作偏好；账单、预算、资产及余额历史仍统一由 Room 保存和备份。 */
class ReferencePreferences(context: Context) {
    val storage: SharedPreferences = context.applicationContext.getSharedPreferences("ledger_ui_preferences", Context.MODE_PRIVATE)

    /**
     * 每次写入后自增，界面读取它来刷新当前页。
     *
     * 不依赖 SharedPreferences 监听回调的到达时机：回调在部分运行环境滞后或缺失，
     * 会让开关、选择项看起来没反应。
     */
    internal var revision by mutableIntStateOf(0)
        private set

    private fun changed() { revision++ }

    var defaultType: TransactionType
        get() = runCatching { TransactionType.valueOf(storage.getString("entry_type", "EXPENSE")!!) }.getOrDefault(TransactionType.EXPENSE)
        set(value) { storage.edit().putString("entry_type", value.name).apply(); changed() }
    var chartType: TransactionType
        get() = runCatching { TransactionType.valueOf(storage.getString("chart_type", "EXPENSE")!!) }.getOrDefault(TransactionType.EXPENSE)
        set(value) { storage.edit().putString("chart_type", value.name).apply(); changed() }
    var chartPeriod: ChartPeriod
        get() = runCatching { ChartPeriod.valueOf(storage.getString("chart_period", "WEEK")!!) }.getOrDefault(ChartPeriod.WEEK)
        set(value) { storage.edit().putString("chart_period", value.name).apply(); changed() }
    var sound: Boolean
        get() = storage.getBoolean("sound", true)
        set(value) { storage.edit().putBoolean("sound", value).apply(); changed() }
    var haptic: Boolean
        get() = storage.getBoolean("haptic", true)
        set(value) { storage.edit().putBoolean("haptic", value).apply(); changed() }
    var quickEdit: Boolean
        get() = storage.getBoolean("quick_edit", true)
        set(value) { storage.edit().putBoolean("quick_edit", value).apply(); changed() }
    var accountAssociation: Boolean
        get() = storage.getBoolean("account_association", false)
        set(value) { storage.edit().putBoolean("account_association", value).apply(); changed() }
    fun defaultAccount(type: TransactionType): String? = storage.getString("account_${type.name}", null)
    fun setDefaultAccount(type: TransactionType, id: String?) { storage.edit().putString("account_${type.name}", id).apply(); changed() }
    var remindersEnabled: Boolean
        get() = storage.getBoolean("reminders_enabled", false)
        set(value) { storage.edit().putBoolean("reminders_enabled", value).apply(); changed() }
    var reminderTimes: Set<String>
        get() = storage.getStringSet("reminder_times", setOf("17:30"))!!.toSet()
        set(value) { storage.edit().putStringSet("reminder_times", value).apply(); changed() }
}

@Composable
internal fun rememberReferencePreferences(): ReferencePreferences {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val prefs = remember(context) { ReferencePreferences(context) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> version++ }
        prefs.storage.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.storage.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    // 读取快照状态，使偏好写入能刷新当前页面（自身写入走 revision，外部写入走监听回调）。
    @Suppress("UNUSED_VARIABLE") val read = version + prefs.revision
    return prefs
}
