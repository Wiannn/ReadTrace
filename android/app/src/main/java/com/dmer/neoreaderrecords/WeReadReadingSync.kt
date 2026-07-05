package com.dmer.neoreaderrecords

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object WeReadReadingSync {
    fun syncCurrentMonth(context: Context, reason: String): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "WeRead snapshot sync skip data store disabled reason=$reason")
            return false
        }
        val monthStart = Calendar.getInstance(TimeZone.getDefault()).apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val stats = WeReadClient.fetchWallpaperStats(
            context,
            WeReadClient.loadApiKey(context),
            "monthly",
            monthStart / 1000L
        )
        if (!stats.ok) {
            AutoRefreshLog.i(context, "WeRead snapshot sync failed reason=$reason detail=${stats.detail.take(120)}")
            return false
        }
        AutoWallpaperGenerator.persistWeReadMonthlyStats(
            context,
            monthStart,
            stats,
            captureSnapshot = true
        )
        AutoRefreshLog.i(context, "WeRead snapshot sync success reason=$reason")
        return true
    }

    @Synchronized
    fun captureCurrentMonth(
        context: Context,
        monthStartMs: Long,
        stats: WeReadClient.WallpaperStatsResult
    ): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) return false
        if (!stats.ok || !isCurrentMonth(monthStartMs)) return false
        val prefs = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastCapture = prefs.getLong(KEY_LAST_CAPTURE_MS, 0L)
        if (now - lastCapture in 0 until CAPTURE_MIN_INTERVAL_MS) {
            AutoRefreshLog.i(context, "WeRead snapshot capture skip duplicate delta=${now - lastCapture}ms")
            return true
        }
        val apiKey = WeReadClient.loadApiKey(context)
        val shelf = WeReadClient.fetchShelfSnapshot(context, apiKey)
        if (!shelf.ok) {
            AutoRefreshLog.i(context, "WeRead snapshot skip shelf failed detail=${shelf.detail.take(120)}")
            return false
        }

        val priorStates = ReadingDataStore.queryWeReadBookStates(context)
        val baseline = priorStates.isEmpty()
        val historyImportPending = !ReadingDataStore.isWeReadShelfHistoryImported(context)
        val changedKeys = if (baseline) {
            emptySet()
        } else {
            shelf.books
                .filter { book ->
                    book.readUpdateTimeMs > (priorStates[book.bookKey]?.lastReadAt ?: 0L)
                }
                .mapTo(linkedSetOf()) { it.bookKey }
        }
        val historyKeys = if (historyImportPending) {
            shelf.books
                .filter { it.readUpdateTimeMs in monthStartMs..now }
                .mapTo(linkedSetOf()) { it.bookKey }
        } else {
            emptySet()
        }
        val rankingDurationByKey = stats.books.associate { it.bookId to it.readSeconds * 1000L }
        val changedSummary = shelf.books
            .filter { it.bookKey in changedKeys }
            .take(10)
            .joinToString(" | ") { book ->
                "${book.title}@${book.readUpdateTimeMs.takeIf { it > 0L }?.let { timestamp ->
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(timestamp))
                } ?: "-"}"
            }
        if (changedSummary.isNotBlank()) {
            AutoRefreshLog.i(context, "WeRead snapshot changed $changedSummary")
        }
        val enriched = linkedMapOf<String, EnrichedBook>()
        shelf.books
            .filter { it.bookKey in historyKeys }
            .forEach { book ->
                enriched[book.bookKey] = EnrichedBook(
                    coverPath = WeReadClient.cacheShelfBookCover(context, book),
                    progress = null
                )
            }
        shelf.books
            .filter { it.bookKey in changedKeys }
            .take(MAX_CHANGED_BOOK_DETAILS)
            .forEach { book ->
                val coverPath = WeReadClient.cacheShelfBookCover(context, book)
                val progress = if (book.bookKey.startsWith("album:")) {
                    null
                } else {
                    WeReadClient.fetchBookProgress(context, apiKey, book.bookKey)
                        .takeIf { it.ok }
                }
                enriched[book.bookKey] = EnrichedBook(
                    coverPath = coverPath ?: enriched[book.bookKey]?.coverPath,
                    progress = progress
                )
            }

        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val periodStartCal = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = monthStartMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val periodStart = dateFmt.format(Date(periodStartCal.timeInMillis))
        periodStartCal.add(Calendar.MONTH, 1)
        periodStartCal.add(Calendar.DAY_OF_MONTH, -1)
        val periodEnd = dateFmt.format(Date(periodStartCal.timeInMillis))
        val today = dateFmt.format(Date())

        val snapshotBooks = shelf.books.map { book ->
            val extra = enriched[book.bookKey]
            val progressPercent = extra?.progress?.progressPercent
            ReadingDataStore.WeReadSnapshotBook(
                bookKey = book.bookKey,
                title = book.title,
                author = book.author,
                coverCachePath = extra?.coverPath,
                lastReadAt = book.readUpdateTimeMs,
                readDate = book.readUpdateTimeMs
                    .takeIf { it > 0L }
                    ?.let { dateFmt.format(Date(it)) },
                monthlyDurationMs = rankingDurationByKey[book.bookKey],
                recordReadingMs = extra?.progress?.recordReadingSeconds?.times(1000L),
                progress = progressPercent?.let { "$it%" },
                status = when {
                    progressPercent == 100 -> 2
                    else -> book.status
                }
            )
        }
        val applied = ReadingDataStore.applyWeReadSnapshot(
            context = context,
            periodStart = periodStart,
            periodEnd = periodEnd,
            trackingDate = today,
            books = snapshotBooks
        ) ?: return false
        AutoRefreshLog.i(
            context,
            "WeRead snapshot applied baseline=${applied.baseline} historyPending=$historyImportPending historyCandidates=${historyKeys.size} historyImported=${applied.historyImported} historyWritten=${applied.historyRecordsWritten} trackingStart=${applied.trackingStartDate} shelf=${shelf.books.size} changedDetected=${changedKeys.size} changedApplied=${applied.changedBooks} dailyWritten=${applied.dailyRecordsWritten} ranking=${rankingDurationByKey.size} enriched=${enriched.size}"
        )
        prefs.edit().putLong(KEY_LAST_CAPTURE_MS, now).apply()
        return true
    }

    private fun isCurrentMonth(monthStartMs: Long): Boolean {
        val month = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = monthStartMs }
        val now = Calendar.getInstance(TimeZone.getDefault())
        return month.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            month.get(Calendar.MONTH) == now.get(Calendar.MONTH)
    }

    private data class EnrichedBook(
        val coverPath: String?,
        val progress: WeReadClient.BookProgressResult?
    )

    private const val MAX_CHANGED_BOOK_DETAILS = 10
    private const val CAPTURE_MIN_INTERVAL_MS = 15_000L
    private const val KEY_LAST_CAPTURE_MS = "weread_snapshot_last_capture_ms"
}
