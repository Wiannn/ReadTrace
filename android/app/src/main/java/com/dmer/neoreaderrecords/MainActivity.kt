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
    private lateinit var readingFilterGroup: RadioGroup
    private lateinit var topNGroup: RadioGroup
    private lateinit var timeUnitGroup: RadioGroup
    private lateinit var footerModeGroup: RadioGroup
    private lateinit var chartStyleGroup: RadioGroup
    private lateinit var yAxisModeGroup: RadioGroup
    private lateinit var showPeakLabelCheck: CheckBox
    private lateinit var yAxisMaxInput: EditText
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
    enum class PeriodMode { TODAY, THIS_WEEK, LAST_WEEK, LAST_7_DAYS, LAST_30_DAYS, CUSTOM }
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
        container.addView(chartStyleGroup)
        container.addView(showPeakLabelCheck)
        container.addView(yAxisModeLabel)
        container.addView(yAxisModeGroup)
        container.addView(yAxisMaxInput)
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
    }

    private fun applySettingsPreview() {
        val settings = readSettingsFromUi()
        saveSettings(settings)
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
        val (bmp, result) = renderWallpaperPreview(settings)
        previewBitmap = bmp
        val saved = saveBitmapToPictures(bmp)
        lastSavedPath = saved
        statusText.text = "已生成并覆盖文件\n$result\n路径: $saved"
        refreshPreview()
        showPreviewPage()
        writeDebugLog("generated_saved")
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
        return Settings(includeUnread, showChart, showProgressStatus, showAuthor, minDurationMinutes, topN, weekStart, weekEnd, periodMode, readingFilterMode, sourceMode, timeUnit, receiptTitle, receiptTitleSize, receiptBodySize, footerMode, noteText, chartStyleMode, showPeakLabel, yAxisMode, yAxisFixedMaxMinutes, titleFont, bodyFont)
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

    private data class WeekStats(val totalMs: Long, val perDay: LongArray)

    private fun renderWallpaperPreview(settings: Settings): Pair<Bitmap, String> {
        val week = resolvePeriodRange(settings) ?: return "日期格式错误，请用 yyyy-MM-dd"
            .let { Pair(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), it) }
        val topBooks = queryTopBooksInWeek(week.first, week.second, settings.topN, settings.includeUnread, settings.readingFilterMode)
        val weekStats = queryWeekStatsByMode(week.first, week.second, settings)

        val width = resources.displayMetrics.widthPixels.coerceAtLeast(1200)
        val height = resources.displayMetrics.heightPixels.coerceAtLeast(1600)

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        drawReceiptWallpaper(canvas, width, height, week.first, week.second, weekStats, topBooks, settings)
        val message = buildString {
            append("已生成\n")
            append("范围: ${fmt(week.first)} - ${fmt(week.second)}\n")
            append("周期: ${settings.periodMode}\n")
            append("口径: ${settings.sourceMode}\n")
            append("阈值: >= ${settings.minDurationMinutes} 分钟\n")
            append("TopN: ${settings.topN}\n")
            append("包含未读: ${settings.includeUnread}\n")
            append("周总时长: ")
            append(formatDuration(weekStats.totalMs, settings.timeUnit))
        }
        return Pair(bmp, message)
    }

    private fun queryWeekStatsByMode(start: Long, end: Long, settings: Settings): WeekStats {
        return when (settings.sourceMode) {
            DataSourceMode.DURATION -> queryWeekDurationStats(start, end, settings.minDurationMinutes)
            DataSourceMode.PATH_SESSION -> queryWeekPathSessionStats(start, end)
            DataSourceMode.METADATA_ACCESS -> queryWeekMetadataAccessStats(start, end)
        }
    }

    private fun queryWeekDurationStats(start: Long, end: Long, minDurationMinutes: Int): WeekStats {
        val perDay = LongArray(7)
        var total = 0L
        val minMs = minDurationMinutes * 60L * 1000L

        val projection = arrayOf("eventTime", "durationTime")
        contentResolver.query(
            statsUri,
            projection,
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: continue
                val dur = c.getString(c.getColumnIndexOrThrow("durationTime"))?.toLongOrNull() ?: 0L
                if (dur < minMs) continue
                total += dur
                val idx = bucketIndex(start, end, event)
                if (idx in 0..6) perDay[idx] += dur
            }
        }
        return WeekStats(total, perDay)
    }

    private fun queryWeekPathSessionStats(start: Long, end: Long): WeekStats {
        val perDay = LongArray(7)
        var total = 0L

        val projection = arrayOf("eventTime")
        contentResolver.query(
            statsUri,
            projection,
            "eventTime >= ? AND eventTime <= ? AND path IS NOT NULL AND path != ''",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: continue
                total += 60_000L
                val idx = bucketIndex(start, end, event)
                if (idx in 0..6) perDay[idx] += 60_000L
            }
        }
        return WeekStats(total, perDay)
    }

    private fun queryWeekMetadataAccessStats(start: Long, end: Long): WeekStats {
        val perDay = LongArray(7)
        var total = 0L

        val projection = arrayOf("lastAccess")
        contentResolver.query(
            metadataUri,
            projection,
            "lastAccess >= ? AND lastAccess <= ?",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("lastAccess"))?.toLongOrNull() ?: continue
                total += 60_000L
                val idx = bucketIndex(start, end, event)
                if (idx in 0..6) perDay[idx] += 60_000L
            }
        }
        return WeekStats(total, perDay)
    }

    private fun bucketIndex(start: Long, end: Long, event: Long): Int {
        if (event < start || event > end) return -1
        val span = (end - start + 1).coerceAtLeast(1L)
        val offset = (event - start).coerceAtLeast(0L)
        val idx = ((offset * 7L) / span).toInt()
        return idx.coerceIn(0, 6)
    }

    private fun queryTopBooksInWeek(start: Long, end: Long, limit: Int, includeUnread: Boolean, filter: ReadingFilterMode): List<BookItem> {
        val result = mutableListOf<BookItem>()
        val richProjection = arrayOf(
            "title", "authors", "progress", "readingStatus", "nativeAbsolutePath", "lastAccess"
        )
        val baseProjection = arrayOf("title", "progress", "readingStatus", "nativeAbsolutePath", "lastAccess")

        val selection = buildString {
            append("lastAccess >= ? AND lastAccess <= ?")
            if (!includeUnread) append(" AND (readingStatus = 1 OR readingStatus = 2)")
            when (filter) {
                ReadingFilterMode.READING_ONLY -> append(" AND readingStatus = 1")
                ReadingFilterMode.FINISHED_ONLY -> append(" AND readingStatus = 2")
                else -> {}
            }
        }

        fun fillFromCursor(c: android.database.Cursor) {
            val cols = c.columnNames?.joinToString(",") ?: "<null>"
            val sampleAuthors = mutableListOf<String>()
            val titleIdx = c.getColumnIndex("title")
            val authorsIdx = c.getColumnIndex("authors")
            val progressIdx = c.getColumnIndex("progress")
            val statusIdx = c.getColumnIndex("readingStatus")
            val pathIdx = c.getColumnIndex("nativeAbsolutePath")
            while (c.moveToNext() && result.size < limit) {
                val title = if (titleIdx >= 0) c.getString(titleIdx) ?: "<未知书名>" else "<未知书名>"
                val authors = if (authorsIdx >= 0) c.getString(authorsIdx) else null
                val authorText = authors?.trim()?.ifBlank { null }
                if (sampleAuthors.size < 3) {
                    sampleAuthors.add("authors=${authors ?: "-"}")
                }
                val progress = if (progressIdx >= 0) c.getString(progressIdx) else null
                val status = if (statusIdx >= 0) c.getString(statusIdx)?.toIntOrNull() ?: 0 else 0
                val path = if (pathIdx >= 0) c.getString(pathIdx) else null
                result.add(BookItem(title, authorText, progress, status, path))
            }
            metadataDebugReport = "columns=$cols | authorSamples=${sampleAuthors.joinToString(" || ")}"
        }

        try {
            contentResolver.query(
                metadataUri,
                richProjection,
                selection,
                arrayOf(start.toString(), end.toString()),
                "readingStatus DESC, lastAccess DESC"
            )?.use { fillFromCursor(it) }
        } catch (_: Exception) {
            contentResolver.query(
                metadataUri,
                baseProjection,
                selection,
                arrayOf(start.toString(), end.toString()),
                "readingStatus DESC, lastAccess DESC"
            )?.use { fillFromCursor(it) }
        }
        metadataRowsDebugReport = dumpMetadataRows(start, end, 4)
        return result
    }

    private fun dumpMetadataRows(start: Long, end: Long, sampleRows: Int): String {
        return try {
            val sb = StringBuilder()
            contentResolver.query(
                metadataUri,
                null,
                "lastAccess >= ? AND lastAccess <= ?",
                arrayOf(start.toString(), end.toString()),
                "lastAccess DESC"
            )?.use { c ->
                val cols = c.columnNames ?: emptyArray()
                sb.append("metaColumns(").append(cols.size).append(")=").append(cols.joinToString(",")).append('\n')
                var row = 0
                while (c.moveToNext() && row < sampleRows) {
                    sb.append("metaRow[").append(row).append("]: ")
                    val pairs = mutableListOf<String>()
                    for (name in cols) {
                        val idx = c.getColumnIndex(name)
                        if (idx >= 0) {
                            val v = c.getString(idx)
                            pairs.add("$name=${v ?: "<null>"}")
                        }
                    }
                    sb.append(pairs.joinToString(" | ")).append('\n')
                    row++
                }
                if (row == 0) sb.append("metaRow=<none>\n")
            } ?: sb.append("metaQuery=<null cursor>\n")
            sb.toString()
        } catch (e: Exception) {
            "metaDumpError=${e.javaClass.simpleName}:${e.message}"
        }
    }

    private fun formatDuration(ms: Long, unit: String): String {
        return if (unit == "MINUTE") {
            String.format(Locale.US, "%.0f分钟", ms / 60000.0)
        } else {
            String.format(Locale.US, "%.2f小时", ms / 3600000.0)
        }
    }

    private fun drawReceiptWallpaper(
        canvas: Canvas,
        w: Int,
        h: Int,
        weekStart: Long,
        weekEnd: Long,
        stats: WeekStats,
        books: List<BookItem>,
        settings: Settings
    ) {
        // Global adaptive scale: keep layout proportion while fitting all enabled sections.
        val bookLines = books.size * (80f + (if (settings.showAuthor) 42f else 0f) + (if (settings.showProgressStatus) 50f else 0f))
        val headerBlock = 110f + 30f + 250f + 48f + 28f
        val summaryBlock = 30f + 60f + 50f
        val chartBlock = if (settings.showChart) 260f else 0f
        val hasFooter = settings.footerMode != "NONE" && settings.noteText.isNotBlank()
        val footerBlock = if (!hasFooter) 0f else if (settings.footerMode == "BARCODE") 280f else 130f
        val requiredH = headerBlock + bookLines + summaryBlock + chartBlock + footerBlock + 120f
        val fitScale = (h.toFloat() - 40f) / requiredH
        val gs = fitScale.coerceIn(0.52f, 1f)
        fun s(v: Float): Float = v * gs

        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val titleFace = resolveTypeface(settings.titleFont, true)
        val bodyFace = resolveTypeface(settings.bodyFont, false)
        val titlePaint = Paint(black).apply { textSize = s(settings.receiptTitleSize); typeface = titleFace }
        val h1 = Paint(black).apply { textSize = s((settings.receiptBodySize * 1.35f).coerceIn(24f, 90f)); typeface = Typeface.create(bodyFace, Typeface.BOLD) }
        val text = Paint(black).apply { textSize = s(settings.receiptBodySize); typeface = bodyFace }
        val mono = Paint(black).apply { textSize = s((settings.receiptBodySize * 0.88f).coerceIn(16f, 56f)); typeface = bodyFace }
        val line = Paint(black).apply { strokeWidth = s(3f) }

        var y = s(110f)
        canvas.drawText(shortTitle(settings.receiptTitle, 12), w - s(360f), y, titlePaint)

        y += s(30f)
        canvas.drawText("单号: ${SimpleDateFormat("MMdd", Locale.US).format(Date())}", s(60f), y + s(40f), h1)
        canvas.drawText("操作编号: ${System.currentTimeMillis().toString().takeLast(6)}", s(60f), y + s(95f), text)
        canvas.drawText("时间: ${fmt(weekStart)} - ${fmt(weekEnd)}", s(60f), y + s(145f), text)
        canvas.drawText("设备: Onyx Leaf5", s(60f), y + s(195f), text)

        canvas.drawText("时长: ${formatDuration(stats.totalMs, settings.timeUnit)}", w - s(520f), y + s(145f), h1)
        canvas.drawText("书籍: ${books.size}", w - s(520f), y + s(195f), text)

        y += s(250f)
        canvas.drawLine(s(40f), y, w - s(40f), y, line)
        y += s(48f)
        canvas.drawText("品类", s(60f), y, text)
        canvas.drawText("数量", w - s(260f), y, text)
        canvas.drawText("单位", w - s(140f), y, text)

        y += s(28f)
        canvas.drawLine(s(40f), y, w - s(40f), y, line)

        books.forEachIndexed { idx, b ->
            y += s(80f)
            canvas.drawText("NO.${(idx + 1).toString().padStart(2, '0')}", s(60f), y, h1)
            canvas.drawText(shortTitle(b.title, 16), s(260f), y, h1)
            canvas.drawText("1", w - s(260f), y, h1)
            canvas.drawText("本", w - s(140f), y, h1)
            if (settings.showAuthor) {
                y += s(42f)
                canvas.drawText("作者:${shortTitle(b.author ?: "未知", 20)}", s(260f), y, mono)
            }
            if (settings.showProgressStatus) {
                y += s(50f)
                val st = when (b.status) { 2 -> "已读完"; 1 -> "阅读中"; else -> "未读" }
                canvas.drawText("进度:${b.progress ?: "-"}  状态:$st", s(260f), y, mono)
            }
        }

        y += s(30f)
        canvas.drawLine(s(40f), y, w - s(40f), y, line)
        y += s(60f)
        canvas.drawText("周日均: ${String.format(Locale.US, "%.0f分钟", stats.totalMs / 7.0 / 60000.0)}", s(60f), y, h1)
        canvas.drawText("本周合计: ${formatDuration(stats.totalMs, settings.timeUnit)}", w - s(560f), y, h1)

        y += s(50f)
        val footerReserved = if (!hasFooter) 0f else if (settings.footerMode == "BARCODE") s(260f) else s(120f)
        val bottomSafe = (h - s(56f)).toFloat()
        val availableChartH = ((bottomSafe - footerReserved - s(24f)) - y).coerceAtLeast(s(70f))
        val maxChartBottom = y + availableChartH
        var chartBottomUsed = y
        if (settings.showChart) {
        val days = arrayOf("日", "一", "二", "三", "四", "五", "六")
        val chartLeft = s(80f)
        val chartRight = (w - s(80f)).toFloat()
        val chartTop = y
        val desiredChartH = s(220f)
        val chartBottom = (chartTop + desiredChartH).coerceAtMost(maxChartBottom)
        chartBottomUsed = chartBottom
        val autoMax = (stats.perDay.maxOrNull() ?: 1L).toFloat().coerceAtLeast(1f)
        val max = if (settings.yAxisMode == YAxisMode.FIXED) (settings.yAxisFixedMaxMinutes * 60000f).coerceAtLeast(1f) else autoMax
        val peakIdx = stats.perDay.indices.maxByOrNull { stats.perDay[it] } ?: 0
        canvas.drawLine(chartLeft, chartBottom, chartRight, chartBottom, line)
        canvas.drawLine(chartLeft, chartTop, chartLeft, chartBottom, line)
        var prevX = 0f
        var prevY = 0f
        for (i in 0..6) {
            val x = chartLeft + i * (chartRight - chartLeft) / 6f
            val yv = chartBottom - ((stats.perDay[i] / max) * (chartBottom - chartTop))
            if (settings.chartStyleMode == ChartStyleMode.BAR) {
                val bw = s(24f)
                canvas.drawRect(x - bw / 2f, yv, x + bw / 2f, chartBottom, black)
            } else {
                canvas.drawCircle(x, yv, s(5f), black)
                if (i > 0) canvas.drawLine(prevX, prevY, x, yv, line)
            }
            canvas.drawText("周${days[i]}", x - s(22f), chartBottom + s(42f), mono)
            if (settings.showPeakLabel && i == peakIdx) {
                canvas.drawText(String.format(Locale.US, "%.0f分", stats.perDay[i] / 60000.0), x - s(28f), yv - s(14f), mono)
            }
            prevX = x
            prevY = yv
        }
            y = chartBottom + s(56f)
        }

        if (hasFooter) {
            val baseY = if (settings.showChart) (chartBottomUsed + s(64f)) else (y + s(16f))
            canvas.drawLine(s(40f), baseY, w - s(40f), baseY, line)
            barcodeDebugReport = "footer:has=true mode=${settings.footerMode} baseY=$baseY chartBottom=$chartBottomUsed bottomSafe=$bottomSafe reserved=$footerReserved noteLen=${settings.noteText.length}"
            if (settings.footerMode == "NOTE") {
                canvas.drawText("备注: ${shortTitle(settings.noteText, 40)}", s(60f), baseY + s(58f), text)
            } else if (settings.footerMode == "BARCODE") {
                val qr = buildQrBitmap(settings.noteText, s(168f).toInt().coerceAtLeast(120))
                if (qr != null) {
                    val qrX = s(60f)
                    val qrY = baseY + s(18f)
                    canvas.drawBitmap(qr, qrX, qrY, null)
                    val decorX = qrX + qr.width + s(24f)
                    val decorY = qrY + s(10f)
                    val decorW = (w - decorX - s(60f)).coerceAtLeast(s(220f))
                    val decorH = (qr.height - s(20f)).toFloat().coerceAtLeast(s(60f))
                    drawBarcodeDecor(canvas, decorX, decorY, decorW, decorH, settings.noteText, black)
                    val textY = qrY + qr.height + s(34f)
                    canvas.drawText(shortTitle(settings.noteText, 36), qrX, textY, mono)
                    barcodeDebugReport = "$barcodeDebugReport | qrDraw=x=$qrX y=$qrY s=${qr.width} decor=x=$decorX y=$decorY w=$decorW h=$decorH textY=$textY"
                } else {
                    canvas.drawText("二维码生成失败，备注: ${shortTitle(settings.noteText, 36)}", s(60f), baseY + s(58f), text)
                }
            }
        } else {
            barcodeDebugReport = "footer:has=false mode=${settings.footerMode} chartBottom=$chartBottomUsed bottomSafe=$bottomSafe reserved=$footerReserved noteLen=${settings.noteText.length}"
        }
    }

    private fun buildQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to "UTF-8")
            val compact = if (content.length > 120) content.take(120) else content
            val matrix: BitMatrix = MultiFormatWriter().encode(compact, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            barcodeDebugReport = "$barcodeDebugReport | qr=QR_CODE ok contentLen=${content.length} usedLen=${compact.length} size=${size}x${size}"
            bmp
        } catch (e: Exception) {
            barcodeDebugReport = "$barcodeDebugReport | qr=QR_CODE fail=${e.javaClass.simpleName}:${e.message}"
            null
        }
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

    private fun resolvePeriodRange(settings: Settings): Pair<Long, Long>? {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        return when (settings.periodMode) {
            PeriodMode.TODAY -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val start = c.timeInMillis
                c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
                start to c.timeInMillis
            }
            PeriodMode.THIS_WEEK -> parseWeek(currentWeekStartYmd())
            PeriodMode.LAST_WEEK -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                parseYmd(currentWeekStartYmd())?.let { c.timeInMillis = it } ?: return null
                c.add(Calendar.DAY_OF_MONTH, -7)
                parseWeek(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis)))
            }
            PeriodMode.LAST_7_DAYS -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, -6)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to end
            }
            PeriodMode.LAST_30_DAYS -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, -29)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to end
            }
            PeriodMode.CUSTOM -> {
                val s = parseYmd(settings.weekStartYmd) ?: return null
                val e = parseYmd(settings.weekEndYmd) ?: return null
                val sc = Calendar.getInstance(TimeZone.getDefault()); sc.timeInMillis = s
                sc.set(Calendar.HOUR_OF_DAY, 0); sc.set(Calendar.MINUTE, 0); sc.set(Calendar.SECOND, 0); sc.set(Calendar.MILLISECOND, 0)
                val ec = Calendar.getInstance(TimeZone.getDefault()); ec.timeInMillis = e
                ec.set(Calendar.HOUR_OF_DAY, 23); ec.set(Calendar.MINUTE, 59); ec.set(Calendar.SECOND, 59); ec.set(Calendar.MILLISECOND, 999)
                if (sc.timeInMillis > ec.timeInMillis) null else (sc.timeInMillis to ec.timeInMillis)
            }
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

    private fun fmt(ts: Long): String = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(ts))

    private fun shortTitle(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

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

    private fun resolveTypeface(spec: String, boldDefault: Boolean): Typeface {
        return when (spec) {
            "SERIF_BOLD" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            "SANS" -> Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            "MONO" -> Typeface.create(Typeface.MONOSPACE, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            else -> {
                try {
                    if (spec.startsWith("content://")) {
                        contentResolver.openFileDescriptor(Uri.parse(spec), "r")?.use { pfd ->
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
