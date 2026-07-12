package net.guardian

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        scheduleWatchdog(context)
        scheduleServiceStart(context)
    }

    private fun scheduleServiceStart(context: Context) {
        try {
            context.startForegroundService(Intent(context, CommandService::class.java))
        } catch (_: Exception) {
            try {
                context.startService(Intent(context, CommandService::class.java))
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        fun scheduleWatchdog(context: Context) {
            val intent = Intent(context, RestartReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                pi
            )
        }

        fun cancelWatchdog(context: Context) {
            val intent = Intent(context, RestartReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pi)
        }
    }
}
