package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

import java.io.File
import java.io.FileOutputStream
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
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

    private lateinit var topNInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var titleSizeInput: EditText
    private lateinit var nicknameInput: EditText

    private lateinit var customWallpaperWidthInput: EditText
    private lateinit var customWallpaperHeightInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var stickerValueView: TextView
    
    private lateinit var periodGroup: RadioGroup

    private lateinit var booxDevicePresetGroup: RadioGroup
    private lateinit var coverFitModeGroup: RadioGroup

    private lateinit var autoModeGroup: RadioGroup
    private lateinit var autoDailyTimeInput: EditText
    private lateinit var autoMinIntervalInput: EditText
    private lateinit var autoModeHintText: TextView
    private lateinit var autoStateText: TextView
    private lateinit var wereadApiKeyInput: EditText
    
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
    private var fontPermissionDebug: String = ""
    private var metadataDebugReport: String = ""
    private var metadataRowsDebugReport: String = ""
    private var localCalendarProbeReport: String = ""
    private var uiDebugReport: String = ""
    private var isTestingWeRead: Boolean = false
    private var lastWeReadWallpaperDebug: String = ""
    private val debugLogName = "neoreader_debug_log.txt"
    private var selectedFontDirUri: String? = null
    private var selectedStickerPath: String? = null

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

    private val pickStickerImageLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
            }
            selectedStickerPath = uri.toString()
            updateStickerDisplay()
            saveSettings(readSettingsFromUi())
            previewWeReadWallpaper()
        }
    }

    private fun updateStickerDisplay() {
        if (::stickerValueView.isInitialized) {
            stickerValueView.text = when {
                selectedStickerPath == null -> "未选择 ▼"
                selectedStickerPath?.startsWith("asset://") == true -> "asset: ${selectedStickerPath?.removePrefix("asset://")} ▼"
                selectedStickerPath?.startsWith("content://") == true -> "已选择 ▼"
                else -> "已选择 ▼"
            }
        }
    }

    data class BookItem(val title: String, val author: String?, val progress: String?, val status: Int, val path: String?)
    private data class CalendarMetaBook(
        val path: String,
        val title: String,
        val author: String,
        val lastAccessMs: Long,
        val hasCoverHint: Boolean
    )
    private data class CalendarDayStat(
        var events: Int = 0,
        var withPath: Int = 0,
        var orphan: Int = 0,
        var matched: Int = 0,
        var unmatched: Int = 0,
        var durationMs: Long = 0L,
        val books: LinkedHashMap<String, Long> = linkedMapOf(),
        val coverBooks: LinkedHashSet<String> = linkedSetOf()
    )

    enum class DataSourceMode { DURATION, PATH_SESSION, METADATA_ACCESS, WEREAD, MIXED }
    enum class PeriodMode { RECENT, THIS_WEEK, THIS_MONTH }

    data class Settings(
        val includeUnread: Boolean,
        val showProgressStatus: Boolean,

        val minDurationMinutes: Int,
        val topN: Int,
        val weekStartYmd: String,
        val weekEndYmd: String,
        val periodMode: PeriodMode,
        val sourceMode: DataSourceMode,
        val wallpaperMode: String,

        val coverFitMode: String,
        val progressMode: String,
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
        selectedStickerPath = prefs.getString("sticker_image_path", null)
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
        writeDebugLog("setupUi_done")
        startReadingStoreBootstrapIfNeeded()
    }

    private fun startReadingStoreBootstrapIfNeeded() {
        val prefs = getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(this)) {
            appendUiDebug("readingStoreMaintenance skip disabled")
            return
        }
        val key = "reading_store_bootstrap_neo_month_v1_done"
        Thread {
            val bootstrapDone = prefs.getBoolean(key, false)
            val bootstrapOk = if (bootstrapDone) {
                appendUiDebug("readingStoreBootstrap skip alreadyDone")
                true
            } else {
                AutoWallpaperGenerator.bootstrapReadingStoreIfNeeded(applicationContext).also { ok ->
                    if (ok) prefs.edit().putBoolean(key, true).apply()
                }
            }
            val incrementalOk = AutoWallpaperGenerator.syncRecentNeoReadingStore(applicationContext, "app_start")
            val weReadOk = WeReadReadingSync.syncCurrentMonth(applicationContext, "app_start")
            appendUiDebug(
                "readingStoreMaintenance finished bootstrapOk=$bootstrapOk incrementalOk=$incrementalOk weReadOk=$weReadOk sourceMode=WEREAD wallpaperMode=STATS"
            )
        }.apply {
            name = "reading-store-bootstrap"
            isDaemon = true
            start()
        }
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

    private fun detectBooxDevicePresetOrNull(): String? {
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
            else -> null
        }
    }

    private fun detectBooxDevicePreset(): String {
        return detectBooxDevicePresetOrNull() ?: BooxDevicePresets.DEFAULT_KEY
    }

    private fun booxPresetKeyByRadioId(id: Int): String {
        if (id == 1301) return BooxDevicePresets.CUSTOM_KEY
        return BooxDevicePresets.all.getOrNull(id - 1302)?.key ?: BooxDevicePresets.DEFAULT_KEY
    }

    private fun wallpaperSizeDisplayText(settings: Settings): String {
        return if (settings.booxDevicePreset == BooxDevicePresets.CUSTOM_KEY) {
            "自定义 ${settings.customWallpaperWidth}x${settings.customWallpaperHeight}"
        } else {
            BooxDevicePresets.byKey(settings.booxDevicePreset).displayText()
        }
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

        fun bindSecretEditRow(label: String, target: EditText): LinearLayout {
            fun masked(): String = WeReadClient.maskKey(target.text.toString()) + " ▼"
            val (row, value) = bindInputRow(label, { masked() }) {
                openTextEditDialog(label, target)
            }
            target.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    value.text = masked()
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

        val periodOptions = listOf(4000 to "最近", 4001 to "本周", 4007 to "本月")
        val periodNames = listOf(PeriodMode.RECENT.name, PeriodMode.THIS_WEEK.name, PeriodMode.THIS_MONTH.name)
        val savedPeriod = prefs.getString("period_mode", PeriodMode.THIS_WEEK.name) ?: PeriodMode.THIS_WEEK.name
        periodGroup = makeRadioGroup(periodOptions, selectedId(savedPeriod, 4001, periodOptions, periodNames))

        val matchedBooxPreset = detectBooxDevicePresetOrNull()
        val detectedBooxPreset = matchedBooxPreset ?: BooxDevicePresets.DEFAULT_KEY
        val booxDevicePresetOptions = listOf(1301 to "自定义分辨率\n手动输入宽度和高度") + BooxDevicePresets.all.mapIndexed { index, preset ->
            val matchMark = if (matchedBooxPreset != null && preset.key == matchedBooxPreset) " [本机匹配]" else ""
            (1302 + index) to "${preset.label}$matchMark\n${preset.inchText} ${preset.heightPx}x${preset.widthPx}"
        }
        val booxDevicePresetNames = listOf(BooxDevicePresets.CUSTOM_KEY) + BooxDevicePresets.all.map { it.key }
        val hasManualBooxPreset = prefs.getBoolean("boox_device_preset_user_set", false)
        val defaultBooxDevicePreset = if (hasManualBooxPreset && prefs.contains("boox_device_preset")) {
            prefs.getString("boox_device_preset", BooxDevicePresets.DEFAULT_KEY) ?: BooxDevicePresets.DEFAULT_KEY
        } else {
            detectedBooxPreset
        }
        appendUiDebug("booxDevicePreset default=$defaultBooxDevicePreset matched=${matchedBooxPreset ?: "none"} hasSaved=${prefs.contains("boox_device_preset")} userSet=$hasManualBooxPreset device=${deviceIdentityText()}")
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
        
        topNInput = makeInput(prefs.getInt("top_n", 5).coerceIn(1, 5).toString())
        titleInput = makeInput(prefs.getString("receipt_title", "Recipe") ?: "Recipe")
        titleSizeInput = makeInput(prefs.getFloat("receipt_title_size", 90f).toInt().toString())
        nicknameInput = makeInput(prefs.getString("weread_nickname", "开卷有益") ?: "开卷有益")

        customWallpaperWidthInput = makeInput(prefs.getInt("custom_wallpaper_width", BooxDevicePresets.byKey(defaultBooxDevicePreset).widthPx).coerceIn(300, 4000).toString())
        customWallpaperHeightInput = makeInput(prefs.getInt("custom_wallpaper_height", BooxDevicePresets.byKey(defaultBooxDevicePreset).heightPx).coerceIn(300, 4000).toString())
        noteInput = makeInput(prefs.getString("note_text", "*祝你用餐愉快！*") ?: "*祝你用餐愉快！*")
        autoDailyTimeInput = makeInput(prefs.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30")
        autoMinIntervalInput = makeInput(prefs.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).toString())
        titleFontSpinner = buildFontSpinner("title_font", "asset://CevicheOne-Regular.ttf")
        bodyFontSpinner = buildFontSpinner("body_font", "asset://迫真打字油印体.ttf")

        root.addView(hiddenHost)

        val booxDevicePresetRow = bindRadioChoiceRow("阅读器尺寸预设", booxDevicePresetGroup, booxDevicePresetOptions)
        appendUiDebug("buildSettingsPage added booxDevicePresetRow rootChildCount=${root.childCount} rowChildren=${booxDevicePresetRow.childCount}")

        val customWidthRow = bindEditRow("自定义宽度(px)", customWallpaperWidthInput, numericOnly = true, maxDigits = 4)
        val customHeightRow = bindEditRow("自定义高度(px)", customWallpaperHeightInput, numericOnly = true, maxDigits = 4)
        
        val periodSegment = bindSegmented("统计周期", periodGroup, periodOptions, isVertical = false)

        val coverFitSegment = bindSegmented("封面显示方式", coverFitModeGroup, coverFitOptions, isVertical = false)

        val topNSlider = bindSlider("Top N（显示书籍数量）", topNInput, 1, 5)

        val titleRow = bindEditRow("标题", titleInput)
        val titleSizeSlider = bindSlider("标题字体大小", titleSizeInput, 60, 140)
        val titleFontRow = bindSpinnerRow("标题字体", titleFontSpinner)
        val bodyFontRow = bindSpinnerRow("正文字体", bodyFontSpinner)
        val fontDirRow = bindInputRow("选择字体目录", { selectedFontDirUri?.let { "已选择 ▼" } ?: "未选择 ▼" }) {
            pickFontTreeLauncher.launch(null)
        }.first
        //选择存储/Fonts 后，可在标题字体和正文字体里使用里面的 ttf/otf 字体
        fontScanText = TextView(this).apply {
            text = fontScanReport
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }
        root.addView(fontScanText)

        val nicknameRow = bindEditRow("学厨名称", nicknameInput)

        val (stickerRow, stickerValue) = bindInputRow("选择贴纸", {
            when {
                selectedStickerPath == null -> "未选择 ▼"
                selectedStickerPath?.startsWith("asset://") == true -> "asset: ${selectedStickerPath?.removePrefix("asset://")} ▼"
                selectedStickerPath?.startsWith("content://") == true -> "已选择 ▼"
                else -> "已选择 ▼"
            }
        }) {
            showStickerSelectionDialog()
        }
        stickerValueView = stickerValue

        pickFontDirBtn = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { pickFontTreeLauncher.launch(null) }
            hiddenHost.addView(this)
        }

        val noteRow = bindEditRow("备注文本", noteInput)

        val autoOptions = listOf(8001 to "定时触发", 8002 to "熄屏触发")
        val autoNames = listOf(AutoRefreshConfig.MODE_DAILY, AutoRefreshConfig.MODE_SCREEN_OFF)
        autoModeGroup = makeRadioGroup(autoOptions, selectedId(prefs.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) ?: AutoRefreshConfig.MODE_DAILY, 8001, autoOptions, autoNames))
        val autoModeSegment = bindSegmented("壁纸刷新模式", autoModeGroup, autoOptions, isVertical = true)

        val autoDailyRow = bindInputRow("每日定时触发时间", { normalizeDailyTime(autoDailyTimeInput.text.toString()) }) { openDailyTimePicker() }.first
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

        wereadApiKeyInput = makeInput(WeReadClient.loadApiKey(this))
        val wereadKeyRow = bindSecretEditRow("微信读书API Key", wereadApiKeyInput)
        val wereadButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        wereadButtons.addView(Button(this).apply {
            text = "保存 Key"
            setOnClickListener {
                saveWeReadApiKeyFromUi()
                writeDebugLog("weread_key_saved")
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) })
        wereadButtons.addView(Button(this).apply {
            text = "测试连接"
            setOnClickListener { testWeReadConnection() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(wereadButtons)
        statusText = TextView(this).apply {
            text = "设置后点击按钮生成。"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 0)
            root.addView(this)
        }

        fun updateConditionalVisibility() {
            val customSize = booxPresetKeyByRadioId(booxDevicePresetGroup.checkedRadioButtonId) == BooxDevicePresets.CUSTOM_KEY
            customWidthRow.visibility = if (customSize) View.VISIBLE else View.GONE
            customHeightRow.visibility = if (customSize) View.VISIBLE else View.GONE

            coverFitSegment.visibility = View.GONE

            noteRow.visibility = View.VISIBLE

            autoModeSegment.visibility = View.VISIBLE
            autoDailyRow.visibility = if (autoModeGroup.checkedRadioButtonId == 8001) View.VISIBLE else View.GONE
            autoMinIntervalSlider.visibility = if (autoModeGroup.checkedRadioButtonId == 8002) View.VISIBLE else View.GONE
            autoModeHintText.visibility = View.VISIBLE
            autoStateText.visibility = View.VISIBLE
        }

        periodGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        autoModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        booxDevicePresetGroup.setOnCheckedChangeListener { _, _ ->
            updateConditionalVisibility()
            if (!isInitializingUi) {
                getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("boox_device_preset_user_set", true)
                    .apply()
                applySettingsPreview()
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
        coverFitModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        titleFontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (!isInitializingUi) applySettingsPreview() })
        bodyFontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (!isInitializingUi) applySettingsPreview() })
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
        customWallpaperWidthInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        customWallpaperHeightInput.addTextChangedListener(object : TextWatcher {
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
        previewPresetText = wallpaperSizeDisplayText(settings)
        statusText.text = "微信读书来源已保存\n请点击“刷新预览”或“生成壁纸”获取最新内容。"
        changeStateText.text = "状态: 微信读书来源参数已变更（未联网）｜尺寸: $previewPresetText"
        refreshPreview()
        writeDebugLog("weread_source_settings_saved")
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

    private fun showStickerSelectionDialog() {
        val options = arrayOf("drink 贴纸", "pudding 贴纸", "从本地导入", "清除选择")
        AlertDialog.Builder(this)
            .setTitle("选择贴纸")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        selectedStickerPath = "asset://drink.png"
                        updateStickerDisplay()
                        saveSettings(readSettingsFromUi())
                        previewWeReadWallpaper()
                    }
                    1 -> {
                        selectedStickerPath = "asset://pudding.png"
                        updateStickerDisplay()
                        saveSettings(readSettingsFromUi())
                        previewWeReadWallpaper()
                    }
                    2 -> {
                        pickStickerImageLauncher.launch(arrayOf("image/*"))
                    }
                    3 -> {
                        selectedStickerPath = null
                        updateStickerDisplay()
                        saveSettings(readSettingsFromUi())
                        previewWeReadWallpaper()
                    }
                }
            }
            .show()
    }

    private fun generateAndSaveFromCurrentSettings() {
        generateWeReadWallpaper()
    }

    private fun saveAndApplyAutoRefreshSettings() {
        val isEnabled = true
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
        autoModeHintText.text = "当前自动模式：$mode，熄屏最小间隔=${autoMinIntervalInput.text}"
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
            "user_present_prewarm" -> "解锁预热"
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

    private fun saveWeReadApiKeyFromUi() {
        if (!::wereadApiKeyInput.isInitialized) return
        WeReadClient.saveApiKey(this, wereadApiKeyInput.text.toString())
        appendUiDebug("weread api key saved key=${WeReadClient.maskKey(wereadApiKeyInput.text.toString())}")
    }

    private fun testWeReadConnection() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        isTestingWeRead = true
        Thread {
            val result = WeReadClient.testConnection(applicationContext, WeReadClient.loadApiKey(applicationContext))
            runOnUiThread {
                isTestingWeRead = false
                appendUiDebug("weread test ok=${result.ok} detail=${result.detail.take(120)}")
                writeDebugLog("weread_test")
            }
        }.start()
    }

    private fun weReadPeriodLabel(periodMode: PeriodMode): String {
        return when (periodMode) {
            PeriodMode.RECENT -> "最近"
            PeriodMode.THIS_WEEK -> "本周"
            PeriodMode.THIS_MONTH -> "本月"
        }
    }

    private fun previewWeReadWallpaper() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        val settings = readSettingsFromUi()
        saveSettings(settings)
        val periodLabel = weReadPeriodLabel(settings.periodMode)
        val sourceLabel = "微信读书"
        isTestingWeRead = true
        changeStateText.text = "状态: 正在生成${sourceLabel}预览..."
        Thread {
            val preview = buildSourcePreviewForWallpaperMode(settings)
            runOnUiThread {
                isTestingWeRead = false
                if (preview != null) {
                    previewBitmap = preview.bitmap
                    previewPresetText = wallpaperSizeDisplayText(readSettingsFromUi())
                    lastWeReadWallpaperDebug = "ok=true, period=$periodLabel, summary=${preview.summary}"
                    refreshPreview()
                    showPreviewPage()
                } else {
                    lastWeReadWallpaperDebug = "ok=false, period=$periodLabel"
                }
                appendUiDebug("weread wallpaper $lastWeReadWallpaperDebug")
                writeDebugLog("weread_wallpaper_preview")
            }
        }.start()
    }

    private fun generateWeReadWallpaper() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        val settings = readSettingsFromUi()
        saveSettings(settings)
        val periodLabel = weReadPeriodLabel(settings.periodMode)
        val sourceLabel = "微信读书"
        isTestingWeRead = true
        Thread {
            val preview = buildSourcePreviewForWallpaperMode(settings)
            runOnUiThread {
                isTestingWeRead = false
                if (preview != null) {
                    val saved = saveBitmapToPictures(preview.bitmap)
                    previewBitmap = preview.bitmap
                    lastSavedPath = saved
                    previewPresetText = wallpaperSizeDisplayText(readSettingsFromUi())
                    statusText.text = "${sourceLabel}壁纸已生成并覆盖文件\n${preview.summary}\n路径: $saved"
                    changeStateText.text = "状态: ${sourceLabel}壁纸已生成并保存｜尺寸: $previewPresetText"
                    lastWeReadWallpaperDebug = "ok=true, period=$periodLabel, saved=$saved, summary=${preview.summary}"
                    refreshPreview()
                    showPreviewPage()
                } else {
                    changeStateText.text = "状态: ${sourceLabel}生成失败"
                    lastWeReadWallpaperDebug = "ok=false, period=$periodLabel, saved=<none>"
                }
                appendUiDebug("weread wallpaper generated $lastWeReadWallpaperDebug")
                writeDebugLog("weread_wallpaper_generated")
            }
        }.start()
    }

    private fun buildWeReadPreviewForWallpaperMode(wallpaperMode: String): AutoWallpaperGenerator.PreviewResult? {
        return AutoWallpaperGenerator.buildWeReadStatsPreviewFromPrefs(applicationContext, "W")
    }

    private fun buildSourcePreviewForWallpaperMode(settings: Settings): AutoWallpaperGenerator.PreviewResult? {
        return buildWeReadPreviewForWallpaperMode(settings.wallpaperMode)
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
        previewWeReadWallpaper()
        collectMetadataDebugSample()
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
        collectLocalCalendarDebugProbe()
    }

    private fun collectLocalCalendarDebugProbe() {
        runCatching {
            val now = System.currentTimeMillis()
            val end = endOfDayMs(now)
            val start = startOfDayMs(now - 29L * 24L * 60L * 60L * 1000L)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val metaByPath = linkedMapOf<String, CalendarMetaBook>()
            val metaByName = linkedMapOf<String, CalendarMetaBook>()
            contentResolver.query(
                metadataUri,
                arrayOf("nativeAbsolutePath", "title", "authors", "lastAccess", "coverUrl", "extraInfo", "downloadInfo"),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    fun col(name: String): String {
                        val i = c.getColumnIndex(name)
                        if (i < 0 || c.isNull(i)) return ""
                        return runCatching { c.getString(i) ?: "" }.getOrDefault("")
                    }
                    val path = col("nativeAbsolutePath")
                    if (path.isBlank()) continue
                    val title = col("title").ifBlank { File(path).nameWithoutExtension }
                    val author = col("authors")
                    val lastAccess = normalizeEpochMs(col("lastAccess").toLongOrNull() ?: 0L)
                    val coverHint = listOf("coverUrl", "extraInfo", "downloadInfo").any { col(it).isNotBlank() } || hasExtractedCoverCache(path)
                    val book = CalendarMetaBook(path, title, author, lastAccess, coverHint)
                    metaByPath[path] = book
                    metaByName[File(path).name] = book
                }
            }

            val days = linkedMapOf<Long, CalendarDayStat>()
            for (i in 0 until 30) {
                days[start + i * 24L * 60L * 60L * 1000L] = CalendarDayStat()
            }
            var statsRows = 0
            var statsRowsWithPath = 0
            var exactMatches = 0
            var nameMatches = 0
            var timeMatches = 0
            var unmatched = 0
            contentResolver.query(
                statsUri,
                arrayOf("path", "eventTime", "durationTime"),
                "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
                arrayOf(start.toString(), end.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    fun col(name: String): String {
                        val i = c.getColumnIndex(name)
                        if (i < 0 || c.isNull(i)) return ""
                        return runCatching { c.getString(i) ?: "" }.getOrDefault("")
                    }
                    statsRows += 1
                    val path = col("path")
                    val eventMs = normalizeEpochMs(col("eventTime").toLongOrNull() ?: 0L)
                    val dur = col("durationTime").toLongOrNull() ?: 0L
                    if (eventMs <= 0L || dur <= 0L) continue
                    val dayStart = startOfDayMs(eventMs)
                    val day = days.getOrPut(dayStart) { CalendarDayStat() }
                    day.events += 1
                    day.durationMs += dur
                    val matchedBook = if (path.isNotBlank()) {
                        statsRowsWithPath += 1
                        day.withPath += 1
                        metaByPath[path]?.also { exactMatches += 1 }
                            ?: metaByName[File(path).name]?.also { nameMatches += 1 }
                    } else {
                        day.orphan += 1
                        null
                    } ?: findNearestCalendarBook(metaByPath.values, eventMs)?.also {
                        timeMatches += 1
                    }

                    if (matchedBook == null) {
                        day.unmatched += 1
                        unmatched += 1
                    } else {
                        day.matched += 1
                        day.books[matchedBook.title] = (day.books[matchedBook.title] ?: 0L) + dur
                        if (matchedBook.hasCoverHint) day.coverBooks.add(matchedBook.title)
                    }
                }
            }

            val out = StringBuilder()
            out.append("range=").append(dateFmt.format(Date(start))).append("~").append(dateFmt.format(Date(end))).append('\n')
            out.append("statsRows=").append(statsRows)
                .append(", rowsWithPath=").append(statsRowsWithPath)
                .append(", metadata=").append(metaByPath.size)
                .append(", exactMatches=").append(exactMatches)
                .append(", nameMatches=").append(nameMatches)
                .append(", timeMatches=").append(timeMatches)
                .append(", unmatched=").append(unmatched)
                .append('\n')
            days.entries.forEach { (dayStart, stat) ->
                if (stat.events == 0 && stat.books.isEmpty()) return@forEach
                val top = stat.books.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .joinToString(" | ") { "${it.key.take(24)}:${formatMinutesForDebug(it.value)}" }
                    .ifBlank { "<no-book-match>" }
                out.append(dateFmt.format(Date(dayStart)))
                    .append(" events=").append(stat.events)
                    .append(", withPath=").append(stat.withPath)
                    .append(", orphan=").append(stat.orphan)
                    .append(", matched=").append(stat.matched)
                    .append(", unmatched=").append(stat.unmatched)
                    .append(", total=").append(formatMinutesForDebug(stat.durationMs))
                    .append(", coverBooks=").append(stat.coverBooks.size)
                    .append(", top=").append(top)
                    .append('\n')
            }
            localCalendarProbeReport = out.toString().ifBlank { "<empty>" }
        }.onFailure {
            localCalendarProbeReport = "error=${it.javaClass.simpleName}:${it.message}"
        }
    }

    private fun findNearestCalendarBook(books: Collection<CalendarMetaBook>, eventMs: Long): CalendarMetaBook? {
        val maxDelta = 12L * 60L * 60L * 1000L
        var best: CalendarMetaBook? = null
        var bestDelta = Long.MAX_VALUE
        books.forEach { book ->
            if (book.lastAccessMs <= 0L) return@forEach
            val delta = kotlin.math.abs(book.lastAccessMs - eventMs)
            if (delta < bestDelta) {
                best = book
                bestDelta = delta
            }
        }
        return if (bestDelta <= maxDelta) best else null
    }

    private fun hasExtractedCoverCache(path: String): Boolean {
        if (path.isBlank()) return false
        val f = File(path)
        val cacheFile = File(File(cacheDir, "extracted_covers"), "${path.hashCode()}_${f.lastModified()}.jpg")
        return cacheFile.exists() && cacheFile.length() > 0L
    }

    private fun normalizeEpochMs(value: Long): Long {
        return when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }
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

    private fun endOfDayMs(ms: Long): Long {
        return startOfDayMs(ms) + 24L * 60L * 60L * 1000L - 1L
    }

    private fun formatMinutesForDebug(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val remain = minutes % 60L
        return if (hours > 0L) "${hours}h${remain}m" else "${minutes}m"
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
        val includeUnread = false
        val showProgressStatus = true
        val minDurationMinutes = 5
        val topN = topNInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 5) ?: 5
        val weekStart = selectedWeekStartYmd.ifBlank { currentWeekStartYmd() }
        val weekEnd = selectedWeekEndYmd.ifBlank { currentWeekEndYmd() }
        val periodMode = when (periodGroup.checkedRadioButtonId) {
            4000 -> PeriodMode.RECENT
            4001 -> PeriodMode.THIS_WEEK
            4007 -> PeriodMode.THIS_MONTH
            else -> PeriodMode.THIS_WEEK
        }
        val sourceMode = DataSourceMode.WEREAD
        val wallpaperMode = "STATS"
        val coverFitMode = when (coverFitModeGroup.checkedRadioButtonId) {
            1212 -> "CROP"
            else -> "FIT"
        }
        val progressMode = "PERCENT"
        val booxDevicePreset = booxPresetKeyByRadioId(booxDevicePresetGroup.checkedRadioButtonId)
        val fallbackPreset = BooxDevicePresets.byKey(if (booxDevicePreset == BooxDevicePresets.CUSTOM_KEY) BooxDevicePresets.DEFAULT_KEY else booxDevicePreset)
        val customWallpaperWidth = customWallpaperWidthInput.text.toString().trim().toIntOrNull()?.coerceIn(300, 4000) ?: fallbackPreset.widthPx
        val customWallpaperHeight = customWallpaperHeightInput.text.toString().trim().toIntOrNull()?.coerceIn(300, 4000) ?: fallbackPreset.heightPx
        val receiptTitle = titleInput.text.toString().ifBlank { "Recipe" }
        val receiptTitleSize = titleSizeInput.text.toString().toFloatOrNull() ?: 90f
        val receiptBodySize = 34f
        val weReadNickname = nicknameInput.text.toString().ifBlank { "开卷有益" }
        val noteText = noteInput.text.toString().trim().ifBlank { "*祝你用餐愉快！*" }
        //默认内容
        val titleFont = fontSpec(titleFontSpinner.selectedItem?.toString() ?: "asset://CevicheOne-Regular.ttf")
        val bodyFont = fontSpec(bodyFontSpinner.selectedItem?.toString() ?: "asset://迫真打字油印体.ttf")
        return Settings(includeUnread, showProgressStatus, minDurationMinutes, topN, weekStart, weekEnd, periodMode, sourceMode, wallpaperMode, coverFitMode, progressMode, receiptTitle, receiptTitleSize, receiptBodySize, weReadNickname, booxDevicePreset, customWallpaperWidth, customWallpaperHeight, noteText, titleFont, bodyFont, selectedStickerPath)
    }

    private fun saveSettings(settings: Settings) {
        getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("include_unread", settings.includeUnread)
            .putBoolean("show_progress_status", settings.showProgressStatus)
            .putInt("top_n", settings.topN)
            .putString("week_start", settings.weekStartYmd)
            .putString("week_end", settings.weekEndYmd)
            .putString("period_mode", settings.periodMode.name)
            .putString("source_mode", settings.sourceMode.name)
            .putString("wallpaper_mode", settings.wallpaperMode)

            .putString("cover_fit_mode", settings.coverFitMode)
            .putString("progress_mode", settings.progressMode)
            .putString("receipt_title", settings.receiptTitle)
            .putFloat("receipt_title_size", settings.receiptTitleSize)
            .putFloat("receipt_body_size", settings.receiptBodySize)
            .putString("weread_nickname", settings.weReadNickname)
            .putString("boox_device_preset", settings.booxDevicePreset)
            .putInt("custom_wallpaper_width", settings.customWallpaperWidth)
            .putInt("custom_wallpaper_height", settings.customWallpaperHeight)
            .putString("note_text", settings.noteText)
            .putString("title_font", settings.titleFont)
            .putString("body_font", settings.bodyFont)
            .putString("sticker_image_path", settings.stickerImagePath)
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
        cal.firstDayOfWeek = Calendar.MONDAY
        //一周从周一开始
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun currentWeekEndYmd(): String {
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        c.add(Calendar.DAY_OF_MONTH, 6)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis))
    }

    private fun loadSystemFonts(): List<String> {
        val result = mutableListOf("SERIF_BOLD", "SANS", "MONO")
        val report = StringBuilder("字体目录扫描:\n")
        report.append("fontTreeUri=").append(selectedFontDirUri ?: "<null>").append("\n")
        report.append("fontPermissionDebug=").append(fontPermissionDebug.ifBlank { "<empty>" }).append("\n")

        try {
            assets.list("")?.forEach { fileName ->
                if (fileName.endsWith(".ttf", true) || fileName.endsWith(".otf", true)) {
                    result.add("${fileName}${FONT_ENTRY_SEP}asset://${fileName}")
                }
            }
            report.append("assetFontCount=").append(result.size - 3).append("\n")
        } catch (e: Exception) {
            report.append("assetFontError=").append(e.message ?: "unknown").append("\n")
        }

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
        fun redactSecrets(text: String): String {
            return Regex("""wrk-[A-Za-z0-9_=-]{8,}""").replace(text) { match ->
                WeReadClient.maskKey(match.value)
            }
        }
        fun walk(v: View, depth: Int) {
            if (out.size >= maxItems) return
            if (v is TextView) {
                val text = redactSecrets(v.text?.toString().orEmpty())
                    .replace('\n', '|')
                    .take(120)
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
            if (localCalendarProbeReport.isBlank()) collectLocalCalendarDebugProbe()
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
                val wereadState = WeReadClient.cachedState(this)
                w.append("weread=").append("key=").append(wereadState.maskedKey)
                    .append(", status=").append(wereadState.status)
                    .append(", lastTestMs=").append(wereadState.lastTestMs.toString())
                    .append(", error=").append(wereadState.error)
                    .append('\n')
                w.append("lastWeReadWallpaper=").append(lastWeReadWallpaperDebug.ifBlank { "<empty>" }).append('\n')
                w.append("currentPageKey=").append(currentPageKey).append('\n')
                if (::settingsPage.isInitialized) {
                    w.append("settingsPageVisibility=").append(settingsPage.visibility.toString()).append('\n')
                    w.append("previewPageVisibility=").append(previewPage.visibility.toString()).append('\n')
                }
                w.append("selectedWeekStart=").append(selectedWeekStartYmd).append('\n')
                w.append("settings=").append("includeUnread=").append(s.includeUnread.toString())
                    .append(", showProgressStatus=").append(s.showProgressStatus.toString())
                    .append(", topN=").append(s.topN.toString())
                    .append(", sourceMode=").append(s.sourceMode.name)
                    .append(", wallpaperMode=").append(s.wallpaperMode)
                    .append(", coverFitMode=").append(s.coverFitMode)
                    .append(", progressMode=").append(s.progressMode)
                    .append(", receiptTitle=").append(s.receiptTitle)
                    .append(", receiptTitleSize=").append(s.receiptTitleSize.toString())
                    .append(", receiptBodySize=").append(s.receiptBodySize.toString())
                    .append(", weReadNickname=").append(s.weReadNickname)
                    .append(", booxDevicePreset=").append(s.booxDevicePreset)
                    .append(", customWallpaperWidth=").append(s.customWallpaperWidth.toString())
                    .append(", customWallpaperHeight=").append(s.customWallpaperHeight.toString())
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
                w.append("metadataDebug=").append(metadataDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("metadataRowsDebug=").append('\n').append(metadataRowsDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("localCalendarProbe=").append('\n').append(localCalendarProbeReport.ifBlank { "<empty>" }).append('\n')
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
