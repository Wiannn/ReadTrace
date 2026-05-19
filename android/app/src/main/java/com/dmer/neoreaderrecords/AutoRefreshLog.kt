package com.dmer.neoreaderrecords

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutoRefreshLog {
    private const val LOG_NAME = "neoreader_auto_refresh_log.txt"
    private val lock = Any()

    fun i(context: Context, msg: String) {
        write(context, "INFO", msg)
    }

    fun e(context: Context, msg: String, t: Throwable? = null) {
        val tail = if (t == null) "" else " | ${t.javaClass.simpleName}: ${t.message}"
        write(context, "ERROR", msg + tail)
    }

    private fun write(context: Context, level: String, msg: String) {
        synchronized(lock) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$now [$level] $msg\n"
            runCatching {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                FileWriter(File(dir, LOG_NAME), true).use { it.append(line) }
                return
            }
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: return
                FileWriter(File(dir, LOG_NAME), true).use { it.append(line) }
            }
        }
    }
}
