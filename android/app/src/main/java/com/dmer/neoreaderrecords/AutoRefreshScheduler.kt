package com.dmer.neoreaderrecords

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar
import java.util.Locale

object AutoRefreshScheduler {
    private const val REQ_DAILY = 33071

    fun reschedule(context: Context) {
        cancelDaily(context)
        if (!AutoRefreshConfig.isEnabled(context)) return
        if (AutoRefreshConfig.mode(context) != AutoRefreshConfig.MODE_DAILY) return
        scheduleDaily(context, AutoRefreshConfig.dailyTime(context))
    }

    fun cancelDaily(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = dailyPendingIntent(context, PendingIntent.FLAG_NO_CREATE) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    private fun scheduleDaily(context: Context, hhmm: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = dailyPendingIntent(context, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        val first = computeNextTrigger(hhmm)
        am.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            first,
            AlarmManager.INTERVAL_DAY,
            pi
        )
    }

    private fun dailyPendingIntent(context: Context, extraFlags: Int): PendingIntent? {
        val intent = Intent(context, AutoRefreshAlarmReceiver::class.java).apply {
            action = "com.dmer.neoreaderrecords.AUTO_DAILY_REFRESH"
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_DAILY,
            intent,
            extraFlags or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun computeNextTrigger(hhmm: String): Long {
        val parts = hhmm.trim().split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        val now = Calendar.getInstance(Locale.getDefault())
        val c = Calendar.getInstance(Locale.getDefault())
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now.timeInMillis) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }
}
