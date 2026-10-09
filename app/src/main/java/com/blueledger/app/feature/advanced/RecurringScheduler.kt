package com.blueledger.app.feature.advanced

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blueledger.app.BlueLedgerApplication
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.contract.LedgerRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** 不需要精确闹钟权限；前台启动补记与后台到期执行共享数据库幂等事务。 */
object RecurringScheduler {
    suspend fun reschedule(context: Context, repository: LedgerRepository, clock: Clock) {
        val alarm = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(context, 82001, Intent(context, RecurringLedgerReceiver::class.java).setAction("com.blueledger.app.RECURRING"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        val next = repository.observeAdvancedSettings().first().recurringRules.filter { it.enabled }.minOfOrNull { LocalDate.parse(it.nextDate) } ?: return
        val at = maxOf(clock.now().toEpochMilli() + 30_000, next.atStartOfDay(clock.zoneId()).toInstant().toEpochMilli())
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }
}

class RecurringLedgerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val container = (context.applicationContext as BlueLedgerApplication).container
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeout(8_000) { container.ledgerRepository.processRecurringTransactions() }
            } catch (error: Exception) {
                android.util.Log.w("RecurringLedger", "到期记账暂未完成，下次启动将继续补记", error)
            } finally {
                try {
                    withTimeout(2_000) { RecurringScheduler.reschedule(context, container.ledgerRepository, container.clock) }
                } catch (error: Exception) {
                    android.util.Log.w("RecurringLedger", "自动记账调度暂未完成", error)
                } finally { pending.finish() }
            }
        }
    }
}
