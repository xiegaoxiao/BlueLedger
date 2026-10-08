package com.blueledger.app.feature.reference

import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.Build
import com.blueledger.app.MainActivity
import com.blueledger.app.R
import com.blueledger.app.core.contract.Clock
import com.blueledger.app.core.time.SystemClock
import java.time.LocalTime

internal fun nextReminderMillis(clock: Clock, time: LocalTime): Long {
    val now = clock.now().atZone(clock.zoneId())
    var next = now.toLocalDate().atTime(time).atZone(clock.zoneId())
    if (!next.isAfter(now)) next = next.plusDays(1)
    return next.toInstant().toEpochMilli()
}

object LedgerReminders {
    fun reschedule(context: Context) {
        val prefs = ReferencePreferences(context)
        val alarm = context.getSystemService(AlarmManager::class.java)
        // 24 小时共 1440 个稳定 requestCode，删除和更改时间也不会残留旧提醒。
        val old = prefs.storage.getStringSet("scheduled_times", emptySet())!!.toSet()
        old.forEach { raw -> alarm.cancel(pending(context, raw)) }
        val allowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val times = if (prefs.remindersEnabled && allowed) prefs.reminderTimes else emptySet()
        times.forEach { raw ->
            val time = runCatching { LocalTime.parse(raw) }.getOrNull() ?: return@forEach
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextReminderMillis(SystemClock(), time), pending(context, raw))
        }
        prefs.storage.edit().putStringSet("scheduled_times", times).apply()
    }
    private fun pending(context: Context, raw: String): PendingIntent {
        val time = runCatching { LocalTime.parse(raw) }.getOrDefault(LocalTime.NOON)
        return PendingIntent.getBroadcast(context, time.hour * 60 + time.minute,
            Intent(context, LedgerReminderReceiver::class.java).setAction("com.blueledger.app.REMIND").putExtra("time", raw),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class LedgerReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = ReferencePreferences(context)
        if (intent.action == "com.blueledger.app.REMIND" && prefs.remindersEnabled && intent.getStringExtra("time") in prefs.reminderTimes) {
            val allowed = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (allowed) {
                val manager = context.getSystemService(NotificationManager::class.java)
                manager.createNotificationChannel(NotificationChannel("ledger_reminder", "记账提醒", NotificationManager.IMPORTANCE_DEFAULT))
                val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                manager.notify(17030, Notification.Builder(context, "ledger_reminder")
                    .setSmallIcon(R.drawable.ic_notification_ledger).setContentTitle("蓝记 · 记账提醒")
                    .setContentText("记录今天的收支，养成记账习惯").setContentIntent(open).setAutoCancel(true).build())
            }
        }
        LedgerReminders.reschedule(context)
    }
}
