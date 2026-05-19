package com.dmer.neoreaderrecords

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

class AutoRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AutoRefreshLog.i(applicationContext, "Worker.doWork start")
        if (!AutoRefreshConfig.isEnabled(applicationContext)) return Result.success()
        val reason = inputData.getString("reason") ?: "unknown"
        AutoRefreshLog.i(applicationContext, "Worker reason=$reason")
        val prefs = applicationContext.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val minIntervalMs = AutoRefreshConfig.minIntervalMinutes(applicationContext) * 60_000L
        val last = prefs.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        if (reason == "screen_off" && now - last < minIntervalMs) {
            AutoRefreshLog.i(applicationContext, "Worker skip by debounce: delta=${now - last}ms < $minIntervalMs ms")
            return Result.success()
        }

        val ok = AutoWallpaperGenerator.generateAndSave(applicationContext, reason)
        if (ok) {
            prefs.edit()
                .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                .apply()
            AutoRefreshLog.i(applicationContext, "Worker success saved")
            return Result.success()
        }
        AutoRefreshLog.i(applicationContext, "Worker failed -> retry")
        return Result.retry()
    }

    companion object {
        fun enqueue(context: Context, reason: String) {
            val req = OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                .setInputData(androidx.work.Data.Builder().putString("reason", reason).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "neoreader_auto_refresh",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
