package com.mybiketraffic.karootraffic

import com.mybiketraffic.karootraffic.engine.TrafficEngine
import com.mybiketraffic.karootraffic.radar.NativeRadarProvider
import com.mybiketraffic.karootraffic.radar.RadarConnectionManager
import com.mybiketraffic.karootraffic.radar.RadarTarget
import com.mybiketraffic.karootraffic.radar.RawAntRadarController
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrafficEngineTest {

    private class FakeNativeRadar : NativeRadarProvider {
        override var isAvailable: Boolean = true
        override var isTracking: Boolean = true
        override var isSearching: Boolean = false
        var currentTargets: List<RadarTarget>? = RadarTarget.buildEmptyTargets()

        override fun resetNativeRadar() {}
        override fun getRadarInfo(): List<RadarTarget>? = currentTargets
    }

    private class DummyRawRadar : RawAntRadarController {
        override val isOpen: Boolean = false
        override val isSearching: Boolean = false
        override val isTracking: Boolean = false
        override val lastPage: Int? = null
        override val lastRfEvent: Int? = null
        override val rawDeviceState: Int = 0
        override val sawErrorPage: Boolean = false
        override fun openChannel(): Boolean = false
        override fun closeChannel() {}
        override fun getRadarInfo(): List<RadarTarget>? = null
    }

    @Test
    fun `counts vehicle only when it crosses 10m threshold and disappears`() {
        val fakeRadar = FakeNativeRadar()
        val radarMgr = RadarConnectionManager(
            connectionBehaviorMode = RadarConnectionManager.CONNECTION_MODE_NEVER_820,
            nativeRadar = fakeRadar,
            rawRadarFactory = { DummyRawRadar() }
        )
        val engine = TrafficEngine(metric = true, bikeRadar = radarMgr)

        // Car approaches at 40m -> 20m -> 8m (crosses THRESH=10m) at 15 m/s (54 kph), rider at 8 m/s (~29 kph)
        val targets = RadarTarget.buildEmptyTargets()
        fakeRadar.currentTargets = targets

        targets[0].set(40f, 15f, 1)
        var snap = engine.compute(isRecording = true, currentSpeedMps = 8f)
        assertFalse(snap.disabled)
        assertEquals(0, snap.count)
        assertEquals(54, snap.approachspd)
        assertEquals(83, snap.absolutespd) // 54 + round(8 * 3.6 = 28.8 -> 29) = 83
        assertEquals(40, snap.dist)

        // Move to 8m (inside 10m threshold)
        targets[0].set(8f, 15f, 1)
        snap = engine.compute(isRecording = true, currentSpeedMps = 8f)
        assertEquals(0, snap.count)
        assertEquals(8, snap.dist)

        // Car passes rider and disappears from radar
        targets[0].clear()
        snap = engine.compute(isRecording = true, currentSpeedMps = 8f)
        assertEquals(1, snap.count)
        assertEquals(1, snap.lapcount)
        assertEquals(83, snap.lastspd)
        assertEquals(0, snap.approachspd)
        assertEquals(0, snap.absolutespd)
    }

    @Test
    fun `does not count vehicle that turns off before 10m threshold`() {
        val fakeRadar = FakeNativeRadar()
        val radarMgr = RadarConnectionManager(
            connectionBehaviorMode = RadarConnectionManager.CONNECTION_MODE_NEVER_820,
            nativeRadar = fakeRadar,
            rawRadarFactory = { DummyRawRadar() }
        )
        val engine = TrafficEngine(metric = true, bikeRadar = radarMgr)
        val targets = RadarTarget.buildEmptyTargets()
        fakeRadar.currentTargets = targets

        // Car approaches to 15m (>= 10m threshold) and then turns off
        targets[0].set(15f, 12f, 1)
        engine.compute(isRecording = true, currentSpeedMps = 7f)

        targets[0].clear()
        val snap = engine.compute(isRecording = true, currentSpeedMps = 7f)
        assertEquals(0, snap.count)
        assertEquals(0, snap.lapcount)
        assertEquals(0, snap.lastspd)
    }

    @Test
    fun `handles multi-car platoon and lap resets and imperial units`() {
        val fakeRadar = FakeNativeRadar()
        val radarMgr = RadarConnectionManager(
            connectionBehaviorMode = RadarConnectionManager.CONNECTION_MODE_NEVER_820,
            nativeRadar = fakeRadar,
            rawRadarFactory = { DummyRawRadar() }
        )
        val engine = TrafficEngine(metric = false, bikeRadar = radarMgr)
        val targets = RadarTarget.buildEmptyTargets()
        fakeRadar.currentTargets = targets

        // 2 cars approaching: car 0 at 6m (<10m), car 1 at 25m; speed 10 m/s (22 mph), rider 5 m/s (11 mph)
        targets[0].set(6f, 10f, 1)
        targets[1].set(25f, 10f, 1)
        var snap = engine.compute(isRecording = true, currentSpeedMps = 5f)
        assertEquals(22, snap.approachspd)
        assertEquals(33, snap.absolutespd)
        assertEquals(19, snap.dist) // int(6 * 3.28084) = 19 ft

        // Car 0 passes, car 1 moves up to 7m
        targets[0].set(7f, 10f, 1)
        targets[1].clear()
        snap = engine.compute(isRecording = true, currentSpeedMps = 5f)
        assertEquals(1, snap.count)
        assertEquals(1, snap.lapcount)
        assertEquals(33, snap.lastspd)

        // Trigger lap reset
        engine.onTimerLap()
        // Car 1 passes
        targets[0].clear()
        snap = engine.compute(isRecording = true, currentSpeedMps = 5f)
        assertEquals(2, snap.count)
        assertEquals(1, snap.lapcount)
    }

    @Test
    fun `sets sentinel FIT values when radar is disabled or ride not recording`() {
        val fakeRadar = FakeNativeRadar()
        fakeRadar.isTracking = false
        fakeRadar.currentTargets = null
        val radarMgr = RadarConnectionManager(
            connectionBehaviorMode = RadarConnectionManager.CONNECTION_MODE_NEVER_820,
            nativeRadar = fakeRadar,
            rawRadarFactory = { DummyRawRadar() }
        )
        val engine = TrafficEngine(metric = true, bikeRadar = radarMgr)

        val snap = engine.compute(isRecording = true, currentSpeedMps = 5f)
        assertTrue(snap.disabled)
        assertTrue(snap.rangeInfo.all { it == -1 })
        assertTrue(snap.speedInfo.all { it == 255 })
    }
}
