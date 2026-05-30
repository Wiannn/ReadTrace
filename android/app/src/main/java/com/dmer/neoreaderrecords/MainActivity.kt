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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.SeekBar
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
    private lateinit var einkUiPage: View
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
        val btnEinkUi = Button(this).apply {
            text = "新UI"
            setOnClickListener { showEinkUiPage() }
        }
        styleEinkButton(btnSettings)
        styleEinkButton(btnPreview)
        styleEinkButton(btnRefreshPreview)
        styleEinkButton(btnGenerateWallpaper)
        styleEinkButton(btnEinkUi)
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
        topBar.addView(btnEinkUi)
        topBar.addView(changeStateText)

        settingsPage = buildSettingsPage(prefs)
        previewPage = buildPreviewPage()
        einkUiPage = buildEinkUiPage()

        root.addView(topBar)
        root.addView(settingsPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(previewPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(einkUiPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showSettingsPage()
        isInitializingUi = false
        applySettingsPreview()
        writeDebugLog("setupUi_done")
    }

    private fun buildSettingsPage(prefs: android.content.SharedPreferences): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 80)
        }
        val hiddenHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun inkBorder(stroke: Int = 4): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(stroke, Color.BLACK)
            }
        }

        fun createDivider(thickness: Int = 4, topMargin: Int = 0, bottomMargin: Int = 24) = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness).apply {
                setMargins(0, topMargin, 0, bottomMargin)
            }
        }

        fun addSectionTitle(text: String, hint: String? = null) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 24f
                setTextColor(Color.BLACK)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, 48, 0, if (hint == null) 16 else 6)
            })
            if (hint != null) {
                root.addView(TextView(this).apply {
                    this.text = hint
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 0, 0, 24)
                })
            }
            root.addView(createDivider(4, 0, 32))
        }

        fun addHint(hint: String): TextView {
            return TextView(this).apply {
                text = hint
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, 16)
                root.addView(this)
            }
        }

        fun makeCheck(checked: Boolean): CheckBox {
            return CheckBox(this).apply {
                isChecked = checked
                hiddenHost.addView(this)
            }
        }

        fun makeInput(text: String): EditText {
            return EditText(this).apply {
                setText(text)
                hiddenHost.addView(this)
            }
        }

        fun makeRadioGroup(options: List<Pair<Int, String>>, selectedId: Int, orientation: Int = RadioGroup.VERTICAL): RadioGroup {
            return RadioGroup(this).apply {
                this.orientation = orientation
                options.forEach { (id, label) ->
                    addView(RadioButton(context).apply {
                        this.id = id
                        text = label
                    })
                }
                check(selectedId)
                hiddenHost.addView(this)
            }
        }

        fun selectedId(saved: String, fallback: Int, pairs: List<Pair<Int, String>>, names: List<String>): Int {
            val index = names.indexOf(saved)
            return if (index >= 0) pairs.getOrNull(index)?.first ?: fallback else fallback
        }

        fun bindToggle(label: String, check: CheckBox): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 32)
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val box = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(64, 64)
                setPadding(12, 12, 12, 12)
                background = inkBorder(4)
            }
            val inner = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            fun render() { inner.setBackgroundColor(if (check.isChecked) Color.BLACK else Color.TRANSPARENT) }
            render()
            box.addView(inner)
            row.addView(box)
            row.setOnClickListener {
                check.isChecked = !check.isChecked
                render()
            }
            root.addView(row)
            return row
        }

        fun bindSegmented(
            label: String,
            group: RadioGroup,
            options: List<Pair<Int, String>>,
            isVertical: Boolean = false
        ): LinearLayout {
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
            wrap.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(0, 16, 0, 16)
            })
            val allViews = mutableListOf<Pair<Int, TextView>>()
            fun render() {
                allViews.forEach { (id, tv) ->
                    val selected = group.checkedRadioButtonId == id
                    tv.setBackgroundColor(if (selected) Color.BLACK else Color.TRANSPARENT)
                    tv.setTextColor(if (selected) Color.WHITE else Color.BLACK)
                }
            }
            if (isVertical) {
                val segmented = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = inkBorder(4)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 32)
                    }
                }
                options.forEachIndexed { index, (id, text) ->
                    val tv = TextView(this).apply {
                        this.text = text
                        textSize = 18f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setPadding(32, 24, 32, 24)
                        setOnClickListener {
                            group.check(id)
                            render()
                        }
                    }
                    allViews.add(id to tv)
                    segmented.addView(tv)
                    if (index < options.size - 1) {
                        segmented.addView(View(this).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4)
                        })
                    }
                }
                wrap.addView(segmented)
            } else {
                val segmented = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = inkBorder(4)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, 32)
                    }
                }
                options.chunked(3).forEachIndexed { rowIndex, rowOptions ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    rowOptions.forEachIndexed { colIndex, (id, text) ->
                        val tv = TextView(this).apply {
                            this.text = text
                            textSize = 16f
                            setTypeface(Typeface.DEFAULT_BOLD)
                            gravity = Gravity.CENTER
                            setPadding(16, 24, 16, 24)
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            setOnClickListener {
                                group.check(id)
                                render()
                            }
                        }
                        allViews.add(id to tv)
                        row.addView(tv)
                        if (colIndex < rowOptions.size - 1) {
                            row.addView(View(this).apply {
                                setBackgroundColor(Color.BLACK)
                                layoutParams = LinearLayout.LayoutParams(4, ViewGroup.LayoutParams.MATCH_PARENT)
                            })
                        }
                    }
                    while (rowOptions.size < 3 && row.childCount < 5) {
                        row.addView(View(this).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(4, ViewGroup.LayoutParams.MATCH_PARENT)
                        })
                        row.addView(View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                    }
                    segmented.addView(row)
                    if (rowIndex < options.chunked(3).size - 1) {
                        segmented.addView(View(this).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4)
                        })
                    }
                }
                wrap.addView(segmented)
            }
            render()
            root.addView(wrap)
            return wrap
        }

        fun bindSlider(label: String, target: EditText, min: Int, max: Int): LinearLayout {
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 32)
            }
            val headerRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val valueText = TextView(this).apply {
                textSize = 24f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.BLACK)
            }
            var bar: SeekBar? = null
            fun setValue(v: Int, fromSeek: Boolean = false) {
                val next = v.coerceIn(min, max)
                valueText.text = next.toString()
                if (target.text.toString() != next.toString()) {
                    target.setText(next.toString())
                    target.setSelection(target.text.length)
                }
                if (!fromSeek) bar?.progress = next - min
            }
            headerRow.addView(valueText)
            wrap.addView(headerRow)
            val initial = target.text.toString().trim().toIntOrNull()?.coerceIn(min, max) ?: min
            bar = SeekBar(this).apply {
                this.max = max - min
                progress = initial - min
                setPadding(0, 32, 0, 32)
                progressDrawable?.setTint(Color.BLACK)
                thumb?.setTint(Color.BLACK)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (fromUser) setValue(progress + min, fromSeek = true)
                        else valueText.text = (progress + min).toString()
                    }
                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            setValue(initial)
            target.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val v = s?.toString()?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: min
                    valueText.text = v.toString()
                    if (bar?.progress != v - min) bar?.progress = v - min
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            bar?.let { wrap.addView(it) }
            root.addView(wrap)
            return wrap
        }

        fun openTextEditDialog(title: String, target: EditText, numericOnly: Boolean = false, maxDigits: Int? = null) {
            val edit = EditText(this).apply {
                setText(target.text.toString())
                setSelection(text.length)
                textSize = 20f
                if (numericOnly) inputType = InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    val raw = edit.text.toString()
                    val next = if (numericOnly) raw.filter { it.isDigit() }.let { v -> maxDigits?.let { v.take(it) } ?: v } else raw
                    target.setText(next)
                    target.setSelection(target.text.length)
                }
                .show()
        }

        fun bindInputRow(label: String, valueProvider: () -> String, onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(32, 40, 32, 40)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, 16, 0, 32)
                }
                background = inkBorder(4)
                setOnClickListener { onClick?.invoke() }
            }
            box.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(this).apply {
                text = valueProvider()
                textSize = 20f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(value)
            root.addView(box)
            return box to value
        }

        fun bindEditRow(label: String, target: EditText, numericOnly: Boolean = false, maxDigits: Int? = null): LinearLayout {
            val (row, value) = bindInputRow(label, { target.text.toString().ifBlank { "点击设置" } }) {
                openTextEditDialog(label, target, numericOnly, maxDigits)
            }
            target.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    value.text = target.text.toString().ifBlank { "点击设置" }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            return row
        }

        fun bindSpinnerRow(label: String, spinner: Spinner): LinearLayout {
            lateinit var value: TextView
            val (row, valueView) = bindInputRow(label, {
                fontLabel(spinner.selectedItem?.toString() ?: "") + " ▼"
            }) {
                val labels = systemFonts.map { fontLabel(it) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(labels) { _, which ->
                        spinner.setSelection(which)
                        value.text = "${labels[which]} ▼"
                    }
                    .show()
            }
            value = valueView
            return row
        }

        fun buildFontSpinner(savedKey: String, fallback: String): Spinner {
            return Spinner(this).apply {
                adapter = buildFontAdapter(systemFonts)
                val saved = prefs.getString(savedKey, fallback) ?: fallback
                setSelection(findSpinnerIndexBySpec(saved))
                hiddenHost.addView(this)
            }
        }

        root.addView(TextView(this).apply {
            text = "阅读壁纸设置 (墨水屏新版)"
            textSize = 28f
            setTextColor(Color.BLACK)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, 40)
        })

        val periodOptions = listOf(4000 to "当天", 4006 to "昨天", 4001 to "本周", 4002 to "上周", 4003 to "最近7天", 4004 to "最近30天", 4005 to "自定义起止")
        val periodNames = listOf(PeriodMode.TODAY.name, PeriodMode.YESTERDAY.name, PeriodMode.THIS_WEEK.name, PeriodMode.LAST_WEEK.name, PeriodMode.LAST_7_DAYS.name, PeriodMode.LAST_30_DAYS.name, PeriodMode.CUSTOM.name)
        val savedPeriod = prefs.getString("period_mode", PeriodMode.THIS_WEEK.name) ?: PeriodMode.THIS_WEEK.name
        periodGroup = makeRadioGroup(periodOptions, selectedId(savedPeriod, 4001, periodOptions, periodNames))

        val sourceOptions = listOf(1001 to "按阅读时长事件（推荐）", 1002 to "按有路径会话", 1003 to "按Metadata最近访问")
        val sourceNames = listOf(DataSourceMode.DURATION.name, DataSourceMode.PATH_SESSION.name, DataSourceMode.METADATA_ACCESS.name)
        sourceGroup = makeRadioGroup(sourceOptions, selectedId(prefs.getString("source_mode", DataSourceMode.DURATION.name) ?: DataSourceMode.DURATION.name, 1001, sourceOptions, sourceNames))

        val wallpaperOptions = listOf(1201 to "统计壁纸", 1202 to "当前阅读封面(实验性,较耗电)", 1203 to "自动(熄屏优先封面)(实验性)")
        val wallpaperNames = listOf("STATS", "COVER", "AUTO_COVER")
        wallpaperModeGroup = makeRadioGroup(wallpaperOptions, selectedId(prefs.getString("wallpaper_mode", "STATS") ?: "STATS", 1201, wallpaperOptions, wallpaperNames))

        val coverFitOptions = listOf(1211 to "完整显示", 1212 to "铺满裁切")
        val coverFitNames = listOf("FIT", "CROP")
        coverFitModeGroup = makeRadioGroup(coverFitOptions, selectedId(prefs.getString("cover_fit_mode", "FIT") ?: "FIT", 1211, coverFitOptions, coverFitNames), RadioGroup.HORIZONTAL)

        val timeUnitOptions = listOf(2001 to "小时", 2002 to "分钟")
        val timeUnitNames = listOf("HOUR", "MINUTE")
        timeUnitGroup = makeRadioGroup(timeUnitOptions, selectedId(prefs.getString("time_unit", "HOUR") ?: "HOUR", 2001, timeUnitOptions, timeUnitNames), RadioGroup.HORIZONTAL)

        val readingFilterOptions = listOf(6001 to "全部", 6002 to "仅在读", 6003 to "仅已读完")
        val readingFilterNames = listOf(ReadingFilterMode.ALL.name, ReadingFilterMode.READING_ONLY.name, ReadingFilterMode.FINISHED_ONLY.name)
        readingFilterGroup = makeRadioGroup(readingFilterOptions, selectedId(prefs.getString("reading_filter_mode", ReadingFilterMode.ALL.name) ?: ReadingFilterMode.ALL.name, 6001, readingFilterOptions, readingFilterNames), RadioGroup.HORIZONTAL)

        val progressOptions = listOf(6101 to "页数", 6102 to "百分比")
        val progressNames = listOf("PAGES", "PERCENT")
        progressModeGroup = makeRadioGroup(progressOptions, selectedId(prefs.getString("progress_mode", "PAGES") ?: "PAGES", 6101, progressOptions, progressNames), RadioGroup.HORIZONTAL)

        val serialOptions = listOf(2011 to "月日", 2012 to "随机", 2013 to "自定义")
        val serialNames = listOf("DATE", "RANDOM", "CUSTOM")
        serialModeGroup = makeRadioGroup(serialOptions, selectedId(prefs.getString("serial_number_mode", "DATE") ?: "DATE", 2011, serialOptions, serialNames), RadioGroup.HORIZONTAL)

        val footerOptions = listOf(3001 to "不显示", 3002 to "只显示备注", 3003 to "显示条码 + 备注")
        val footerNames = listOf("NONE", "NOTE", "BARCODE")
        footerModeGroup = makeRadioGroup(footerOptions, selectedId(prefs.getString("footer_mode", "NONE") ?: "NONE", 3001, footerOptions, footerNames))

        val barcodeWidthOptions = listOf(3101 to "细(0.8x)", 3102 to "标准(1.0x)", 3103 to "粗(1.2x)")
        val savedBarcodeWidth = when (prefs.getFloat("barcode_width_scale", 1.0f)) {
            0.8f -> 3101
            1.2f -> 3103
            else -> 3102
        }
        barcodeWidthGroup = makeRadioGroup(barcodeWidthOptions, savedBarcodeWidth, RadioGroup.HORIZONTAL)

        val barcodeGapOptions = listOf(3111 to "紧凑", 3112 to "标准", 3113 to "疏松")
        val barcodeGapNames = listOf("TIGHT", "STANDARD", "LOOSE")
        barcodeGapGroup = makeRadioGroup(barcodeGapOptions, selectedId(prefs.getString("barcode_gap_mode", "STANDARD") ?: "STANDARD", 3112, barcodeGapOptions, barcodeGapNames))

        val chartStyleOptions = listOf(7001 to "折线", 7002 to "柱状")
        val chartStyleNames = listOf(ChartStyleMode.LINE.name, ChartStyleMode.BAR.name)
        chartStyleGroup = makeRadioGroup(chartStyleOptions, selectedId(prefs.getString("chart_style_mode", ChartStyleMode.LINE.name) ?: ChartStyleMode.LINE.name, 7001, chartStyleOptions, chartStyleNames), RadioGroup.HORIZONTAL)

        val yAxisOptions = listOf(7101 to "自动", 7102 to "固定")
        val yAxisNames = listOf(YAxisMode.AUTO.name, YAxisMode.FIXED.name)
        yAxisModeGroup = makeRadioGroup(yAxisOptions, selectedId(prefs.getString("y_axis_mode", YAxisMode.AUTO.name) ?: YAxisMode.AUTO.name, 7101, yAxisOptions, yAxisNames), RadioGroup.HORIZONTAL)

        val autoOptions = listOf(8001 to "每日定时一次（省电，推荐）", 8002 to "熄屏触发（更实时，较耗电）")
        val autoNames = listOf(AutoRefreshConfig.MODE_DAILY, AutoRefreshConfig.MODE_SCREEN_OFF)
        autoModeGroup = makeRadioGroup(autoOptions, selectedId(prefs.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) ?: AutoRefreshConfig.MODE_DAILY, 8001, autoOptions, autoNames))

        includeUnreadCheck = makeCheck(prefs.getBoolean("include_unread", false))
        showProgressStatusCheck = makeCheck(prefs.getBoolean("show_progress_status", true))
        showAuthorCheck = makeCheck(prefs.getBoolean("show_author", true))
        showChartCheck = makeCheck(prefs.getBoolean("show_chart", true))
        showPeakLabelCheck = makeCheck(prefs.getBoolean("show_peak_label", true))
        autoRefreshCheck = makeCheck(prefs.getBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, true))

        minDurationInput = makeInput(prefs.getInt("min_duration_minutes", 1).toString())
        topNInput = makeInput(prefs.getInt("top_n", 5).coerceIn(1, 5).toString())
        titleInput = makeInput(prefs.getString("receipt_title", "阅读账单") ?: "阅读账单")
        titleSizeInput = makeInput((prefs.getFloat("receipt_title_size", 74f)).toInt().toString())
        bodySizeInput = makeInput((prefs.getFloat("receipt_body_size", 34f)).toInt().toString())
        serialCustomInput = makeInput(prefs.getString("serial_number_custom", "") ?: "")
        serialNumberSizeInput = makeInput((prefs.getFloat("serial_number_size", 46f)).toInt().toString())
        noteInput = makeInput(prefs.getString("note_text", "") ?: "")
        yAxisMaxInput = makeInput(prefs.getInt("y_axis_fixed_max_minutes", 300).toString())
        autoDailyTimeInput = makeInput(prefs.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30")
        autoMinIntervalInput = makeInput(prefs.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).toString())
        titleFontSpinner = buildFontSpinner("title_font", "SERIF_BOLD")
        bodyFontSpinner = buildFontSpinner("body_font", "MONO")

        root.addView(hiddenHost)

        addSectionTitle("数据与统计", "周期、数据口径、时长单位与日期范围")
        val periodSegment = bindSegmented("统计周期", periodGroup, periodOptions, isVertical = false)
        val sourceSegment = bindSegmented("数据口径", sourceGroup, sourceOptions, isVertical = true)
        val wallpaperModeSegment = bindSegmented("壁纸类型", wallpaperModeGroup, wallpaperOptions, isVertical = true)
        val wallpaperModeHint = addHint("提示：封面模式依赖 NeoReader 元数据落库。通常需要先退出当前正在阅读的书籍再锁屏，才会刷新到最新封面；如果在书籍打开状态下直接锁屏，往往仍显示旧封面，通常下一次锁屏才会生效。")
        val coverFitSegment = bindSegmented("封面显示方式", coverFitModeGroup, coverFitOptions, isVertical = false)
        val timeUnitSegment = bindSegmented("时长显示单位", timeUnitGroup, timeUnitOptions, isVertical = false)
        val weekStartRow = bindInputRow("选择起始日期", { selectedWeekStartYmd.ifBlank { currentWeekStartYmd() } }) { openWeekStartDatePicker() }.first
        weekStartText = weekStartRow.getChildAt(1) as TextView
        val weekEndRow = bindInputRow("选择结束日期", { selectedWeekEndYmd.ifBlank { currentWeekEndYmd() } }) { openWeekEndDatePicker() }.first
        weekEndText = weekEndRow.getChildAt(1) as TextView

        addSectionTitle("书单筛选", "控制展示书目与统计阈值")
        val includeUnreadRow = bindToggle("最近阅读包含未读（readingStatus=0）", includeUnreadCheck)
        val readingFilterSegment = bindSegmented("书单筛选（状态）", readingFilterGroup, readingFilterOptions, isVertical = false)
        val topNSlider = bindSlider("Top N（最多显示书籍数量）", topNInput, 1, 5)
        val minDurationSlider = bindSlider("最小时长阈值（分钟，作用于“按阅读时长事件”）", minDurationInput, 0, 240)

        addSectionTitle("排版与字体", "标题、字号、进度与字体")
        val titleRow = bindEditRow("账单标题", titleInput)
        val titleSizeSlider = bindSlider("标题字号", titleSizeInput, 24, 120)
        val bodySizeSlider = bindSlider("正文字号基准", bodySizeInput, 18, 60)
        val serialSegment = bindSegmented("单号数字模式", serialModeGroup, serialOptions, isVertical = false)
        val serialCustomRow = bindEditRow("自定义数字", serialCustomInput, numericOnly = true, maxDigits = 12)
        val serialSizeSlider = bindSlider("单号数字字号", serialNumberSizeInput, 24, 140)
        val progressStatusRow = bindToggle("显示进度和状态行", showProgressStatusCheck)
        val progressSegment = bindSegmented("进度显示方式", progressModeGroup, progressOptions, isVertical = false)
        val authorRow = bindToggle("显示作者行（在进度行上方）", showAuthorCheck)
        val titleFontRow = bindSpinnerRow("标题字体（系统字体）", titleFontSpinner)
        val bodyFontRow = bindSpinnerRow("正文字体（系统字体）", bodyFontSpinner)
        pickFontDirBtn = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { pickFontTreeLauncher.launch(null) }
            hiddenHost.addView(this)
        }
        val fontDirRow = bindInputRow("选择字体目录（SAF）", { selectedFontDirUri?.let { "已选择 ▼" } ?: "未选择 ▼" }) {
            pickFontTreeLauncher.launch(null)
        }.first
        fontScanText = TextView(this).apply {
            text = fontScanReport
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }
        root.addView(fontScanText)

        addSectionTitle("图表", "图形样式与坐标设置")
        val chartToggleRow = bindToggle("显示下方周曲线图", showChartCheck)
        val chartStyleSegment = bindSegmented("图表样式", chartStyleGroup, chartStyleOptions, isVertical = false)
        val chartRuleHint = addHint("图表横轴规则：当天/昨天=按小时；本周/上周/最近7天=按天；最近30天=按天；自定义<=14天按天，15-90天按周，>90天按月。")
        val peakLabelRow = bindToggle("显示峰值标签", showPeakLabelCheck)
        val yAxisSegment = bindSegmented("Y轴最大值", yAxisModeGroup, yAxisOptions, isVertical = false)
        val yAxisFixedSlider = bindSlider("Y轴固定最大值(分钟)", yAxisMaxInput, 1, 2000)

        addSectionTitle("底部备注与条码", "备注文本与装饰条码参数")
        val footerSegment = bindSegmented("底部备注/条码", footerModeGroup, footerOptions, isVertical = true)
        val noteRow = bindEditRow("备注文本 / 条码内容", noteInput)
        val barcodeWidthSegment = bindSegmented("条码粗细强度", barcodeWidthGroup, barcodeWidthOptions, isVertical = false)
        val barcodeGapSegment = bindSegmented("条码留白密度", barcodeGapGroup, barcodeGapOptions, isVertical = false)

        addSectionTitle("自动刷新", "默认自动模式，可切换定时或熄屏触发")
        val autoToggleRow = bindToggle("启用自动刷新与自动覆盖保存", autoRefreshCheck)
        val autoModeSegment = bindSegmented("自动刷新模式", autoModeGroup, autoOptions, isVertical = true)
        val autoDailyRow = bindInputRow("每日执行时间", { normalizeDailyTime(autoDailyTimeInput.text.toString()) }) { openDailyTimePicker() }.first
        val autoDailyValue = autoDailyRow.getChildAt(1) as TextView
        autoDailyTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { autoDailyValue.text = normalizeDailyTime(autoDailyTimeInput.text.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        val autoMinIntervalSlider = bindSlider("熄屏触发最小间隔(分钟)", autoMinIntervalInput, 1, 240)
        autoModeHintText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        autoStateText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        val autoWarningText = addHint("提示：熄屏触发会增加唤醒次数与耗电；NeoReader 常在退出当前书籍/会话落库后才更新元数据，所以可能出现“本次锁屏仍是旧封面、下次锁屏生效”的现象。")

        statusText = TextView(this).apply {
            text = "设置后点击按钮生成。"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 0)
            root.addView(this)
        }

        fun updateConditionalVisibility() {
            val customPeriod = periodGroup.checkedRadioButtonId == 4005
            weekStartRow.visibility = if (customPeriod) View.VISIBLE else View.GONE
            weekEndRow.visibility = if (customPeriod) View.VISIBLE else View.GONE

            val coverOptsVisible = wallpaperModeGroup.checkedRadioButtonId != 1201
            coverFitSegment.visibility = if (coverOptsVisible) View.VISIBLE else View.GONE

            serialCustomRow.visibility = if (serialModeGroup.checkedRadioButtonId == 2013) View.VISIBLE else View.GONE

            val showChart = showChartCheck.isChecked
            chartStyleSegment.visibility = if (showChart) View.VISIBLE else View.GONE
            chartRuleHint.visibility = if (showChart) View.VISIBLE else View.GONE
            peakLabelRow.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisSegment.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisFixedSlider.visibility = if (showChart && yAxisModeGroup.checkedRadioButtonId == 7102) View.VISIBLE else View.GONE

            val footerMode = footerModeGroup.checkedRadioButtonId
            noteRow.visibility = if (footerMode != 3001) View.VISIBLE else View.GONE
            barcodeWidthSegment.visibility = if (footerMode == 3003) View.VISIBLE else View.GONE
            barcodeGapSegment.visibility = if (footerMode == 3003) View.VISIBLE else View.GONE

            val autoEnabled = autoRefreshCheck.isChecked
            autoModeSegment.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoDailyRow.visibility = if (autoEnabled && autoModeGroup.checkedRadioButtonId == 8001) View.VISIBLE else View.GONE
            autoMinIntervalSlider.visibility = if (autoEnabled && autoModeGroup.checkedRadioButtonId == 8002) View.VISIBLE else View.GONE
            autoModeHintText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoStateText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoWarningText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
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

        updateAutoRefreshHint()
        updateAutoRuntimeState()
        attachAutoRefreshListeners()

        scroll.addView(root)
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

    private fun buildEinkUiPage(): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 40, 32, 80)
        }

        root.addView(TextView(this).apply {
            text = "阅读壁纸设置 (墨水屏新版)"
            textSize = 28f
            setTextColor(Color.BLACK)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 40)
        })

        fun createDivider(thickness: Int = 4, topMargin: Int = 0, bottomMargin: Int = 24) = View(this@MainActivity).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness).apply {
                setMargins(0, topMargin, 0, bottomMargin)
            }
        }

        fun addSectionTitle(text: String, hint: String? = null) {
            root.addView(TextView(this@MainActivity).apply {
                this.text = text
                textSize = 24f
                setTextColor(Color.BLACK)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 48, 0, if (hint == null) 16 else 6)
            })
            if (hint != null) {
                root.addView(TextView(this@MainActivity).apply {
                    this.text = hint
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 0, 0, 24)
                })
            }
            root.addView(createDivider(4, 0, 32))
        }

        fun addHint(hint: String) {
            root.addView(TextView(this@MainActivity).apply {
                text = hint
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, 16)
            })
        }

        fun addToggle(label: String, checked: Boolean = false) {
            val row = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 16, 0, 32)
            }
            row.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val einkToggle = LinearLayout(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(64, 64)
                setPadding(12, 12, 12, 12)
                background = android.graphics.drawable.GradientDrawable().apply { setStroke(4, Color.BLACK) }
            }
            val einkToggleInner = View(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                setBackgroundColor(if (checked) Color.BLACK else Color.TRANSPARENT)
            }
            einkToggle.addView(einkToggleInner)
            var state = checked
            row.setOnClickListener {
                state = !state
                einkToggleInner.setBackgroundColor(if (state) Color.BLACK else Color.TRANSPARENT)
            }
            row.addView(einkToggle)
            root.addView(row)
        }

        fun addSegmented(label: String, options: List<String>, selected: Int = 0, isVertical: Boolean = false) {
            root.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(0, 16, 0, 16)
            })
            if (isVertical) {
                val sg = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,32) }
                    background = android.graphics.drawable.GradientDrawable().apply { setStroke(4, Color.BLACK) }
                }
                val views = mutableListOf<TextView>()
                options.forEachIndexed { index, opt ->
                    val tv = TextView(this@MainActivity).apply {
                        text = opt
                        textSize = 18f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(32, 24, 32, 24)
                    }
                    views.add(tv)
                    sg.addView(tv)
                    if (index < options.size - 1) {
                        sg.addView(View(this@MainActivity).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4)
                        })
                    }
                }
                views.forEach { it.setBackgroundColor(Color.TRANSPARENT); it.setTextColor(Color.BLACK) }
                views[selected].setBackgroundColor(Color.BLACK)
                views[selected].setTextColor(Color.WHITE)
                root.addView(sg)
            } else {
                 // Break into rows if many options, roughly 3 per row for horizontal
                 val rows = options.chunked(3)
                 val outerGroup = LinearLayout(this@MainActivity).apply {
                     orientation = LinearLayout.VERTICAL
                     layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0,0,0,32) }
                     background = android.graphics.drawable.GradientDrawable().apply { setStroke(4, Color.BLACK) }
                 }
                 var globalIndex = 0
                 val allViews = mutableListOf<TextView>()
                 rows.forEachIndexed { rIndex, rowOps ->
                     val hGroup = LinearLayout(this@MainActivity).apply {
                         orientation = LinearLayout.HORIZONTAL
                         layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                     }
                     rowOps.forEachIndexed { cIndex, opt ->
                         val tv = TextView(this@MainActivity).apply {
                             text = opt
                             textSize = 16f
                             setTypeface(typeface, android.graphics.Typeface.BOLD)
                             gravity = android.view.Gravity.CENTER
                             setPadding(16, 24, 16, 24)
                             layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                         }
                         allViews.add(tv)
                         hGroup.addView(tv)
                         if (cIndex < rowOps.size - 1) {
                             hGroup.addView(View(this@MainActivity).apply { setBackgroundColor(Color.BLACK); layoutParams = LinearLayout.LayoutParams(4, ViewGroup.LayoutParams.MATCH_PARENT) })
                         }
                     }
                     // Pad with empty views if row not full to keep alignment
                     while(hGroup.childCount < 5 && rIndex > 0) { // 5 because 3 items + 2 dividers
                         hGroup.addView(View(this@MainActivity).apply { setBackgroundColor(Color.BLACK); layoutParams = LinearLayout.LayoutParams(4, ViewGroup.LayoutParams.MATCH_PARENT) })
                         hGroup.addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
                     }
                     outerGroup.addView(hGroup)
                     if (rIndex < rows.size - 1) {
                         outerGroup.addView(View(this@MainActivity).apply { setBackgroundColor(Color.BLACK); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 4) })
                     }
                 }
                 allViews.forEach { it.setBackgroundColor(Color.TRANSPARENT); it.setTextColor(Color.BLACK) }
                 if(selected < allViews.size) {
                     allViews[selected].setBackgroundColor(Color.BLACK)
                     allViews[selected].setTextColor(Color.WHITE)
                 }
                 root.addView(outerGroup)
            }
        }

        fun addSliderControl(label: String, min: Int, max: Int, defaultVal: Int) {
            val wrap = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 16, 0, 32)
            }
            val headerRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val valText = TextView(this@MainActivity).apply {
                text = defaultVal.toString()
                textSize = 24f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
            }
            headerRow.addView(valText)
            wrap.addView(headerRow)

            val einkSeekBar = SeekBar(this@MainActivity).apply {
                this.max = max - min
                this.progress = defaultVal - min
                setPadding(0, 32, 0, 32)
                progressDrawable?.setTint(Color.BLACK)
                thumb?.setTint(Color.BLACK)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, prog: Int, fromUser: Boolean) {
                        val realVal = prog + min
                        valText.text = realVal.toString()
                    }
                    override fun onStartTrackingTouch(p0: SeekBar?) {}
                    override fun onStopTrackingTouch(p0: SeekBar?) {}
                })
            }
            wrap.addView(einkSeekBar)
            root.addView(wrap)
        }
        
        fun addInputMock(label: String, defaultVal: String) {
             val box = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(32, 40, 32, 40)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 32) }
                background = android.graphics.drawable.GradientDrawable().apply { setStroke(4, Color.BLACK) }
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            box.addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            box.addView(TextView(this@MainActivity).apply {
                text = defaultVal
                textSize = 20f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.BLACK)
            })
            root.addView(box)
        }

        // --- Sections Replicating buildSettingsPage exactly ---
        
        addSectionTitle("数据与统计", "周期、数据口径、时长单位与日期范围")
        addToggle("最近阅读包含未读（readingStatus=0）", false)
        addSegmented("统计周期", listOf("当天", "昨天", "本周", "上周", "最近7天", "最近30天", "自定义起止"), 2, isVertical = false)
        addSegmented("数据口径", listOf("按阅读时长事件（推荐）", "按有路径会话", "按Metadata最近访问"), 0, isVertical = true)
        addSegmented("壁纸类型", listOf("统计壁纸", "当前阅读封面(实验性,较耗电)", "自动(熄屏优先封面)(实验性)"), 0, isVertical = true)
        addHint("提示：封面模式依赖 NeoReader 元数据落库。通常需要先退出当前正在阅读的书籍再锁屏，才会刷新到最新封面；如果在书籍打开状态下直接锁屏，往往仍显示旧封面，通常下一次锁屏才会生效。")
        addSegmented("封面显示方式", listOf("完整显示", "铺满裁切"), 0, isVertical = false)
        addSliderControl("最小时长阈值（分钟，作用于“按阅读时长事件”）", 1, 60, 1)
        addSegmented("时长显示单位", listOf("小时", "分钟"), 0, isVertical = false)
        addInputMock("选择起始日期", "点击选择")
        addInputMock("选择结束日期", "点击选择")
        
        addSectionTitle("书单筛选", "控制展示书目与统计阈值")
        addSegmented("书单筛选（状态）", listOf("全部", "仅在读", "仅已读完"), 0, isVertical = false)
        addSliderControl("Top N（最多显示书籍数量）", 1, 5, 5)

        addSectionTitle("排版与字体", "标题、字号、进度与字体")
        addInputMock("账单标题", "阅读账单")
        addSliderControl("标题字号", 24, 120, 74)
        addSliderControl("正文字号基准", 18, 60, 34)
        addSegmented("单号数字模式", listOf("月日", "随机", "自定义"), 0, isVertical = false)
        addInputMock("自定义数字", "")
        addSliderControl("单号数字字号", 24, 140, 46)
        addToggle("显示进度和状态行", true)
        addSegmented("进度显示方式", listOf("页数", "百分比"), 0, isVertical = false)
        addToggle("显示作者行（在进度行上方）", true)
        addInputMock("标题字体（系统字体）", "SERIF_BOLD ▼")
        addInputMock("正文字体（系统字体）", "MONO ▼")
        addInputMock("选择字体目录（SAF）", "更换 ▼")

        addSectionTitle("图表", "图形样式与坐标设置")
        addToggle("显示下方周曲线图", true)
        addSegmented("图表样式", listOf("折线", "柱状"), 0, isVertical = false)
        addHint("图表横轴规则：当天/昨天=按小时；本周/上周/最近7天=按天；最近30天=按天；自定义<=14天按天，15-90天按周，>90天按月。")
        addToggle("显示峰值标签", true)
        addSegmented("Y轴最大值", listOf("自动", "固定"), 0, isVertical = false)
        addSliderControl("Y轴固定最大值(分钟)", 1, 2000, 300)

        addSectionTitle("底部备注与条码", "备注文本与装饰条码参数")
        addSegmented("底部备注/条码", listOf("不显示", "只显示备注", "显示条码 + 备注"), 0, isVertical = true)
        addInputMock("备注文本 / 条码内容", "点击设置")
        addSegmented("条码粗细强度", listOf("细(0.8x)", "标准(1.0x)", "粗(1.2x)"), 1, isVertical = false)
        addSegmented("条码留白密度", listOf("紧凑", "标准", "疏松"), 1, isVertical = false)

        addSectionTitle("自动刷新", "默认自动模式，可切换定时或熄屏触发")
        addToggle("启用自动刷新与自动覆盖保存", true)
        addSegmented("自动刷新模式", listOf("每日定时一次（省电，推荐）", "熄屏触发（更实时，较耗电）"), 0, isVertical = true)
        addInputMock("每日执行时间", "22:30")
        addSliderControl("熄屏触发最小间隔(分)", 1, 240, 3)
        addHint("提示：熄屏触发会增加唤醒次数与耗电；NeoReader 常在退出当前书籍/会话落库后才更新元数据，所以可能出现“本次锁屏仍是旧封面、下次锁屏生效”的现象。")

        scroll.addView(root)
        return scroll
    }

    private fun showSettingsPage() {
        settingsPage.visibility = View.VISIBLE
        previewPage.visibility = View.GONE
        einkUiPage.visibility = View.GONE
    }

    private fun showPreviewPage() {
        settingsPage.visibility = View.GONE
        previewPage.visibility = View.VISIBLE
        einkUiPage.visibility = View.GONE
        refreshPreview()
    }

    private fun showEinkUiPage() {
        settingsPage.visibility = View.GONE
        previewPage.visibility = View.GONE
        einkUiPage.visibility = View.VISIBLE
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
