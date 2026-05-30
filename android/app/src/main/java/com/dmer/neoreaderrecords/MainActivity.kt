package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class MainActivity : AppCompatActivity() {
    companion object {
        private const val FONT_ENTRY_SEP = "@@"
    }

    private class SimpleItemSelectedListener(val onChange: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange()
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")

    private lateinit var includeUnreadCheck: CheckBox
    private lateinit var showChartCheck: CheckBox
    private lateinit var showProgressStatusCheck: CheckBox
    private lateinit var showAuthorCheck: CheckBox
    private lateinit var minDurationInput: EditText
    private lateinit var topNInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var titleSizeInput: EditText
    private lateinit var bodySizeInput: EditText
    private lateinit var serialNumberSizeInput: EditText
    private lateinit var serialCustomInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var weekStartText: TextView
    private lateinit var weekEndText: TextView
    private lateinit var sourceGroup: RadioGroup
    private lateinit var periodGroup: RadioGroup
    private lateinit var progressModeGroup: RadioGroup
    private lateinit var readingFilterGroup: RadioGroup
    private lateinit var timeUnitGroup: RadioGroup
    private lateinit var wallpaperModeGroup: RadioGroup
    private lateinit var coverFitModeGroup: RadioGroup
    private lateinit var serialModeGroup: RadioGroup
    private lateinit var footerModeGroup: RadioGroup
    private lateinit var barcodeWidthGroup: RadioGroup
    private lateinit var barcodeGapGroup: RadioGroup
    private lateinit var chartStyleGroup: RadioGroup
    private lateinit var yAxisModeGroup: RadioGroup
    private lateinit var showPeakLabelCheck: CheckBox
    private lateinit var yAxisMaxInput: EditText
    private lateinit var autoRefreshCheck: CheckBox
    private lateinit var autoModeGroup: RadioGroup
    private lateinit var autoDailyTimeInput: EditText
    private lateinit var autoMinIntervalInput: EditText
    private lateinit var autoModeHintText: TextView
    private lateinit var autoStateText: TextView
    private lateinit var pickFontDirBtn: Button
    private lateinit var titleFontSpinner: Spinner
    private lateinit var bodyFontSpinner: Spinner
    private lateinit var fontScanText: TextView
    private lateinit var statusText: TextView
    private lateinit var changeStateText: TextView

    private lateinit var settingsPage: View
    private lateinit var previewPage: View
    private lateinit var previewImage: ImageView
    private lateinit var previewText: TextView

    private var lastSavedPath: String? = null
    private var previewBitmap: Bitmap? = null
    private var isInitializingUi: Boolean = false
    private var selectedWeekStartYmd: String = ""
    private var selectedWeekEndYmd: String = ""
    private val systemFonts: MutableList<String> = mutableListOf()
    private var fontScanReport: String = ""
    private var barcodeDebugReport: String = ""
    private var fontPermissionDebug: String = ""
    private var metadataDebugReport: String = ""
    private var metadataRowsDebugReport: String = ""
    private val debugLogName = "neoreader_debug_log.txt"
    private var selectedFontDirUri: String? = null

    private val pickFontTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                // OpenDocumentTree already grants a temporary read permission. Persist it explicitly.
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                fontPermissionDebug = "takePersistableUriPermission=ok uri=$uri"
            } catch (e: Exception) {
                fontPermissionDebug = "takePersistableUriPermission=fail ${e.javaClass.simpleName}:${e.message}"
            }
            selectedFontDirUri = uri.toString()
            getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE).edit().putString("font_tree_uri", selectedFontDirUri).apply()
            reloadFontsFromSources()
            writeDebugLog("font_tree_selected")
            applySettingsPreview()
        }
    }

    data class BookItem(val title: String, val author: String?, val progress: String?, val status: Int, val path: String?)

    enum class DataSourceMode { DURATION, PATH_SESSION, METADATA_ACCESS }
    enum class PeriodMode { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, LAST_7_DAYS, LAST_30_DAYS, CUSTOM }
    enum class ReadingFilterMode { ALL, READING_ONLY, FINISHED_ONLY }
    enum class ChartStyleMode { LINE, BAR }
    enum class YAxisMode { AUTO, FIXED }

    data class Settings(
        val includeUnread: Boolean,
        val showChart: Boolean,
        val showProgressStatus: Boolean,
        val showAuthor: Boolean,
        val minDurationMinutes: Int,
        val topN: Int,
        val weekStartYmd: String,
        val weekEndYmd: String,
        val periodMode: PeriodMode,
        val readingFilterMode: ReadingFilterMode,
        val sourceMode: DataSourceMode,
        val wallpaperMode: String,
        val coverFitMode: String,
        val progressMode: String,
        val timeUnit: String,
        val receiptTitle: String,
        val receiptTitleSize: Float,
        val receiptBodySize: Float,
        val serialNumberMode: String,
        val serialNumberCustom: String,
        val serialNumberSize: Float,
        val footerMode: String,
        val barcodeWidthScale: Float,
        val barcodeGapMode: String,
        val noteText: String,
        val chartStyleMode: ChartStyleMode,
        val showPeakLabel: Boolean,
        val yAxisMode: YAxisMode,
        val yAxisFixedMaxMinutes: Int,
        val titleFont: String,
        val bodyFont: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAllFilesAccessPermission()
        setupUi()
    }

    private fun checkAllFilesAccessPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Return from permission/settings pages: refresh font scan and preview context.
        if (!isInitializingUi) {
            validateFontTreePermission()
            reloadFontsFromSources()
            updateAutoRuntimeState()
            writeDebugLog("onResume_rescan")
        }
    }

    private fun validateFontTreePermission() {
        val tree = selectedFontDirUri ?: return
        val persisted = contentResolver.persistedUriPermissions
        val ok = persisted.any { it.uri.toString() == tree && it.isReadPermission }
        fontPermissionDebug = "persistedCheck uri=$tree readGranted=$ok persistedCount=${persisted.size}"
        if (!ok) {
            // Permission is gone after reboot/app restart, force re-select to avoid silent empty list.
            selectedFontDirUri = null
            getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE).edit().remove("font_tree_uri").apply()
        }
    }

    private fun reloadFontsFromSources() {
        val oldTitle = titleFontSpinner.selectedItem?.toString()
        val oldBody = bodyFontSpinner.selectedItem?.toString()
        val latestFonts = loadSystemFonts()
        systemFonts.clear()
        systemFonts.addAll(latestFonts)
        (titleFontSpinner.adapter as? ArrayAdapter<String>)?.apply {
            clear()
            addAll(latestFonts)
            notifyDataSetChanged()
        }
        (bodyFontSpinner.adapter as? ArrayAdapter<String>)?.apply {
            clear()
            addAll(latestFonts)
            notifyDataSetChanged()
        }
        oldTitle?.let { v -> systemFonts.indexOf(v).takeIf { it >= 0 }?.let { titleFontSpinner.setSelection(it) } }
        oldBody?.let { v -> systemFonts.indexOf(v).takeIf { it >= 0 }?.let { bodyFontSpinner.setSelection(it) } }
        fontScanText.text = fontScanReport
    }

    private fun fontLabel(entry: String): String {
        val idx = entry.indexOf(FONT_ENTRY_SEP)
        return if (idx > 0) entry.substring(0, idx) else entry
    }

    private fun fontSpec(entry: String): String {
        val idx = entry.indexOf(FONT_ENTRY_SEP)
        return if (idx > 0) entry.substring(idx + FONT_ENTRY_SEP.length) else entry
    }

    private fun findSpinnerIndexBySpec(spec: String): Int {
        val idx = systemFonts.indexOfFirst { fontSpec(it) == spec }
        return idx.coerceAtLeast(0)
    }

    private fun buildFontAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.text = fontLabel(getItem(position) ?: "")
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.text = fontLabel(getItem(position) ?: "")
                return v
            }
        }
    }

    private fun setupUi() {
        isInitializingUi = true
        val prefs = getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
        selectedFontDirUri = prefs.getString("font_tree_uri", null)
        systemFonts.clear()
        systemFonts.addAll(loadSystemFonts())
        selectedWeekStartYmd = prefs.getString("week_start", currentWeekStartYmd()) ?: currentWeekStartYmd()
        selectedWeekEndYmd = prefs.getString("week_end", currentWeekEndYmd()) ?: currentWeekEndYmd()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(Color.WHITE)
        }
        fun styleEinkButton(btn: Button) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(3, Color.BLACK)
                cornerRadius = 6f
            }
            btn.background = bg
            btn.setTextColor(Color.BLACK)
            btn.textSize = 16f
            btn.minHeight = 92
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 10)
        }
        val btnSettings = Button(this).apply {
            text = "设置"
            setOnClickListener { showSettingsPage() }
        }
        val btnPreview = Button(this).apply {
            text = "预览"
            setOnClickListener { showPreviewPage() }
        }
        val btnRefreshPreview = Button(this).apply {
            text = "刷新预览"
            setOnClickListener { refreshPreviewData() }
        }
        val btnGenerateWallpaper = Button(this).apply {
            text = "生成壁纸"
            setOnClickListener { generateAndSaveFromCurrentSettings() }
        }
        styleEinkButton(btnSettings)
        styleEinkButton(btnPreview)
        styleEinkButton(btnRefreshPreview)
        styleEinkButton(btnGenerateWallpaper)
        changeStateText = TextView(this).apply {
            text = "状态: 初始化"
            textSize = 12f
            setPadding(20, 14, 0, 0)
            setTextColor(Color.DKGRAY)
        }
        topBar.addView(btnSettings)
        topBar.addView(btnPreview)
        topBar.addView(btnRefreshPreview)
        topBar.addView(btnGenerateWallpaper)
        topBar.addView(changeStateText)

        settingsPage = buildSettingsPage(prefs)
        previewPage = buildPreviewPage()

        root.addView(topBar)
        root.addView(settingsPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(previewPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showSettingsPage()
        isInitializingUi = false
        applySettingsPreview()
        writeDebugLog("setupUi_done")
    }

    private fun buildSettingsPage(prefs: android.content.SharedPreferences): View {
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 24, 12, 24)
        }
        fun styleEinkButton(btn: Button) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(3, Color.BLACK)
                cornerRadius = 6f
            }
            btn.background = bg
            btn.setTextColor(Color.BLACK)
            btn.textSize = 14f
            btn.minHeight = 86
        }

        val title = TextView(this).apply {
            text = "阅读壁纸设置"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
        }
        val sectionContents = mutableListOf<LinearLayout>()
        fun section(
            titleText: String,
            desc: String,
            defaultExpanded: Boolean,
            onReset: (() -> Unit)? = null
        ): Pair<LinearLayout, LinearLayout> {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(22, 18, 22, 18)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 0, 0, 14)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    setColor(Color.WHITE)
                    setStroke(3, Color.BLACK)
                    cornerRadius = 8f
                }
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            val header = TextView(this).apply {
                text = titleText
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            }
            val resetBtn = Button(this).apply {
                text = "重置本组"
                visibility = if (onReset == null) View.GONE else View.VISIBLE
                setOnClickListener { onReset?.invoke() }
            }
            styleEinkButton(resetBtn)
            headerRow.addView(header, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            headerRow.addView(resetBtn)
            box.addView(headerRow)
            box.addView(TextView(this).apply {
                text = desc
                textSize = 11f
                setTextColor(Color.DKGRAY)
            })
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 10, 0, 0)
            }
            box.addView(content)
            sectionContents.add(content)
            var expanded = defaultExpanded
            header.setOnClickListener {
                expanded = !expanded
                content.visibility = if (expanded) View.VISIBLE else View.GONE
                header.text = if (expanded) "$titleText  ▾" else "$titleText  ▸"
            }
            header.text = "$titleText  ▾"
            content.visibility = if (expanded) View.VISIBLE else View.GONE
            if (!expanded) header.text = "$titleText  ▸"
            return box to content
        }
        fun numberControl(label: String, target: EditText, min: Int, max: Int): LinearLayout {
            val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            wrap.addView(TextView(this).apply { text = label })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val minus = Button(this).apply { text = "-" }
            val plus = Button(this).apply { text = "+" }
            styleEinkButton(minus)
            styleEinkButton(plus)
            val value = TextView(this).apply {
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(24, 8, 24, 8)
                setTextColor(Color.BLACK)
            }
            fun setValue(v: Int) {
                val nv = v.coerceIn(min, max)
                target.setText(nv.toString())
                value.text = nv.toString()
            }
            val initial = target.text.toString().trim().toIntOrNull()?.coerceIn(min, max) ?: min
            setValue(initial)
            minus.setOnClickListener { setValue((target.text.toString().toIntOrNull() ?: initial) - 1) }
            plus.setOnClickListener { setValue((target.text.toString().toIntOrNull() ?: initial) + 1) }
            row.addView(minus)
            row.addView(value, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(plus)
            wrap.addView(row)
            return wrap
        }

        includeUnreadCheck = CheckBox(this).apply {
            text = "最近阅读包含未读（readingStatus=0）"
            isChecked = prefs.getBoolean("include_unread", false)
        }
        val periodLabel = TextView(this).apply { text = "统计周期" }
        periodGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getString("period_mode", PeriodMode.THIS_WEEK.name) ?: PeriodMode.THIS_WEEK.name
            addView(RadioButton(context).apply { id = 4000; text = "当天"; isChecked = saved == PeriodMode.TODAY.name })
            addView(RadioButton(context).apply { id = 4006; text = "昨天"; isChecked = saved == PeriodMode.YESTERDAY.name })
            addView(RadioButton(context).apply { id = 4001; text = "本周"; isChecked = saved == PeriodMode.THIS_WEEK.name })
            addView(RadioButton(context).apply { id = 4002; text = "上周"; isChecked = saved == PeriodMode.LAST_WEEK.name })
            addView(RadioButton(context).apply { id = 4003; text = "最近7天"; isChecked = saved == PeriodMode.LAST_7_DAYS.name })
            addView(RadioButton(context).apply { id = 4004; text = "最近30天"; isChecked = saved == PeriodMode.LAST_30_DAYS.name })
            addView(RadioButton(context).apply { id = 4005; text = "自定义起止"; isChecked = saved == PeriodMode.CUSTOM.name })
        }
        titleInput = EditText(this).apply {
            hint = "账单标题"
            setText(prefs.getString("receipt_title", "阅读账单") ?: "阅读账单")
        }
        val titleSizeLabel = TextView(this).apply { text = "标题字号（24-120）" }
        titleSizeInput = EditText(this).apply {
            hint = "例如 74"
            setText((prefs.getFloat("receipt_title_size", 74f)).toInt().toString())
        }
        val bodySizeLabel = TextView(this).apply { text = "正文字号基准（18-60）" }
        bodySizeInput = EditText(this).apply {
            hint = "例如 34"
            setText((prefs.getFloat("receipt_body_size", 34f)).toInt().toString())
        }
        showProgressStatusCheck = CheckBox(this).apply {
            text = "显示进度和状态行"
            isChecked = prefs.getBoolean("show_progress_status", true)
        }
        val progressModeLabel = TextView(this).apply { text = "进度显示方式" }
        progressModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("progress_mode", "PAGES") ?: "PAGES"
            addView(RadioButton(context).apply { id = 6101; text = "页数"; isChecked = saved == "PAGES" })
            addView(RadioButton(context).apply { id = 6102; text = "百分比"; isChecked = saved == "PERCENT" })
        }
        showAuthorCheck = CheckBox(this).apply {
            text = "显示作者行（在进度行上方）"
            isChecked = prefs.getBoolean("show_author", true)
        }
        showChartCheck = CheckBox(this).apply {
            text = "显示下方周曲线图"
            isChecked = prefs.getBoolean("show_chart", true)
        }

        val sourceLabel = TextView(this).apply { text = "数据口径" }
        sourceGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getString("source_mode", DataSourceMode.DURATION.name) ?: DataSourceMode.DURATION.name
            addView(RadioButton(context).apply { id = 1001; text = "按阅读时长事件（推荐）"; isChecked = saved == DataSourceMode.DURATION.name })
            addView(RadioButton(context).apply { id = 1002; text = "按有路径会话"; isChecked = saved == DataSourceMode.PATH_SESSION.name })
            addView(RadioButton(context).apply { id = 1003; text = "按Metadata最近访问"; isChecked = saved == DataSourceMode.METADATA_ACCESS.name })
        }
        val wallpaperModeLabel = TextView(this).apply { text = "壁纸类型" }
        wallpaperModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getString("wallpaper_mode", "STATS") ?: "STATS"
            addView(RadioButton(context).apply { id = 1201; text = "统计壁纸"; isChecked = saved == "STATS" })
            addView(RadioButton(context).apply { id = 1202; text = "当前阅读封面(实验性,较耗电)"; isChecked = saved == "COVER" })
            addView(RadioButton(context).apply { id = 1203; text = "自动(熄屏优先封面)(实验性,较耗电)"; isChecked = saved == "AUTO_COVER" })
        }
        val wallpaperModeHint = TextView(this).apply {
            text = "提示：封面模式依赖 NeoReader 元数据落库。通常需要先退出当前正在阅读的书籍再锁屏，才会刷新到最新封面；如果在书籍打开状态下直接锁屏，往往仍显示旧封面，通常下一次锁屏才会生效。"
            textSize = 12f
        }
        val coverFitModeLabel = TextView(this).apply { text = "封面显示方式" }
        coverFitModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("cover_fit_mode", "FIT") ?: "FIT"
            addView(RadioButton(context).apply { id = 1211; text = "完整显示"; isChecked = saved == "FIT" })
            addView(RadioButton(context).apply { id = 1212; text = "铺满裁切"; isChecked = saved == "CROP" })
        }

        val minDurationLabel = TextView(this).apply {
            text = "最小时长阈值（分钟，作用于“按阅读时长事件”）"
        }
        minDurationInput = EditText(this).apply {
            hint = "例如 1"
            setText(prefs.getInt("min_duration_minutes", 1).toString())
        }
        val topNLabel = TextView(this).apply { text = "Top N（最多显示书籍数量）" }
        topNInput = EditText(this).apply {
            hint = "TopN(1-5)"
            setText(prefs.getInt("top_n", 5).coerceIn(1, 5).toString())
        }
        val readingFilterLabel = TextView(this).apply { text = "书单筛选（状态）" }
        readingFilterGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("reading_filter_mode", ReadingFilterMode.ALL.name) ?: ReadingFilterMode.ALL.name
            addView(RadioButton(context).apply { id = 6001; text = "全部"; isChecked = saved == ReadingFilterMode.ALL.name })
            addView(RadioButton(context).apply { id = 6002; text = "仅在读"; isChecked = saved == ReadingFilterMode.READING_ONLY.name })
            addView(RadioButton(context).apply { id = 6003; text = "仅已读完"; isChecked = saved == ReadingFilterMode.FINISHED_ONLY.name })
        }

        val timeUnitLabel = TextView(this).apply { text = "时长显示单位" }
        timeUnitGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("time_unit", "HOUR") ?: "HOUR"
            addView(RadioButton(context).apply { id = 2001; text = "小时"; isChecked = saved == "HOUR" })
            addView(RadioButton(context).apply { id = 2002; text = "分钟"; isChecked = saved == "MINUTE" })
        }
        val serialModeLabel = TextView(this).apply { text = "单号数字模式" }
        serialModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("serial_number_mode", "DATE") ?: "DATE"
            addView(RadioButton(context).apply { id = 2011; text = "月日"; isChecked = saved == "DATE" })
            addView(RadioButton(context).apply { id = 2012; text = "随机"; isChecked = saved == "RANDOM" })
            addView(RadioButton(context).apply { id = 2013; text = "自定义"; isChecked = saved == "CUSTOM" })
        }
        serialCustomInput = EditText(this).apply {
            hint = "自定义数字(1-12位)"
            setText(prefs.getString("serial_number_custom", "") ?: "")
        }
        serialNumberSizeInput = EditText(this).apply {
            hint = "单号数字字号(24-140)"
            setText((prefs.getFloat("serial_number_size", 46f)).toInt().toString())
        }
        val footerLabel = TextView(this).apply { text = "底部备注/条码" }
        footerModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getString("footer_mode", "NONE") ?: "NONE"
            addView(RadioButton(context).apply { id = 3001; text = "不显示"; isChecked = saved == "NONE" })
            addView(RadioButton(context).apply { id = 3002; text = "只显示备注"; isChecked = saved == "NOTE" })
            addView(RadioButton(context).apply { id = 3003; text = "显示条码 + 备注"; isChecked = saved == "BARCODE" })
        }
        noteInput = EditText(this).apply {
            hint = "备注文本 / 条码内容"
            setText(prefs.getString("note_text", "") ?: "")
        }
        val barcodeWidthLabel = TextView(this).apply { text = "条码粗细强度" }
        barcodeWidthGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getFloat("barcode_width_scale", 1.0f)
            addView(RadioButton(context).apply { id = 3101; text = "细(0.8x)"; isChecked = saved == 0.8f })
            addView(RadioButton(context).apply { id = 3102; text = "标准(1.0x)"; isChecked = saved != 0.8f && saved != 1.2f })
            addView(RadioButton(context).apply { id = 3103; text = "粗(1.2x)"; isChecked = saved == 1.2f })
        }
        val barcodeGapLabel = TextView(this).apply { text = "条码留白密度" }
        barcodeGapGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getString("barcode_gap_mode", "STANDARD") ?: "STANDARD"
            addView(RadioButton(context).apply { id = 3111; text = "紧凑"; isChecked = saved == "TIGHT" })
            addView(RadioButton(context).apply { id = 3112; text = "标准"; isChecked = saved == "STANDARD" })
            addView(RadioButton(context).apply { id = 3113; text = "疏松"; isChecked = saved == "LOOSE" })
        }
        val chartStyleLabel = TextView(this).apply { text = "图表样式" }
        val chartRuleHint = TextView(this).apply {
            textSize = 12f
            text = "图表横轴规则：当天/昨天=按小时；本周/上周/最近7天=按天；最近30天=按天；自定义<=14天按天，15-90天按周，>90天按月。"
        }
        chartStyleGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("chart_style_mode", ChartStyleMode.LINE.name) ?: ChartStyleMode.LINE.name
            addView(RadioButton(context).apply { id = 7001; text = "折线"; isChecked = saved == ChartStyleMode.LINE.name })
            addView(RadioButton(context).apply { id = 7002; text = "柱状"; isChecked = saved == ChartStyleMode.BAR.name })
        }
        showPeakLabelCheck = CheckBox(this).apply {
            text = "显示峰值标签"
            isChecked = prefs.getBoolean("show_peak_label", true)
        }
        val yAxisModeLabel = TextView(this).apply { text = "Y轴最大值" }
        yAxisModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val saved = prefs.getString("y_axis_mode", YAxisMode.AUTO.name) ?: YAxisMode.AUTO.name
            addView(RadioButton(context).apply { id = 7101; text = "自动"; isChecked = saved == YAxisMode.AUTO.name })
            addView(RadioButton(context).apply { id = 7102; text = "固定"; isChecked = saved == YAxisMode.FIXED.name })
        }
        yAxisMaxInput = EditText(this).apply {
            hint = "固定最大值(分钟)"
            setText(prefs.getInt("y_axis_fixed_max_minutes", 300).toString())
        }

        val autoSectionLabel = TextView(this).apply { text = "自动刷新（默认开启）" }
        autoRefreshCheck = CheckBox(this).apply {
            text = "启用自动刷新与自动覆盖保存"
            isChecked = prefs.getBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, true)
        }
        autoModeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            val saved = prefs.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) ?: AutoRefreshConfig.MODE_DAILY
            addView(RadioButton(context).apply { id = 8001; text = "每日定时一次（省电，推荐）"; isChecked = saved == AutoRefreshConfig.MODE_DAILY })
            addView(RadioButton(context).apply { id = 8002; text = "熄屏触发（更实时，较耗电）"; isChecked = saved == AutoRefreshConfig.MODE_SCREEN_OFF })
        }
        autoDailyTimeInput = EditText(this).apply {
            hint = "每日执行时间"
            setText(prefs.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30")
            isFocusable = false
            isClickable = true
            setOnClickListener { openDailyTimePicker() }
        }
        autoMinIntervalInput = EditText(this).apply {
            hint = "熄屏触发最小间隔(分钟, 1-240)"
            setText(prefs.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).toString())
        }
        autoModeHintText = TextView(this).apply {
            textSize = 12f
        }
        autoStateText = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.DKGRAY)
        }
        val autoWarningText = TextView(this).apply {
            text = "提示：熄屏触发会增加唤醒次数与耗电；NeoReader 常在退出当前书籍/会话落库后才更新元数据，所以可能出现“本次锁屏仍是旧封面、下次锁屏生效”的现象。"
            textSize = 12f
        }

        val titleFontLabel = TextView(this).apply { text = "标题字体（系统字体）" }
        titleFontSpinner = Spinner(this).apply {
            adapter = buildFontAdapter(systemFonts)
            val saved = prefs.getString("title_font", "SERIF_BOLD") ?: "SERIF_BOLD"
            setSelection(findSpinnerIndexBySpec(saved))
        }
        val bodyFontLabel = TextView(this).apply { text = "正文字体（系统字体）" }
        bodyFontSpinner = Spinner(this).apply {
            adapter = buildFontAdapter(systemFonts)
            val saved = prefs.getString("body_font", "MONO") ?: "MONO"
            setSelection(findSpinnerIndexBySpec(saved))
        }
        pickFontDirBtn = Button(this).apply {
            text = "选择字体目录（SAF）"
            setOnClickListener { pickFontTreeLauncher.launch(null) }
        }
        styleEinkButton(pickFontDirBtn)
        fontScanText = TextView(this).apply {
            textSize = 12f
            text = fontScanReport
        }

        val weekLabel = TextView(this).apply { text = "自定义起止日期" }
        weekStartText = TextView(this).apply {
            text = selectedWeekStartYmd
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        weekEndText = TextView(this).apply {
            text = selectedWeekEndYmd
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
        }
        val weekPickerBtn = Button(this).apply {
            text = "选择起始日期"
            setOnClickListener { openWeekStartDatePicker() }
        }
        styleEinkButton(weekPickerBtn)
        val weekEndPickerBtn = Button(this).apply {
            text = "选择结束日期"
            setOnClickListener { openWeekEndDatePicker() }
        }
        styleEinkButton(weekEndPickerBtn)

        statusText = TextView(this).apply {
            text = "设置后点击按钮生成。"
            textSize = 15f
        }

        container.addView(title)
        val foldRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnExpandAll = Button(this).apply {
            text = "全部展开"
            setOnClickListener {
                sectionContents.forEach { it.visibility = View.VISIBLE }
            }
        }
        styleEinkButton(btnExpandAll)
        val btnCollapseAll = Button(this).apply {
            text = "全部收起"
            setOnClickListener {
                sectionContents.forEach { it.visibility = View.GONE }
            }
        }
        styleEinkButton(btnCollapseAll)
        foldRow.addView(btnExpandAll)
        foldRow.addView(btnCollapseAll)
        container.addView(foldRow)

        val (secDataBox, secData) = section("数据与统计", "周期、数据口径、时长单位与日期范围", true, onReset = {
            periodGroup.check(4001)
            sourceGroup.check(1001)
            wallpaperModeGroup.check(1201)
            coverFitModeGroup.check(1211)
            timeUnitGroup.check(2001)
            selectedWeekStartYmd = currentWeekStartYmd()
            selectedWeekEndYmd = currentWeekEndYmd()
            weekStartText.text = selectedWeekStartYmd
            weekEndText.text = selectedWeekEndYmd
            applySettingsPreview()
        })
        secData.addView(periodLabel); secData.addView(periodGroup); secData.addView(sourceLabel); secData.addView(sourceGroup)
        secData.addView(wallpaperModeLabel); secData.addView(wallpaperModeGroup); secData.addView(wallpaperModeHint); secData.addView(coverFitModeLabel); secData.addView(coverFitModeGroup)
        secData.addView(timeUnitLabel); secData.addView(timeUnitGroup); secData.addView(weekLabel); secData.addView(weekStartText)
        secData.addView(weekEndText); secData.addView(weekPickerBtn); secData.addView(weekEndPickerBtn); container.addView(secDataBox)

        val (secFilterBox, secFilter) = section("书单筛选", "控制展示书目与统计阈值", true, onReset = {
            includeUnreadCheck.isChecked = false
            readingFilterGroup.check(6001)
            topNInput.setText("5")
            minDurationInput.setText("1")
            applySettingsPreview()
        })
        secFilter.addView(includeUnreadCheck); secFilter.addView(readingFilterLabel); secFilter.addView(readingFilterGroup)
        secFilter.addView(topNLabel); secFilter.addView(numberControl("Top N", topNInput, 1, 5))
        secFilter.addView(minDurationLabel); secFilter.addView(numberControl("最小时长(分钟)", minDurationInput, 0, 240)); container.addView(secFilterBox)

        val (secLayoutBox, secLayout) = section("排版与字体", "标题、字号、进度与字体", true, onReset = {
            titleInput.setText("阅读账单")
            titleSizeInput.setText("74")
            bodySizeInput.setText("34")
            serialModeGroup.check(2011)
            serialCustomInput.setText("")
            serialNumberSizeInput.setText("46")
            showProgressStatusCheck.isChecked = true
            progressModeGroup.check(6101)
            showAuthorCheck.isChecked = true
            applySettingsPreview()
        })
        secLayout.addView(titleInput); secLayout.addView(numberControl("标题字号", titleSizeInput, 24, 120)); secLayout.addView(numberControl("正文字号", bodySizeInput, 18, 60))
        secLayout.addView(serialModeLabel); secLayout.addView(serialModeGroup); secLayout.addView(serialCustomInput); secLayout.addView(numberControl("单号数字字号", serialNumberSizeInput, 24, 140))
        secLayout.addView(showProgressStatusCheck); secLayout.addView(progressModeLabel); secLayout.addView(progressModeGroup); secLayout.addView(showAuthorCheck)
        secLayout.addView(titleFontLabel); secLayout.addView(titleFontSpinner); secLayout.addView(bodyFontLabel); secLayout.addView(bodyFontSpinner)
        secLayout.addView(pickFontDirBtn); secLayout.addView(fontScanText); container.addView(secLayoutBox)

        val (secChartBox, secChart) = section("图表", "图形样式与坐标设置", false, onReset = {
            showChartCheck.isChecked = true
            chartStyleGroup.check(7001)
            showPeakLabelCheck.isChecked = true
            yAxisModeGroup.check(7101)
            yAxisMaxInput.setText("300")
            applySettingsPreview()
        })
        val yAxisFixedControl = numberControl("Y轴固定最大值(分钟)", yAxisMaxInput, 1, 2000)
        secChart.addView(showChartCheck); secChart.addView(chartStyleLabel); secChart.addView(chartRuleHint); secChart.addView(chartStyleGroup)
        secChart.addView(showPeakLabelCheck); secChart.addView(yAxisModeLabel); secChart.addView(yAxisModeGroup); secChart.addView(yAxisFixedControl); container.addView(secChartBox)

        val (secFooterBox, secFooter) = section("底部备注与条码", "备注文本与装饰条码参数", false, onReset = {
            footerModeGroup.check(3001)
            noteInput.setText("")
            barcodeWidthGroup.check(3102)
            barcodeGapGroup.check(3112)
            applySettingsPreview()
        })
        secFooter.addView(footerLabel); secFooter.addView(footerModeGroup); secFooter.addView(noteInput)
        secFooter.addView(barcodeWidthLabel); secFooter.addView(barcodeWidthGroup); secFooter.addView(barcodeGapLabel); secFooter.addView(barcodeGapGroup); container.addView(secFooterBox)

        val (secAutoBox, secAuto) = section("自动刷新", "默认自动模式，可切换定时或熄屏触发", false, onReset = {
            autoRefreshCheck.isChecked = true
            autoModeGroup.check(8001)
            autoDailyTimeInput.setText("22:30")
            autoMinIntervalInput.setText("3")
            applySettingsPreview()
        })
        val autoMinIntervalControl = numberControl("熄屏最小间隔(分钟)", autoMinIntervalInput, 1, 240)
        secAuto.addView(autoSectionLabel); secAuto.addView(autoRefreshCheck); secAuto.addView(autoModeGroup); secAuto.addView(autoDailyTimeInput)
        secAuto.addView(autoMinIntervalControl); secAuto.addView(autoModeHintText); secAuto.addView(autoStateText); secAuto.addView(autoWarningText); container.addView(secAutoBox)

        fun updateConditionalVisibility() {
            val showChart = showChartCheck.isChecked
            chartStyleLabel.visibility = if (showChart) View.VISIBLE else View.GONE
            chartRuleHint.visibility = if (showChart) View.VISIBLE else View.GONE
            chartStyleGroup.visibility = if (showChart) View.VISIBLE else View.GONE
            showPeakLabelCheck.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisModeLabel.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisModeGroup.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisFixedControl.visibility = if (showChart && yAxisModeGroup.checkedRadioButtonId == 7102) View.VISIBLE else View.GONE

            val customPeriod = periodGroup.checkedRadioButtonId == 4005
            weekLabel.visibility = if (customPeriod) View.VISIBLE else View.GONE
            weekStartText.visibility = if (customPeriod) View.VISIBLE else View.GONE
            weekEndText.visibility = if (customPeriod) View.VISIBLE else View.GONE
            weekPickerBtn.visibility = if (customPeriod) View.VISIBLE else View.GONE
            weekEndPickerBtn.visibility = if (customPeriod) View.VISIBLE else View.GONE

            val footerVisible = footerModeGroup.checkedRadioButtonId != 3001
            noteInput.visibility = if (footerVisible) View.VISIBLE else View.GONE
            barcodeWidthLabel.visibility = if (footerModeGroup.checkedRadioButtonId == 3003) View.VISIBLE else View.GONE
            barcodeWidthGroup.visibility = if (footerModeGroup.checkedRadioButtonId == 3003) View.VISIBLE else View.GONE
            barcodeGapLabel.visibility = if (footerModeGroup.checkedRadioButtonId == 3003) View.VISIBLE else View.GONE
            barcodeGapGroup.visibility = if (footerModeGroup.checkedRadioButtonId == 3003) View.VISIBLE else View.GONE

            val autoEnabled = autoRefreshCheck.isChecked
            autoModeGroup.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoDailyTimeInput.visibility = if (autoEnabled && autoModeGroup.checkedRadioButtonId == 8001) View.VISIBLE else View.GONE
            autoMinIntervalControl.visibility = if (autoEnabled && autoModeGroup.checkedRadioButtonId == 8002) View.VISIBLE else View.GONE
            autoModeHintText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoStateText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoWarningText.visibility = if (autoEnabled) View.VISIBLE else View.GONE

            serialCustomInput.visibility = if (serialModeGroup.checkedRadioButtonId == 2013) View.VISIBLE else View.GONE
            val coverOptsVisible = wallpaperModeGroup.checkedRadioButtonId != 1201
            coverFitModeGroup.visibility = if (coverOptsVisible) View.VISIBLE else View.GONE
        }
        showChartCheck.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        periodGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        yAxisModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        footerModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        autoRefreshCheck.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        autoModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        serialModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        wallpaperModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        updateConditionalVisibility()

        container.addView(statusText)

        updateAutoRefreshHint()
        updateAutoRuntimeState()
        attachAutoRefreshListeners()

        scroll.addView(container)
        return scroll
    }

    private fun attachAutoRefreshListeners() {
        includeUnreadCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showProgressStatusCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showAuthorCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showPeakLabelCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        sourceGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        coverFitModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        readingFilterGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        progressModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        timeUnitGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        chartStyleGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        barcodeWidthGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        barcodeGapGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        titleFontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (!isInitializingUi) applySettingsPreview() })
        bodyFontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (!isInitializingUi) applySettingsPreview() })
        minDurationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        topNInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        titleSizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        bodySizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        serialNumberSizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        serialCustomInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        yAxisMaxInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        autoDailyTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        autoMinIntervalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun applySettingsPreview() {
        val settings = readSettingsFromUi()
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val (bmp, result) = renderWallpaperPreview(settings)
        previewBitmap = bmp
        statusText.text = "预览已更新（未写入文件）\n$result"
        changeStateText.text = "状态: 参数已变更（仅预览）"
        refreshPreview()
        writeDebugLog("preview_updated")
    }

    private fun openWeekStartDatePicker() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        parseWeek(selectedWeekStartYmd)?.let { cal.timeInMillis = it.first }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance(TimeZone.getDefault())
                picked.set(year, month, dayOfMonth, 0, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                selectedWeekStartYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(picked.timeInMillis))
                weekStartText.text = selectedWeekStartYmd
                if (!isInitializingUi) applySettingsPreview()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openWeekEndDatePicker() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        parseYmd(selectedWeekEndYmd)?.let { cal.timeInMillis = it }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance(TimeZone.getDefault())
                picked.set(year, month, dayOfMonth, 0, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                selectedWeekEndYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(picked.timeInMillis))
                weekEndText.text = selectedWeekEndYmd
                if (!isInitializingUi) applySettingsPreview()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openDailyTimePicker() {
        val raw = autoDailyTimeInput.text.toString().trim()
        val parts = raw.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        TimePickerDialog(
            this,
            { _, h, m ->
                autoDailyTimeInput.setText(String.format(Locale.US, "%02d:%02d", h, m))
                if (!isInitializingUi) applySettingsPreview()
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun generateAndSaveFromCurrentSettings() {
        val settings = readSettingsFromUi()
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val (bmp, result) = renderWallpaperPreview(settings)
        previewBitmap = bmp
        val saved = saveBitmapToPictures(bmp)
        lastSavedPath = saved
        statusText.text = "已生成并覆盖文件\n$result\n路径: $saved"
        changeStateText.text = "状态: 已生成并保存"
        refreshPreview()
        showPreviewPage()
        writeDebugLog("generated_saved")
    }

    private fun saveAndApplyAutoRefreshSettings() {
        val isEnabled = autoRefreshCheck.isChecked
        val mode = if (autoModeGroup.checkedRadioButtonId == 8002) AutoRefreshConfig.MODE_SCREEN_OFF else AutoRefreshConfig.MODE_DAILY
        val dailyTime = normalizeDailyTime(autoDailyTimeInput.text.toString())
        val minInterval = autoMinIntervalInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 240) ?: 3
        val prefs = getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, isEnabled)
            .putString(AutoRefreshConfig.KEY_AUTO_MODE, mode)
            .putString(AutoRefreshConfig.KEY_DAILY_TIME, dailyTime)
            .putInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, minInterval)
            .apply()
        if (autoDailyTimeInput.text.toString() != dailyTime) {
            autoDailyTimeInput.setText(dailyTime)
            autoDailyTimeInput.setSelection(dailyTime.length)
        }
        updateAutoRefreshHint()
        updateAutoRuntimeState()
        AutoRefreshScheduler.reschedule(this)
        AutoRefreshRuntime.sync(this)
        AutoRefreshLog.i(this, "auto settings updated: enabled=$isEnabled mode=$mode dailyTime=$dailyTime minInterval=$minInterval")
    }

    private fun updateAutoRefreshHint() {
        val mode = if (::autoModeGroup.isInitialized && autoModeGroup.checkedRadioButtonId == 8002) "熄屏触发" else "每日定时"
        autoModeHintText.text = if (::autoRefreshCheck.isInitialized && autoRefreshCheck.isChecked) {
            "当前自动模式：$mode，熄屏最小间隔=${autoMinIntervalInput.text}"
        } else {
            "当前自动模式：已关闭"
        }
    }

    private fun updateAutoRuntimeState() {
        if (!::autoStateText.isInitialized) return
        val p = getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = p.getBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, true)
        val mode = p.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) ?: AutoRefreshConfig.MODE_DAILY
        val dailyTime = p.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30"
        val minInterval = p.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).coerceIn(1, 240)
        val lastMs = p.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        val lastReasonRaw = p.getString(AutoRefreshConfig.KEY_LAST_REASON, "") ?: ""
        val lastReason = when (lastReasonRaw) {
            "screen_off" -> "熄屏触发"
            "screen_on_prewarm" -> "亮屏预热"
            "book_content_changed" -> "内容变化"
            "daily_alarm" -> "每日定时"
            "" -> "暂无"
            else -> lastReasonRaw
        }
        val lastTime = if (lastMs > 0L) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(lastMs))
        } else {
            "暂无"
        }
        val runtimeHint = if (!enabled) {
            "自动已关闭"
        } else if (mode == AutoRefreshConfig.MODE_SCREEN_OFF) {
            "熄屏监听应运行（前台服务）"
        } else {
            "按每日定时运行（$dailyTime）"
        }
        autoStateText.text = "自动状态：$runtimeHint\n最近触发：$lastTime（$lastReason）\n当前参数：模式=$mode，定时=$dailyTime，熄屏间隔=${minInterval}分钟"
    }

    private fun normalizeDailyTime(raw: String): String {
        val m = Regex("""^\s*(\d{1,2}):(\d{1,2})\s*$""").find(raw)
        val h = m?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val mm = m?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        return String.format(Locale.US, "%02d:%02d", h, mm)
    }

    private fun buildPreviewPage(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 24, 12, 24)
        }
        previewText = TextView(this).apply {
            text = "暂无图片，请先在设置页生成。"
        }
        previewImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(previewText)
        container.addView(previewImage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun showSettingsPage() {
        settingsPage.visibility = View.VISIBLE
        previewPage.visibility = View.GONE
    }

    private fun showPreviewPage() {
        settingsPage.visibility = View.GONE
        previewPage.visibility = View.VISIBLE
        refreshPreview()
    }

    private fun refreshPreviewData() {
        applySettingsPreview()
        collectMetadataDebugSample()
        writeDebugLog("manual_refresh_preview")
        showPreviewPage()
    }

    private fun collectMetadataDebugSample() {
        runCatching {
            val maxRows = 30
            val rows = StringBuilder()
            contentResolver.query(metadataUri, null, null, null, "lastAccess DESC")?.use { c ->
                metadataDebugReport = "columns=${c.columnNames.joinToString(",")} count=${c.count}"
                var row = 0
                while (row < maxRows && c.moveToNext()) {
                    row++
                    fun col(name: String): String {
                        val i = c.getColumnIndex(name)
                        if (i < 0 || c.isNull(i)) return ""
                        return runCatching { c.getString(i) ?: "" }.getOrDefault("")
                    }
                    val title = col("title")
                    val path = col("nativeAbsolutePath")
                    val status = col("readingStatus")
                    val author = col("authors").ifBlank { col("author") }
                    val coverVals = listOf("coverPath", "cover", "coverUri", "thumbnail", "thumbnailPath", "bookCoverPath", "frontCoverPath", "coverUrl", "cover_url")
                        .mapNotNull { k ->
                            val v = col(k)
                            if (v.isBlank()) null else "$k=${v.take(120)}"
                        }
                    rows.append("row=").append(row)
                        .append(" title=").append(title.take(80))
                        .append(" status=").append(status.ifBlank { "?" })
                        .append(" author=").append(author.take(40))
                        .append(" ext=").append(File(path).extension.lowercase(Locale.ROOT))
                        .append(" path=").append(path.take(160))
                        .append('\n')
                    if (coverVals.isEmpty()) {
                        rows.append("  coverCandidates=<empty>\n")
                    } else {
                        rows.append("  coverCandidates=").append(coverVals.joinToString(" | ")).append('\n')
                    }
                }
            } ?: run {
                metadataDebugReport = "query=null"
                rows.append("<query returned null>")
            }
            metadataRowsDebugReport = rows.toString().ifBlank { "<empty>" }
        }.onFailure {
            metadataDebugReport = "error=${it.javaClass.simpleName}:${it.message}"
            metadataRowsDebugReport = "<error>"
        }
    }

    private fun refreshPreview() {
        val bmp = previewBitmap
        if (bmp != null) {
            previewImage.setImageBitmap(bmp)
            previewText.text = "App 内实时预览（未写入文件，除非点击生成）"
            return
        }
        previewText.text = "暂无预览，请在设置页修改参数或点击生成。"
        previewImage.setImageDrawable(null)
    }

    private fun readSettingsFromUi(): Settings {
        val includeUnread = includeUnreadCheck.isChecked
        val showChart = showChartCheck.isChecked
        val showProgressStatus = showProgressStatusCheck.isChecked
        val showAuthor = showAuthorCheck.isChecked
        val minDurationMinutes = minDurationInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 1
        val topN = topNInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 5) ?: 5
        val weekStart = selectedWeekStartYmd.ifBlank { currentWeekStartYmd() }
        val weekEnd = selectedWeekEndYmd.ifBlank { currentWeekEndYmd() }
        val periodMode = when (periodGroup.checkedRadioButtonId) {
            4000 -> PeriodMode.TODAY
            4006 -> PeriodMode.YESTERDAY
            4002 -> PeriodMode.LAST_WEEK
            4003 -> PeriodMode.LAST_7_DAYS
            4004 -> PeriodMode.LAST_30_DAYS
            4005 -> PeriodMode.CUSTOM
            else -> PeriodMode.THIS_WEEK
        }
        val readingFilterMode = when (readingFilterGroup.checkedRadioButtonId) {
            6002 -> ReadingFilterMode.READING_ONLY
            6003 -> ReadingFilterMode.FINISHED_ONLY
            else -> ReadingFilterMode.ALL
        }
        val sourceMode = when (sourceGroup.checkedRadioButtonId) {
            1002 -> DataSourceMode.PATH_SESSION
            1003 -> DataSourceMode.METADATA_ACCESS
            else -> DataSourceMode.DURATION
        }
        val wallpaperMode = when (wallpaperModeGroup.checkedRadioButtonId) {
            1202 -> "COVER"
            1203 -> "AUTO_COVER"
            else -> "STATS"
        }
        val coverFitMode = when (coverFitModeGroup.checkedRadioButtonId) {
            1212 -> "CROP"
            else -> "FIT"
        }
        val progressMode = when (progressModeGroup.checkedRadioButtonId) {
            6102 -> "PERCENT"
            else -> "PAGES"
        }
        val timeUnit = when (timeUnitGroup.checkedRadioButtonId) {
            2002 -> "MINUTE"
            else -> "HOUR"
        }
        val serialNumberMode = when (serialModeGroup.checkedRadioButtonId) {
            2012 -> "RANDOM"
            2013 -> "CUSTOM"
            else -> "DATE"
        }
        val serialNumberCustomRaw = serialCustomInput.text.toString().trim()
        val serialNumberCustom = serialNumberCustomRaw.filter { it.isDigit() }.take(12)
        val serialNumberSize = serialNumberSizeInput.text.toString().trim().toFloatOrNull()?.coerceIn(24f, 140f) ?: 46f
        val receiptTitle = titleInput.text.toString().ifBlank { "阅读账单" }
        val receiptTitleSize = titleSizeInput.text.toString().trim().toFloatOrNull()?.coerceIn(24f, 120f) ?: 74f
        val receiptBodySize = bodySizeInput.text.toString().trim().toFloatOrNull()?.coerceIn(18f, 60f) ?: 34f
        val chartStyleMode = when (chartStyleGroup.checkedRadioButtonId) {
            7002 -> ChartStyleMode.BAR
            else -> ChartStyleMode.LINE
        }
        val showPeakLabel = showPeakLabelCheck.isChecked
        val yAxisMode = when (yAxisModeGroup.checkedRadioButtonId) {
            7102 -> YAxisMode.FIXED
            else -> YAxisMode.AUTO
        }
        val yAxisFixedMaxMinutes = yAxisMaxInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 2000) ?: 300
        val footerMode = when (footerModeGroup.checkedRadioButtonId) {
            3002 -> "NOTE"
            3003 -> "BARCODE"
            else -> "NONE"
        }
        val noteText = noteInput.text.toString().trim()
        val barcodeWidthScale = when (barcodeWidthGroup.checkedRadioButtonId) {
            3101 -> 0.8f
            3103 -> 1.2f
            else -> 1.0f
        }
        val barcodeGapMode = when (barcodeGapGroup.checkedRadioButtonId) {
            3111 -> "TIGHT"
            3113 -> "LOOSE"
            else -> "STANDARD"
        }
        val titleFont = fontSpec(titleFontSpinner.selectedItem?.toString() ?: "SERIF_BOLD")
        val bodyFont = fontSpec(bodyFontSpinner.selectedItem?.toString() ?: "MONO")
        return Settings(includeUnread, showChart, showProgressStatus, showAuthor, minDurationMinutes, topN, weekStart, weekEnd, periodMode, readingFilterMode, sourceMode, wallpaperMode, coverFitMode, progressMode, timeUnit, receiptTitle, receiptTitleSize, receiptBodySize, serialNumberMode, serialNumberCustom, serialNumberSize, footerMode, barcodeWidthScale, barcodeGapMode, noteText, chartStyleMode, showPeakLabel, yAxisMode, yAxisFixedMaxMinutes, titleFont, bodyFont)
    }

    private fun saveSettings(settings: Settings) {
        getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("include_unread", settings.includeUnread)
            .putBoolean("show_chart", settings.showChart)
            .putBoolean("show_progress_status", settings.showProgressStatus)
            .putBoolean("show_author", settings.showAuthor)
            .putInt("min_duration_minutes", settings.minDurationMinutes)
            .putInt("top_n", settings.topN)
            .putString("week_start", settings.weekStartYmd)
            .putString("week_end", settings.weekEndYmd)
            .putString("period_mode", settings.periodMode.name)
            .putString("reading_filter_mode", settings.readingFilterMode.name)
            .putString("source_mode", settings.sourceMode.name)
            .putString("wallpaper_mode", settings.wallpaperMode)
            .putString("cover_fit_mode", settings.coverFitMode)
            .putString("progress_mode", settings.progressMode)
            .putString("time_unit", settings.timeUnit)
            .putString("receipt_title", settings.receiptTitle)
            .putFloat("receipt_title_size", settings.receiptTitleSize)
            .putFloat("receipt_body_size", settings.receiptBodySize)
            .putString("serial_number_mode", settings.serialNumberMode)
            .putString("serial_number_custom", settings.serialNumberCustom)
            .putFloat("serial_number_size", settings.serialNumberSize)
            .putString("footer_mode", settings.footerMode)
            .putFloat("barcode_width_scale", settings.barcodeWidthScale)
            .putString("barcode_gap_mode", settings.barcodeGapMode)
            .putString("note_text", settings.noteText)
            .putString("chart_style_mode", settings.chartStyleMode.name)
            .putBoolean("show_peak_label", settings.showPeakLabel)
            .putString("y_axis_mode", settings.yAxisMode.name)
            .putInt("y_axis_fixed_max_minutes", settings.yAxisFixedMaxMinutes)
            .putString("title_font", settings.titleFont)
            .putString("body_font", settings.bodyFont)
            .apply()
    }

    private fun renderWallpaperPreview(settings: Settings): Pair<Bitmap, String> {
        val preview = AutoWallpaperGenerator.buildPreviewFromPrefs(this)
            ?: return "日期格式错误，请用 yyyy-MM-dd".let { Pair(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), it) }
        return Pair(preview.bitmap, "已生成\n${preview.summary}")
    }

    private fun parseWeek(startYmd: String): Pair<Long, Long>? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            val d = sdf.parse(startYmd) ?: return null
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.time = d
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 6)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            start to cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    private fun parseYmd(ymd: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.parse(ymd)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun currentWeekStartYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.SUNDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun currentWeekEndYmd(): String {
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.firstDayOfWeek = Calendar.SUNDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        c.add(Calendar.DAY_OF_MONTH, 6)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis))
    }

    private fun loadSystemFonts(): List<String> {
        val result = mutableListOf("SERIF_BOLD", "SANS", "MONO")
        val report = StringBuilder("字体目录扫描:\n")
        report.append("fontTreeUri=").append(selectedFontDirUri ?: "<null>").append("\n")
        report.append("fontPermissionDebug=").append(fontPermissionDebug.ifBlank { "<empty>" }).append("\n")

        if (!selectedFontDirUri.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(selectedFontDirUri!!)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                var treeCount = 0
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val display = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                        if (mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                            (display.endsWith(".ttf", true) || display.endsWith(".otf", true) || display.endsWith(".ttc", true))
                        ) {
                            val childId = if (idIdx >= 0) c.getString(idIdx) else null
                            if (!childId.isNullOrBlank()) {
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                result.add("${display}${FONT_ENTRY_SEP}${childUri}")
                                treeCount++
                            }
                        }
                    }
                }
                report.append("fontTreeCount=").append(treeCount).append("\n")
            } catch (e: Exception) {
                report.append("fontTreeError=").append(e.message ?: "unknown").append("\n")
            }
        }

        fontScanReport = report.toString()
        return result.distinct()
    }

    private fun saveBitmapToPictures(bitmap: Bitmap): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NeoReader")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "neoreader_wallpaper.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        runCatching {
            android.media.MediaScannerConnection.scanFile(
                this,
                arrayOf(file.absolutePath),
                arrayOf("image/png")
            ) { _, _ -> }
        }
        return file.absolutePath
    }

    private fun writeDebugLog(event: String) {
        try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, debugLogName)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val s = readSettingsFromUi()
            FileWriter(f, false).use { w ->
                w.append("event=").append(event).append('\n')
                w.append("time=").append(now).append('\n')
                w.append("selectedWeekStart=").append(selectedWeekStartYmd).append('\n')
                w.append("settings=").append("includeUnread=").append(s.includeUnread.toString())
                    .append(", showChart=").append(s.showChart.toString())
                    .append(", showProgressStatus=").append(s.showProgressStatus.toString())
                    .append(", showAuthor=").append(s.showAuthor.toString())
                    .append(", minDuration=").append(s.minDurationMinutes.toString())
                    .append(", topN=").append(s.topN.toString())
                    .append(", sourceMode=").append(s.sourceMode.name)
                    .append(", wallpaperMode=").append(s.wallpaperMode)
                    .append(", coverFitMode=").append(s.coverFitMode)
                    .append(", progressMode=").append(s.progressMode)
                    .append(", timeUnit=").append(s.timeUnit)
                    .append(", receiptTitle=").append(s.receiptTitle)
                    .append(", receiptTitleSize=").append(s.receiptTitleSize.toString())
                    .append(", receiptBodySize=").append(s.receiptBodySize.toString())
                    .append(", serialNumberMode=").append(s.serialNumberMode)
                    .append(", serialNumberCustom=").append(s.serialNumberCustom)
                    .append(", serialNumberSize=").append(s.serialNumberSize.toString())
                    .append(", footerMode=").append(s.footerMode)
                    .append(", noteText=").append(s.noteText)
                    .append(", titleFont=").append(s.titleFont)
                    .append(", bodyFont=").append(s.bodyFont)
                    .append('\n')
                w.append("lastSavedPath=").append(lastSavedPath ?: "<null>").append('\n')
                w.append("fontCount=").append(systemFonts.size.toString()).append('\n')
                w.append('\n')
                w.append(fontScanReport)
                w.append('\n')
                w.append("barcodeDebug=").append(barcodeDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("metadataDebug=").append(metadataDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("metadataRowsDebug=").append('\n').append(metadataRowsDebugReport.ifBlank { "<empty>" }).append('\n')
                val persisted = contentResolver.persistedUriPermissions
                w.append("persistedUriPermissions=").append(persisted.size.toString()).append('\n')
                persisted.forEachIndexed { i, p ->
                    w.append("persisted[").append(i.toString()).append("]=")
                        .append(p.uri.toString())
                        .append(" read=").append(p.isReadPermission.toString())
                        .append(" write=").append(p.isWritePermission.toString())
                        .append('\n')
                }
            }
        } catch (_: Exception) {
        }
    }
}
