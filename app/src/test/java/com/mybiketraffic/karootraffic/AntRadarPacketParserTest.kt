package com.mybiketraffic.karootraffic

import com.mybiketraffic.karootraffic.radar.AntRadarPacketParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AntRadarPacketParserTest {

    private fun buildTargetsPayload(
        page: Int,
        threats: List<Int>,
        rawRangeSteps: List<Int>,
        rawSpeedSteps: List<Int>
    ): ByteArray {
        var threatByte = 0
        var rawRanges = 0
        for (i in 0 until 4) {
            threatByte = threatByte or ((threats.getOrElse(i) { 0 } and 0x03) shl (i * 2))
            rawRanges = rawRanges or ((rawRangeSteps.getOrElse(i) { 0 } and 0x3F) shl (i * 6))
        }
        val speedByte6 = ((rawSpeedSteps.getOrElse(0) { 0 } and 0x0F)) or
            ((rawSpeedSteps.getOrElse(1) { 0 } and 0x0F) shl 4)
        val speedByte7 = ((rawSpeedSteps.getOrElse(2) { 0 } and 0x0F)) or
            ((rawSpeedSteps.getOrElse(3) { 0 } and 0x0F) shl 4)

        return byteArrayOf(
            page.toByte(),
            threatByte.toByte(),
            0,
            (rawRanges and 0xFF).toByte(),
            ((rawRanges ushr 8) and 0xFF).toByte(),
            ((rawRanges ushr 16) and 0xFF).toByte(),
            speedByte6.toByte(),
            speedByte7.toByte()
        )
    }

    @Test
    fun `parses page 0x30 Targets A and 0x31 Targets B accurately`() {
        var nowSec = 100L
        val parser = AntRadarPacketParser { nowSec }
        parser.onChannelOpened()
        assertTrue(parser.isSearching)

        // Page 0x30: Target 0 at 10 steps (31.25m), speed 5 steps (15.20 m/s), threat 1
        // Target 1 at 20 steps (62.50m), speed 6 steps (18.24 m/s), threat 2
        val payloadA = buildTargetsPayload(
            page = AntRadarPacketParser.ANT_RADAR_PAGE_TARGETS_A,
            threats = listOf(1, 2, 0, 0),
            rawRangeSteps = listOf(10, 20, 0, 0),
            rawSpeedSteps = listOf(5, 6, 0, 0)
        )
        parser.onBroadcastPayload(payloadA)

        assertTrue(parser.isTracking())
        assertFalse(parser.isSearching)
        val targetsAfterA = parser.getRadarInfo()
        assertNotNull(targetsAfterA)
        assertEquals(31.25f, targetsAfterA!![0].range, 0.01f)
        assertEquals(15.20f, targetsAfterA[0].speed, 0.01f)
        assertEquals(1, targetsAfterA[0].threat)

        assertEquals(62.50f, targetsAfterA[1].range, 0.01f)
        assertEquals(18.24f, targetsAfterA[1].speed, 0.01f)
        assertEquals(2, targetsAfterA[1].threat)
        assertEquals(0, targetsAfterA[2].threat)

        // Page 0x31: Target 4 (slot index 4) at 30 steps (93.75m), speed 4 steps (12.16 m/s), threat 1
        val payloadB = buildTargetsPayload(
            page = AntRadarPacketParser.ANT_RADAR_PAGE_TARGETS_B,
            threats = listOf(1, 0, 0, 0),
            rawRangeSteps = listOf(30, 0, 0, 0),
            rawSpeedSteps = listOf(4, 0, 0, 0)
        )
        parser.onBroadcastPayload(payloadB)

        val targetsAfterB = parser.getRadarInfo()!!
        assertEquals(93.75f, targetsAfterB[4].range, 0.01f)
        assertEquals(12.16f, targetsAfterB[4].speed, 0.01f)
        assertEquals(1, targetsAfterB[4].threat)
    }

    @Test
    fun `expires Targets B after 1s and all targets after 2s`() {
        var nowSec = 200L
        val parser = AntRadarPacketParser { nowSec }
        parser.onChannelOpened()

        val payloadA = buildTargetsPayload(0x30, listOf(1, 0, 0, 0), listOf(8, 0, 0, 0), listOf(5, 0, 0, 0))
        val payloadB = buildTargetsPayload(0x31, listOf(1, 0, 0, 0), listOf(16, 0, 0, 0), listOf(5, 0, 0, 0))
        parser.onBroadcastPayload(payloadA)
        parser.onBroadcastPayload(payloadB)

        // Advance 1 second: Targets B should expire, Targets A still valid
        nowSec += 1L
        val after1s = parser.getRadarInfo()
        assertNotNull(after1s)
        assertEquals(1, after1s!![0].threat)
        assertEquals(0, after1s[4].threat)

        // Advance another 1 second (total 2s): all targets expire
        nowSec += 1L
        assertFalse(parser.isTracking())
        assertNull(parser.getRadarInfo())
    }

    @Test
    fun `handles error page 0x57 and shutdown device status page 0x01`() {
        val parser = AntRadarPacketParser { 500L }
        parser.onChannelOpened()
        parser.onBroadcastPayload(
            buildTargetsPayload(0x30, listOf(1, 0, 0, 0), listOf(5, 0, 0, 0), listOf(4, 0, 0, 0))
        )
        assertTrue(parser.isTracking())

        // Error page 0x57 clears targets
        parser.onBroadcastPayload(byteArrayOf(0x57, 0, 0, 0, 0, 0, 0, 0))
        assertTrue(parser.sawErrorPage)
        assertFalse(parser.isTracking())
        assertNull(parser.getRadarInfo())

        // Recover with target page, then send device status shutdown requested
        parser.onBroadcastPayload(
            buildTargetsPayload(0x30, listOf(1, 0, 0, 0), listOf(5, 0, 0, 0), listOf(4, 0, 0, 0))
        )
        assertTrue(parser.isTracking())

        parser.onBroadcastPayload(byteArrayOf(0x01, 0x01, 0, 0, 0, 0, 0, 0x01))
        assertEquals(AntRadarPacketParser.ANT_RADAR_DEVICE_STATE_SHUTDOWN_REQUESTED, parser.rawDeviceState)
        assertFalse(parser.isTracking())
    }
}
