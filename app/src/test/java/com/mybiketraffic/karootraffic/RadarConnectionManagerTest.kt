package com.mybiketraffic.karootraffic

import com.mybiketraffic.karootraffic.radar.KarooStreamRadarNormalizer
import com.mybiketraffic.karootraffic.radar.NativeRadarProvider
import com.mybiketraffic.karootraffic.radar.RadarConnectionManager
import com.mybiketraffic.karootraffic.radar.RadarTarget
import com.mybiketraffic.karootraffic.radar.RawAntRadarController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RadarConnectionManagerTest {

    private class FakeNative : NativeRadarProvider {
        override var isAvailable: Boolean = true
        override var isTracking: Boolean = false
        override var isSearching: Boolean = false
        var targets: List<RadarTarget>? = null
        override fun resetNativeRadar() {}
        override fun getRadarInfo(): List<RadarTarget>? = targets
    }

    private class FakeRaw : RawAntRadarController {
        override var isOpen: Boolean = false
        override var isSearching: Boolean = false
        override var isTracking: Boolean = false
        override var lastPage: Int? = null
        override var lastRfEvent: Int? = null
        override var rawDeviceState: Int = 0
        override var sawErrorPage: Boolean = false
        override fun openChannel(): Boolean {
            isOpen = true
            isSearching = true
            return true
        }
        override fun closeChannel() {
            isOpen = false
            isSearching = false
            isTracking = false
        }
        override fun getRadarInfo(): List<RadarTarget>? = if (isTracking) RadarTarget.buildEmptyTargets() else null
    }

    @Test
    fun `prioritizes built-in Karoo radar stream and supports double-tap fallback in sometimes mode`() {
        val native = FakeNative()
        val raw = FakeRaw()
        val mgr = RadarConnectionManager(
            connectionBehaviorMode = RadarConnectionManager.CONNECTION_MODE_SOMETIMES_820,
            nativeRadar = native,
            rawRadarFactory = { raw }
        )

        // When native is searching, status is PAIR
        native.isSearching = true
        mgr.tick()
        assertEquals("PAIR", mgr.getDisplayStatus())

        // When native is tracking, locks onto native session
        native.isSearching = false
        native.isTracking = true
        mgr.tick()
        assertEquals("PAIRED", mgr.getDisplayStatus())

        // Reset and test double-tap skipWaitOnce in SOMETIMES_820 mode
        native.isTracking = false
        mgr.requestRawWaitReset()
        assertEquals("RESET", mgr.getDisplayStatus())

        // Next tick starts raw ANT+ scan immediately because skipWaitOnce was armed by reset
        mgr.tick()
        assertTrue(raw.isOpen)
    }

    @Test
    fun `KarooStreamRadarNormalizer estimates closing speed from range deltas`() {
        var nowMs = 1_000L
        val normalizer = KarooStreamRadarNormalizer { nowMs }

        normalizer.onRadarStreamData(threatLevel = 1, rangesMeters = listOf(80f))
        nowMs = 1_500L
        normalizer.onRadarStreamData(threatLevel = 1, rangesMeters = listOf(72.5f))
        nowMs = 2_000L
        normalizer.onRadarStreamData(threatLevel = 1, rangesMeters = listOf(65f))

        val targets = normalizer.getTargetsSnapshot()
        assertNotNull(targets)
        assertEquals(65f, targets!![0].range, 0.01f)
        // Dropped 15m in 1.0s => 15.0 m/s closing speed
        assertEquals(15f, targets[0].speed, 0.1f)
        assertEquals(1, targets[0].threat)
    }
}
