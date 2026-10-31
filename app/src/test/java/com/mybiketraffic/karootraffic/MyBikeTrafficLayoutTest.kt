package com.mybiketraffic.karootraffic

import com.mybiketraffic.karootraffic.karoo.MyBikeTrafficLayoutCalculator
import com.mybiketraffic.karootraffic.preferences.TrafficDisplaySettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MyBikeTrafficLayoutTest {

    private val fakeMeasurer = object : MyBikeTrafficLayoutCalculator.TextMeasurer {
        override fun getTextDimensions(text: String, fontIdx: Int): Pair<Int, Int> {
            val charWidth = 8 + fontIdx * 4
            val height = getFontHeight(fontIdx)
            return Pair(text.length * charWidth, height)
        }

        override fun getFontHeight(fontIdx: Int): Int {
            return 12 + fontIdx * 6
        }
    }

    @Test
    fun `orders displayed fields by slot and selects horizontal or vertical layout accurately`() {
        val settings = TrafficDisplaySettings(
            displayTotalPosition = 2,
            displayLapPosition = 0,
            displaySpeedRelativePosition = 0,
            displaySpeedAbsolutePosition = 1,
            displaySpeedLastPosition = 3,
            displayClosestDistPosition = 0,
            autoOrientation = -1
        )
        val calc = MyBikeTrafficLayoutCalculator(settings)

        assertEquals(
            listOf(
                MyBikeTrafficLayoutCalculator.FIELD_SPEED_ABSOLUTE,
                MyBikeTrafficLayoutCalculator.FIELD_TOTAL,
                MyBikeTrafficLayoutCalculator.FIELD_SPEED_LAST
            ),
            calc.displayedFields
        )
        assertEquals(3, calc.labelSet)

        // Narrow tile (<180px) with 3 fields => Vertical stack
        calc.onLayout(fakeMeasurer, totalWidth = 160, totalHeight = 200)
        assertTrue(calc.vertical)
        assertEquals(3, calc.labelY.size)

        // Wide tile (>180px) with 3 fields => Horizontal layout
        calc.onLayout(fakeMeasurer, totalWidth = 320, totalHeight = 100)
        assertFalse(calc.vertical)
        assertEquals(3, calc.labelX.size)
    }

    @Test
    fun `handles double-tap reset confirmation window`() {
        var nowSec = 1000L
        val calc = MyBikeTrafficLayoutCalculator(clockSeconds = { nowSec })
        var resetCalled = false

        // First tap arms confirmation
        val firstTap = calc.handleResetTap { resetCalled = true }
        assertFalse(firstTap)
        assertFalse(resetCalled)
        assertTrue(calc.pendingResetTap)
        assertEquals("TAP AGAIN", calc.getStatusText(null))

        // Second tap within 1s triggers reset
        val secondTap = calc.handleResetTap { resetCalled = true }
        assertTrue(secondTap)
        assertTrue(resetCalled)
        assertFalse(calc.pendingResetTap)
    }
}
