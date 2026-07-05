package com.dmer.neoreaderrecords

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import java.util.TimeZone

object AutoWallpaperGenerator {
    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")
    private const val DAY_MS = 86_400_000L
    private const val READING_STORE_SYNC_MIN_INTERVAL_MS = 60_000L

    private val typefaceCache = mutableMapOf<String, Typeface>()
    private fun typefaceCacheKey(spec: String, bold: Boolean): String = "$spec|$bold"

    private const val API_CACHE_EXPIRE_MS = 5 * 60 * 1000L
    private data class CachedWeReadData(val data: WeReadBuildData, val timestamp: Long)
    private val weReadDataCache = mutableMapOf<String, CachedWeReadData>()
    private fun weReadCacheKey(s: AutoSettings): String = "${s.periodMode}|${s.topN}|${s.minDurationMinutes}|${s.weekStart}|${s.weekEnd}"

    private data class CachedBitmap(val bitmap: Bitmap, val settingsHash: Int, val dataHash: Int)
    private val bitmapCache = mutableMapOf<String, CachedBitmap>()
    private fun bitmapCacheKey(sourceMark: String, width: Int, height: Int): String = "$sourceMark|$width|$height"
    private const val READING_STORE_SYNC_LOOKBACK_DAYS = 3
    private const val KEY_READING_STORE_LAST_SYNC_ATTEMPT_MS = "reading_store_last_sync_attempt_ms"
    private const val KEY_READING_STORE_LAST_SYNC_SUCCESS_MS = "reading_store_last_sync_success_ms"
    private val readingStoreSyncLock = Any()

    private val BRACKET_PATTERN = Regex("""[（\(][^）\)]*[）\)]""")
    private val SQUARE_BRACKET_PATTERN = Regex("""\[[^\]]*\]""")

    private fun cleanTitle(title: String): String {
        return BRACKET_PATTERN.replace(title, "").trim()
    }

    private fun cleanAuthor(author: String?): String? {
        return author?.let { SQUARE_BRACKET_PATTERN.replace(BRACKET_PATTERN.replace(it, ""), "").trim().ifBlank { null } }
    }

    private data class BookItem(
        val bookId: String?,
        val title: String,
        val author: String?,
        val progress: String?,
        val status: Int,
        val progressText: String? = null,
        val durationText: String? = null,
        val durationMs: Long = 0L
    )
    private data class MetadataBook(
        val path: String,
        val lastAccessMs: Long,
        val item: BookItem
    )
    private data class CalendarCoverItem(
        val title: String,
        val author: String?,
        val path: String,
        val status: Int,
        val durationMs: Long,
        val lastSeenAt: Long
    )
    private data class CalendarDayCell(
        val dayStartMs: Long,
        val dayOfMonth: Int,
        val inMonth: Boolean,
        val totalMs: Long,
        val eventCount: Int,
        val unmatchedCount: Int,
        val books: List<CalendarCoverItem>,
        val sourceKind: String = "NEO"
    )
    private data class CalendarBuildData(
        val monthStartMs: Long,
        val monthEndMs: Long,
        val weekRows: Int,
        val cells: List<CalendarDayCell>,
        val statsRows: Int,
        val matchedRows: Int,
        val unmatchedRows: Int,
        val footerLabel: String = "Neo 本地月历 · 近似匹配",
        val showDurationOnlyLabel: Boolean = true,
        val showFooterLabel: Boolean = true,
        val showDaySourceLabel: Boolean = false
    )
    private data class CalendarMonthFrame(
        val monthStart: Long,
        val monthEnd: Long,
        val weekRows: Int,
        val gridStart: Long
    )
    private data class ChartStats(val totalMs: Long, val points: LongArray, val labels: List<String>)
    private data class WallpaperSize(val label: String, val width: Int, val height: Int)
    private data class WeReadBuildData(
        val rangeStart: Long,
        val rangeEnd: Long,
        val chart: ChartStats,
        val books: List<BookItem>,
        val label: String,
        val note: String
    )
    private enum class BucketMode { HOUR, DAY, WEEK, MONTH }

    private data class AutoSettings(
        val includeUnread: Boolean,
        val showProgressStatus: Boolean,

        val minDurationMinutes: Int,
        val topN: Int,
        val periodMode: String,
        val weekStart: String,
        val weekEnd: String,
        val sourceMode: String,
        val wallpaperMode: String,
        val calendarStackOrder: String,
        val progressMode: String,
        val timeUnit: String,
        val receiptTitle: String,
        val receiptTitleSize: Float,
        val receiptBodySize: Float,
        val weReadNickname: String,
        val booxDevicePreset: String,
        val customWallpaperWidth: Int,
        val customWallpaperHeight: Int,
        val noteText: String,
        val titleFont: String,
        val bodyFont: String,
        val stickerImagePath: String?
    )

    data class PreviewResult(val bitmap: Bitmap, val summary: String)


    fun generateAndSave(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "Generator start reason=$reason")
        return runCatching {
            val built = buildPreviewInternal(context, "A", true) ?: return false
            val path = saveBitmap(context, built.bitmap)
            AutoRefreshLog.i(context, "Generator saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "Generator exception", it)
            false
        }
    }

    fun generateAndSaveWeRead(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "WeRead auto generator start reason=$reason")
        return runCatching {
            val s = readSettings(context)
            if (s.wallpaperMode != "STATS" && s.wallpaperMode != "CALENDAR") {
                WeReadReadingSync.syncCurrentMonth(context, "weread_auto_$reason")
            }
            val built = buildWeReadPreviewForWallpaperMode(context, s.wallpaperMode) ?: return false
            if (built.summary.contains("source=fallback_cache")) {
                AutoRefreshLog.i(context, "WeRead auto skip saving fallback cache and request retry ${built.summary}")
                return false
            }
            val path = saveBitmap(context, built.bitmap)
            AutoRefreshLog.i(context, "WeRead auto saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "WeRead auto generator exception", it)
            false
        }
    }

    fun generateAndSaveMixed(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "Mixed auto generator start reason=$reason")
        return runCatching {
            val settings = readSettings(context)
            if (settings.wallpaperMode != "STATS" && settings.wallpaperMode != "CALENDAR") {
                WeReadReadingSync.syncCurrentMonth(context, "mixed_auto_$reason")
            }
            val built = buildMixedPreviewFromPrefs(context, "A") ?: return false
            if ((reason.startsWith("screen_on_prewarm") || reason.startsWith("user_present_prewarm")) &&
                built.summary.contains("source=fallback_cache")
            ) {
                AutoRefreshLog.i(context, "Mixed auto skip saving fallback cache and request retry ${built.summary}")
                return false
            }
            val path = saveBitmap(context, built.bitmap)
            AutoRefreshLog.i(context, "Mixed auto saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "Mixed auto generator exception", it)
            false
        }
    }

    fun buildPreviewFromPrefs(context: Context, sourceMark: String = "M"): PreviewResult? {
        return runCatching { buildPreviewInternal(context, sourceMark, false) }.getOrNull()
    }

    fun bootstrapReadingStoreIfNeeded(context: Context): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore bootstrap skip disabled")
            return true
        }
        return runCatching {
            val bootstrapSettings = readSettings(context).copy(
                sourceMode = "DURATION",
                periodMode = "THIS_MONTH"
            )
            val now = System.currentTimeMillis()
            val monthBases = linkedSetOf(now)
            val recentStart = Calendar.getInstance(TimeZone.getDefault()).apply {
                timeInMillis = now
                add(Calendar.DAY_OF_MONTH, -29)
            }.timeInMillis
            if (!isSameMonth(now, recentStart)) {
                monthBases.add(recentStart)
            }

            var months = 0
            monthBases.forEach { baseMs ->
                val frame = calendarMonthFrame(baseMs)
                val data = buildLiveNeoCalendarData(
                    context = context,
                    s = bootstrapSettings,
                    monthStart = frame.monthStart,
                    monthEnd = frame.monthEnd,
                    weekRows = frame.weekRows,
                    gridStart = frame.gridStart
                )
                if (data != null) months += 1
            }
            AutoRefreshLog.i(
                context,
                "ReadingDataStore bootstrap done months=$months totalDb=${ReadingDataStore.countDailyBooks(context)}"
            )
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore bootstrap failed", it)
            false
        }
    }

    fun syncRecentNeoReadingStore(context: Context, reason: String): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore incremental skip disabled reason=$reason")
            return true
        }
        return synchronized(readingStoreSyncLock) {
            val prefs = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastAttempt = prefs.getLong(KEY_READING_STORE_LAST_SYNC_ATTEMPT_MS, 0L)
            val delta = now - lastAttempt
            if (delta in 0 until READING_STORE_SYNC_MIN_INTERVAL_MS) {
                AutoRefreshLog.i(
                    context,
                    "ReadingDataStore incremental skip reason=$reason delta=${delta}ms"
                )
                return@synchronized true
            }
            prefs.edit().putLong(KEY_READING_STORE_LAST_SYNC_ATTEMPT_MS, now).apply()

            runCatching {
                val end = Calendar.getInstance(TimeZone.getDefault()).apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                val start = Calendar.getInstance(TimeZone.getDefault()).apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_MONTH, -(READING_STORE_SYNC_LOOKBACK_DAYS - 1))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val settings = readSettings(context).copy(
                    sourceMode = "DURATION",
                    periodMode = "THIS_MONTH",
                    weekStart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start)),
                    weekEnd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(end))
                )
                val data = buildLiveNeoCalendarData(
                    context = context,
                    s = settings,
                    monthStart = start,
                    monthEnd = end,
                    weekRows = 1,
                    gridStart = start
                )
                prefs.edit().putLong(KEY_READING_STORE_LAST_SYNC_SUCCESS_MS, now).apply()
                AutoRefreshLog.i(
                    context,
                    "ReadingDataStore incremental done reason=$reason range=${fmt(start)}~${fmt(end)} rows=${data?.statsRows ?: 0} totalDb=${ReadingDataStore.countDailyBooks(context)}"
                )
                true
            }.getOrElse {
                AutoRefreshLog.e(context, "ReadingDataStore incremental failed reason=$reason", it)
                false
            }
        }
    }

    fun buildWeReadStatsPreviewFromPrefs(context: Context, sourceMark: String = "W"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            val data = buildWeReadStatsForSettings(context, s)
            if (data == null) {
                AutoRefreshLog.i(context, "WeRead wallpaper preview failed: no range data")
                return@runCatching null
            }
            val wallpaperSize = resolveWallpaperSize(s)
            val cacheKey = bitmapCacheKey(sourceMark, wallpaperSize.width, wallpaperSize.height)
            val settingsHash = s.hashCode()
            val dataHash = data.hashCode()
            bitmapCache[cacheKey]?.let { cached ->
                if (cached.settingsHash == settingsHash && cached.dataHash == dataHash && !cached.bitmap.isRecycled) {
                    AutoRefreshLog.i(context, "Bitmap cache hit for $cacheKey")
                    val summary = buildString {
                        append("微信读书 ")
                        append(data.label)
                        append(", 书籍=").append(data.books.size)
                        append(", 时长=").append(formatDuration(data.chart.totalMs, s.timeUnit))
                        append(", 输出=").append(canvasSizeText(s))
                        if (data.note.isNotBlank()) append(", ").append(data.note)
                    }
                    val config = cached.bitmap.config ?: Bitmap.Config.ARGB_8888
                    return@runCatching PreviewResult(cached.bitmap.copy(config, true), summary)
                }
            }
            val bmp = draw(context, data.rangeStart, data.rangeEnd, data.chart, data.books, s, sourceMark)
            bitmapCache[cacheKey] = CachedBitmap(bmp, settingsHash, dataHash)
            val summary = buildString {
                append("微信读书 ")
                append(data.label)
                append(", 书籍=").append(data.books.size)
                append(", 时长=").append(formatDuration(data.chart.totalMs, s.timeUnit))
                append(", 输出=").append(canvasSizeText(s))
                if (data.note.isNotBlank()) append(", ").append(data.note)
            }
            PreviewResult(bmp, summary)
        }.getOrNull()
    }



    fun buildLocalCalendarPreviewFromPrefs(context: Context, sourceMark: String = "M"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            buildLocalCalendarPreviewForSettings(context, s, sourceMark)
        }.getOrNull()
    }

    fun buildWeReadCalendarPreviewFromPrefs(context: Context, sourceMark: String = "W"): PreviewResult? {
        return runCatching {
            val syncOk = WeReadReadingSync.syncCurrentMonth(context, "calendar_preview")
            val s = readSettings(context)
            buildWeReadCalendarPreviewForSettings(context, s, sourceMark, syncOk)
        }.getOrNull()
    }

    fun buildMixedPreviewFromPrefs(context: Context, sourceMark: String = "A"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            val data = buildMixedStatsForSettings(context, s) ?: return@runCatching null
            val bmp = draw(context, data.rangeStart, data.rangeEnd, data.chart, data.books, s, sourceMark)
            PreviewResult(
                bmp,
                "混合统计 范围=${fmt(data.rangeStart)}~${fmt(data.rangeEnd)}, 书籍=${data.books.size}, 时长=${formatDuration(data.chart.totalMs, s.timeUnit)}, ${data.note}, 输出=${canvasSizeText(s)}"
            )
        }.getOrNull()
    }

    private fun buildMixedCalendarPreviewForSettings(
        context: Context,
        s: AutoSettings,
        sourceMark: String
    ): PreviewResult? {
        val range = resolvePeriodRange(s)
        val frame = calendarFrameForSettings(s, range)
        val syncOk = WeReadReadingSync.syncCurrentMonth(context, "mixed_calendar_preview")
        val localData = buildLocalCalendarData(context, s)
        val weReadData = buildStoredWeReadCalendarData(
            context = context,
            s = s,
            monthStart = frame.monthStart,
            monthEnd = frame.monthEnd,
            weekRows = frame.weekRows,
            gridStart = frame.gridStart
        )
        val data = mergeCalendarData(localData, weReadData, s) ?: return null
        val bmp = drawCalendarWallpaper(context, data, s, sourceMark)
        val localDays = data.cells.count {
            it.inMonth && it.sourceKind == "NEO" && (it.totalMs > 0L || it.books.isNotEmpty())
        }
        val weReadDays = data.cells.count {
            it.inMonth && it.sourceKind == "WEREAD" && (it.totalMs > 0L || it.books.isNotEmpty())
        }
        val mixedDays = data.cells.count {
            it.inMonth && it.sourceKind == "MIXED" && (it.totalMs > 0L || it.books.isNotEmpty())
        }
        val source = when {
            syncOk -> "network+db"
            !AutoRefreshConfig.isReadingDataStoreEnabled(context) && weReadData != null ->
                "cache_store_disabled"
            weReadData != null -> "fallback_cache"
            else -> "local_only"
        }
        return PreviewResult(
            bmp,
            "混合月历 month=${calendarTitleLabel(data, s)} source=$source stackOrder=${s.calendarStackOrder} localDays=$localDays weReadDays=$weReadDays mixedDays=$mixedDays records=${data.matchedRows} 输出=${canvasSizeText(s)}"
        )
    }

    private fun mergeCalendarData(
        local: CalendarBuildData?,
        weRead: CalendarBuildData?,
        s: AutoSettings
    ): CalendarBuildData? {
        if (local == null) {
            return weRead?.copy(
                footerLabel = "",
                showDurationOnlyLabel = false,
                showFooterLabel = false,
                showDaySourceLabel = true,
                cells = weRead.cells.map { cell ->
                    cell.copy(sourceKind = if (cell.totalMs > 0L || cell.books.isNotEmpty()) "WEREAD" else "NEO")
                }
            )
        }
        if (weRead == null) {
            return local.copy(
                footerLabel = "",
                showFooterLabel = false,
                showDaySourceLabel = true
            )
        }
        val localByDay = local.cells.associateBy { it.dayStartMs }
        val weReadByDay = weRead.cells.associateBy { it.dayStartMs }
        val cells = (localByDay.keys + weReadByDay.keys).sorted().map { day ->
            val neoCell = localByDay[day]
            val weReadCell = weReadByDay[day]
            val neoActive = neoCell != null && (neoCell.totalMs > 0L || neoCell.books.isNotEmpty())
            val weReadActive = weReadCell != null && (weReadCell.totalMs > 0L || weReadCell.books.isNotEmpty())
            CalendarDayCell(
                dayStartMs = day,
                dayOfMonth = neoCell?.dayOfMonth ?: weReadCell?.dayOfMonth ?: 0,
                inMonth = neoCell?.inMonth ?: weReadCell?.inMonth ?: false,
                totalMs = (neoCell?.totalMs ?: 0L) + (weReadCell?.totalMs ?: 0L),
                eventCount = (neoCell?.eventCount ?: 0) + (weReadCell?.eventCount ?: 0),
                unmatchedCount = (neoCell?.unmatchedCount ?: 0) + (weReadCell?.unmatchedCount ?: 0),
                books = mergeCalendarBooks(
                    neoCell?.books.orEmpty() + weReadCell?.books.orEmpty(),
                    s.calendarStackOrder
                ),
                sourceKind = when {
                    neoActive && weReadActive -> "MIXED"
                    weReadActive -> "WEREAD"
                    else -> "NEO"
                }
            )
        }
        return CalendarBuildData(
            monthStartMs = local.monthStartMs,
            monthEndMs = local.monthEndMs,
            weekRows = local.weekRows,
            cells = cells,
            statsRows = local.statsRows + weRead.statsRows,
            matchedRows = local.matchedRows + weRead.matchedRows,
            unmatchedRows = local.unmatchedRows + weRead.unmatchedRows,
            footerLabel = "",
            showDurationOnlyLabel = false,
            showFooterLabel = false,
            showDaySourceLabel = true
        )
    }

    private fun mergeCalendarBooks(
        books: List<CalendarCoverItem>,
        stackOrder: String
    ): List<CalendarCoverItem> {
        val merged = books
            .groupBy { calendarBookIdentity(it.title) }
            .map { (_, sameBook) ->
                val preferred = sameBook.maxWithOrNull(compareBy { it.lastSeenAt }) ?: sameBook.first()
                preferred.copy(
                    durationMs = sameBook.sumOf { it.durationMs },
                    lastSeenAt = sameBook.maxOf { it.lastSeenAt },
                    status = sameBook.maxOf { it.status }
                )
            }
        val selected = when (stackOrder) {
            "SHORTEST_TOP" -> merged.sortedBy { it.durationMs }
            "LATEST_TOP" -> merged.sortedByDescending { it.lastSeenAt }
            else -> merged.sortedByDescending { it.durationMs }
        }.take(4)
        return orderCalendarStack(selected, stackOrder)
    }

    private fun calendarBookIdentity(title: String): String {
        return title.lowercase(Locale.ROOT)
            .replace(Regex("[\\s·•:：,，.。()（）《》【】\\[\\]_-]+"), "")
    }

    private fun buildWeReadPreviewForWallpaperMode(context: Context, wallpaperMode: String): PreviewResult? {
        return buildWeReadStatsPreviewFromPrefs(context, "W")
    }

    private fun normalizeEpochMs(value: Long): Long {
        return when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }
    }

    private fun buildPreviewInternal(context: Context, sourceMark: String, fromAutoWorker: Boolean): PreviewResult? {
        val s = readSettings(context)
        val range = resolvePeriodRange(s) ?: return null
        val books = if (s.sourceMode == "DURATION") {
            queryTopBooksByDuration(context, range.first, range.second, s)
                .take(s.topN)
                .map { it.first }
        } else {
            queryTopBooks(context.contentResolver, range.first, range.second, s.topN, s.includeUnread)
        }
        val stats = queryStatsByMode(context.contentResolver, range.first, range.second, s)
        AutoRefreshLog.i(
            context,
            "Local stats books source=${s.sourceMode} books=${books.size} withDuration=${books.count { !it.durationText.isNullOrBlank() }}"
        )
        val bmp = draw(context, range.first, range.second, stats, books, s, sourceMark)
        val summary = buildString {
            append("范围=").append(fmt(range.first)).append("~").append(fmt(range.second))
            append(", 周期=").append(s.periodMode)
            append(", 口径=").append(s.sourceMode)
            append(", TopN=").append(s.topN)
            append(", 书籍=").append(books.size)
            append(", 时长=").append(formatDuration(stats.totalMs, s.timeUnit))
            append(", 输出=").append(canvasSizeText(s))
        }
        return PreviewResult(bmp, summary)
    }

    private fun buildLocalCalendarPreviewForSettings(context: Context, s: AutoSettings, sourceMark: String): PreviewResult? {
        val data = buildLocalCalendarData(context, s) ?: return null
        val bmp = drawCalendarWallpaper(context, data, s, sourceMark)
        val monthLabel = calendarTitleLabel(data, s)
        return PreviewResult(
            bmp,
            "月历 month=$monthLabel stackOrder=${s.calendarStackOrder} rows=${data.statsRows} matched=${data.matchedRows} unmatched=${data.unmatchedRows} 输出=${canvasSizeText(s)}"
        )
    }

    private fun buildWeReadCalendarPreviewForSettings(
        context: Context,
        s: AutoSettings,
        sourceMark: String,
        syncOk: Boolean
    ): PreviewResult? {
        val range = resolvePeriodRange(s)
        val frame = calendarFrameForSettings(s, range)
        val data = buildStoredWeReadCalendarData(
            context,
            s,
            frame.monthStart,
            frame.monthEnd,
            frame.weekRows,
            frame.gridStart
        ) ?: return null
        val bmp = drawCalendarWallpaper(context, data, s, sourceMark)
        val activeDays = data.cells.count { it.inMonth && it.totalMs > 0L }
        val source = when {
            syncOk -> "network+db"
            !AutoRefreshConfig.isReadingDataStoreEnabled(context) -> "cache_store_disabled"
            else -> "fallback_cache"
        }
        return PreviewResult(
            bmp,
            "微信读书月历 month=${calendarTitleLabel(data, s)} source=$source stackOrder=${s.calendarStackOrder} activeDays=$activeDays records=${data.matchedRows} 输出=${canvasSizeText(s)}"
        )
    }

    private fun buildStoredWeReadCalendarData(
        context: Context,
        s: AutoSettings,
        monthStart: Long,
        monthEnd: Long,
        weekRows: Int,
        gridStart: Long
    ): CalendarBuildData? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startDate = dateFmt.format(Date(monthStart))
        val endDate = dateFmt.format(Date(monthEnd))
        val totals = ReadingDataStore.queryDailyTotals(context, "WEREAD", startDate, endDate)
        val records = ReadingDataStore.queryDailyBooks(context, "WEREAD", startDate, endDate)
            .filter { record ->
                (s.includeUnread || record.status != 0)
            }
        if (totals.isEmpty() && records.isEmpty()) {
            AutoRefreshLog.i(context, "WeRead calendar database empty range=$startDate~$endDate")
            return null
        }
        val totalsByDate = totals.associateBy { it.date }
        val recordsByDate = records.groupBy { it.date }
        val cells = (0 until weekRows * 7).map { index ->
            val dayMs = gridStart + index * DAY_MS
            val dc = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = dayMs }
            val dayKey = dateFmt.format(Date(dayMs))
            val dayRecords = recordsByDate[dayKey].orEmpty()
            val selectedRecords = when (s.calendarStackOrder) {
                "SHORTEST_TOP" -> dayRecords.sortedBy { it.durationMs }
                "LATEST_TOP" -> dayRecords.sortedByDescending { it.lastSeenAt }
                else -> dayRecords.sortedByDescending { it.durationMs }
            }.take(4)
            val books = orderCalendarStack(
                selectedRecords.map { record ->
                    CalendarCoverItem(
                        title = cleanTitle(record.title),
                        author = record.author,
                        path = record.bookKey,
                        status = record.status,
                        durationMs = record.durationMs,
                        lastSeenAt = record.lastSeenAt
                    )
                },
                s.calendarStackOrder
            )
            val totalMs = totalsByDate[dayKey]?.durationMs ?: dayRecords.sumOf { it.durationMs }
            CalendarDayCell(
                dayStartMs = dayMs,
                dayOfMonth = dc.get(Calendar.DAY_OF_MONTH),
                inMonth = dayMs in monthStart..monthEnd,
                totalMs = totalMs,
                eventCount = if (totalMs > 0L) 1 else 0,
                unmatchedCount = if (totalMs > 0L && books.isEmpty()) 1 else 0,
                books = books
            )
        }
        AutoRefreshLog.i(
            context,
            "WeRead calendar data source=db range=$startDate~$endDate totals=${totals.size} dailyBooks=${records.size} activeDays=${cells.count { it.inMonth && it.totalMs > 0L }} daysWithBooks=${cells.count { it.inMonth && it.books.isNotEmpty() }}"
        )
        return CalendarBuildData(
            monthStartMs = monthStart,
            monthEndMs = monthEnd,
            weekRows = weekRows,
            cells = cells,
            statsRows = totals.size,
            matchedRows = records.size,
            unmatchedRows = cells.count { it.inMonth && it.totalMs > 0L && it.books.isEmpty() },
            footerLabel = "",
            showDurationOnlyLabel = false,
            showFooterLabel = false
        )
    }

    private fun buildLocalCalendarData(context: Context, s: AutoSettings): CalendarBuildData? {
        val baseRange = resolvePeriodRange(s)
        val frame = calendarFrameForSettings(s, baseRange)
        val monthStart = frame.monthStart
        val monthEnd = frame.monthEnd
        val weekRows = frame.weekRows
        val gridStart = frame.gridStart

        val liveData = buildLiveNeoCalendarData(context, s, monthStart, monthEnd, weekRows, gridStart)
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "calendar wallpaper data store disabled, use live Neo data")
            return liveData
        }
        val storedData = buildStoredNeoCalendarData(context, s, monthStart, monthEnd, weekRows, gridStart)
        if (storedData != null) {
            AutoRefreshLog.i(
                context,
                "calendar wallpaper use data store month=${fmt(monthStart)} rows=${storedData.statsRows} daysWithBooks=${storedData.cells.count { it.inMonth && it.books.isNotEmpty() }}"
            )
            return storedData
        }
        AutoRefreshLog.i(context, "calendar wallpaper data store empty, fallback live month=${fmt(monthStart)}")
        return liveData
    }

    private fun calendarFrameForSettings(s: AutoSettings, range: Pair<Long, Long>?): CalendarMonthFrame {
        val safeRange = range ?: resolvePeriodRange(s)
        return calendarMonthFrame(safeRange?.second ?: System.currentTimeMillis())
    }

    private fun calendarMonthFrame(baseMs: Long): CalendarMonthFrame {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = baseMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        cal.add(Calendar.MILLISECOND, -1)
        val monthEnd = cal.timeInMillis

        val gridCal = Calendar.getInstance(TimeZone.getDefault())
        gridCal.timeInMillis = monthStart
        val mondayIndex = (gridCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val daysInMonth = cal.get(Calendar.DAY_OF_MONTH)
        val weekRows = ((mondayIndex + daysInMonth + 6) / 7).coerceIn(5, 6)
        gridCal.add(Calendar.DAY_OF_MONTH, -mondayIndex)
        val gridStart = startOfDayMs(gridCal.timeInMillis)
        return CalendarMonthFrame(monthStart, monthEnd, weekRows, gridStart)
    }

    private fun isSameMonth(aMs: Long, bMs: Long): Boolean {
        val a = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = aMs }
        val b = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = bMs }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
    }

    private fun buildLiveNeoCalendarData(
        context: Context,
        s: AutoSettings,
        monthStart: Long,
        monthEnd: Long,
        weekRows: Int,
        gridStart: Long
    ): CalendarBuildData? {
        val metadata = loadCalendarMetadata(context, s)
        val metadataByPath = metadata.associateBy { it.path }
        val candidates = metadata.filter { it.lastAccessMs > 0L }.ifEmpty { metadata }
        val durationByDayPath = linkedMapOf<Long, LinkedHashMap<String, Long>>()
        val latestEventByDayPath = linkedMapOf<Long, LinkedHashMap<String, Long>>()
        val eventsByDay = linkedMapOf<Long, Int>()
        val unmatchedByDay = linkedMapOf<Long, Int>()
        val minMs = s.minDurationMinutes * 60_000L
        var rows = 0
        var matchedRows = 0
        var unmatchedRows = 0
        var querySucceeded = false

        context.contentResolver.query(
            statsUri,
            arrayOf("path", "eventTime", "durationTime"),
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(monthStart.toString(), monthEnd.toString()),
            null
        )?.use { c ->
            querySucceeded = true
            while (c.moveToNext()) {
                rows += 1
                val rawEvent = readColString(c, "eventTime")?.toLongOrNull() ?: continue
                val eventMs = normalizeEpochMs(rawEvent)
                val durationMs = readColString(c, "durationTime")?.toLongOrNull() ?: 0L
                if (durationMs < minMs) continue
                val day = startOfDayMs(eventMs)
                eventsByDay[day] = (eventsByDay[day] ?: 0) + 1
                val rawPath = readColString(c, "path").orEmpty()
                val matchedPath = when {
                    rawPath.isNotBlank() && metadataByPath.containsKey(rawPath) -> rawPath
                    rawPath.isNotBlank() -> rawPath
                    else -> nearestMetadataPath(eventMs, candidates, monthStart, monthEnd)
                }
                if (matchedPath.isNullOrBlank()) {
                    unmatchedRows += 1
                    unmatchedByDay[day] = (unmatchedByDay[day] ?: 0) + 1
                    continue
                }
                matchedRows += 1
                val dayMap = durationByDayPath.getOrPut(day) { linkedMapOf() }
                dayMap[matchedPath] = (dayMap[matchedPath] ?: 0L) + durationMs
                val latestMap = latestEventByDayPath.getOrPut(day) { linkedMapOf() }
                latestMap[matchedPath] = maxOf(latestMap[matchedPath] ?: 0L, eventMs)
            }
        }

        val cells = (0 until weekRows * 7).map { index ->
            val dayMs = gridStart + index * DAY_MS
            val dc = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = dayMs }
            val inMonth = dayMs in monthStart..monthEnd
            val dayMap = durationByDayPath[dayMs].orEmpty()
            val latestMap = latestEventByDayPath[dayMs].orEmpty()
            val selectedEntries = when (s.calendarStackOrder) {
                "SHORTEST_TOP" -> dayMap.entries.sortedBy { it.value }
                "LATEST_TOP" -> dayMap.entries.sortedByDescending { latestMap[it.key] ?: 0L }
                else -> dayMap.entries.sortedByDescending { it.value }
            }
                .take(4)
            val candidatesForDay = selectedEntries
                .map { (path, ms) ->
                    val meta = metadataByPath[path]
                    CalendarCoverItem(
                        title = meta?.item?.title ?: cleanTitle(File(path).nameWithoutExtension.ifBlank { "未知书名" }),
                        author = meta?.item?.author,
                        path = path,
                        status = meta?.item?.status ?: 1,
                        durationMs = ms,
                        lastSeenAt = latestMap[path] ?: dayMs
                    )
                }
            val books = orderCalendarStack(candidatesForDay, s.calendarStackOrder)
            CalendarDayCell(
                dayStartMs = dayMs,
                dayOfMonth = dc.get(Calendar.DAY_OF_MONTH),
                inMonth = inMonth,
                totalMs = dayMap.values.sum(),
                eventCount = eventsByDay[dayMs] ?: 0,
                unmatchedCount = unmatchedByDay[dayMs] ?: 0,
                books = books
            )
        }
        AutoRefreshLog.i(
            context,
            "calendar wallpaper data month=${fmt(monthStart)} stackOrder=${s.calendarStackOrder} rows=$rows metadata=${metadata.size} matched=$matchedRows unmatched=$unmatchedRows daysWithBooks=${cells.count { it.inMonth && it.books.isNotEmpty() }} querySucceeded=$querySucceeded"
        )
        if (querySucceeded && AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            persistNeoCalendarEstimates(context, cells)
        } else if (querySucceeded) {
            AutoRefreshLog.i(context, "calendar persisted neo estimates skip: data store disabled")
        } else {
            AutoRefreshLog.i(
                context,
                "calendar persisted neo estimates skip: statistics provider returned null range=${fmt(monthStart)}~${fmt(monthEnd)}"
            )
        }
        return CalendarBuildData(monthStart, monthEnd, weekRows, cells, rows, matchedRows, unmatchedRows)
    }

    private fun buildStoredNeoCalendarData(
        context: Context,
        s: AutoSettings,
        monthStart: Long,
        monthEnd: Long,
        weekRows: Int,
        gridStart: Long
    ): CalendarBuildData? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startDate = dateFmt.format(Date(monthStart))
        val endDate = dateFmt.format(Date(monthEnd))
        val records = ReadingDataStore.queryDailyBooks(context, "NEO", startDate, endDate)
            .filter { record ->
                (s.includeUnread || record.status != 0)
            }
        if (records.isEmpty()) return null
        val grouped = records.groupBy { it.date }
        val cells = (0 until weekRows * 7).map { index ->
            val dayMs = gridStart + index * DAY_MS
            val dc = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = dayMs }
            val inMonth = dayMs in monthStart..monthEnd
            val dayKey = dateFmt.format(Date(dayMs))
            val dayRecords = grouped[dayKey].orEmpty()
            val selectedRecords = when (s.calendarStackOrder) {
                "SHORTEST_TOP" -> dayRecords.sortedBy { it.durationMs }
                "LATEST_TOP" -> dayRecords.sortedByDescending { it.lastSeenAt }
                else -> dayRecords.sortedByDescending { it.durationMs }
            }
                .take(4)
            val candidatesForDay = selectedRecords
                .map { record ->
                    CalendarCoverItem(
                        title = cleanTitle(record.title),
                        author = record.author,
                        path = record.bookKey,
                        status = record.status,
                        durationMs = record.durationMs,
                        lastSeenAt = record.lastSeenAt
                    )
                }
            val books = orderCalendarStack(candidatesForDay, s.calendarStackOrder)
            CalendarDayCell(
                dayStartMs = dayMs,
                dayOfMonth = dc.get(Calendar.DAY_OF_MONTH),
                inMonth = inMonth,
                totalMs = dayRecords.sumOf { it.durationMs },
                eventCount = dayRecords.size,
                unmatchedCount = 0,
                books = books
            )
        }
        AutoRefreshLog.i(
            context,
            "calendar wallpaper data source=db month=${fmt(monthStart)} stackOrder=${s.calendarStackOrder} records=${records.size} daysWithBooks=${cells.count { it.inMonth && it.books.isNotEmpty() }}"
        )
        return CalendarBuildData(monthStart, monthEnd, weekRows, cells, records.size, records.size, 0)
    }

    private fun persistNeoCalendarEstimates(context: Context, cells: List<CalendarDayCell>) {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rangeCells = cells.filter { it.inMonth }
        if (rangeCells.isEmpty()) {
            AutoRefreshLog.i(context, "calendar persisted neo estimates skip: empty range cells")
            return
        }
        val records = rangeCells
            .filter { it.books.isNotEmpty() }
            .flatMap { cell ->
                cell.books.map { book ->
                    ReadingDataStore.DailyBookRecord(
                        date = dateFmt.format(Date(cell.dayStartMs)),
                        source = "NEO",
                        bookKey = book.path.ifBlank { book.title },
                        title = book.title,
                        author = book.author,
                        coverCachePath = null,
                        durationMs = book.durationMs,
                        progress = null,
                        status = book.status,
                        confidence = "ESTIMATED",
                        lastSeenAt = book.lastSeenAt
                    )
                }
            }
        val startDate = dateFmt.format(Date(rangeCells.minOf { it.dayStartMs }))
        val endDate = dateFmt.format(Date(rangeCells.maxOf { it.dayStartMs }))
        val written = ReadingDataStore.replaceDailyBooksForRange(
            context = context,
            source = "NEO",
            startDate = startDate,
            endDate = endDate,
            records = records,
            reason = "neo_calendar_estimates"
        )
        AutoRefreshLog.i(
            context,
            "calendar persisted neo estimates range=$startDate~$endDate records=${records.size} written=$written totalDb=${ReadingDataStore.countDailyBooks(context)}"
        )
    }

    private fun orderCalendarStack(
        books: List<CalendarCoverItem>,
        mode: String
    ): List<CalendarCoverItem> {
        // Canvas 后绘制的封面位于最上层，因此目标书必须排在列表末尾。
        return when (mode) {
            "SHORTEST_TOP" -> books.sortedByDescending { it.durationMs }
            "LATEST_TOP" -> books.sortedBy { it.lastSeenAt }
            else -> books.sortedBy { it.durationMs }
        }
    }

    private fun loadCalendarMetadata(context: Context, s: AutoSettings): List<MetadataBook> {
        val out = mutableListOf<MetadataBook>()
        context.contentResolver.query(metadataUri, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = readColString(c, "nativeAbsolutePath").orEmpty()
                if (path.isBlank()) continue
                val status = readColString(c, "readingStatus")?.toIntOrNull() ?: 0
                if (!s.includeUnread && status == 0) continue
                out.add(
                    MetadataBook(
                        path = path,
                        lastAccessMs = normalizeEpochMs(readColString(c, "lastAccess")?.toLongOrNull() ?: 0L),
                        item = BookItem(
                            bookId = null,
                            title = cleanTitle(readColString(c, "title") ?: File(path).nameWithoutExtension.ifBlank { "未知书名" }),
                            author = cleanAuthor(readColString(c, "authors")),
                            progress = readColString(c, "progress"),
                            status = status
                        )
                    )
                )
            }
        }
        return out
    }

    private fun nearestMetadataPath(eventMs: Long, candidates: List<MetadataBook>, monthStart: Long, monthEnd: Long): String? {
        if (candidates.isEmpty()) return null
        val maxDelta = when {
            monthEnd - monthStart <= 8L * DAY_MS -> 3L * DAY_MS
            else -> 10L * DAY_MS
        }
        val best = candidates.minByOrNull { kotlin.math.abs(it.lastAccessMs - eventMs) } ?: return null
        if (best.lastAccessMs <= 0L) return null
        val delta = kotlin.math.abs(best.lastAccessMs - eventMs)
        return if (delta <= maxDelta) best.path else null
    }

    

    private fun canvasSizeText(s: AutoSettings): String {
        val size = resolveWallpaperSize(s)
        return "${size.label} ${size.width}x${size.height}"
    }

    private fun resolveWallpaperSize(s: AutoSettings): WallpaperSize {
        return if (s.booxDevicePreset == BooxDevicePresets.CUSTOM_KEY) {
            WallpaperSize("自定义", s.customWallpaperWidth.coerceIn(300, 4000), s.customWallpaperHeight.coerceIn(300, 4000))
        } else {
            val preset = BooxDevicePresets.byKey(s.booxDevicePreset)
            WallpaperSize(preset.label, preset.widthPx, preset.heightPx)
        }
    }

    private fun buildWeReadStatsForSettings(context: Context, s: AutoSettings): WeReadBuildData? {
        val cacheKey = weReadCacheKey(s)
        weReadDataCache[cacheKey]?.let { cached ->
            if (System.currentTimeMillis() - cached.timestamp < API_CACHE_EXPIRE_MS) {
                AutoRefreshLog.i(context, "WeRead cache hit for $cacheKey")
                return cached.data
            }
        }

        val key = WeReadClient.loadApiKey(context)

        if (s.periodMode == "RECENT") {
            val shelf = WeReadClient.fetchShelfSnapshot(context, key)
            if (!shelf.ok) {
                AutoRefreshLog.i(context, "WeRead recent shelf failed detail=${shelf.detail}")
                return null
            }
            val now = System.currentTimeMillis()
            val books = shelf.books
                .filter { it.readUpdateTimeMs > 0L }
                .sortedByDescending { it.readUpdateTimeMs }
                .map { toWeReadBookItemFromShelf(context, key, it) }
                .filter { matchesReadingFilter(it, s) }
                .take(s.topN)
            val totalMs = books.sumOf { it.durationMs }
            val chart = ChartStats(
                totalMs = totalMs,
                points = longArrayOf(totalMs),
                labels = listOf(weReadPeriodLabel(s.periodMode))
            )
            val note = "按最近阅读时间排序"
            AutoRefreshLog.i(context, "WeRead recent stats books=${books.size} shelfSize=${shelf.books.size} totalMs=${totalMs / 1000L}s")
            val result = WeReadBuildData(now, now, chart, books, weReadPeriodLabel(s.periodMode), note)
            weReadDataCache[cacheKey] = CachedWeReadData(result, System.currentTimeMillis())
            return result
        }

        val range = resolvePeriodRange(s) ?: return null
        val apiMode = when (s.periodMode) {
            "THIS_WEEK" -> "weekly"
            "THIS_MONTH" -> "monthly"
            else -> "monthly"
        }
        val stats = WeReadClient.fetchWallpaperStats(context, key, apiMode, range.first / 1000L)
        if (!stats.ok) {
            AutoRefreshLog.i(context, "WeRead fetch failed period=${s.periodMode} detail=${stats.detail}")
            return null
        }

        val bucketMap = linkedMapOf<Long, Long>()
        stats.buckets.forEach { (bucketSec, seconds) ->
            val bucketStart = startOfDayMs(bucketSec * 1000L)
            if (bucketStart in range.first..range.second && seconds > 0L) {
                bucketMap[bucketStart] = (bucketMap[bucketStart] ?: 0L) + seconds
            }
        }
        val sortedBuckets = bucketMap.toSortedMap()
        val values = sortedBuckets.values.map { it * 1000L }.toLongArray()
        val labels = sortedBuckets.keys.map { SimpleDateFormat("MM-dd", Locale.US).format(Date(it)) }
        val totalMs = values.sum()
        val chart = ChartStats(
            totalMs = totalMs,
            points = if (values.isNotEmpty()) values else longArrayOf(0L),
            labels = if (labels.isNotEmpty()) labels else listOf(weReadPeriodLabel(s.periodMode))
        )
        val books = stats.books
            .filter { it.readSeconds >= s.minDurationMinutes * 60L }
            .sortedByDescending { it.readSeconds }
            .map { toWeReadBookItem(context, key, it) }
            .filter { matchesReadingFilter(it, s) }
            .take(s.topN)
        val note = "时长按日分桶过滤，书单按${weReadPeriodLabel(s.periodMode)}排行"
        AutoRefreshLog.i(context, "WeRead range stats period=${s.periodMode} range=${fmt(range.first)}~${fmt(range.second)} buckets=${sortedBuckets.size} totalSec=${totalMs / 1000L} books=${books.size}")
        val result = WeReadBuildData(range.first, range.second, chart, books, weReadPeriodLabel(s.periodMode), note)
        weReadDataCache[cacheKey] = CachedWeReadData(result, System.currentTimeMillis())
        return result
    }

    private fun buildMixedStatsForSettings(context: Context, s: AutoSettings): WeReadBuildData? {
        val range = resolvePeriodRange(s) ?: return null
        val localEvents = collectDurationEvents(context.contentResolver, range.first, range.second, s.minDurationMinutes)
        val weReadEvents = mutableListOf<Pair<Long, Long>>()
        val weReadBookScores = linkedMapOf<String, Pair<WeReadClient.WallpaperBook, Long>>()
        val monthStarts = monthStartsBetween(range.first, range.second)
        val key = WeReadClient.loadApiKey(context)
        val weReadFailures = mutableListOf<String>()
        monthStarts.forEach { monthStart ->
            val stats = WeReadClient.fetchWallpaperStats(context, key, "monthly", monthStart / 1000L)
            if (!stats.ok) {
                AutoRefreshLog.i(context, "Mixed WeRead fetch failed month=${fmt(monthStart)} detail=${stats.detail}")
                weReadFailures.add("${fmt(monthStart)} ${stats.detail.take(80)}")
                return@forEach
            }
            persistWeReadMonthlyStats(context, monthStart, stats)
            stats.buckets.forEach { (bucketSec, seconds) ->
                val bucketStart = startOfDayMs(bucketSec * 1000L)
                if (bucketStart in range.first..range.second && seconds > 0L) {
                    weReadEvents.add(bucketStart to seconds * 1000L)
                }
            }
            stats.books.filter { it.readSeconds >= s.minDurationMinutes * 60L }.forEach { book ->
                val id = "${book.title.trim()}|${book.author.trim()}"
                val old = weReadBookScores[id]
                val newMs = book.readSeconds * 1000L
                weReadBookScores[id] = if (old == null) {
                    book to newMs
                } else {
                    old.first.copy(readSeconds = old.first.readSeconds + book.readSeconds) to (old.second + newMs)
                }
            }
        }

        val chart = bucketize(localEvents + weReadEvents, range.first, range.second, chooseBucketMode(s, range.first, range.second))
        val localBooks = queryTopBooksByDuration(context, range.first, range.second, s)
            .map { it.first to it.second }
        val weReadBooks = weReadBookScores.values
            .map { (book, scoreMs) ->
                toWeReadBookItem(context, key, book).copy(durationText = formatDuration(scoreMs, s.timeUnit)) to scoreMs
            }
            .filter { matchesReadingFilter(it.first, s) }
        val mergedBooks = mergeScoredBooksWithScores(localBooks + weReadBooks, s.topN, s.timeUnit).map { it.first }
        val note = buildString {
            append("本地+微信，图表按时间相加，书单按阅读时长合并排序")
            if (weReadFailures.isNotEmpty()) {
                append("；微信读书读取失败，已使用本地数据")
                if (weReadEvents.isNotEmpty() || weReadBooks.isNotEmpty()) append("和已读取到的微信数据")
            }
        }
        AutoRefreshLog.i(
            context,
            "Mixed stats range=${fmt(range.first)}~${fmt(range.second)} localEvents=${localEvents.size} weReadEvents=${weReadEvents.size} localBooks=${localBooks.size} weReadBooks=${weReadBooks.size} mergedBooks=${mergedBooks.size} weReadFailures=${weReadFailures.size} totalMs=${chart.totalMs}"
        )
        return WeReadBuildData(range.first, range.second, chart, mergedBooks, "混合", note)
    }

    internal fun persistWeReadMonthlyStats(
        context: Context,
        monthStartMs: Long,
        stats: WeReadClient.WallpaperStatsResult,
        captureSnapshot: Boolean = true
    ) {
        if (!stats.ok) return
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "WeRead data store persist skip disabled")
            return
        }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val monthStart = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = monthStartMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val periodStart = dateFmt.format(Date(monthStart.timeInMillis))
        monthStart.add(Calendar.MONTH, 1)
        monthStart.add(Calendar.DAY_OF_MONTH, -1)
        val periodEnd = dateFmt.format(Date(monthStart.timeInMillis))
        val now = System.currentTimeMillis()

        val totals = stats.buckets.mapNotNull { (bucketSeconds, durationSeconds) ->
            val dayMs = startOfDayMs(bucketSeconds * 1000L)
            val date = dateFmt.format(Date(dayMs))
            if (date !in periodStart..periodEnd || durationSeconds <= 0L) {
                null
            } else {
                ReadingDataStore.DailyTotalRecord(
                    date = date,
                    source = "WEREAD",
                    durationMs = durationSeconds * 1000L,
                    confidence = "EXACT_API",
                    updatedAt = now
                )
            }
        }
        val totalsWritten = ReadingDataStore.replaceDailyTotalsForRange(
            context = context,
            source = "WEREAD",
            startDate = periodStart,
            endDate = periodEnd,
            records = totals,
            reason = "weread_monthly_stats"
        )

        val periodBooks = stats.books.map { book ->
            ReadingDataStore.PeriodBookRecord(
                periodStart = periodStart,
                periodEnd = periodEnd,
                source = "WEREAD",
                bookKey = book.bookId.ifBlank { "${book.title.trim()}|${book.author.trim()}" },
                title = book.title,
                author = book.author,
                coverCachePath = null,
                durationMs = book.readSeconds * 1000L,
                confidence = "MONTHLY_RANKING",
                lastSeenAt = 0L
            )
        }
        val booksWritten = ReadingDataStore.replacePeriodBooks(
            context = context,
            source = "WEREAD",
            periodStart = periodStart,
            periodEnd = periodEnd,
            records = periodBooks,
            reason = "weread_monthly_ranking"
        )
        AutoRefreshLog.i(
            context,
            "WeRead data store persisted period=$periodStart~$periodEnd daily=${totals.size}/$totalsWritten candidates=${periodBooks.size}/$booksWritten totalDaily=${ReadingDataStore.countDailyTotals(context, "WEREAD")} totalCandidates=${ReadingDataStore.countPeriodBooks(context, "WEREAD")}"
        )
        if (captureSnapshot) {
            WeReadReadingSync.captureCurrentMonth(context, monthStartMs, stats)
        }
    }

    private fun queryTopBooksByDuration(
        context: Context,
        start: Long,
        end: Long,
        s: AutoSettings
    ): List<Pair<BookItem, Long>> {
        val resolver = context.contentResolver
        val durationByPath = linkedMapOf<String, Long>()
        val orphanEvents = mutableListOf<Pair<Long, Long>>()
        val minMs = s.minDurationMinutes * 60_000L
        var statsRows = 0
        var statsRowsWithPath = 0
        resolver.query(
            statsUri,
            arrayOf("path", "eventTime", "durationTime"),
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                statsRows += 1
                val path = c.getString(c.getColumnIndexOrThrow("path")).orEmpty()
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: 0L
                val dur = c.getString(c.getColumnIndexOrThrow("durationTime"))?.toLongOrNull() ?: 0L
                if (dur < minMs) continue
                if (path.isBlank()) {
                    if (event > 0L) orphanEvents.add(normalizeEpochMs(event) to dur)
                } else {
                    statsRowsWithPath += 1
                    durationByPath[path] = (durationByPath[path] ?: 0L) + dur
                }
            }
        }

        val metadata = linkedMapOf<String, MetadataBook>()
        resolver.query(
            metadataUri,
            arrayOf("nativeAbsolutePath", "title", "authors", "progress", "readingStatus", "lastAccess", "idString", "digest", "hashTag", "uuid", "guid", "id"),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(c.getColumnIndexOrThrow("nativeAbsolutePath")).orEmpty()
                if (path.isBlank()) continue
                val status = c.getString(c.getColumnIndexOrThrow("readingStatus"))?.toIntOrNull() ?: 0
                if (!s.includeUnread && status == 0) continue
                metadata[path] = MetadataBook(
                    path = path,
                    lastAccessMs = normalizeEpochMs(c.getString(c.getColumnIndexOrThrow("lastAccess"))?.toLongOrNull() ?: 0L),
                    item = BookItem(
                        null,
                        cleanTitle(c.getString(c.getColumnIndexOrThrow("title")) ?: File(path).nameWithoutExtension),
                        cleanAuthor(c.getString(c.getColumnIndexOrThrow("authors"))),
                        c.getString(c.getColumnIndexOrThrow("progress")),
                        status
                    )
                )
            }
        }

        var timeMatched = 0
        var timeUnmatched = 0
        if (orphanEvents.isNotEmpty() && metadata.isNotEmpty()) {
            val candidates = metadata.values
                .filter { it.lastAccessMs > 0L }
                .ifEmpty { metadata.values.toList() }
            val maxDelta = when {
                end - start <= DAY_MS -> 12L * 60L * 60L * 1000L
                end - start <= 8L * DAY_MS -> 3L * DAY_MS
                else -> 10L * DAY_MS
            }
            orphanEvents.forEach { (eventMs, dur) ->
                val best = candidates.minByOrNull {
                    val access = if (it.lastAccessMs > 0L) it.lastAccessMs else start
                    kotlin.math.abs(access - eventMs)
                }
                val bestAccess = best?.lastAccessMs ?: 0L
                val delta = if (best != null && bestAccess > 0L) kotlin.math.abs(bestAccess - eventMs) else Long.MAX_VALUE
                if (best != null && delta <= maxDelta) {
                    durationByPath[best.path] = (durationByPath[best.path] ?: 0L) + dur
                    timeMatched += 1
                } else {
                    timeUnmatched += 1
                }
            }
        }

        AutoRefreshLog.i(context, "Local duration book match rows=$statsRows rowsWithPath=$statsRowsWithPath orphan=${orphanEvents.size} timeMatched=$timeMatched timeUnmatched=$timeUnmatched metadata=${metadata.size} durationBooks=${durationByPath.size}")

        if (durationByPath.isEmpty()) return queryTopBooks(
            resolver,
            start,
            end,
            s.topN,
            s.includeUnread
        ).mapIndexed { idx, item -> item to ((s.topN - idx).coerceAtLeast(1) * 60_000L).toLong() }

        return durationByPath.mapNotNull { (path, ms) ->
            val item = metadata[path]?.item ?: BookItem(null, cleanTitle(File(path).nameWithoutExtension), null, null, 1)
            item.copy(durationText = formatDuration(ms, s.timeUnit)) to ms
        }.sortedByDescending { it.second }
    }

    private fun toWeReadBookItem(context: Context, apiKey: String, book: WeReadClient.WallpaperBook): BookItem {
        val progress = if (book.bookId.isNotBlank()) {
            WeReadClient.fetchBookProgress(context, apiKey, book.bookId)
        } else {
            null
        }
        val progressValue = progress?.progressPercent
        val progressText = progressValue?.let { "${it.coerceIn(0, 100)}%" }
        val status = when {
            progressValue != null && progressValue >= 100 -> 2
            progressValue != null && progressValue > 0 -> 1
            book.readSeconds > 0L -> 1
            else -> 0
        }
        val durationSeconds = book.readSeconds.takeIf { it > 0L } ?: progress?.recordReadingSeconds ?: 0L
        return BookItem(
            bookId = book.bookId,
            title = cleanTitle(book.title),
            author = cleanAuthor(book.author),
            progress = null,
            status = status,
            progressText = progressText,
            durationText = WeReadClient.formatSeconds(durationSeconds),
            durationMs = durationSeconds * 1000L
        )
    }

    private fun toWeReadBookItemFromShelf(context: Context, apiKey: String, book: WeReadClient.ShelfBook): BookItem {
        val progress = if (book.bookKey.isNotBlank()) {
            WeReadClient.fetchBookProgress(context, apiKey, book.bookKey)
        } else {
            null
        }
        val progressValue = progress?.progressPercent
        val progressText = progressValue?.let { "${it.coerceIn(0, 100)}%" }
        val status = when (book.status) {
            2 -> 2
            1 -> 1
            else -> if (progressValue != null && progressValue > 0) 1 else 0
        }
        val durationSeconds = progress?.recordReadingSeconds ?: 0L
        return BookItem(
            bookId = book.bookKey,
            title = cleanTitle(book.title),
            author = cleanAuthor(book.author),
            progress = null,
            status = status,
            progressText = progressText,
            durationText = if (durationSeconds > 0L) WeReadClient.formatSeconds(durationSeconds) else null,
            durationMs = durationSeconds * 1000L
        )
    }

    private fun matchesReadingFilter(book: BookItem, s: AutoSettings): Boolean {
        if (!s.includeUnread && book.status == 0) return false
        if (book.durationMs < s.minDurationMinutes * 60000L) return false
        return true
    }

    private fun mergeScoredBooks(items: List<Pair<BookItem, Long>>, limit: Int, unit: String): List<BookItem> {
        return mergeScoredBooksWithScores(items, limit, unit).map { it.first }
    }

    private fun mergeScoredBooksWithScores(items: List<Pair<BookItem, Long>>, limit: Int, unit: String): List<Pair<BookItem, Long>> {
        val merged = linkedMapOf<String, Pair<BookItem, Long>>()
        items.forEach { (book, score) ->
            val key = "${book.title.trim()}|${book.author.orEmpty().trim()}"
            val old = merged[key]
            merged[key] = if (old == null) {
                book to score
            } else {
                val total = old.second + score
                val progressBase = if (old.first.progressText.isNullOrBlank() && !book.progressText.isNullOrBlank()) book else old.first
                val base = when {
                    progressBase.bookId.isNullOrBlank() && !book.bookId.isNullOrBlank() -> book
                    else -> progressBase
                }
                base.copy(durationText = formatDuration(total, unit)) to total
            }
        }
        return merged.values
            .sortedByDescending { it.second }
            .take(limit)
    }

    private fun weReadPeriodLabel(periodMode: String): String {
        return when (periodMode) {
            "RECENT" -> "最近"
            "THIS_WEEK" -> "本周"
            "THIS_MONTH" -> "本月"
            else -> periodMode
        }
    }

    private fun monthStartsBetween(startMs: Long, endMs: Long): List<Long> {
        val out = mutableListOf<Long>()
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = startMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.timeInMillis <= endMs) {
            out.add(cal.timeInMillis)
            cal.add(Calendar.MONTH, 1)
        }
        return out
    }

    private fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun weReadRange(stats: WeReadClient.WallpaperStatsResult): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val baseMs = stats.baseTimeSeconds * 1000L
        if (stats.mode == "overall") {
            val first = stats.buckets.firstOrNull()?.first?.times(1000L) ?: now
            return first to now
        }
        if (baseMs <= 0L) return now to now
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = baseMs
        val start = cal.timeInMillis
        when (stats.mode) {
            "weekly" -> cal.add(Calendar.DAY_OF_MONTH, 6)
            "monthly" -> {
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            "annually" -> {
                cal.add(Calendar.YEAR, 1)
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            else -> cal.add(Calendar.DAY_OF_MONTH, 0)
        }
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return start to minOf(cal.timeInMillis, now)
    }

    private fun weReadChartStats(stats: WeReadClient.WallpaperStatsResult, mode: String): ChartStats {
        if (stats.buckets.isEmpty()) {
            return ChartStats(stats.totalReadSeconds * 1000L, longArrayOf(stats.totalReadSeconds * 1000L), listOf(WeReadClient.modeLabel(mode)))
        }
        val values = stats.buckets.map { it.second * 1000L }.toLongArray()
        val labels = stats.buckets.map { (seconds, _) ->
            val d = Date(seconds * 1000L)
            when (mode) {
                "annually" -> SimpleDateFormat("MM月", Locale.US).format(d)
                "overall" -> SimpleDateFormat("yyyy", Locale.US).format(d)
                else -> SimpleDateFormat("MM-dd", Locale.US).format(d)
            }
        }
        val total = if (stats.totalReadSeconds > 0L) stats.totalReadSeconds * 1000L else values.sum()
        return ChartStats(total, values, labels)
    }





    private fun readColString(c: android.database.Cursor, name: String): String? {
        val idx = c.getColumnIndex(name)
        if (idx < 0 || c.isNull(idx)) return null
        return runCatching { c.getString(idx) }.getOrNull()
    }

    private fun readBitmapFromColumn(context: Context, c: android.database.Cursor, name: String): Bitmap? {
        val idx = c.getColumnIndex(name)
        if (idx < 0 || c.isNull(idx)) return null
        return when (c.getType(idx)) {
            android.database.Cursor.FIELD_TYPE_BLOB -> {
                val blob = c.getBlob(idx) ?: return null
                BitmapFactory.decodeByteArray(blob, 0, blob.size)
            }
            android.database.Cursor.FIELD_TYPE_STRING -> {
                val v = c.getString(idx) ?: return null
                decodeBitmapByPathOrUri(context, v)
            }
            else -> null
        }
    }

    private fun decodeBitmapByPathOrUri(context: Context, value: String): Bitmap? {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            AutoRefreshLog.i(context, "ignored network url decode request (local-only)")
            return null
        }
        return runCatching {
            when {
                value.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(value))?.use { BitmapFactory.decodeStream(it) }
                value.startsWith("/") -> BitmapFactory.decodeFile(value)
                else -> null
            }
        }.getOrNull()
    }

    private fun drawCalendarWallpaper(context: Context, data: CalendarBuildData, s: AutoSettings, sourceMark: String): Bitmap {
        val size = resolveWallpaperSize(s)
        val w = size.width
        val h = size.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Color.rgb(247, 242, 234)
        val paper = Color.rgb(252, 249, 243)
        val ink = Color.rgb(35, 22, 20)
        val muted = Color.rgb(128, 106, 101)
        val accent = Color.rgb(170, 62, 52)
        canvas.drawColor(bg)

        val titleFace = resolveTypeface(context, s.titleFont, true)
        val bodyFace = resolveTypeface(context, s.bodyFont, false)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.072f).coerceIn(58f, 116f)
            typeface = Typeface.create(titleFace, Typeface.BOLD)
        }
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.024f).coerceIn(22f, 38f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.03f).coerceIn(26f, 48f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = (w * 0.036f).coerceIn(32f, 56f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.017f).coerceIn(16f, 28f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 80, 62, 58)
            strokeWidth = (w * 0.0015f).coerceIn(1.5f, 3f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paper
            style = Paint.Style.FILL
        }
        val gridStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 80, 62, 58)
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.001f).coerceIn(1f, 2f)
        }

        val marginX = w * 0.055f
        val top = h * 0.034f
        val monthText = calendarTitleLabel(data, s)
        canvas.drawText(monthText, marginX, top + title.textSize, title)
        val totalDuration = data.cells.filter { it.inMonth }.sumOf { it.totalMs }
        val uniqueBooks = data.cells
            .filter { it.inMonth }
            .flatMap { it.books }
            .distinctBy { calendarBookIdentity(it.title) }
        val activeDays = data.cells.count { it.inMonth && it.totalMs > 0L }
        val averagePerActiveDay = if (activeDays > 0) totalDuration / activeDays else 0L
        canvas.drawText("总时长 ${compactDuration(totalDuration)}", w - marginX, top + title.textSize * 0.46f, summaryPaint)
        summaryPaint.typeface = Typeface.create(bodyFace, Typeface.NORMAL)
        canvas.drawText("日均 ${compactDuration(averagePerActiveDay)} · 读过${uniqueBooks.size}本", w - marginX, top + title.textSize * 0.82f, summaryPaint)
        canvas.drawText("读完${uniqueBooks.count { it.status == 2 }}本 · ${data.matchedRows}次记录", w - marginX, top + title.textSize * 1.18f, summaryPaint)

        val gridTop = top + title.textSize + h * 0.035f
        val gridLeft = marginX
        val gridRight = w - marginX
        val gridWidth = gridRight - gridLeft
        val weekH = h * 0.058f
        val gridBottom = h - h * 0.055f
        val rowCount = data.weekRows
        val rowH = ((gridBottom - gridTop - weekH) / rowCount.toFloat()).coerceAtLeast(100f)
        val colW = gridWidth / 7f
        val gridRect = RectF(gridLeft, gridTop, gridRight, gridTop + weekH + rowH * rowCount)
        canvas.drawRoundRect(gridRect, w * 0.018f, w * 0.018f, fill)
        canvas.drawRoundRect(gridRect, w * 0.018f, w * 0.018f, gridStroke)

        val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        weekdays.forEachIndexed { i, label ->
            weekPaint.color = if (i == 6) accent else muted
            canvas.drawText(label, gridLeft + colW * (i + 0.5f), gridTop + weekH * 0.63f, weekPaint)
        }
        canvas.drawLine(gridLeft, gridTop + weekH, gridRight, gridTop + weekH, line)

        for (r in 0 until rowCount) {
            val y0 = gridTop + weekH + rowH * r
            if (r > 0) canvas.drawLine(gridLeft, y0, gridRight, y0, line)
            for (col in 0 until 7) {
                val cell = data.cells[r * 7 + col]
                val x0 = gridLeft + colW * col
                if (col > 0) canvas.drawLine(x0, y0, x0, y0 + rowH, gridStroke)
                val alpha = if (cell.inMonth) 255 else 70
                dayPaint.color = if (col == 6) Color.argb(alpha, 200, 56, 48) else Color.argb(alpha, 48, 20, 20)
                canvas.drawText(cell.dayOfMonth.toString(), x0 + colW * 0.08f, y0 + rowH * 0.22f, dayPaint)
                if (
                    data.showDaySourceLabel &&
                    cell.inMonth &&
                    (cell.totalMs > 0L || cell.books.isNotEmpty())
                ) {
                    smallPaint.color = when (cell.sourceKind) {
                        "WEREAD" -> Color.rgb(47, 105, 76)
                        "MIXED" -> accent
                        else -> muted
                    }
                    smallPaint.textAlign = Paint.Align.RIGHT
                    val sourceLabel = when (cell.sourceKind) {
                        "WEREAD" -> "W"
                        "MIXED" -> "M"
                        else -> "N"
                    }
                    canvas.drawText(sourceLabel, x0 + colW * 0.91f, y0 + rowH * 0.18f, smallPaint)
                }

                if (cell.inMonth && cell.books.isNotEmpty()) {
                    if (cell.totalMs > 0L) {
                        drawCalendarOutlineDuration(
                            canvas,
                            compactDuration(cell.totalMs),
                            x0 + colW * 0.92f,
                            y0 + rowH * 0.88f,
                            bodyFace
                        )
                    }
                } else if (cell.inMonth && cell.eventCount > 0) {
                    if (data.showDurationOnlyLabel) {
                        smallPaint.color = muted
                        smallPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText("仅时长", x0 + colW * 0.08f, y0 + rowH * 0.52f, smallPaint)
                    }
                    if (cell.totalMs > 0L) {
                        drawCalendarOutlineDuration(
                            canvas,
                            compactDuration(cell.totalMs),
                            x0 + colW * 0.92f,
                            y0 + rowH * 0.88f,
                            bodyFace
                        )
                    }
                }
            }
        }

        if (data.showFooterLabel) {
            val note = data.footerLabel
            smallPaint.color = muted
            smallPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(note, marginX, h - h * 0.024f, smallPaint)
        }
        return out
    }

    private fun calendarTitleLabel(data: CalendarBuildData, s: AutoSettings): String {
        return SimpleDateFormat("M/yyyy", Locale.US).format(Date(data.monthStartMs))
    }

    private fun drawCalendarOutlineDuration(canvas: Canvas, label: String, right: Float, baseline: Float, bodyFace: Typeface) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            style = Paint.Style.STROKE
            strokeWidth = 5.2f
        }
        val fill = Paint(stroke).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
        canvas.drawText(label, right, baseline, stroke)
        canvas.drawText(label, right, baseline, fill)
    }

    private fun compactDuration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        return if (minutes >= 60L) {
            val h = minutes / 60L
            val m = minutes % 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        } else {
            "${minutes}m"
        }
    }

    private fun readSettings(context: Context): AutoSettings {
        val p = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return AutoSettings(
            includeUnread = p.getBoolean("include_unread", false),
            showProgressStatus = p.getBoolean("show_progress_status", true),
            minDurationMinutes = p.getInt("min_duration_minutes", 5).coerceAtLeast(0),
            topN = p.getInt("top_n", 5).coerceIn(1, 5),
            periodMode = p.getString("period_mode", "THIS_WEEK") ?: "THIS_WEEK",
            weekStart = p.getString("week_start", currentWeekStartYmd()) ?: currentWeekStartYmd(),
            weekEnd = p.getString("week_end", currentWeekEndYmd()) ?: currentWeekEndYmd(),
            sourceMode = p.getString("source_mode", "DURATION") ?: "DURATION",
            wallpaperMode = p.getString("wallpaper_mode", "STATS") ?: "STATS",
            calendarStackOrder = p.getString("calendar_stack_order", "LONGEST_TOP") ?: "LONGEST_TOP",
            progressMode = p.getString("progress_mode", "PAGES") ?: "PAGES",
            timeUnit = "HOUR",
            receiptTitle = p.getString("receipt_title", "Recipe") ?: "Recipe",
            receiptTitleSize = p.getFloat("receipt_title_size", 90f),
            receiptBodySize = p.getFloat("receipt_body_size", 34f),
            weReadNickname = p.getString("weread_nickname", "开卷有益") ?: "开卷有益",
            booxDevicePreset = p.getString("boox_device_preset", BooxDevicePresets.DEFAULT_KEY) ?: BooxDevicePresets.DEFAULT_KEY,
            customWallpaperWidth = p.getInt("custom_wallpaper_width", BooxDevicePresets.byKey(BooxDevicePresets.DEFAULT_KEY).widthPx).coerceIn(300, 4000),
            customWallpaperHeight = p.getInt("custom_wallpaper_height", BooxDevicePresets.byKey(BooxDevicePresets.DEFAULT_KEY).heightPx).coerceIn(300, 4000),
            noteText = p.getString("note_text", "*感谢您的光临  祝您用餐愉快*") ?: "*感谢您的光临  祝您用餐愉快*",
            titleFont = p.getString("title_font", "asset://CevicheOne-Regular.ttf") ?: "asset://CevicheOne-Regular.ttf",
            bodyFont = p.getString("body_font", "asset://迫真打字油印体.ttf") ?: "asset://迫真打字油印体.ttf",
            stickerImagePath = p.getString("sticker_image_path", null)
        )
    }

    private fun draw(
        context: Context,
        rangeStart: Long,
        rangeEnd: Long,
        stats: ChartStats,
        books: List<BookItem>,
        s0: AutoSettings,
        sourceMark: String
    ): Bitmap {
        val wallpaperSize = resolveWallpaperSize(s0)
        val w = wallpaperSize.width
        val h = wallpaperSize.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val bookLines = books.size * (80f + (if (s0.showProgressStatus) 50f else 0f))
        val headerBlock = 110f + 30f + 250f + 48f + 28f
        val hasFooter = s0.noteText.isNotBlank()
        val summaryBlock = 30f + 60f + 50f
        val footerBlock = if (!hasFooter) 0f else 130f
        val requiredH = headerBlock + bookLines + summaryBlock + footerBlock + 120f
        val fitScale = (h.toFloat() - 40f) / requiredH
        val gs = fitScale.coerceIn(0.52f, 1f)
        fun s(v: Float): Float = v * gs

        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val titleFace = resolveTypeface(context, s0.titleFont, true)
        val bodyFace = resolveTypeface(context, s0.bodyFont, false)
        val titlePaint = Paint(black).apply { textSize = s(s0.receiptTitleSize); typeface = titleFace }
        val h1 = Paint(black).apply { textSize = s((s0.receiptBodySize * 1.35f).coerceIn(24f, 90f)); typeface = Typeface.create(bodyFace, Typeface.BOLD) }
        val text = Paint(black).apply { textSize = s(s0.receiptBodySize); typeface = bodyFace }
        val mono = Paint(black).apply { textSize = s((s0.receiptBodySize * 0.88f).coerceIn(16f, 56f)); typeface = bodyFace }
        val line = Paint(black).apply { strokeWidth = s(3f) }
        val dashLine = Paint(black).apply { strokeWidth = s(3f); pathEffect = android.graphics.DashPathEffect(floatArrayOf(s(15f), s(10f)), 0f) }

        val leftMargin = s(60f)
        val rightMargin = s(60f)
        val rightEdge = w - rightMargin
        val summaryWidth = maxOf(s(380f), w * 0.46f)
            .coerceAtMost((w - leftMargin - rightMargin - s(280f)).coerceAtLeast(w * 0.34f))
        val summaryLeft = rightEdge - summaryWidth
        val leftInfoMaxWidth = (summaryLeft - leftMargin - s(24f)).coerceAtLeast(w * 0.35f)
        val noX = leftMargin
        val titleX = leftMargin + s(20f)
        val authorX = w - s(360f)
        val titleColumnMaxWidth = (authorX - titleX - s(28f)).coerceAtLeast(s(160f))

        fun calculateContentHeight(): Float {
            var cy = s(110f)
            cy += s(30f)
            cy += s(30f)
            cy += s(130f)
            cy += s(48f)
            cy += s(28f)
            books.forEachIndexed { _, b ->
                cy += s(80f)
                if (s0.showProgressStatus) {
                    cy += s(50f)
                }
            }
            cy += s(30f)
            cy += s(60f)
            cy += s(50f)
            if (hasFooter) {
                cy += s(16f) + s(58f) + s(20f)
            } else {
                cy += s(20f)
            }
            return cy
        }

        val contentHeight = calculateContentHeight()
        val verticalOffset = (h - contentHeight) / 2f

        c.save()
        c.translate(0f, verticalOffset)

        var y = s(110f)
        y += s(30f)
        drawFittedText(c, s0.receiptTitle, w / 2f, y, titlePaint, summaryWidth, Paint.Align.CENTER, 0.8f)

        y += s(30f)
        
        drawFittedText(c, "学厨: ${s0.weReadNickname}", leftMargin, y + s(60f), text, leftInfoMaxWidth, Paint.Align.LEFT, 0.78f)
        val dateText = if (rangeStart == rangeEnd) "日期: 最近" else "日期: ${formatDateRange(rangeStart, rangeEnd)}"
        drawFittedText(c, dateText, leftMargin, y + s(110f), text, leftInfoMaxWidth, Paint.Align.LEFT, 0.78f)

        y += s(140f)
        c.drawLine(s(40f), y, w - s(40f), y, dashLine)
        y += s(48f)
        drawFittedText(c, "菜品", titleX + titleColumnMaxWidth / 2f, y, h1, titleColumnMaxWidth, Paint.Align.CENTER, 0.8f)
        drawFittedText(c, "主厨", authorX + s(360f) / 2f, y, h1, s(360f), Paint.Align.CENTER, 0.8f)
        y += s(28f)
        c.drawLine(s(40f), y, w - s(40f), y, dashLine)

        books.forEachIndexed { idx, b ->
            y += s(80f)
            drawFittedText(c, "${String.format("%02d.", idx + 1)}${b.title}", titleX, y, h1, titleColumnMaxWidth, Paint.Align.LEFT, 0.68f)
            
            val clippedAuthor = clipTextToWidth(b.author ?: "未知", h1, s(360f))
            c.drawText(clippedAuthor, authorX, y, h1)

            if (s0.showProgressStatus) {
                y += s(50f)
                val value = b.progressText ?: formatProgress(b.progress, s0.progressMode)
                val duration = if (!b.durationText.isNullOrBlank()) "  时长:${b.durationText}" else ""
                drawFittedText(c, "进度:$value$duration", titleX, y, mono, (rightEdge - titleX).coerceAtLeast(s(180f)), Paint.Align.LEFT, 0.78f)
            }
        }

        y += s(30f)
        c.drawLine(s(40f), y, w - s(40f), y, dashLine)
        y += s(60f)
        val avgDiv = stats.points.size.coerceAtLeast(1)
        drawFittedText(c, "经验值: ${formatDuration(stats.totalMs, s0.timeUnit)}", rightEdge, y, h1, (w * 0.54f), Paint.Align.RIGHT, 0.72f)

        y += s(50f)
        if (hasFooter) {
            val baseY = y + s(16f)
            drawFittedText(c, "${s0.noteText}", w / 2f, baseY + s(58f), h1, (rightEdge - leftMargin), Paint.Align.CENTER, 0.78f)
        }

        c.restore()

        s0.stickerImagePath?.let { stickerPath ->
            val stickerSize = s(280f)
            val stickerX = w - rightMargin - stickerSize
            val stickerBottomY = verticalOffset + s(300f)
            val stickerY = stickerBottomY - stickerSize
            drawSticker(c, context, stickerPath, stickerX, stickerY, stickerSize, stickerSize)
        }

        return bmp
    }

    private fun drawFittedText(
        canvas: Canvas,
        raw: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Float,
        align: Paint.Align = Paint.Align.LEFT,
        minScale: Float = 0.72f
    ) {
        if (raw.isBlank() || maxWidth <= 0f) return
        val originalTextSize = paint.textSize
        val originalAlign = paint.textAlign
        val safeMinScale = minScale.coerceIn(0.45f, 1f)
        val minTextSize = originalTextSize * safeMinScale

        var fitted = raw
        if (paint.measureText(fitted) > maxWidth) {
            val ratio = (maxWidth / paint.measureText(fitted)).coerceIn(safeMinScale, 1f)
            paint.textSize = originalTextSize * ratio
            if (paint.textSize < minTextSize) paint.textSize = minTextSize
        }
        fitted = ellipsizeToWidth(fitted, paint, maxWidth)
        paint.textAlign = align
        canvas.drawText(fitted, x, y, paint)
        paint.textSize = originalTextSize
        paint.textAlign = originalAlign
    }

    private fun ellipsizeToWidth(raw: String, paint: Paint, maxWidth: Float): String {
        if (raw.isEmpty() || paint.measureText(raw) <= maxWidth) return raw
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        if (ellipsisWidth >= maxWidth) return ellipsis
        var lo = 0
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val candidate = raw.take(mid) + ellipsis
            if (paint.measureText(candidate) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return raw.take(lo).trimEnd() + ellipsis
    }

    private fun clipTextToWidth(raw: String, paint: Paint, maxWidth: Float): String {
        if (raw.isEmpty() || paint.measureText(raw) <= maxWidth) return raw
        var lo = 0
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(raw.take(mid)) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return raw.take(lo)
    }

    private fun drawSticker(canvas: Canvas, context: Context, stickerPath: String, x: Float, y: Float, maxWidth: Float, maxHeight: Float) {
        val bitmap = try {
            when {
                stickerPath.startsWith("asset://") -> {
                    val assetName = stickerPath.removePrefix("asset://")
                    context.assets.open(assetName).use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
                stickerPath.startsWith("content://") -> {
                    val uri = android.net.Uri.parse(stickerPath)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                }
                else -> {
                    BitmapFactory.decodeFile(stickerPath)
                }
            }
        } catch (e: Exception) {
            null
        } ?: return

        val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        var drawWidth = maxWidth
        var drawHeight = maxHeight

        if (aspectRatio > maxWidth / maxHeight) {
            drawHeight = maxWidth / aspectRatio
        } else {
            drawWidth = maxHeight * aspectRatio
        }

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isFilterBitmap = true
        }
        canvas.drawBitmap(bitmap, null, android.graphics.RectF(x, y, x + drawWidth, y + drawHeight), paint)
        bitmap.recycle()
    }

    private fun queryStatsByMode(resolver: ContentResolver, start: Long, end: Long, s: AutoSettings): ChartStats {
        val events: List<Pair<Long, Long>> = when (s.sourceMode) {
            "PATH_SESSION" -> collectPathEvents(resolver, start, end)
            "METADATA_ACCESS" -> collectMetadataEvents(resolver, start, end)
            else -> collectDurationEvents(resolver, start, end, s.minDurationMinutes)
        }
        return bucketize(events, start, end, chooseBucketMode(s, start, end))
    }

    private fun collectDurationEvents(resolver: ContentResolver, start: Long, end: Long, minDurationMinutes: Int): List<Pair<Long, Long>> {
        val events = mutableListOf<Pair<Long, Long>>()
        val minMs = minDurationMinutes * 60_000L
        resolver.query(
            statsUri,
            arrayOf("eventTime", "durationTime"),
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: continue
                val dur = c.getString(c.getColumnIndexOrThrow("durationTime"))?.toLongOrNull() ?: 0L
                if (dur < minMs) continue
                events.add(event to dur)
            }
        }
        return events
    }

    private fun collectPathEvents(resolver: ContentResolver, start: Long, end: Long): List<Pair<Long, Long>> {
        val events = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            statsUri,
            arrayOf("eventTime"),
            "eventTime >= ? AND eventTime <= ? AND path IS NOT NULL AND path != ''",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: continue
                events.add(event to 60_000L)
            }
        }
        return events
    }

    private fun collectMetadataEvents(resolver: ContentResolver, start: Long, end: Long): List<Pair<Long, Long>> {
        val events = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            metadataUri,
            arrayOf("lastAccess"),
            "lastAccess >= ? AND lastAccess <= ?",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("lastAccess"))?.toLongOrNull() ?: continue
                events.add(event to 60_000L)
            }
        }
        return events
    }

    private fun chooseBucketMode(s: AutoSettings, start: Long, end: Long): BucketMode {
        val days = (((end - start) / DAY_MS) + 1L).toInt().coerceAtLeast(1)
        return when (s.periodMode) {
            "RECENT" -> BucketMode.DAY
            "THIS_WEEK", "THIS_MONTH" -> BucketMode.DAY
            else -> if (days <= 14) BucketMode.DAY else if (days <= 90) BucketMode.WEEK else BucketMode.MONTH
        }
    }

    private fun bucketize(events: List<Pair<Long, Long>>, start: Long, end: Long, mode: BucketMode): ChartStats {
        val values: LongArray
        val labels: List<String>

        when (mode) {
            BucketMode.HOUR -> {
                values = LongArray(24)
                labels = (0..23).map { "${it}时" }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val c = Calendar.getInstance(TimeZone.getDefault())
                        c.timeInMillis = ts
                        values[c.get(Calendar.HOUR_OF_DAY)] += v
                    }
                }
            }
            BucketMode.DAY -> {
                val days = (((end - start) / DAY_MS) + 1L).toInt().coerceAtLeast(1)
                values = LongArray(days)
                labels = (0 until days).map {
                    SimpleDateFormat("MM-dd", Locale.US).format(Date(start + it * DAY_MS))
                }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val idx = ((ts - start) / DAY_MS).toInt().coerceIn(0, days - 1)
                        values[idx] += v
                    }
                }
            }
            BucketMode.WEEK -> {
                val days = (((end - start) / DAY_MS) + 1L).toInt().coerceAtLeast(1)
                val n = ((days + 6) / 7).coerceAtLeast(1)
                values = LongArray(n)
                labels = (0 until n).map { i ->
                    val d = Date(start + i * 7L * DAY_MS)
                    "W${i + 1} ${SimpleDateFormat("MM-dd", Locale.US).format(d)}"
                }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val idx = (((ts - start) / DAY_MS) / 7L).toInt().coerceIn(0, n - 1)
                        values[idx] += v
                    }
                }
            }
            BucketMode.MONTH -> {
                val sc = Calendar.getInstance(TimeZone.getDefault()); sc.timeInMillis = start
                val ec = Calendar.getInstance(TimeZone.getDefault()); ec.timeInMillis = end
                val sm = sc.get(Calendar.YEAR) * 12 + sc.get(Calendar.MONTH)
                val em = ec.get(Calendar.YEAR) * 12 + ec.get(Calendar.MONTH)
                val n = (em - sm + 1).coerceAtLeast(1)
                values = LongArray(n)
                labels = (0 until n).map { i ->
                    val c = Calendar.getInstance(TimeZone.getDefault())
                    c.timeInMillis = start
                    c.set(Calendar.DAY_OF_MONTH, 1)
                    c.add(Calendar.MONTH, i)
                    SimpleDateFormat("yyyy-MM", Locale.US).format(Date(c.timeInMillis))
                }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val c = Calendar.getInstance(TimeZone.getDefault()); c.timeInMillis = ts
                        val cm = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
                        val idx = (cm - sm).coerceIn(0, n - 1)
                        values[idx] += v
                    }
                }
            }
        }

        return ChartStats(values.sum(), values, labels)
    }

    private fun shouldShowLabel(i: Int, n: Int): Boolean {
        val step = when {
            n <= 8 -> 1
            n <= 16 -> 2
            n <= 24 -> 3
            n <= 40 -> 5
            else -> 8
        }
        return i % step == 0 || i == n - 1
    }

    private fun queryTopBooks(
        resolver: ContentResolver,
        start: Long,
        end: Long,
        limit: Int,
        includeUnread: Boolean
    ): List<BookItem> {
        val list = mutableListOf<BookItem>()
        val selection = buildString {
            append("lastAccess >= ? AND lastAccess <= ?")
            if (!includeUnread) append(" AND (readingStatus = 1 OR readingStatus = 2)")
        }
        resolver.query(
            metadataUri,
            arrayOf("title", "authors", "progress", "readingStatus", "idString", "digest", "hashTag", "uuid", "guid", "id", "nativeAbsolutePath"),
            selection,
            arrayOf(start.toString(), end.toString()),
            "readingStatus DESC, lastAccess DESC"
        )?.use { c ->
            while (c.moveToNext() && list.size < limit) {
                list.add(
                    BookItem(
                        null,
                        cleanTitle(c.getString(c.getColumnIndexOrThrow("title")) ?: "未知书名"),
                        cleanAuthor(c.getString(c.getColumnIndexOrThrow("authors"))),
                        c.getString(c.getColumnIndexOrThrow("progress")),
                        c.getString(c.getColumnIndexOrThrow("readingStatus"))?.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return list
    }

    private fun resolveTypeface(context: Context, spec: String, boldDefault: Boolean): Typeface {
        val cacheKey = typefaceCacheKey(spec, boldDefault)
        typefaceCache[cacheKey]?.let { return it }

        val result = when (spec) {
            "SERIF_BOLD" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            "SANS" -> Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            "MONO" -> Typeface.create(Typeface.MONOSPACE, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            else -> {
                try {
                    when {
                        spec.startsWith("asset://") -> {
                            val assetName = spec.removePrefix("asset://")
                            Typeface.createFromAsset(context.assets, assetName)
                        }
                        spec.startsWith("content://") -> {
                            context.contentResolver.openFileDescriptor(Uri.parse(spec), "r")?.use { pfd ->
                                Typeface.Builder(pfd.fileDescriptor).build()
                            } ?: Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
                        }
                        else -> {
                            Typeface.createFromFile(spec)
                        }
                    }
                } catch (_: Exception) {
                    Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
                }
            }
        }
        typefaceCache[cacheKey] = result
        return result
    }

    private fun resolvePeriodRange(settings: AutoSettings): Pair<Long, Long>? {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        return when (settings.periodMode) {
            "RECENT" -> null
            "THIS_WEEK" -> parseWeek(currentWeekStartYmd())
            "THIS_MONTH" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            else -> parseWeek(currentWeekStartYmd())
        }
    }

    private fun parseWeek(startYmd: String): Pair<Long, Long>? {
        val s = parseYmd(startYmd) ?: return null
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.timeInMillis = s
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 6)
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
        return start to c.timeInMillis
    }

    private fun parseYmd(ymd: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.parse(ymd)?.time
        }.getOrNull()
    }

    private fun currentWeekStartYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun currentWeekEndYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun fmt(ts: Long): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(ts))
    private fun fmtDate(ts: Long): String = SimpleDateFormat("yyyy-M-d", Locale.US).format(Date(ts))
    
    private fun formatDateRange(start: Long, end: Long): String {
        val calStart = Calendar.getInstance().apply { time = Date(start) }
        val calEnd = Calendar.getInstance().apply { time = Date(end) }
        val yearStart = calStart.get(Calendar.YEAR)
        val monthStart = calStart.get(Calendar.MONTH) + 1
        val dayStart = calStart.get(Calendar.DAY_OF_MONTH)
        val yearEnd = calEnd.get(Calendar.YEAR)
        val monthEnd = calEnd.get(Calendar.MONTH) + 1
        val dayEnd = calEnd.get(Calendar.DAY_OF_MONTH)
        return when {
            yearStart == yearEnd && monthStart == monthEnd && dayStart == dayEnd -> "$yearStart.$monthStart.$dayStart"
            yearStart == yearEnd && monthStart == monthEnd -> "$yearStart.$monthStart.$dayStart-$dayEnd"
            yearStart == yearEnd -> "$yearStart.$monthStart.$dayStart-$monthEnd.$dayEnd"
            else -> "$yearStart.$monthStart.$dayStart-$yearEnd.$monthEnd.$dayEnd"
        }
    }

    private fun shortTitle(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

    private fun formatDuration(ms: Long, unit: String): String {
        if (unit == "MINUTE") return String.format(Locale.US, "%dmins", ms / 60000L)
        val totalMinutes = (ms / 60000L).coerceAtLeast(0L)
        val hours = totalMinutes / 60L
        val minutes = totalMinutes % 60L
        return when {
            hours > 0L && minutes > 0L -> "${hours}h ${minutes}mins"
            hours > 0L -> "${hours}h"
            else -> "${minutes}mins"
        }
    }

    private fun formatProgress(raw: String?, mode: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return "-"
        if (mode != "PERCENT") return value
        val parts = value.split("/")
        if (parts.size != 2) return value
        val cur = parts[0].trim().toDoubleOrNull() ?: return value
        val total = parts[1].trim().toDoubleOrNull() ?: return value
        if (total <= 0.0) return value
        return String.format(Locale.US, "%.1f%%", (cur / total) * 100.0)
    }

    private fun saveBitmap(context: android.content.Context, bitmap: Bitmap): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NeoReader")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "neoreader_wallpaper.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/png")
            ) { path, uri ->
                AutoRefreshLog.i(context, "MediaScanner scanned updated image: uri=$uri")
            }
        }
        return file.absolutePath
    }
}
