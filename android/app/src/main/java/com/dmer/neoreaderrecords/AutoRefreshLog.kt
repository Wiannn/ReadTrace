package com.dmer.neoreaderrecords

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.RandomAccessFile
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
            val safeMsg = sanitize(msg)
            val line = "$now [$level] $safeMsg\n"
            runCatching {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                appendLine(File(dir, LOG_NAME), line)
                return
            }
            runCatching {
                val dir = context.getExternalFilesDir(null) ?: return
                appendLine(File(dir, LOG_NAME), line)
            }
        }
    }

    private fun appendLine(file: File, line: String) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.use { ch ->
                ch.lock().use {
                    raf.seek(raf.length())
                    raf.write(line.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    private fun sanitize(msg: String): String {
        val sb = StringBuilder(msg.length)
        for (ch in msg) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                ch.isISOControl() -> sb.append('?')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
