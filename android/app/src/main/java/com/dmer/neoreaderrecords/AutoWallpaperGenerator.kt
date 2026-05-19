package com.dmer.neoreaderrecords

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object AutoWallpaperGenerator {
    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")
    private const val DAY_MS = 86_400_000L

    private data class BookItem(val title: String, val author: String?, val progress: String?, val status: Int)
    private data class ChartStats(val totalMs: Long, val points: LongArray, val labels: List<String>)
    private enum class BucketMode { HOUR, DAY, WEEK, MONTH }

    private data class AutoSettings(
        val includeUnread: Boolean,
        val showChart: Boolean,
        val showProgressStatus: Boolean,
        val showAuthor: Boolean,
        val minDurationMinutes: Int,
        val topN: Int,
        val periodMode: String,
        val weekStart: String,
        val weekEnd: String,
        val readingFilterMode: String,
        val sourceMode: String,
        val progressMode: String,
        val timeUnit: String,
        val receiptTitle: String,
        val receiptTitleSize: Float,
        val receiptBodySize: Float,
        val footerMode: String,
        val noteText: String,
        val chartStyleMode: String,
        val showPeakLabel: Boolean,
        val yAxisMode: String,
        val yAxisFixedMaxMinutes: Int,
        val titleFont: String,
        val bodyFont: String
    )

    data class PreviewResult(val bitmap: Bitmap, val summary: String)

    fun generateAndSave(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "Generator start reason=$reason")
        return runCatching {
            val built = buildPreviewInternal(context, "A") ?: return false
            val path = saveBitmap(built.bitmap)
            AutoRefreshLog.i(context, "Generator saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "Generator exception", it)
            false
        }
    }

    fun buildPreviewFromPrefs(context: Context, sourceMark: String = "M"): PreviewResult? {
        return runCatching { buildPreviewInternal(context, sourceMark) }.getOrNull()
    }

    private fun buildPreviewInternal(context: Context, sourceMark: String): PreviewResult? {
        val s = readSettings(context)
        val range = resolvePeriodRange(s) ?: return null
        val books = queryTopBooks(context.contentResolver, range.first, range.second, s.topN, s.includeUnread, s.readingFilterMode)
        val stats = queryStatsByMode(context.contentResolver, range.first, range.second, s)
        val bmp = draw(context, range.first, range.second, stats, books, s, sourceMark)
        val summary = buildString {
            append("范围=").append(fmt(range.first)).append("~").append(fmt(range.second))
            append(", 周期=").append(s.periodMode)
            append(", 口径=").append(s.sourceMode)
            append(", TopN=").append(s.topN)
            append(", 书籍=").append(books.size)
            append(", 时长=").append(formatDuration(stats.totalMs, s.timeUnit))
        }
        return PreviewResult(bmp, summary)
    }

    private fun readSettings(context: Context): AutoSettings {
        val p = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return AutoSettings(
            includeUnread = p.getBoolean("include_unread", false),
            showChart = p.getBoolean("show_chart", true),
            showProgressStatus = p.getBoolean("show_progress_status", true),
            showAuthor = p.getBoolean("show_author", true),
            minDurationMinutes = p.getInt("min_duration_minutes", 1).coerceAtLeast(0),
            topN = p.getInt("top_n", 5).coerceIn(1, 5),
            periodMode = p.getString("period_mode", "THIS_WEEK") ?: "THIS_WEEK",
            weekStart = p.getString("week_start", currentWeekStartYmd()) ?: currentWeekStartYmd(),
            weekEnd = p.getString("week_end", currentWeekEndYmd()) ?: currentWeekEndYmd(),
            readingFilterMode = p.getString("reading_filter_mode", "ALL") ?: "ALL",
            sourceMode = p.getString("source_mode", "DURATION") ?: "DURATION",
            progressMode = p.getString("progress_mode", "PAGES") ?: "PAGES",
            timeUnit = p.getString("time_unit", "HOUR") ?: "HOUR",
            receiptTitle = p.getString("receipt_title", "阅读账单") ?: "阅读账单",
            receiptTitleSize = p.getFloat("receipt_title_size", 74f).coerceIn(24f, 120f),
            receiptBodySize = p.getFloat("receipt_body_size", 34f).coerceIn(18f, 60f),
            footerMode = p.getString("footer_mode", "NONE") ?: "NONE",
            noteText = p.getString("note_text", "") ?: "",
            chartStyleMode = p.getString("chart_style_mode", "LINE") ?: "LINE",
            showPeakLabel = p.getBoolean("show_peak_label", true),
            yAxisMode = p.getString("y_axis_mode", "AUTO") ?: "AUTO",
            yAxisFixedMaxMinutes = p.getInt("y_axis_fixed_max_minutes", 300).coerceIn(1, 2000),
            titleFont = p.getString("title_font", "SERIF_BOLD") ?: "SERIF_BOLD",
            bodyFont = p.getString("body_font", "MONO") ?: "MONO"
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
        val w = context.resources.displayMetrics.widthPixels.coerceAtLeast(1200)
        val h = context.resources.displayMetrics.heightPixels.coerceAtLeast(1600)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val bookLines = books.size * (80f + (if (s0.showAuthor) 42f else 0f) + (if (s0.showProgressStatus) 50f else 0f))
        val headerBlock = 110f + 30f + 250f + 48f + 28f
        val summaryBlock = 30f + 60f + 50f
        val chartBlock = if (s0.showChart) 260f else 0f
        val hasFooter = s0.footerMode != "NONE" && s0.noteText.isNotBlank()
        val footerBlock = if (!hasFooter) 0f else if (s0.footerMode == "BARCODE") 280f else 130f
        val requiredH = headerBlock + bookLines + summaryBlock + chartBlock + footerBlock + 120f
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

        var y = s(110f)
        c.drawText(shortTitle(s0.receiptTitle, 12), w - s(360f), y, titlePaint)
        y += s(30f)
        c.drawText("单号: ${SimpleDateFormat("MMdd", Locale.US).format(Date())}", s(60f), y + s(40f), h1)
        c.drawText("操作编号: ${System.currentTimeMillis().toString().takeLast(6)}", s(60f), y + s(95f), text)
        c.drawText("时间: ${fmt(rangeStart)} - ${fmt(rangeEnd)}", s(60f), y + s(145f), text)
        c.drawText("设备: Onyx Leaf5", s(60f), y + s(195f), text)
        c.drawText("时长: ${formatDuration(stats.totalMs, s0.timeUnit)}", w - s(520f), y + s(145f), h1)
        c.drawText("书籍: ${books.size}", w - s(520f), y + s(195f), text)

        y += s(250f)
        c.drawLine(s(40f), y, w - s(40f), y, line)
        y += s(48f)
        c.drawText("品类", s(60f), y, text)
        c.drawText("数量", w - s(260f), y, text)
        c.drawText("单位", w - s(140f), y, text)
        y += s(28f)
        c.drawLine(s(40f), y, w - s(40f), y, line)

        books.forEachIndexed { idx, b ->
            y += s(80f)
            c.drawText("NO.${(idx + 1).toString().padStart(2, '0')}", s(60f), y, h1)
            c.drawText(shortTitle(b.title, 16), s(260f), y, h1)
            c.drawText("1", w - s(260f), y, h1)
            c.drawText("本", w - s(140f), y, h1)
            if (s0.showAuthor) {
                y += s(42f)
                c.drawText("作者:${shortTitle(b.author ?: "未知", 20)}", s(260f), y, mono)
            }
            if (s0.showProgressStatus) {
                y += s(50f)
                val st = when (b.status) { 2 -> "已读完"; 1 -> "阅读中"; else -> "未读" }
                c.drawText("进度:${formatProgress(b.progress, s0.progressMode)}  状态:$st", s(260f), y, mono)
            }
        }

        y += s(30f)
        c.drawLine(s(40f), y, w - s(40f), y, line)
        y += s(60f)
        val avgDiv = stats.points.size.coerceAtLeast(1)
        c.drawText("日均: ${String.format(Locale.US, "%.0f分钟", stats.totalMs / avgDiv.toDouble() / 60000.0)}", s(60f), y, h1)
        c.drawText("本期合计: ${formatDuration(stats.totalMs, s0.timeUnit)}", w - s(560f), y, h1)

        y += s(50f)
        val footerReserved = if (!hasFooter) 0f else if (s0.footerMode == "BARCODE") s(260f) else s(120f)
        val bottomSafe = (h - s(56f)).toFloat()
        val availableChartH = ((bottomSafe - footerReserved - s(24f)) - y).coerceAtLeast(s(70f))
        val maxChartBottom = y + availableChartH
        var chartBottomUsed = y

        if (s0.showChart) {
            val chartLeft = s(80f)
            val chartRight = (w - s(80f)).toFloat()
            val chartTop = y
            val desiredChartH = s(220f)
            val chartBottom = (chartTop + desiredChartH).coerceAtMost(maxChartBottom)
            chartBottomUsed = chartBottom

            val autoMax = (stats.points.maxOrNull() ?: 1L).toFloat().coerceAtLeast(1f)
            val max = if (s0.yAxisMode == "FIXED") (s0.yAxisFixedMaxMinutes * 60000f).coerceAtLeast(1f) else autoMax
            val peakIdx = stats.points.indices.maxByOrNull { stats.points[it] } ?: 0

            c.drawLine(chartLeft, chartBottom, chartRight, chartBottom, line)
            c.drawLine(chartLeft, chartTop, chartLeft, chartBottom, line)
            var prevX = 0f
            var prevY = 0f
            val n = stats.points.size.coerceAtLeast(1)

            for (i in 0 until n) {
                val x = if (n == 1) (chartLeft + chartRight) / 2f else chartLeft + i * (chartRight - chartLeft) / (n - 1).toFloat()
                val yv = chartBottom - ((stats.points[i] / max) * (chartBottom - chartTop))
                if (s0.chartStyleMode == "BAR") {
                    val bw = (s(24f)).coerceAtMost((chartRight - chartLeft) / n * 0.7f)
                    c.drawRect(x - bw / 2f, yv, x + bw / 2f, chartBottom, black)
                } else {
                    c.drawCircle(x, yv, s(5f), black)
                    if (i > 0) c.drawLine(prevX, prevY, x, yv, line)
                }
                val label = stats.labels.getOrNull(i) ?: ""
                if (shouldShowLabel(i, n)) {
                    c.drawText(label, x - s((label.length * 8).toFloat()), chartBottom + s(42f), mono)
                }
                if (s0.showPeakLabel && i == peakIdx) {
                    c.drawText(String.format(Locale.US, "%.0f分", stats.points[i] / 60000.0), x - s(28f), yv - s(14f), mono)
                }
                prevX = x
                prevY = yv
            }
            y = chartBottom + s(56f)
        }

        if (hasFooter) {
            val baseY = if (s0.showChart) (chartBottomUsed + s(64f)) else (y + s(16f))
            c.drawLine(s(40f), baseY, w - s(40f), baseY, line)
            if (s0.footerMode == "NOTE") {
                c.drawText("备注: ${shortTitle(s0.noteText, 40)}", s(60f), baseY + s(58f), text)
            } else if (s0.footerMode == "BARCODE") {
                val qr = buildQrBitmap(s0.noteText, s(168f).toInt().coerceAtLeast(120))
                if (qr != null) {
                    val qrX = s(60f)
                    val qrY = baseY + s(18f)
                    c.drawBitmap(qr, qrX, qrY, null)
                    val decorX = qrX + qr.width + s(24f)
                    val decorY = qrY + s(10f)
                    val decorW = (w - decorX - s(60f)).coerceAtLeast(s(220f))
                    val decorH = (qr.height - s(20f)).toFloat().coerceAtLeast(s(60f))
                    drawBarcodeDecor(c, decorX, decorY, decorW, decorH, s0.noteText, black)
                    val textY = qrY + qr.height + s(34f)
                    c.drawText(shortTitle(s0.noteText, 36), qrX, textY, mono)
                } else {
                    c.drawText("二维码生成失败，备注: ${shortTitle(s0.noteText, 36)}", s(60f), baseY + s(58f), text)
                }
            }
        }

        drawSourceCornerMark(c, w, h, sourceMark, gs)
        return bmp
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
            "TODAY", "YESTERDAY" -> BucketMode.HOUR
            "THIS_WEEK", "LAST_WEEK", "LAST_7_DAYS" -> BucketMode.DAY
            "LAST_30_DAYS" -> BucketMode.DAY
            "CUSTOM" -> when {
                days <= 14 -> BucketMode.DAY
                days <= 90 -> BucketMode.WEEK
                else -> BucketMode.MONTH
            }
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
        includeUnread: Boolean,
        filter: String
    ): List<BookItem> {
        val list = mutableListOf<BookItem>()
        val selection = buildString {
            append("lastAccess >= ? AND lastAccess <= ?")
            if (!includeUnread) append(" AND (readingStatus = 1 OR readingStatus = 2)")
            when (filter) {
                "READING_ONLY" -> append(" AND readingStatus = 1")
                "FINISHED_ONLY" -> append(" AND readingStatus = 2")
            }
        }
        resolver.query(
            metadataUri,
            arrayOf("title", "authors", "progress", "readingStatus"),
            selection,
            arrayOf(start.toString(), end.toString()),
            "readingStatus DESC, lastAccess DESC"
        )?.use { c ->
            while (c.moveToNext() && list.size < limit) {
                list.add(
                    BookItem(
                        c.getString(c.getColumnIndexOrThrow("title")) ?: "未知书名",
                        c.getString(c.getColumnIndexOrThrow("authors")),
                        c.getString(c.getColumnIndexOrThrow("progress")),
                        c.getString(c.getColumnIndexOrThrow("readingStatus"))?.toIntOrNull() ?: 0
                    )
                )
            }
        }
        return list
    }

    private fun drawSourceCornerMark(canvas: Canvas, w: Int, h: Int, sourceMark: String, gs: Float) {
        val label = if (sourceMark.uppercase(Locale.US).startsWith("A")) "A" else "M"
        val radius = (11f * gs).coerceAtLeast(9f)
        val cx = w - (26f * gs)
        val cy = h - (24f * gs)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = (1.4f * gs).coerceAtLeast(1f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(12, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
            textSize = (12f * gs).coerceAtLeast(9f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, ring)
        val baseline = cy - ((t.descent() + t.ascent()) / 2f)
        canvas.drawText(label, cx, baseline, t)
    }

    private fun buildQrBitmap(content: String, size: Int): Bitmap? {
        return runCatching {
            val hints = hashMapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to "UTF-8")
            val compact = if (content.length > 120) content.take(120) else content
            val matrix: BitMatrix = MultiFormatWriter().encode(compact, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }

    private fun drawBarcodeDecor(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, seedText: String, paint: Paint) {
        val seed = seedText.hashCode().toLong()
        var state = if (seed == 0L) 1L else kotlin.math.abs(seed)
        var cursor = x
        val end = x + width
        while (cursor < end) {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            val barW = (1 + (state % 5)).toFloat()
            state = (state * 1103515245 + 12345) and 0x7fffffff
            val gapW = (1 + (state % 4)).toFloat()
            canvas.drawRect(cursor, y, (cursor + barW).coerceAtMost(end), y + height, paint)
            cursor += barW + gapW
        }
    }

    private fun resolveTypeface(context: Context, spec: String, boldDefault: Boolean): Typeface {
        return when (spec) {
            "SERIF_BOLD" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            "SANS" -> Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            "MONO" -> Typeface.create(Typeface.MONOSPACE, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            else -> {
                try {
                    if (spec.startsWith("content://")) {
                        context.contentResolver.openFileDescriptor(Uri.parse(spec), "r")?.use { pfd ->
                            Typeface.Builder(pfd.fileDescriptor).build()
                        } ?: Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
                    } else {
                        Typeface.createFromFile(spec)
                    }
                } catch (_: Exception) {
                    Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
                }
            }
        }
    }

    private fun resolvePeriodRange(settings: AutoSettings): Pair<Long, Long>? {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        return when (settings.periodMode) {
            "TODAY" -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val start = c.timeInMillis
                c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
                start to c.timeInMillis
            }
            "YESTERDAY" -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                c.add(Calendar.DAY_OF_MONTH, -1)
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val start = c.timeInMillis
                c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
                start to c.timeInMillis
            }
            "THIS_WEEK" -> parseWeek(currentWeekStartYmd())
            "LAST_WEEK" -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                parseYmd(currentWeekStartYmd())?.let { c.timeInMillis = it } ?: return null
                c.add(Calendar.DAY_OF_MONTH, -7)
                parseWeek(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis)))
            }
            "LAST_7_DAYS" -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, -6)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to end
            }
            "LAST_30_DAYS" -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, -29)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to end
            }
            "CUSTOM" -> {
                val s = parseYmd(settings.weekStart) ?: return null
                val e = parseYmd(settings.weekEnd) ?: return null
                val sc = Calendar.getInstance(TimeZone.getDefault()); sc.timeInMillis = s
                sc.set(Calendar.HOUR_OF_DAY, 0); sc.set(Calendar.MINUTE, 0); sc.set(Calendar.SECOND, 0); sc.set(Calendar.MILLISECOND, 0)
                val ec = Calendar.getInstance(TimeZone.getDefault()); ec.timeInMillis = e
                ec.set(Calendar.HOUR_OF_DAY, 23); ec.set(Calendar.MINUTE, 59); ec.set(Calendar.SECOND, 59); ec.set(Calendar.MILLISECOND, 999)
                if (sc.timeInMillis > ec.timeInMillis) null else (sc.timeInMillis to ec.timeInMillis)
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
        cal.firstDayOfWeek = Calendar.SUNDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun currentWeekEndYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.SUNDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun fmt(ts: Long): String = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(ts))

    private fun shortTitle(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

    private fun formatDuration(ms: Long, unit: String): String {
        if (unit == "MINUTE") return String.format(Locale.US, "%.0f分钟", ms / 60000.0)
        val totalMinutes = (ms / 60000L).coerceAtLeast(0L)
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L
        return when {
            days > 0L -> "${days}天${hours}小时${minutes}分钟"
            hours > 0L -> "${hours}小时${minutes}分钟"
            else -> "${minutes}分钟"
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

    private fun saveBitmap(bitmap: Bitmap): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NeoReader")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "neoreader_wallpaper.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }
}
