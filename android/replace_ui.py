import re

with open("app/src/main/java/com/dmer/neoreaderrecords/MainActivity.kt", "r") as f:
    content = f.read()

# Make sure we got it
match = re.search(r'private fun buildEinkUiPage\(\): View \{.*?\n    \}', content, flags=re.DOTALL)
if not match:
    print("Function not found!")
    exit(1)

new_fn = """private fun buildEinkUiPage(): View {
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
    }"""

content = content[:match.start()] + new_fn + content[match.end():]
with open("app/src/main/java/com/dmer/neoreaderrecords/MainActivity.kt", "w") as f:
    f.write(content)
print("done")
