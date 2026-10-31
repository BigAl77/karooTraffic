package com.mybiketraffic.karootraffic.karoo

import com.mybiketraffic.karootraffic.engine.TrafficSnapshot
import com.mybiketraffic.karootraffic.preferences.TrafficDisplaySettings
import kotlin.math.roundToInt

/**
 * Pure Kotlin port of MyBikeTrafficView.mc layout, field ordering, font sizing,
 * digit-growth layout triggers, and double-tap reset state.
 */
class MyBikeTrafficLayoutCalculator(
    settings: TrafficDisplaySettings = TrafficDisplaySettings(),
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    companion object {
        const val FIELD_TOTAL = 0
        const val FIELD_LAP = 1
        const val FIELD_SPEED_RELATIVE = 2
        const val FIELD_SPEED_ABSOLUTE = 3
        const val FIELD_SPEED_LAST = 4
        const val FIELD_DISTANCE_CLOSEST = 5

        const val FONT_XTINY = 0
        const val FONT_TINY = 1
        const val FONT_SMALL = 2
        const val FONT_MEDIUM = 3
        const val FONT_LARGE = 4
        const val FONT_NUMBER_MILD = 5
        const val FONT_NUMBER_MEDIUM = 6
        const val FONT_NUMBER_HOT = 7
        const val FONT_NUMBER_THAI_HOT = 8
        const val FONT_COUNT = 9

        const val RESET_CONFIRM_SECONDS = 1L

        val LABELS_SET_1 = listOf(
            "VehicleCount",
            "Lap Vehicle Count",
            "Relative Speed",
            "Absolute Speed",
            "Last Speed",
            "Vehicle Distance"
        )

        val LABELS_SET_2 = listOf("VC", "LAP", "REL", "ABS", "LAST", "DIST")
        val LABELS_SET_3 = listOf("VC", "LAP", "REL", "ABS", "LAST", "DIST")

        fun countDigits(num: Int): Int {
            return if (num < 1000) {
                if (num < 100) {
                    if (num < 10) 1 else 2
                } else {
                    3
                }
            } else {
                5
            }
        }
    }

    interface TextMeasurer {
        fun getTextDimensions(text: String, fontIdx: Int): Pair<Int, Int>
        fun getFontHeight(fontIdx: Int): Int
    }

    var vertical: Boolean = true
        private set
    var autoOrientation: Int = settings.autoOrientation
        private set
    var autoLabelSize: Int = settings.autoLabelSize
        private set
    var autoValueSize: Int = settings.autoValueSize
        private set
    var showDebugStatus: Boolean = settings.displayDebugStatus
        private set

    val statusFont: Int = FONT_TINY
    val unitsFont: Int = FONT_XTINY
    var labelFont: Int = FONT_TINY
        private set
    var valueFont: Int = FONT_MEDIUM
        private set
    var fh: Int = 0
        private set

    var pendingResetTap: Boolean = false
        private set
    private var pendingResetTapDeadline: Long? = null

    var labelSet: Int = 1
        private set
    var displayedFields: List<Int> = listOf(FIELD_TOTAL)
        private set
    val numFields: Int
        get() = displayedFields.size

    var labelX: List<Float> = emptyList()
        private set
    var labelY: List<Float> = emptyList()
        private set

    var testString: String = "888"
        private set
    var totalDigits: Int = 2
        private set
    var needLayout: Boolean = true
        private set

    private var fieldPositions: IntArray = intArrayOf(1, 0, 0, 2, 0, 0)

    init {
        applySettings(settings)
    }

    fun applySettings(settings: TrafficDisplaySettings) {
        val displayPositions = settings.displayPositions
        for (i in fieldPositions.indices) {
            if (i < displayPositions.size) {
                fieldPositions[i] = displayPositions[i]
            }
        }
        showDebugStatus = settings.displayDebugStatus
        autoOrientation = settings.autoOrientation
        autoLabelSize = settings.autoLabelSize
        autoValueSize = settings.autoValueSize

        rebuildDisplayedFields()

        labelFont = if (autoLabelSize in 0 until FONT_COUNT) autoLabelSize else FONT_TINY
        valueFont = if (autoValueSize in 0 until FONT_COUNT) autoValueSize else FONT_MEDIUM

        labelSet = when (numFields) {
            1 -> 1
            2 -> 2
            else -> 3
        }
        needLayout = true
    }

    private fun rebuildDisplayedFields() {
        val list = mutableListOf<Int>()
        for (slot in 1..fieldPositions.size) {
            for (fieldIndex in fieldPositions.indices) {
                if (fieldPositions[fieldIndex] == slot) {
                    list.add(fieldIndex)
                }
            }
        }
        for (fieldIndex in fieldPositions.indices) {
            if (fieldPositions[fieldIndex] > fieldPositions.size) {
                list.add(fieldIndex)
            }
        }
        if (list.isEmpty()) {
            list.add(FIELD_TOTAL)
        }
        displayedFields = list
    }

    fun getLabels(): List<String> {
        return when (labelSet) {
            1 -> LABELS_SET_1
            2 -> LABELS_SET_2
            else -> LABELS_SET_3
        }
    }

    fun getStatusReserveHeight(measurer: TextMeasurer): Int {
        if (!showDebugStatus && !pendingResetTap) {
            return 0
        }
        return measurer.getFontHeight(statusFont) + 2
    }

    fun tickPendingResetTap() {
        if (!pendingResetTap) return
        val now = clockSeconds()
        val deadline = pendingResetTapDeadline
        if (deadline == null || now >= deadline) {
            clearPendingResetTap()
            needLayout = true
        }
    }

    fun clearPendingResetTap() {
        pendingResetTap = false
        pendingResetTapDeadline = null
    }

    /**
     * Handles a user tap on the data field.
     *
     * @return True if the second tap confirmed a radar reset, false if first tap armed confirmation.
     */
    fun handleResetTap(onConfirmedReset: () -> Unit): Boolean {
        tickPendingResetTap()
        if (!pendingResetTap) {
            val now = clockSeconds()
            pendingResetTap = true
            pendingResetTapDeadline = now + RESET_CONFIRM_SECONDS
            needLayout = true
            return false
        }

        clearPendingResetTap()
        needLayout = true
        onConfirmedReset()
        return true
    }

    fun onCompute(count: Int) {
        tickPendingResetTap()
        val newTotalDigits = if (vertical) {
            countDigits(count)
        } else {
            countDigits(count * numFields)
        }
        if (newTotalDigits > totalDigits) {
            while (totalDigits < newTotalDigits) {
                testString += "8"
                totalDigits += 1
            }
            needLayout = true
        }
    }

    fun selectFont(measurer: TextMeasurer, width: Int, height: Int): Int {
        var fontIdx = FONT_COUNT - 1
        while (fontIdx > 0) {
            val dimensions = measurer.getTextDimensions(testString, fontIdx)
            if (dimensions.first <= width && dimensions.second <= height - 2) {
                break
            }
            fontIdx--
        }
        fh = measurer.getFontHeight(fontIdx)
        return fontIdx
    }

    fun onLayout(measurer: TextMeasurer, totalWidth: Int, totalHeight: Int) {
        val width = totalWidth
        val height = totalHeight - getStatusReserveHeight(measurer)
        val top = 5f

        labelFont = if (autoLabelSize in 0 until FONT_COUNT) autoLabelSize else FONT_TINY

        if (autoOrientation == 0 || (autoOrientation == -1 && (numFields < 3 || width > 180))) {
            vertical = false
            val vroom = height - top
            val labelHeight = if (autoLabelSize != -1) measurer.getFontHeight(labelFont).toFloat() else top
            val vfontmax = (vroom - labelHeight).roundToInt()
            val hfontmax = (width.toFloat() / numFields).roundToInt()
            if (autoValueSize == -1) {
                valueFont = selectFont(measurer, hfontmax, vfontmax)
            } else {
                valueFont = autoValueSize.coerceIn(0, FONT_COUNT - 1)
                fh = measurer.getFontHeight(valueFont)
            }
            labelY = listOf(top, labelHeight)
            labelX = when (numFields) {
                1 -> listOf(0.5f * width)
                2 -> listOf(0.33f * width, 0.67f * width)
                3 -> listOf(0.25f * width, 0.55f * width, 0.8f * width)
                4 -> listOf(0.2f * width, 0.43f * width, 0.63f * width, 0.84f * width)
                5 -> listOf(0.12f * width, 0.27f * width, 0.43f * width, 0.59f * width, 0.84f * width)
                6 -> listOf(0.08f * width, 0.22f * width, 0.36f * width, 0.50f * width, 0.64f * width, 0.88f * width)
                else -> listOf(0.5f * width)
            }
        } else {
            vertical = true
            val vroom = height - top
            val vfontmax = (vroom / numFields).roundToInt()
            val nonlabelfract = if (autoLabelSize != -1) 2.0f / 3.0f else 0.95f
            val hfontmax = (width * nonlabelfract).roundToInt()
            labelX = if (autoLabelSize != -1) {
                val split = (width * (1.15f / 3.0f)).roundToInt().toFloat()
                listOf(split - 3f, split + 3f)
            } else {
                listOf(0f, 3f)
            }
            if (autoValueSize == -1) {
                valueFont = selectFont(measurer, hfontmax, vfontmax)
            } else {
                valueFont = autoValueSize.coerceIn(0, FONT_COUNT - 1)
                fh = measurer.getFontHeight(valueFont)
            }
            val valueHeight = measurer.getFontHeight(valueFont).toFloat()
            labelY = List(numFields) { idx -> top + valueHeight * idx }
        }

        while (autoLabelSize != -1 && labelFont >= valueFont && labelFont > 0) {
            labelFont -= 1
        }
        needLayout = false
    }

    fun formatFieldValue(fieldIndex: Int, snapshot: TrafficSnapshot?): String {
        if (snapshot == null || snapshot.disabled) {
            return "--"
        }
        return when (fieldIndex) {
            FIELD_TOTAL -> snapshot.count.toString()
            FIELD_LAP -> snapshot.lapcount.toString()
            FIELD_SPEED_RELATIVE -> snapshot.approachspd.toString()
            FIELD_SPEED_ABSOLUTE -> snapshot.absolutespd.toString()
            FIELD_SPEED_LAST -> snapshot.lastspd.toString()
            FIELD_DISTANCE_CLOSEST -> snapshot.dist.toString()
            else -> snapshot.count.toString()
        }
    }

    fun showsSpeedUnits(fieldIndex: Int): Boolean {
        return fieldIndex == FIELD_SPEED_RELATIVE ||
            fieldIndex == FIELD_SPEED_ABSOLUTE ||
            fieldIndex == FIELD_SPEED_LAST
    }

    fun showsDistanceUnits(fieldIndex: Int): Boolean {
        return fieldIndex == FIELD_DISTANCE_CLOSEST
    }

    fun getStatusText(snapshot: TrafficSnapshot?): String {
        if (pendingResetTap) return "TAP AGAIN"
        return snapshot?.statusText ?: "START"
    }
}
