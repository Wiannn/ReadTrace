package com.dmer.neoreaderrecords

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class ScreenOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutoRefreshLog.i(context, "ScreenOffReceiver.onReceive action=${intent?.action}")
        if (intent?.action != Intent.ACTION_SCREEN_OFF) {
            AutoRefreshLog.i(context, "skip: not SCREEN_OFF")
            return
        }
        if (!AutoRefreshConfig.isEnabled(context)) {
            AutoRefreshLog.i(context, "skip: auto disabled")
            return
        }
        if (AutoRefreshConfig.mode(context) != AutoRefreshConfig.MODE_SCREEN_OFF) {
            AutoRefreshLog.i(context, "skip: mode != SCREEN_OFF")
            return
        }
        AutoRefreshLog.i(context, "enqueue worker reason=screen_off")
        AutoRefreshWorker.enqueue(context, "screen_off")
    }
}
