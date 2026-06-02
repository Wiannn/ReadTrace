package com.dmer.neoreaderrecords

data class BooxDevicePreset(
    val key: String,
    val label: String,
    val inchText: String,
    val widthPx: Int,
    val heightPx: Int
) {
    fun displayText(): String = "$label $inchText ${heightPx}x$widthPx"
}

object BooxDevicePresets {
    const val DEFAULT_KEY = "LEAF5"

    val all: List<BooxDevicePreset> = listOf(
        BooxDevicePreset("POKE6S", "Poke6S", "6英寸", 758, 1024),
        BooxDevicePreset("POKE6", "Poke6", "6英寸", 1072, 1448),
        BooxDevicePreset("POKE7", "Poke7", "6英寸", 1072, 1448),
        BooxDevicePreset("POKE7_PRO", "Poke7 Pro", "6英寸", 1072, 1448),
        BooxDevicePreset("P6", "P6 / P6+", "6.13英寸", 824, 1648),
        BooxDevicePreset("P6_PRO", "P6 Pro / P6 Pro C", "6.13英寸", 824, 1648),
        BooxDevicePreset("PALMA", "Palma", "6.13英寸", 824, 1648),
        BooxDevicePreset("LEAF5", "Leaf5", "7英寸", 1264, 1680),
        BooxDevicePreset("LEAF5C", "Leaf5C", "7英寸", 1264, 1680),
        BooxDevicePreset("LEAF5_PLUS", "Leaf5+", "7英寸", 1264, 1680),
        BooxDevicePreset("PAGE", "Page", "7英寸", 1264, 1680),
        BooxDevicePreset("NOTE_X5_MINI", "Note X5 mini", "7.8英寸", 1404, 1872),
        BooxDevicePreset("NOTE_AIR3", "Note Air3", "10.3英寸", 1404, 1872),
        BooxDevicePreset("NOTE_X5S", "Note X5S", "10.3英寸", 1404, 1872),
        BooxDevicePreset("NOTE_X5", "Note X5", "10.3英寸", 1860, 2480),
        BooxDevicePreset("NOTEX6", "NoteX6", "10.3英寸", 1860, 2480),
        BooxDevicePreset("T10C", "T10 C / T10C+", "10.3英寸", 1860, 2480),
        BooxDevicePreset("TAB10C_PRO", "Tab 10C Pro", "10.3英寸", 1860, 2480),
        BooxDevicePreset("NOTE_AIR3C", "Note Air3 C", "10.3英寸", 1860, 2480),
        BooxDevicePreset("T13C", "T13 C", "13.3英寸", 2400, 3200)
    )

    fun byKey(key: String?): BooxDevicePreset {
        return all.firstOrNull { it.key == key } ?: all.first { it.key == DEFAULT_KEY }
    }
}
