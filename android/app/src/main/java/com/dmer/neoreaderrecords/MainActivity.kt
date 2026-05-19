package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.View
import android.view.ViewGroup
import android.app.DatePickerDialog
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
    private lateinit var noteInput: EditText
    private lateinit var weekStartText: TextView
    private lateinit var weekEndText: TextView
    private lateinit var sourceGroup: RadioGroup
    private lateinit var periodGroup: RadioGroup
    private lateinit var progressModeGroup: RadioGroup
    private lateinit var readingFilterGroup: RadioGroup
    private lateinit var topNGroup: RadioGroup
    private lateinit var timeUnitGroup: RadioGroup
    private lateinit var footerModeGroup: RadioGroup
    private lateinit var chartStyleGroup: RadioGroup
    private lateinit var yAxisModeGroup: RadioGroup
    private lateinit var showPeakLabelCheck: CheckBox
    private lateinit var yAxisMaxInput: EditText
    private lateinit var autoRefreshCheck: CheckBox
    private lateinit var autoModeGroup: RadioGroup
    private lateinit var autoDailyTimeInput: EditText
    private lateinit var autoMinIntervalInput: EditText
    private lateinit var autoModeHintText: TextView
    private lateinit var pickFontDirBtn: Button
    private lateinit var titleFontSpinner: Spinner
    private lateinit var bodyFontSpinner: Spinner
    private lateinit var fontScanText: TextView
    private lateinit var statusText: TextView

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
        val progressMode: String,
        val timeUnit: String,
        val receiptTitle: String,
        val receiptTitleSize: Float,
        val receiptBodySize: Float,
        val footerMode: String,
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
        setupUi()
    }

    override fun onResume() {
        super.onResume()
        // Return from permission/settings pages: refresh font scan and preview context.
        if (!isInitializingUi) {
            validateFontTreePermission()
            reloadFontsFromSources()
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
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
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
        topBar.addView(btnSettings)
        topBar.addView(btnPreview)
        topBar.addView(btnRefreshPreview)

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

        val title = TextView(this).apply {
            text = "阅读壁纸设置"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
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

        val minDurationLabel = TextView(this).apply {
            text = "最小时长阈值（分钟，作用于“按阅读时长事件”）"
        }
        minDurationInput = EditText(this).apply {
            hint = "例如 1"
            setText(prefs.getInt("min_duration_minutes", 1).toString())
        }
        val topNLabel = TextView(this).apply { text = "Top N" }
        topNGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val savedN = prefs.getInt("top_n", 5).coerceAtMost(5)
            addView(RadioButton(context).apply { id = 5003; text = "3"; isChecked = savedN == 3 })
            addView(RadioButton(context).apply { id = 5005; text = "5"; isChecked = savedN == 5 })
        }
        topNInput = EditText(this).apply {
            hint = "自定义TopN(1-5,可空)"
            setText("")
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
            hint = "每日执行时间，例如 22:30"
            setText(prefs.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30")
        }
        autoMinIntervalInput = EditText(this).apply {
            hint = "熄屏触发最小间隔(分钟, 1-240)"
            setText(prefs.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).toString())
        }
        autoModeHintText = TextView(this).apply {
            textSize = 12f
        }
        val autoWarningText = TextView(this).apply {
            text = "提示：熄屏触发会增加唤醒次数与耗电，墨水屏建议优先每日定时。"
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
        val weekEndPickerBtn = Button(this).apply {
            text = "选择结束日期"
            setOnClickListener { openWeekEndDatePicker() }
        }

        val generateButton = Button(this).apply {
            text = "生成并覆盖壁纸文件"
            setOnClickListener { generateAndSaveFromCurrentSettings() }
        }

        statusText = TextView(this).apply {
            text = "设置后点击按钮生成。"
            textSize = 15f
        }

        container.addView(title)
        container.addView(periodLabel)
        container.addView(periodGroup)
        container.addView(includeUnreadCheck)
        container.addView(titleInput)
        container.addView(titleSizeLabel)
        container.addView(titleSizeInput)
        container.addView(bodySizeLabel)
        container.addView(bodySizeInput)
        container.addView(showProgressStatusCheck)
        container.addView(progressModeLabel)
        container.addView(progressModeGroup)
        container.addView(showAuthorCheck)
        container.addView(showChartCheck)
        container.addView(sourceLabel)
        container.addView(sourceGroup)
        container.addView(minDurationLabel)
        container.addView(minDurationInput)
        container.addView(readingFilterLabel)
        container.addView(readingFilterGroup)
        container.addView(topNLabel)
        container.addView(topNGroup)
        container.addView(topNInput)
        container.addView(timeUnitLabel)
        container.addView(timeUnitGroup)
        container.addView(chartStyleLabel)
        container.addView(chartRuleHint)
        container.addView(chartStyleGroup)
        container.addView(showPeakLabelCheck)
        container.addView(yAxisModeLabel)
        container.addView(yAxisModeGroup)
        container.addView(yAxisMaxInput)
        container.addView(autoSectionLabel)
        container.addView(autoRefreshCheck)
        container.addView(autoModeGroup)
        container.addView(autoDailyTimeInput)
        container.addView(autoMinIntervalInput)
        container.addView(autoModeHintText)
        container.addView(autoWarningText)
        container.addView(footerLabel)
        container.addView(footerModeGroup)
        container.addView(noteInput)
        container.addView(titleFontLabel)
        container.addView(titleFontSpinner)
        container.addView(bodyFontLabel)
        container.addView(bodyFontSpinner)
        container.addView(pickFontDirBtn)
        container.addView(fontScanText)
        container.addView(weekLabel)
        container.addView(weekStartText)
        container.addView(weekEndText)
        container.addView(weekPickerBtn)
        container.addView(weekEndPickerBtn)
        container.addView(generateButton)
        container.addView(statusText)

        updateAutoRefreshHint()
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
        showChartCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showPeakLabelCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        sourceGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        periodGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        readingFilterGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        progressModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        topNGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        timeUnitGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        chartStyleGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        yAxisModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        footerModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        autoRefreshCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        autoModeGroup.setOnCheckedChangeListener { _, _ ->
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

    private fun generateAndSaveFromCurrentSettings() {
        val settings = readSettingsFromUi()
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val (bmp, result) = renderWallpaperPreview(settings)
        previewBitmap = bmp
        val saved = saveBitmapToPictures(bmp)
        lastSavedPath = saved
        statusText.text = "已生成并覆盖文件\n$result\n路径: $saved"
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
        writeDebugLog("manual_refresh_preview")
        showPreviewPage()
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
        val topN = (topNInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 5))
            ?: when (topNGroup.checkedRadioButtonId) { 5003 -> 3; else -> 5 }
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
        val progressMode = when (progressModeGroup.checkedRadioButtonId) {
            6102 -> "PERCENT"
            else -> "PAGES"
        }
        val timeUnit = when (timeUnitGroup.checkedRadioButtonId) {
            2002 -> "MINUTE"
            else -> "HOUR"
        }
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
        val titleFont = fontSpec(titleFontSpinner.selectedItem?.toString() ?: "SERIF_BOLD")
        val bodyFont = fontSpec(bodyFontSpinner.selectedItem?.toString() ?: "MONO")
        return Settings(includeUnread, showChart, showProgressStatus, showAuthor, minDurationMinutes, topN, weekStart, weekEnd, periodMode, readingFilterMode, sourceMode, progressMode, timeUnit, receiptTitle, receiptTitleSize, receiptBodySize, footerMode, noteText, chartStyleMode, showPeakLabel, yAxisMode, yAxisFixedMaxMinutes, titleFont, bodyFont)
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
            .putString("progress_mode", settings.progressMode)
            .putString("time_unit", settings.timeUnit)
            .putString("receipt_title", settings.receiptTitle)
            .putFloat("receipt_title_size", settings.receiptTitleSize)
            .putFloat("receipt_body_size", settings.receiptBodySize)
            .putString("footer_mode", settings.footerMode)
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
                    .append(", progressMode=").append(s.progressMode)
                    .append(", timeUnit=").append(s.timeUnit)
                    .append(", receiptTitle=").append(s.receiptTitle)
                    .append(", receiptTitleSize=").append(s.receiptTitleSize.toString())
                    .append(", receiptBodySize=").append(s.receiptBodySize.toString())
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
