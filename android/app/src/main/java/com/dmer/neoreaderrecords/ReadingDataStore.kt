package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object ReadingDataStore {
    private const val DB_NAME = "readtrace_reading.db"
    private const val DB_VERSION = 1
    private const val TABLE_DAILY_BOOKS = "reading_daily_books"

    data class DailyBookRecord(
        val date: String,
        val source: String,
        val bookKey: String,
        val title: String,
        val author: String?,
        val coverCachePath: String?,
        val durationMs: Long,
        val progress: String?,
        val status: Int,
        val confidence: String,
        val lastSeenAt: Long
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DAILY_BOOKS (
                    date TEXT NOT NULL,
                    source TEXT NOT NULL,
                    book_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_cache_path TEXT,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    progress TEXT,
                    status INTEGER NOT NULL DEFAULT 0,
                    confidence TEXT NOT NULL,
                    last_seen_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(date, source, book_key)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_books_date ON $TABLE_DAILY_BOOKS(date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_books_source_date ON $TABLE_DAILY_BOOKS(source, date)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // First schema version. Future migrations must keep user reading history.
        }
    }

    fun upsertDailyBooks(context: Context, records: List<DailyBookRecord>, reason: String): Int {
        if (records.isEmpty()) {
            AutoRefreshLog.i(context, "ReadingDataStore upsert skip reason=$reason records=0")
            return 0
        }
        return runCatching {
            val now = System.currentTimeMillis()
            var written = 0
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    records.forEach { record ->
                        val values = ContentValues().apply {
                            put("date", record.date)
                            put("source", record.source)
                            put("book_key", record.bookKey)
                            put("title", record.title)
                            put("author", record.author)
                            put("cover_cache_path", record.coverCachePath)
                            put("duration_ms", record.durationMs)
                            put("progress", record.progress)
                            put("status", record.status)
                            put("confidence", record.confidence)
                            put("last_seen_at", record.lastSeenAt)
                            put("updated_at", now)
                        }
                        db.insertWithOnConflict(TABLE_DAILY_BOOKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                        written += 1
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            AutoRefreshLog.i(context, "ReadingDataStore upsert reason=$reason records=${records.size} written=$written")
            written
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore upsert failed reason=$reason records=${records.size}", it)
            0
        }
    }

    fun countDailyBooks(context: Context): Int {
        return runCatching {
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.rawQuery("SELECT COUNT(*) FROM $TABLE_DAILY_BOOKS", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
    }
}
