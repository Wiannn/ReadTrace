package com.dmer.neoreaderrecords

import android.content.Context
import android.net.Uri
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
        val wallpaperMode = prefs.getString("wallpaper_mode", "STATS") ?: "STATS"
        val now = System.currentTimeMillis()
        val minIntervalMinutes = AutoRefreshConfig.minIntervalMinutes(applicationContext)
        val lastMs = prefs.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        val lastContentMs = prefs.getLong(AutoRefreshConfig.KEY_LAST_CONTENT_TRIGGER_MS, 0L)
        val lastBookKey = prefs.getString("auto_last_book_key", "") ?: ""

        val decision = AutoRefreshPolicy.decide(
            reason = reason,
            wallpaperMode = wallpaperMode,
            nowMs = now,
            minIntervalMinutes = minIntervalMinutes,
            lastTriggerMs = lastMs,
            lastContentTriggerMs = lastContentMs,
            lastBookKey = lastBookKey
        ) { getLatestBookIdentifier(applicationContext) }
        AutoRefreshLog.i(applicationContext, "Worker decision: ${decision.logReason}")
        if (!decision.shouldGenerate) {
            return Result.success()
        }

        val ok = AutoWallpaperGenerator.generateAndSave(applicationContext, reason)
        if (ok) {
            val editor = prefs.edit()
                .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                .putString("auto_last_book_key", decision.latestBookKey)
            if (reason == "book_content_changed") {
                editor.putLong(AutoRefreshConfig.KEY_LAST_CONTENT_TRIGGER_MS, now)
            }
            editor.apply()
            AutoRefreshLog.i(applicationContext, "Worker success saved")
            return Result.success()
        }
        AutoRefreshLog.i(applicationContext, "Worker failed -> retry")
        return Result.retry()
    }

    private fun getLatestBookIdentifier(context: Context): String {
        return runCatching {
            val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
            context.contentResolver.query(metadataUri, null, null, null, "lastAccess DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex("title")
                    val pathIdx = c.getColumnIndex("nativeAbsolutePath")
                    val title = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else "unknown"
                    val path = if (pathIdx >= 0 && !c.isNull(pathIdx)) c.getString(pathIdx) else "unknown"
                    "${title}_${path}"
                } else {
                    "empty"
                }
            } ?: "empty"
        }.getOrDefault("error")
    }

    companion object {
        fun enqueue(context: Context, reason: String) {
            val delaySeconds = when (reason) {
                "screen_off", "screen_on_prewarm", "book_content_changed" -> 12L
                else -> 0L
            }
            val reqBuilder = OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                .setInputData(androidx.work.Data.Builder().putString("reason", reason).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
            if (delaySeconds > 0) reqBuilder.setInitialDelay(delaySeconds, java.util.concurrent.TimeUnit.SECONDS)
            val req = reqBuilder.build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "neoreader_auto_refresh",
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
