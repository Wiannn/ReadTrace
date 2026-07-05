package com.dmer.neoreaderrecords

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutoRefreshLog.i(context, "BootCompletedReceiver.onReceive action=${intent?.action}")
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED || intent?.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            AutoRefreshScheduler.reschedule(context)
            AutoRefreshRuntime.sync(context)
            AutoRefreshLog.i(context, "reschedule daily done")
        }
    }
}
