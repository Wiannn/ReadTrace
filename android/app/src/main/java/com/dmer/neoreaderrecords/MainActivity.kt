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
import androidx.activity.ComponentActivity
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

class MainActivity : ComponentActivity() {
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
    private lateinit var booxDevicePresetGroup: RadioGroup
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
    private lateinit var updateStatusText: TextView
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

    private var currentPageKey: String = "settings"
    private var updateTopNavState: (() -> Unit)? = null
    private var lastSavedPath: String? = null
    private var previewBitmap: Bitmap? = null
    private var previewPresetText: String = ""
    private var isInitializingUi: Boolean = false
    private var selectedWeekStartYmd: String = ""
    private var selectedWeekEndYmd: String = ""
    private val systemFonts: MutableList<String> = mutableListOf()
    private var fontScanReport: String = ""
    private var barcodeDebugReport: String = ""
    private var fontPermissionDebug: String = ""
    private var metadataDebugReport: String = ""
    private var metadataRowsDebugReport: String = ""
    private var uiDebugReport: String = ""
    private var isCheckingUpdates: Boolean = false
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
        val booxDevicePreset: String,
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
            updateReleaseStatusFromCache()
            checkForUpdatesIfNeeded(force = false)
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
        fun inkBorder(stroke: Int = 4, fill: Int = Color.WHITE): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                setStroke(stroke, Color.BLACK)
            }
        }
        fun makeNavItem(textValue: String, key: String, onTap: () -> Unit): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(12, 22, 12, 22)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    currentPageKey = key
                    onTap()
                }
            }
        }
        fun makeActionItem(textValue: String, primary: Boolean, onTap: () -> Unit): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(if (primary) Color.WHITE else Color.BLACK)
                background = inkBorder(4, if (primary) Color.BLACK else Color.WHITE)
                setPadding(12, 22, 12, 22)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, 12, 0)
                }
                setOnClickListener { onTap() }
            }
        }
        fun dividerVertical(): View = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(4, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val navGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = inkBorder(4)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, 16)
            }
        }
        val navSettings = makeNavItem("设置", "settings") { showSettingsPage() }
        val navPreview = makeNavItem("预览", "preview") { showPreviewPage() }
        val refreshAction = makeActionItem("刷新预览", false) { refreshPreviewData() }
        val generateAction = makeActionItem("生成壁纸", true) { generateAndSaveFromCurrentSettings() }
        refreshAction.background = null
        refreshAction.setTextColor(Color.BLACK)
        refreshAction.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        generateAction.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        navGroup.addView(navSettings)
        navGroup.addView(dividerVertical())
        navGroup.addView(navPreview)
        navGroup.addView(dividerVertical())
        navGroup.addView(refreshAction)
        navGroup.addView(dividerVertical())
        navGroup.addView(generateAction)

        changeStateText = TextView(this).apply {
            text = "状态：初始化"
            textSize = 13f
            setPadding(4, 0, 4, 18)
            setTextColor(Color.DKGRAY)
        }
        updateTopNavState = {
            val items = listOf("settings" to navSettings, "preview" to navPreview)
            items.forEach { (key, item) ->
                val selected = currentPageKey == key
                item.setTextColor(if (selected) Color.WHITE else Color.BLACK)
                item.setBackgroundColor(if (selected) Color.BLACK else Color.TRANSPARENT)
            }
        }

        settingsPage = buildSettingsPage(prefs)
        previewPage = buildPreviewPage()
        appendUiDebug("setupUi pages built settings=${settingsPage.javaClass.simpleName} preview=${previewPage.javaClass.simpleName}")

        root.addView(navGroup)
        root.addView(changeStateText)
        root.addView(settingsPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(previewPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showSettingsPage()
        isInitializingUi = false
        applySettingsPreview()
        checkForUpdatesIfNeeded(force = false)
        writeDebugLog("setupUi_done")
    }

    private fun appendUiDebug(message: String) {
        val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        uiDebugReport += "[$now] $message\n"
    }

    private fun deviceIdentityText(): String {
        return listOf(
            "manufacturer=${android.os.Build.MANUFACTURER}",
            "brand=${android.os.Build.BRAND}",
            "model=${android.os.Build.MODEL}",
            "device=${android.os.Build.DEVICE}",
            "product=${android.os.Build.PRODUCT}"
        ).joinToString(", ")
    }

    private fun detectBooxDevicePreset(): String {
        val raw = listOf(
            android.os.Build.MANUFACTURER,
            android.os.Build.BRAND,
            android.os.Build.MODEL,
            android.os.Build.DEVICE,
            android.os.Build.PRODUCT
        ).joinToString(" ").uppercase(Locale.ROOT)

        return when {
            raw.contains("PALMA") -> "PALMA"
            raw.contains("POKE") && raw.contains("7") && raw.contains("PRO") -> "POKE7_PRO"
            raw.contains("POKE") && raw.contains("7") -> "POKE7"
            raw.contains("POKE") && raw.contains("6") && raw.contains("S") -> "POKE6S"
            raw.contains("POKE") && raw.contains("6") -> "POKE6"
            raw.contains("P6") && raw.contains("PRO") -> "P6_PRO"
            raw.contains("P6") -> "P6"
            raw.contains("LEAF") && raw.contains("5") && raw.contains("C") -> "LEAF5C"
            raw.contains("LEAF") && raw.contains("5") && raw.contains("+") -> "LEAF5_PLUS"
            raw.contains("LEAF") && raw.contains("5") -> "LEAF5"
            raw.contains("NOTE") && raw.contains("X5") && raw.contains("MINI") -> "NOTE_X5_MINI"
            raw.contains("NOTE") && raw.contains("X5S") -> "NOTE_X5S"
            raw.contains("NOTE") && raw.contains("X5") -> "NOTE_X5"
            raw.contains("NOTEX6") || (raw.contains("NOTE") && raw.contains("X6")) -> "NOTEX6"
            raw.contains("TAB") && raw.contains("10C") && raw.contains("PRO") -> "TAB10C_PRO"
            raw.contains("T10") && raw.contains("C") -> "T10C"
            raw.contains("T13") && raw.contains("C") -> "T13C"
            raw.contains("NOTE") && raw.contains("AIR") && raw.contains("3") && raw.contains("C") -> "NOTE_AIR3C"
            raw.contains("NOTE") && raw.contains("AIR") && raw.contains("3") -> "NOTE_AIR3"
            raw.contains("PAGE") -> "PAGE"
            else -> BooxDevicePresets.DEFAULT_KEY
        }
    }

    private fun booxPresetKeyByRadioId(id: Int): String {
        return BooxDevicePresets.all.getOrNull(id - 1301)?.key ?: BooxDevicePresets.DEFAULT_KEY
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
                        textSize = if (text.contains("\n")) 16f else 18f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setLineSpacing(4f, 1.0f)
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
                            textSize = if (text.contains("\n")) 13f else 16f
                            setTypeface(Typeface.DEFAULT_BOLD)
                            gravity = Gravity.CENTER
                            setLineSpacing(4f, 1.0f)
                            setPadding(12, 20, 12, 20)
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

        fun bindRadioChoiceRow(label: String, group: RadioGroup, options: List<Pair<Int, String>>): LinearLayout {
            fun optionText(id: Int): String {
                return (options.firstOrNull { it.first == id }?.second ?: options.first().second)
                    .replace('\n', ' ')
            }

            lateinit var value: TextView
            val (row, valueView) = bindInputRow(label, { optionText(group.checkedRadioButtonId) + " ▼" }) {
                val labels = options.map { it.second.replace('\n', ' ') }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(labels) { _, which ->
                        group.check(options[which].first)
                        value.text = "${labels[which]} ▼"
                        if (!isInitializingUi) applySettingsPreview()
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
            text = "阅读壁纸设置"
            textSize = 28f
            setTextColor(Color.BLACK)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "提示：少数情况下，锁屏时系统可能还没读取到刚生成的壁纸，通常下一次锁屏会显示最新结果。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 40)
        })

        val periodOptions = listOf(4000 to "当天\n只看今天", 4006 to "昨天\n只看昨日", 4001 to "本周\n周视图", 4002 to "上周\n回看上周", 4003 to "最近7天\n滚动7天", 4004 to "最近30天\n月度概览", 4005 to "自定义起止\n手动选日期")
        val periodNames = listOf(PeriodMode.TODAY.name, PeriodMode.YESTERDAY.name, PeriodMode.THIS_WEEK.name, PeriodMode.LAST_WEEK.name, PeriodMode.LAST_7_DAYS.name, PeriodMode.LAST_30_DAYS.name, PeriodMode.CUSTOM.name)
        val savedPeriod = prefs.getString("period_mode", PeriodMode.THIS_WEEK.name) ?: PeriodMode.THIS_WEEK.name
        periodGroup = makeRadioGroup(periodOptions, selectedId(savedPeriod, 4001, periodOptions, periodNames))

        val sourceOptions = listOf(1001 to "按阅读时长事件（推荐）\n优先统计真实阅读分钟数", 1002 to "按有路径会话\n有打开记录就算一本", 1003 to "按Metadata最近访问\n按书库最近打开排序")
        val sourceNames = listOf(DataSourceMode.DURATION.name, DataSourceMode.PATH_SESSION.name, DataSourceMode.METADATA_ACCESS.name)
        sourceGroup = makeRadioGroup(sourceOptions, selectedId(prefs.getString("source_mode", DataSourceMode.DURATION.name) ?: DataSourceMode.DURATION.name, 1001, sourceOptions, sourceNames))

        val wallpaperOptions = listOf(1201 to "统计壁纸\n生成阅读账单图片", 1202 to "当前阅读封面\n尝试用最近书籍封面", 1203 to "自动封面优先\n有封面用封面，否则用账单")
        val wallpaperNames = listOf("STATS", "COVER", "AUTO_COVER")
        wallpaperModeGroup = makeRadioGroup(wallpaperOptions, selectedId(prefs.getString("wallpaper_mode", "STATS") ?: "STATS", 1201, wallpaperOptions, wallpaperNames))

        val detectedBooxPreset = detectBooxDevicePreset()
        val booxDevicePresetOptions = BooxDevicePresets.all.mapIndexed { index, preset ->
            val matchMark = if (preset.key == detectedBooxPreset) " [本机匹配]" else ""
            (1301 + index) to "${preset.label}$matchMark\n${preset.inchText} ${preset.heightPx}x${preset.widthPx}"
        }
        val booxDevicePresetNames = BooxDevicePresets.all.map { it.key }
        val hasManualBooxPreset = prefs.getBoolean("boox_device_preset_user_set", false)
        val defaultBooxDevicePreset = if (hasManualBooxPreset && prefs.contains("boox_device_preset")) {
            prefs.getString("boox_device_preset", BooxDevicePresets.DEFAULT_KEY) ?: BooxDevicePresets.DEFAULT_KEY
        } else {
            detectedBooxPreset
        }
        appendUiDebug("booxDevicePreset default=$defaultBooxDevicePreset hasSaved=${prefs.contains("boox_device_preset")} userSet=$hasManualBooxPreset device=${deviceIdentityText()}")
        booxDevicePresetGroup = makeRadioGroup(
            booxDevicePresetOptions,
            selectedId(
                defaultBooxDevicePreset,
                1301,
                booxDevicePresetOptions,
                booxDevicePresetNames
            ),
            RadioGroup.VERTICAL
        )

        val coverFitOptions = listOf(1211 to "完整显示\n不裁掉封面", 1212 to "铺满裁切\n铺满屏幕边缘")
        val coverFitNames = listOf("FIT", "CROP")
        coverFitModeGroup = makeRadioGroup(coverFitOptions, selectedId(prefs.getString("cover_fit_mode", "FIT") ?: "FIT", 1211, coverFitOptions, coverFitNames), RadioGroup.HORIZONTAL)

        val timeUnitOptions = listOf(2001 to "小时\n自动显示x小时y分钟", 2002 to "分钟\n全部换算成分钟")
        val timeUnitNames = listOf("HOUR", "MINUTE")
        timeUnitGroup = makeRadioGroup(timeUnitOptions, selectedId(prefs.getString("time_unit", "HOUR") ?: "HOUR", 2001, timeUnitOptions, timeUnitNames), RadioGroup.HORIZONTAL)

        val readingFilterOptions = listOf(6001 to "全部\n不按状态过滤", 6002 to "仅在读\n只显示没读完", 6003 to "仅已读完\n只显示已完成")
        val readingFilterNames = listOf(ReadingFilterMode.ALL.name, ReadingFilterMode.READING_ONLY.name, ReadingFilterMode.FINISHED_ONLY.name)
        readingFilterGroup = makeRadioGroup(readingFilterOptions, selectedId(prefs.getString("reading_filter_mode", ReadingFilterMode.ALL.name) ?: ReadingFilterMode.ALL.name, 6001, readingFilterOptions, readingFilterNames), RadioGroup.HORIZONTAL)

        val progressOptions = listOf(6101 to "页数\n例如32/198", 6102 to "百分比\n例如16%")
        val progressNames = listOf("PAGES", "PERCENT")
        progressModeGroup = makeRadioGroup(progressOptions, selectedId(prefs.getString("progress_mode", "PAGES") ?: "PAGES", 6101, progressOptions, progressNames), RadioGroup.HORIZONTAL)

        val serialOptions = listOf(2011 to "月日\n自动用当前日期", 2012 to "随机\n每次生成变化", 2013 to "自定义\n手动固定数字")
        val serialNames = listOf("DATE", "RANDOM", "CUSTOM")
        serialModeGroup = makeRadioGroup(serialOptions, selectedId(prefs.getString("serial_number_mode", "DATE") ?: "DATE", 2011, serialOptions, serialNames), RadioGroup.HORIZONTAL)

        val footerOptions = listOf(3001 to "不显示\n底部留白更干净", 3002 to "只显示备注\n显示一句自定义文字", 3003 to "条码 + 备注\n增加票据装饰感")
        val footerNames = listOf("NONE", "NOTE", "BARCODE")
        footerModeGroup = makeRadioGroup(footerOptions, selectedId(prefs.getString("footer_mode", "NONE") ?: "NONE", 3001, footerOptions, footerNames))

        val barcodeWidthOptions = listOf(3101 to "细(0.8x)\n更轻", 3102 to "标准(1.0x)\n默认", 3103 to "粗(1.2x)\n更醒目")
        val savedBarcodeWidth = when (prefs.getFloat("barcode_width_scale", 1.0f)) {
            0.8f -> 3101
            1.2f -> 3103
            else -> 3102
        }
        barcodeWidthGroup = makeRadioGroup(barcodeWidthOptions, savedBarcodeWidth, RadioGroup.HORIZONTAL)

        val barcodeGapOptions = listOf(3111 to "紧凑\n线条更密", 3112 to "标准\n推荐", 3113 to "疏松\n留白更多")
        val barcodeGapNames = listOf("TIGHT", "STANDARD", "LOOSE")
        barcodeGapGroup = makeRadioGroup(barcodeGapOptions, selectedId(prefs.getString("barcode_gap_mode", "STANDARD") ?: "STANDARD", 3112, barcodeGapOptions, barcodeGapNames))

        val chartStyleOptions = listOf(7001 to "折线\n看趋势", 7002 to "柱状\n看每天差异")
        val chartStyleNames = listOf(ChartStyleMode.LINE.name, ChartStyleMode.BAR.name)
        chartStyleGroup = makeRadioGroup(chartStyleOptions, selectedId(prefs.getString("chart_style_mode", ChartStyleMode.LINE.name) ?: ChartStyleMode.LINE.name, 7001, chartStyleOptions, chartStyleNames), RadioGroup.HORIZONTAL)

        val yAxisOptions = listOf(7101 to "自动\n按数据自己缩放", 7102 to "固定\n不同周期更好对比")
        val yAxisNames = listOf(YAxisMode.AUTO.name, YAxisMode.FIXED.name)
        yAxisModeGroup = makeRadioGroup(yAxisOptions, selectedId(prefs.getString("y_axis_mode", YAxisMode.AUTO.name) ?: YAxisMode.AUTO.name, 7101, yAxisOptions, yAxisNames), RadioGroup.HORIZONTAL)

        val autoOptions = listOf(8001 to "每日定时一次（推荐）\n省电，适合稳定更新", 8002 to "熄屏触发\n更及时，但更耗电")
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
        val booxDevicePresetRow = bindRadioChoiceRow("阅读器尺寸预设", booxDevicePresetGroup, booxDevicePresetOptions)
        appendUiDebug("buildSettingsPage added booxDevicePresetRow rootChildCount=${root.childCount} rowChildren=${booxDevicePresetRow.childCount}")
        addHint("说明：这里只保存阅读器和屏幕尺寸选择；暂时不影响生成图片。默认 Leaf5。")
        val periodSegment = bindSegmented("统计周期", periodGroup, periodOptions, isVertical = false)
        addHint("说明：选择账单统计哪一段时间；自定义模式会显示起止日期选择。")
        val sourceSegment = bindSegmented("数据口径", sourceGroup, sourceOptions, isVertical = true)
        val wallpaperModeSegment = bindSegmented("壁纸类型", wallpaperModeGroup, wallpaperOptions, isVertical = true)
        val wallpaperModeHint = addHint("说明：统计壁纸最稳定；封面模式只读取本地书籍封面，不访问网络。提示：封面模式依赖 NeoReader 元数据落库。通常需要先退出当前正在阅读的书籍再锁屏，才会刷新到最新封面；如果在书籍打开状态下直接锁屏，往往仍显示旧封面，通常下一次锁屏才会生效。")
        val coverFitSegment = bindSegmented("封面显示方式", coverFitModeGroup, coverFitOptions, isVertical = false)
        val timeUnitSegment = bindSegmented("时长显示单位", timeUnitGroup, timeUnitOptions, isVertical = false)
        addHint("说明：小时模式更适合壁纸阅读，分钟模式更适合精确核对。")
        val weekStartRow = bindInputRow("选择起始日期", { selectedWeekStartYmd.ifBlank { currentWeekStartYmd() } }) { openWeekStartDatePicker() }.first
        weekStartText = weekStartRow.getChildAt(1) as TextView
        val weekEndRow = bindInputRow("选择结束日期", { selectedWeekEndYmd.ifBlank { currentWeekEndYmd() } }) { openWeekEndDatePicker() }.first
        weekEndText = weekEndRow.getChildAt(1) as TextView

        addSectionTitle("书单筛选", "控制展示书目与统计阈值")
        val includeUnreadRow = bindToggle("最近阅读包含未读（readingStatus=0）", includeUnreadCheck)
        addHint("说明：关闭后会尽量排除只进过书库但没真正开始读的书。")
        val readingFilterSegment = bindSegmented("书单筛选（状态）", readingFilterGroup, readingFilterOptions, isVertical = false)
        val topNSlider = bindSlider("Top N（最多显示书籍数量）", topNInput, 1, 5)
        addHint("说明：默认最多5本；如果底部还要显示图表和条码，3本会更宽松。")
        val minDurationSlider = bindSlider("最小时长阈值（分钟，作用于“按阅读时长事件”）", minDurationInput, 0, 240)
        addHint("说明：小于这个时长的阅读事件会被忽略，可过滤误打开。")

        addSectionTitle("排版与字体", "标题、字号、进度与字体")
        val titleRow = bindEditRow("账单标题", titleInput)
        addHint("说明：会显示在壁纸右上角，例如“阅读账单”或“留台单”。")
        val titleSizeSlider = bindSlider("标题字号", titleSizeInput, 24, 120)
        val bodySizeSlider = bindSlider("正文字号基准", bodySizeInput, 18, 60)
        addHint("说明：字号会影响整张壁纸能放下多少内容；书多时建议调小。")
        val serialSegment = bindSegmented("单号数字模式", serialModeGroup, serialOptions, isVertical = false)
        val serialCustomRow = bindEditRow("自定义数字", serialCustomInput, numericOnly = true, maxDigits = 12)
        val serialSizeSlider = bindSlider("单号数字字号", serialNumberSizeInput, 24, 140)
        addHint("说明：数字变大时会向上扩展，避免挤压下面的操作编号。")
        val progressStatusRow = bindToggle("显示进度和状态行", showProgressStatusCheck)
        addHint("说明：关闭后书单更简洁，但看不到读到哪里。")
        val progressSegment = bindSegmented("进度显示方式", progressModeGroup, progressOptions, isVertical = false)
        val authorRow = bindToggle("显示作者行（在进度行上方）", showAuthorCheck)
        addHint("说明：如果文石元数据里没有作者，会显示未知；关闭后可节省空间。")
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
        addHint("说明：选择存储/Fonts 后，可在标题字体和正文字体里使用里面的 ttf/otf 字体。")
        fontScanText = TextView(this).apply {
            text = fontScanReport
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }
        root.addView(fontScanText)

        addSectionTitle("图表", "图形样式与坐标设置")
        val chartToggleRow = bindToggle("显示下方周曲线图", showChartCheck)
        addHint("说明：打开后能看到时间分布；如果书单或备注太多，可关闭节省空间。")
        val chartStyleSegment = bindSegmented("图表样式", chartStyleGroup, chartStyleOptions, isVertical = false)
        val chartRuleHint = addHint("图表横轴规则：当天/昨天=按小时；本周/上周/最近7天=按天；最近30天=按天；自定义<=14天按天，15-90天按周，>90天按月。")
        val peakLabelRow = bindToggle("显示峰值标签", showPeakLabelCheck)
        val yAxisSegment = bindSegmented("Y轴最大值", yAxisModeGroup, yAxisOptions, isVertical = false)
        val yAxisFixedSlider = bindSlider("Y轴固定最大值(分钟)", yAxisMaxInput, 1, 2000)
        addHint("说明：固定值越大，柱子/曲线越矮；用于不同周期之间保持同一比例。")

        addSectionTitle("底部备注与条码", "备注文本与装饰条码参数")
        val footerSegment = bindSegmented("底部备注/条码", footerModeGroup, footerOptions, isVertical = true)
        val noteRow = bindEditRow("备注文本 / 条码内容", noteInput)
        addHint("说明：备注会显示在底部；条码只是装饰风格，不保证所有扫码软件都能识别。")
        val barcodeWidthSegment = bindSegmented("条码粗细强度", barcodeWidthGroup, barcodeWidthOptions, isVertical = false)
        val barcodeGapSegment = bindSegmented("条码留白密度", barcodeGapGroup, barcodeGapOptions, isVertical = false)

        addSectionTitle("自动刷新", "默认自动模式，可切换定时或熄屏触发")
        val autoToggleRow = bindToggle("启用自动刷新与自动覆盖保存", autoRefreshCheck)
        addHint("说明：开启后 App 会按自动模式覆盖保存同一张壁纸图片。")
        val autoModeSegment = bindSegmented("自动刷新模式", autoModeGroup, autoOptions, isVertical = true)
        val autoDailyRow = bindInputRow("每日执行时间", { normalizeDailyTime(autoDailyTimeInput.text.toString()) }) { openDailyTimePicker() }.first
        val autoDailyValue = autoDailyRow.getChildAt(1) as TextView
        autoDailyTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { autoDailyValue.text = normalizeDailyTime(autoDailyTimeInput.text.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        val autoMinIntervalSlider = bindSlider("熄屏触发最小间隔(分钟)", autoMinIntervalInput, 1, 240)
        addHint("说明：间隔越短越及时，也越容易增加耗电；3分钟是折中值。")
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

        addSectionTitle("版本与更新", "GitHub Release 分发与更新检查")
        updateStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        val updateButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        updateButtons.addView(Button(this).apply {
            text = "检查更新"
            setOnClickListener { checkForUpdatesIfNeeded(force = true) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) })
        updateButtons.addView(Button(this).apply {
            text = "打开 Release 页面"
            setOnClickListener { openReleasePage() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(updateButtons)
        addHint("说明：App 只检查并跳转 GitHub Release 页面，不会自动下载或安装 APK。")
        updateReleaseStatusFromCache()

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
        booxDevicePresetGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) {
                getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("boox_device_preset_user_set", true)
                    .apply()
                applySettingsPreview()
                if (wallpaperModeGroup.checkedRadioButtonId == 1201) {
                    changeStateText.text = "状态: 尺寸已切换，统计壁纸预览已重新生成｜尺寸: $previewPresetText"
                }
            }
        }
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
        previewPresetText = BooxDevicePresets.byKey(settings.booxDevicePreset).displayText()
        statusText.text = "预览已更新（未写入文件）\n$result"
        changeStateText.text = "状态: 参数已变更（仅预览）｜尺寸: $previewPresetText"
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
        previewPresetText = BooxDevicePresets.byKey(settings.booxDevicePreset).displayText()
        val saved = saveBitmapToPictures(bmp)
        lastSavedPath = saved
        statusText.text = "已生成并覆盖文件\n$result\n路径: $saved"
        changeStateText.text = "状态: 已生成并保存｜尺寸: $previewPresetText"
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

    private fun updateReleaseStatusFromCache() {
        if (!::updateStatusText.isInitialized) return
        renderReleaseState(GitHubReleaseChecker.cachedState(this))
    }

    private fun checkForUpdatesIfNeeded(force: Boolean) {
        if (!force && !GitHubReleaseChecker.shouldAutoCheck(this)) {
            updateReleaseStatusFromCache()
            return
        }
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        if (::updateStatusText.isInitialized) {
            updateStatusText.text = "当前版本：${GitHubReleaseChecker.currentVersionName(this)}\n更新状态：正在检查 GitHub Release..."
        }
        Thread {
            val state = GitHubReleaseChecker.check(applicationContext)
            runOnUiThread {
                isCheckingUpdates = false
                renderReleaseState(state)
            }
        }.start()
    }

    private fun renderReleaseState(state: GitHubReleaseChecker.State) {
        if (!::updateStatusText.isInitialized) return
        val checkedAt = if (state.lastCheckMs > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(state.lastCheckMs))
        } else {
            "尚未检查"
        }
        val latest = state.latestTag.ifBlank { "未知" }
        val error = if (state.error.isBlank()) "" else "\n失败原因：${state.error.take(80)}"
        updateStatusText.text = "当前版本：${GitHubReleaseChecker.currentVersionName(this)}\n最新版本：$latest\n更新状态：${state.status}\n最近检查：$checkedAt$error"
    }

    private fun openReleasePage() {
        val url = GitHubReleaseChecker.cachedState(this).latestUrl.ifBlank { GitHubReleaseChecker.RELEASES_URL }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            if (::updateStatusText.isInitialized) {
                updateStatusText.text = "${updateStatusText.text}\n无法打开链接：${it.javaClass.simpleName}"
            }
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
            visibility = View.GONE
        }
        previewImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(previewImage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun showSettingsPage() {
        currentPageKey = "settings"
        settingsPage.visibility = View.VISIBLE
        previewPage.visibility = View.GONE
        updateTopNavState?.invoke()
        appendUiDebug("showSettingsPage settingsVisible=${settingsPage.visibility} previewVisible=${previewPage.visibility}")
        if (!isInitializingUi) writeDebugLog("showSettingsPage")
    }

    private fun showPreviewPage() {
        currentPageKey = "preview"
        settingsPage.visibility = View.GONE
        previewPage.visibility = View.VISIBLE
        updateTopNavState?.invoke()
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
            previewText.text = ""
            return
        }
        previewText.text = ""
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
        val booxDevicePreset = booxPresetKeyByRadioId(booxDevicePresetGroup.checkedRadioButtonId)
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
        return Settings(includeUnread, showChart, showProgressStatus, showAuthor, minDurationMinutes, topN, weekStart, weekEnd, periodMode, readingFilterMode, sourceMode, wallpaperMode, coverFitMode, progressMode, timeUnit, receiptTitle, receiptTitleSize, receiptBodySize, serialNumberMode, serialNumberCustom, serialNumberSize, booxDevicePreset, footerMode, barcodeWidthScale, barcodeGapMode, noteText, chartStyleMode, showPeakLabel, yAxisMode, yAxisFixedMaxMinutes, titleFont, bodyFont)
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
            .putString("boox_device_preset", settings.booxDevicePreset)
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

    private fun dumpTextTree(view: View, maxItems: Int = 80): String {
        val out = mutableListOf<String>()
        fun walk(v: View, depth: Int) {
            if (out.size >= maxItems) return
            if (v is TextView) {
                val text = v.text?.toString()?.replace('\n', '|')?.take(120).orEmpty()
                if (text.isNotBlank()) {
                    out += "${"  ".repeat(depth)}${v.javaClass.simpleName}:$text visibility=${v.visibility}"
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(view, 0)
        return out.joinToString("\n").ifBlank { "<empty>" }
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
                w.append("deviceIdentity=").append(deviceIdentityText()).append('\n')
                w.append("detectedBooxDevicePreset=").append(detectBooxDevicePreset()).append('\n')
                w.append("currentPageKey=").append(currentPageKey).append('\n')
                if (::settingsPage.isInitialized) {
                    w.append("settingsPageVisibility=").append(settingsPage.visibility.toString()).append('\n')
                    w.append("previewPageVisibility=").append(previewPage.visibility.toString()).append('\n')
                }
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
                    .append(", booxDevicePreset=").append(s.booxDevicePreset)
                    .append(", footerMode=").append(s.footerMode)
                    .append(", noteText=").append(s.noteText)
                    .append(", titleFont=").append(s.titleFont)
                    .append(", bodyFont=").append(s.bodyFont)
                    .append('\n')
                w.append("lastSavedPath=").append(lastSavedPath ?: "<null>").append('\n')
                w.append("fontCount=").append(systemFonts.size.toString()).append('\n')
                w.append('\n')
                w.append("uiDebugReport=").append('\n').append(uiDebugReport.ifBlank { "<empty>" }).append('\n')
                if (::settingsPage.isInitialized) {
                    w.append("settingsPageTextDump=").append('\n').append(dumpTextTree(settingsPage)).append('\n')
                }
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
