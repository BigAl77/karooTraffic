package com.mybiketraffic.karootraffic.karoo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.mybiketraffic.karootraffic.engine.TrafficSnapshot
import com.mybiketraffic.karootraffic.preferences.TrafficDisplaySettings
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders the Garmin MyBikeTrafficView layout onto an Android Canvas / Bitmap.
 */
class MyBikeTrafficCanvasRenderer(
    settings: TrafficDisplaySettings = TrafficDisplaySettings(),
    clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    val layoutCalculator = MyBikeTrafficLayoutCalculator(settings, clockSeconds)

    // Pixel sizes matching the 9 Garmin ConnectIQ font constants on Karoo's ~240dpi display
    private val fontPixelSizes = floatArrayOf(
        14f, // 0: FONT_XTINY
        18f, // 1: FONT_TINY
        22f, // 2: FONT_SMALL
        28f, // 3: FONT_MEDIUM
        36f, // 4: FONT_LARGE
        46f, // 5: FONT_NUMBER_MILD
        58f, // 6: FONT_NUMBER_MEDIUM
        72f, // 7: FONT_NUMBER_HOT
        90f  // 8: FONT_NUMBER_THAI_HOT
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val boundsRect = Rect()
    private var lastWidth: Int = -1
    private var lastHeight: Int = -1

    private val measurer = object : MyBikeTrafficLayoutCalculator.TextMeasurer {
        override fun getTextDimensions(text: String, fontIdx: Int): Pair<Int, Int> {
            configurePaintForFont(fontIdx)
            val width = paint.measureText(text).roundToInt()
            val height = getFontHeight(fontIdx)
            return Pair(width, height)
        }

        override fun getFontHeight(fontIdx: Int): Int {
            configurePaintForFont(fontIdx)
            val fm = paint.fontMetrics
            return (fm.descent - fm.ascent).roundToInt()
        }
    }

    private fun configurePaintForFont(fontIdx: Int) {
        val idx = fontIdx.coerceIn(0, fontPixelSizes.size - 1)
        paint.textSize = fontPixelSizes[idx]
        paint.typeface = if (idx >= MyBikeTrafficLayoutCalculator.FONT_NUMBER_MILD) {
            Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        } else {
            Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }
    }

    fun applySettings(settings: TrafficDisplaySettings) {
        layoutCalculator.applySettings(settings)
    }

    fun handleResetTap(onConfirmedReset: () -> Unit): Boolean {
        return layoutCalculator.handleResetTap(onConfirmedReset)
    }

    fun renderToBitmap(
        bitmap: Bitmap,
        snapshot: TrafficSnapshot?,
        darkTheme: Boolean
    ) {
        val canvas = Canvas(bitmap)
        render(canvas, bitmap.width, bitmap.height, snapshot, darkTheme)
    }

    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        snapshot: TrafficSnapshot?,
        darkTheme: Boolean
    ) {
        if (width <= 0 || height <= 0) return

        layoutCalculator.onCompute(snapshot?.count ?: 0)
        if (layoutCalculator.needLayout || width != lastWidth || height != lastHeight) {
            lastWidth = width
            lastHeight = height
            layoutCalculator.onLayout(measurer, width, height)
        }

        val bgColor = if (darkTheme) Color.BLACK else Color.WHITE
        val fgColor = if (darkTheme) Color.WHITE else Color.BLACK
        val lblColor = fgColor
        val statusColor = if (darkTheme) Color.LTGRAY else Color.DKGRAY

        canvas.drawColor(bgColor)

        val disabled = snapshot == null || snapshot.disabled
        val metric = snapshot?.metric ?: true
        val unitsStr = if (disabled) " " else if (metric) "kph" else "mph"
        val dUnitsStr = if (disabled) " " else if (metric) "m" else "ft"

        val labels = if (layoutCalculator.autoLabelSize != -1) layoutCalculator.getLabels() else null
        val displayedFields = layoutCalculator.displayedFields
        val labelX = layoutCalculator.labelX
        val labelY = layoutCalculator.labelY

        if (layoutCalculator.vertical) {
            if (layoutCalculator.autoLabelSize != -1 && labels != null && labelX.isNotEmpty()) {
                configurePaintForFont(layoutCalculator.labelFont)
                paint.color = lblColor
                paint.textAlign = Paint.Align.RIGHT
                for (labeli in displayedFields.indices) {
                    val fieldIndex = displayedFields[labeli]
                    val yTop = labelY.getOrElse(labeli) { 5f }
                    drawTextFromTop(canvas, labels[fieldIndex], labelX[0], yTop)
                }
            }

            for (valuei in displayedFields.indices) {
                val fieldIndex = displayedFields[valuei]
                val valStr = layoutCalculator.formatFieldValue(fieldIndex, snapshot)
                val yTop = labelY.getOrElse(valuei) { 5f }
                val xLeft = labelX.getOrElse(1) { 3f }

                configurePaintForFont(layoutCalculator.valueFont)
                paint.color = fgColor
                paint.textAlign = Paint.Align.LEFT
                drawTextFromTop(canvas, valStr, xLeft, yTop)
                val valWidth = paint.measureText(valStr)

                if (layoutCalculator.showsSpeedUnits(fieldIndex)) {
                    configurePaintForFont(layoutCalculator.unitsFont)
                    paint.color = fgColor
                    paint.textAlign = Paint.Align.LEFT
                    drawTextFromTop(canvas, unitsStr, xLeft + valWidth + 3f, yTop)
                }
                if (layoutCalculator.showsDistanceUnits(fieldIndex)) {
                    configurePaintForFont(layoutCalculator.unitsFont)
                    paint.color = fgColor
                    paint.textAlign = Paint.Align.LEFT
                    drawTextFromTop(canvas, dUnitsStr, xLeft + valWidth + 3f, yTop)
                }
            }
        } else {
            if (layoutCalculator.autoLabelSize != -1 && labels != null && labelY.isNotEmpty()) {
                configurePaintForFont(layoutCalculator.labelFont)
                paint.color = lblColor
                paint.textAlign = Paint.Align.CENTER
                for (labeli in displayedFields.indices) {
                    val fieldIndex = displayedFields[labeli]
                    val xCenter = labelX.getOrElse(labeli) { width * 0.5f }
                    drawTextFromTop(canvas, labels[fieldIndex], xCenter, labelY[0])
                }
            }

            val fh = measurer.getFontHeight(layoutCalculator.valueFont)
            val valYTop = labelY.getOrElse(1) { 5f }
            for (valuei in displayedFields.indices) {
                val fieldIndex = displayedFields[valuei]
                val valStr = layoutCalculator.formatFieldValue(fieldIndex, snapshot)
                val xCenter = labelX.getOrElse(valuei) { width * 0.5f }

                configurePaintForFont(layoutCalculator.valueFont)
                paint.color = fgColor
                paint.textAlign = Paint.Align.CENTER
                drawTextFromTop(canvas, valStr, xCenter, valYTop)

                if (layoutCalculator.showsSpeedUnits(fieldIndex)) {
                    configurePaintForFont(layoutCalculator.unitsFont)
                    paint.color = fgColor
                    paint.textAlign = Paint.Align.CENTER
                    drawTextFromTop(canvas, unitsStr, xCenter, max(0f, valYTop + fh - 5f))
                }
                if (layoutCalculator.showsDistanceUnits(fieldIndex)) {
                    configurePaintForFont(layoutCalculator.unitsFont)
                    paint.color = fgColor
                    paint.textAlign = Paint.Align.CENTER
                    drawTextFromTop(canvas, dUnitsStr, xCenter, max(0f, valYTop + fh - 5f))
                }
            }
        }

        if (layoutCalculator.showDebugStatus || layoutCalculator.pendingResetTap) {
            val statusText = layoutCalculator.getStatusText(snapshot)
            val statusFontHeight = measurer.getFontHeight(layoutCalculator.statusFont)
            val statusYTop = (height - statusFontHeight - 1).toFloat()
            configurePaintForFont(layoutCalculator.statusFont)
            paint.color = statusColor
            paint.textAlign = Paint.Align.CENTER
            drawTextFromTop(canvas, statusText, width / 2f, statusYTop)
        }
    }

    private fun drawTextFromTop(canvas: Canvas, text: String, x: Float, yTop: Float) {
        val baseline = yTop - paint.fontMetrics.ascent
        canvas.drawText(text, x, baseline, paint)
    }
}
